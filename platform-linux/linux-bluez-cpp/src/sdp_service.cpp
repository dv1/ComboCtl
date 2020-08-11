#include <assert.h>
#include "scope_guard.hpp"
#include "gerror_exception.hpp"
#include "glib_misc.hpp"
#include "sdp_service.hpp"
#include "log.hpp"


namespace comboctl
{


namespace
{


std::string const profile_path = "/io/bluetooth/comboctl/sdpProfile";

std::string const profile_interface_xml =
	"<?xml version=\"1.0\" encoding=\"UTF-8\" ?>"
	"<node>"
	"    <interface name='org.bluez.Profile1'>"
	"        <method name='Release'/>"
	"        <method name='NewConnection'>"
	"            <arg type='o' name='device' direction='in' />"
	"            <arg type='h' name='fd' direction='in' />"
	"            <arg type='a{sv}' name='fd_properties' direction='in' />"
	"        </method>"
	"        <method name='RequestDisconnection'>"
	"            <arg type='o' name='device' direction='in' />"
	"        </method>"
	"    </interface>"
	"</node>";

std::string const serial_port_profile_uuid_str = "00001101-0000-1000-8000-00805f9b34fb";

std::string const sdp_service_record_xml_template =
	"<?xml version=\"1.0\" encoding=\"UTF-8\" ?>"
	"<record>"
	"    <attribute id='0x0001'> <!-- ServiceClassIDList -->"
	"        <sequence>"
	"            <uuid value='0x1101' /> <!-- 0x1101 = Serial Port Profile UUID -->"
	"        </sequence>"
	"    </attribute>"
	"    <attribute id='0x0003'> <!-- ServiceID -->"
	"        <uuid value='0x1101' /> <!-- 0x1101 = Serial Port Profile UUID -->"
	"    </attribute>"
	"    <attribute id='0x0100'> <!-- ServiceName -->"
	"        <text value='{0}' /> <!-- Using placeholder for fmt::format() -->"
	"    </attribute>"
	"    <attribute id='0x0101'> <!-- ServiceDescription -->"
	"        <text value='{1}' /> <!-- Using placeholder for fmt::format() -->"
	"    </attribute>"
	"    <attribute id='0x0102'> <!-- ServiceProvider -->"
	"        <text value='{2}' /> <!-- Using placeholder for fmt::format() -->"
	"    </attribute>"
	"    <attribute id='0x0008'> <!-- ServiceAvailability -->"
	"        <uint8 value='0xff' /> <!-- 0xff = service is fully available -->"
	"    </attribute>"
	"    <attribute id='0x0004'> <!-- ProtocolDescriptorList -->"
	"        <sequence>"
	"            <sequence>"
	"                <uuid value='0x0003' /> <!-- 0x0003 = RFCOMM -->"
	"                <uint8 value='{3}' />   <!-- RFCOMM channel -->"
	"            </sequence>"
	"        </sequence>"
	"    </attribute>"
	"    <attribute id='0x0009'> <!-- BluetoothProfileDescriptorList -->"
	"        <sequence>"
	"            <sequence>"
	"                <uuid value='0x1101' />   <!-- 0x1101 = Serial Port Profile UUID -->"
	"                <uint16 value='0x0100' /> <!-- Version -->"
	"            </sequence>"
	"        </sequence>"
	"    </attribute>"
	"    <attribute id='0x0005'> <!-- BrowseGroupList -->"
	"        <sequence>"
	"            <uuid value='0x1002' /> <!-- PublicBrowseRoot -->"
	"        </sequence>"
	"    </attribute>"
	"</record>";


} // unnamed namespace end


sdp_service::sdp_service()
	: m_dbus_connection(nullptr)
	, m_profile_manager_proxy(nullptr)
	, m_profile_object_id(0)
	, m_profile_registered(false)
{
}


sdp_service::~sdp_service()
{
	teardown();
}


void sdp_service::setup(GDBusConnection *dbus_connection, std::string service_name, std::string service_provider, std::string service_description, unsigned int rfcomm_channel)
{
	// Prerequisites.

	GError *error = nullptr;

	assert(dbus_connection != nullptr);

	if (m_profile_object_id != 0)
		throw invalid_call_exception("SDP service already set up");

	// Store the arguments.
	m_dbus_connection = dbus_connection;

	// Install scope guard to call teardown() if something
	// goes wrong. This makes sure that any changes done
	// by this function are rolled back then.
	auto guard = make_scope_guard([&]() { teardown(); });

	// Get the proxy object for future profile manager calls.
	m_profile_manager_proxy = g_dbus_proxy_new_sync(
		m_dbus_connection,
		G_DBUS_PROXY_FLAGS_NONE,
		nullptr,
		"org.bluez",
		"/org/bluez",
		"org.bluez.ProfileManager1",
		nullptr,
		&error
	);
	if (error != nullptr)
	{
		LOG(error, "Could not create ProfileManager GDBus proxy: {}", error->message);
		throw gerror_exception(error);
	}

	// Create node info object. This will be needed for
	// creating our own D-Bus BlueZ profile object that
	// is used by BlueZ For advertising our SDP service
	// record.

	GDBusNodeInfo *node_info = g_dbus_node_info_new_for_xml(profile_interface_xml.c_str(), &error);
	if (error != nullptr)
	{
		LOG(error, "Could not create DBus interface node info for BlueZ profile: {}", error->message);
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

	// Register our profile object. This does not yet
	// register it as a BlueZ profile, it just makes
	// it appears as an object in D-Bus.
	m_profile_object_id = g_dbus_connection_register_object(
		m_dbus_connection,
		profile_path.c_str(),
		node_info->interfaces[0],
		nullptr,
		nullptr,
		nullptr,
		&error
	);
	if (error != nullptr)
	{
		LOG(error, "Could not register profile object: {}", error->message);
		throw gerror_exception(error);
	}

	// This is now the actual profile registration.
	{
		// We Use a manual service record XML, since
		// the profile manager interface for creating
		// service records is very limited.

		std::string sdp_service_record_xml = fmt::format(sdp_service_record_xml_template, service_name, service_description, service_provider, rfcomm_channel);

		GVariant *profile;
		GVariantBuilder profile_builder;

		g_variant_builder_init(&profile_builder, G_VARIANT_TYPE("(osa{sv})"));
		g_variant_builder_add (&profile_builder, "o", profile_path.c_str());
		g_variant_builder_add (&profile_builder, "s", serial_port_profile_uuid_str.c_str());

		g_variant_builder_open(&profile_builder, G_VARIANT_TYPE("a{sv}"));

		g_variant_builder_open(&profile_builder, G_VARIANT_TYPE("{sv}"));	
		g_variant_builder_add (&profile_builder, "s", "Channel");
		g_variant_builder_add (&profile_builder, "v", g_variant_new_uint16(rfcomm_channel));
		g_variant_builder_close(&profile_builder);

		g_variant_builder_open(&profile_builder, G_VARIANT_TYPE("{sv}"));
		g_variant_builder_add (&profile_builder, "s", "ServiceRecord");
		g_variant_builder_add (&profile_builder, "v", g_variant_new_string(sdp_service_record_xml.c_str()));
		g_variant_builder_close(&profile_builder);

		g_variant_builder_open(&profile_builder, G_VARIANT_TYPE("{sv}"));
		g_variant_builder_add (&profile_builder, "s", "AutoConnect");
		g_variant_builder_add (&profile_builder, "v", g_variant_new_boolean(FALSE));
		g_variant_builder_close(&profile_builder);

		g_variant_builder_close(&profile_builder);
		profile = g_variant_builder_end(&profile_builder);

		g_dbus_proxy_call_sync(
			m_profile_manager_proxy,
			"RegisterProfile",
			profile,
			G_DBUS_CALL_FLAGS_NONE,
			-1,
			nullptr,
			&error
		);
		if (error != nullptr)
		{
			LOG(error, "Could not register profile: {}", error->message);
			throw gerror_exception(error);
		}
	}

	// Our SDP service record is ready. Dismiss the guard to make
	// sure it is not torn down again.

	guard.dismiss();

	LOG(trace, "SDP service set up");
}


void sdp_service::teardown()
{
	if (m_profile_registered)
	{
		g_dbus_proxy_call_sync(
			m_profile_manager_proxy,
			"UnregisterProfile",
			g_variant_new("(o)", profile_path.c_str()),
			G_DBUS_CALL_FLAGS_NONE,
			-1,
			nullptr,
			nullptr
		);

		m_profile_registered = false;
	}

	if (m_profile_object_id != 0)
	{
		g_dbus_connection_unregister_object(m_dbus_connection, m_profile_object_id);
		m_profile_object_id = 0;
	}

	if (m_profile_manager_proxy != nullptr)
	{
		g_object_unref(G_OBJECT(m_profile_manager_proxy));
		m_profile_manager_proxy = nullptr;
	}

	m_dbus_connection = nullptr;

	LOG(trace, "SDP service torn down");
}


} // namespace comboctl end
