#include <assert.h>
#include <glib.h>
#include <gio/gio.h>
#include "gerror_exception.hpp"
#include "glib_misc.hpp"
#include "agent.hpp"
#include "scope_guard.hpp"
#include "log.hpp"


DEFINE_LOGGING_TAG("BlueZAgent")


namespace comboctl
{


namespace
{


std::string const agent_path("/io/bluetooth/comboctl/bluetoothAgent");

std::string const agent_interface_xml =
	"<?xml version=\"1.0\" encoding=\"UTF-8\" ?>"
	"<node>"
	"   <interface name='org.bluez.Agent1'>"
	"       <method name='Release'/>"
	"       <method name='RequestPinCode'>"
	"           <arg type='o' name='device' direction='in' />"
	"           <arg type='s' name='pincode' direction='out' />"
	"       </method>"
	"       <method name='DisplayPinCode'>"
	"           <arg type='o' name='device' direction='in' />"
	"           <arg type='s' name='pincode' direction='in' />"
	"       </method>"
	"       <method name='RequestPasskey'>"
	"           <arg type='o' name='device' direction='in' />"
	"           <arg type='u' name='passkey' direction='out' />"
	"       </method>"
	"       <method name='DisplayPasskey'>"
	"           <arg type='o' name='device' direction='in' />"
	"           <arg type='u' name='passkey' direction='in' />"
	"           <arg type='q' name='entered' direction='in' />"
	"       </method>"
	"       <method name='RequestConfirmation'>"
	"           <arg type='o' name='device' direction='in' />"
	"           <arg type='u' name='passkey' direction='in' />"
	"       </method>"
	"       <method name='RequestAuthorization'>"
	"           <arg type='o' name='device' direction='in' />"
	"       </method>"
	"       <method name='AuthorizeService'>"
	"           <arg type='o' name='device' direction='in' />"
	"           <arg type='s' name='uuid' direction='in' />"
	"       </method>"
	"       <method name='Cancel'/>"
	"   </interface>"
	"</node>";


} // unnamed namespace end


agent::agent()
	: m_dbus_connection(nullptr)
	, m_agent_manager_proxy(nullptr)
	, m_agent_object_id(0)
	, m_agent_registered(false)
{
}


agent::~agent()
{
	teardown();
}


void agent::setup(
	GDBusConnection *dbus_connection,
	std::string pairing_pin_code,
	filter_device_callback on_filter_device
)
{
	// Prerequisites.

	GError *error = nullptr;

	assert(dbus_connection != nullptr);

	if (m_agent_object_id != 0)
		throw invalid_call_exception("Agent already set up");

	// Store the arguments.
	m_dbus_connection = dbus_connection;
	m_pairing_pin_code = std::move(pairing_pin_code);
	m_on_filter_device = std::move(on_filter_device);

	// Install scope guard to call teardown() if something
	// goes wrong. This makes sure that any changes done
	// by this function are rolled back then.
	auto guard = make_scope_guard([&]() { teardown(); });

	// Get the proxy object for future agent manager calls.
	m_agent_manager_proxy = g_dbus_proxy_new_sync(
		m_dbus_connection,
		G_DBUS_PROXY_FLAGS_NONE,
		nullptr,
		"org.bluez",
		"/org/bluez",
		"org.bluez.AgentManager1",
		nullptr,
		&error
	);
	if (error != nullptr)
	{
		LOG(error, "Could not create AgentManager GDBus proxy: {}", error->message);
		throw gerror_exception(error);
	}

	// Create node info object. This will be needed for
	// creating our own D-Bus BlueZ agent object that
	// is later used when devices request authorization.
	GDBusNodeInfo *node_info = g_dbus_node_info_new_for_xml(agent_interface_xml.c_str(), &error);
	if (error != nullptr)
	{
		LOG(error, "Could not create DBus interface node info for BlueZ agent: {}", error->message);
		throw gerror_exception(error);
	}

	// Create separate node info guard to unref
	// our reference to the node info. Unlike the
	// scope guard from the beginning of this function,
	// this one is does not dismissed when this
	// function finishes successfully, since the
	// node info reference always needs to be unref'd.
	auto node_info_guard = make_scope_guard([&]() {
		g_dbus_node_info_unref(node_info);
	});

	// Set up the VTable of our agent D-Bus object.

	static auto static_method_call = [](GDBusConnection *connection, const gchar *sender_name, const gchar *object_path, const gchar *interface_name, const gchar *method_name, GVariant *parameters, GDBusMethodInvocation *invocation, gpointer user_data) -> void
	{
		reinterpret_cast<agent*>(user_data)->handle_agent_method_call(
			connection,
			sender_name,
			object_path,
			interface_name,
			method_name,
			parameters,
			invocation
		);
	};
	static GDBusInterfaceVTable const agent_method_table = make_gdbus_iface_vtable(static_method_call);

	// Register our agent object. This does not yet
	// register it as a BlueZ agent, it just makes
	// it appears as an object in D-Bus.
	m_agent_object_id = g_dbus_connection_register_object(
		m_dbus_connection,
		agent_path.c_str(),
		node_info->interfaces[0],
		&agent_method_table,
		gpointer(this),
		nullptr,
		&error
	);
	if (error != nullptr)
	{
		LOG(error, "Could not register agent object: {}", error->message);
		throw gerror_exception(error);
	}

	// This is now the actual agent registration.
	g_dbus_proxy_call_sync(
		m_agent_manager_proxy,
		"RegisterAgent",
		g_variant_new("(os)", agent_path.c_str(), "DisplayYesNo"),
		G_DBUS_CALL_FLAGS_NONE,
		-1,
		nullptr,
		&error
	);
	if (error != nullptr)
	{
		LOG(error, "Could not register agent: {}", error->message);
		throw gerror_exception(error);
	}

	g_dbus_proxy_call_sync(
		m_agent_manager_proxy,
		"RequestDefaultAgent",
		g_variant_new("(o)", agent_path.c_str()),
		G_DBUS_CALL_FLAGS_NONE,
		-1,
		nullptr,
		&error
	);
	if (error != nullptr)
	{
		LOG(error, "Could not set agent as default: {}", error->message);
		throw gerror_exception(error);
	}
	// Our agent is ready. Dismiss the guard to make
	// sure it is not torn down again.

	guard.dismiss();

	LOG(trace, "Agent set up");
}


void agent::teardown()
{
	if (m_agent_registered)
	{
		g_dbus_proxy_call_sync(
			m_agent_manager_proxy,
			"UnregisterAgent",
			g_variant_new("(o)", agent_path.c_str()),
			G_DBUS_CALL_FLAGS_NONE,
			-1,
			nullptr,
			nullptr
		);

		m_agent_registered = false;
	}

	if (m_agent_object_id != 0)
	{
		g_dbus_connection_unregister_object(m_dbus_connection, m_agent_object_id);
		m_agent_object_id = 0;
	}

	if (m_agent_manager_proxy != nullptr)
	{
		g_object_unref(G_OBJECT(m_agent_manager_proxy));
		m_agent_manager_proxy = nullptr;
	}

	m_dbus_connection = nullptr;

	LOG(trace, "Agent torn down");
}


void agent::handle_agent_method_call(GDBusConnection *, gchar const *sender_name, gchar const *object_path, gchar const *interface_name, gchar const *method_name, GVariant *parameters, GDBusMethodInvocation *invocation)
{
	LOG(trace,
		"Agent method \"{}\" called by sender \"{}\" (object path \"{}\" interface name \"{}\" parameters type = \"{}\"; parameters = {})",
		method_name,
		sender_name,
		object_path,
		interface_name,
		g_variant_get_type_string(parameters),
		to_string(parameters)
	);

	if (!g_strcmp0(method_name, "RequestPinCode"))
	{
		// Add scope guard that rejects the request. If everything
		// checks out, we dismiss that guard and accept the request.
		auto reject_guard = make_scope_guard([&]() {
			g_dbus_method_invocation_return_dbus_error(invocation, "org.bluez.Error.Rejected", "Not supported");
		});

		GError *error = nullptr;
		gchar *device_object_path = nullptr;
		GDBusProxy *device_proxy = nullptr;
		GVariant *device_address_cstr_property_variant = nullptr;

		// Another scope guard, but this one is there purely
		// for cleanup when this function finishes.
		auto cleanup_guard = make_scope_guard([&]() {
			if (device_address_cstr_property_variant != nullptr)
				g_variant_unref(device_address_cstr_property_variant);

			g_free(device_object_path);

			if (device_proxy != nullptr)
				g_object_unref(G_OBJECT(device_proxy));
		});

		// Get the device's object path so we can access its D-Bus properties.
		g_variant_get(parameters, "(o)", &device_object_path);
		assert(device_object_path != nullptr);

		// Get a proxy to the device object.
		device_proxy = g_dbus_proxy_new_sync(
			m_dbus_connection,
			G_DBUS_PROXY_FLAGS_NONE,
			nullptr,
			"org.bluez",
			device_object_path,
			"org.bluez.Device1",
			nullptr,
			&error
		);
		if (error != nullptr)
		{
			LOG(error, "Could not create Bluetooth device GDBus proxy: {}", error->message);
			throw gerror_exception(error);
		}

		// Check if there is an Address property, if it is a string,
		// and if that string is a valid Bluetooth address in string form.

		device_address_cstr_property_variant = g_dbus_proxy_get_cached_property(device_proxy, "Address");
		if (device_address_cstr_property_variant == nullptr)
		{
			LOG(debug, "Rejecting device object path {} because it has no Address property", device_object_path);
			return;
		}

		gsize device_address_cstr_size = 0;
		gchar const *device_address_cstr = g_variant_get_string(device_address_cstr_property_variant, &device_address_cstr_size);
		if (device_address_cstr == nullptr)
		{
			LOG(debug, "Rejecting device with object path {} because its Address property is not a string", device_object_path);
			return;
		}

		std::string_view device_address_str(device_address_cstr, device_address_cstr_size);

		// If there is a filter callback, use it. If it returns false,
		// then this device is to be rejected.
		if (m_on_filter_device)
		{
			comboctl::bluetooth_address device_address;
			if (!comboctl::from_string(device_address, device_address_str))
			{
				LOG(debug, "Rejecting device object path {} because its Address property's value \"{}\" is not a valid Bluetooth address", device_object_path, device_address_str);
				return;
			}

			if (!m_on_filter_device(device_address))
			{
				LOG(debug, "Rejecting device {} because it was filtered out", device_address_str);
				return;
			}
		}

		LOG(info, "Bluetooth device {} requested PIN code", device_address_str);

		// This device is authorized to get a PIN code.
		// Dismiss the reject guard, since we want to accept the request.
		reject_guard.dismiss();

		// Accept the request by returning the PIN code.
		g_dbus_method_invocation_return_value(invocation, g_variant_new("(s)", m_pairing_pin_code.c_str()));
	}
}


} // namespace comboctl end
