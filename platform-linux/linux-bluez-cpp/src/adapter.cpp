#include <optional>
#include <assert.h>
#include "scope_guard.hpp"
#include "gerror_exception.hpp"
#include "adapter.hpp"
#include "glib_misc.hpp"
#include "log.hpp"


namespace comboctl
{


namespace
{


constexpr gchar const *obj_array_gvformat_string = "(a{oa{sa{sv}}})";


} // unnamed namespace end


adapter::adapter()
	: m_dbus_connection(nullptr)
	, m_adapter_proxy(nullptr)
	, m_dbus_connection_signal_subscription(0)
	, m_discovery_started(false)
{
}


adapter::~adapter()
{
	teardown();
}


void adapter::setup(GDBusConnection *dbus_connection)
{
	// Prerequisites.

	GError *error = nullptr;

	assert(dbus_connection != nullptr);

	if (m_dbus_connection_signal_subscription != 0)
		throw invalid_call_exception("Adapter already set up");

	// Store the arguments.
	m_dbus_connection = dbus_connection;

	// Install scope guard to call teardown() if something
	// goes wrong. This makes sure that any changes done
	// by this function are rolled back then.
	auto guard = make_scope_guard([&]() { teardown(); });

	std::string adapter_object_path;

	// Go through all of BlueZ's managed D-Bus objects
	// to find the first Bluetooth adapter available.
	{
		gvariant_uptr managed_objects_gvariant = get_managed_bluez_objects();
		gvariant_iter_uptr object_iter = get_gvariant_iter_from(managed_objects_gvariant, obj_array_gvformat_string);

		gchar *object_path;
		GVariant *interfaces_dict_variant;
		while (g_variant_iter_loop(object_iter.get(), "{o*}", &object_path, &interfaces_dict_variant))
		{
			gvariant_iter_uptr interface_iter = get_gvariant_iter_from(interfaces_dict_variant, "a{sa{sv}}");

			GVariantIter *properties_iter;
			gchar const *interface_name;
			while (g_variant_iter_loop(interface_iter.get(), "{sa{sv}}", &interface_name, &properties_iter))
			{
				if (g_strcmp0(interface_name, "org.bluez.Adapter1") == 0)
				{
					adapter_object_path = object_path;

					LOG(trace, "Found adapter object path {}", adapter_object_path);
					break;
				}
			}
		}
	}

	if (adapter_object_path.empty())
		throw comboctl::io_exception("No Bluetooth adapter found");

	// Get the proxy object for future adapter calls.
	m_adapter_proxy = g_dbus_proxy_new_sync(
		m_dbus_connection,
		G_DBUS_PROXY_FLAGS_NONE,
		nullptr,
		"org.bluez",
		adapter_object_path.c_str(),
		"org.bluez.Adapter1",
		nullptr,
		&error
	);
	if (error != nullptr)
	{
		LOG(error, "Could not create Adapter GDBus proxy: {}", error->message);
		throw gerror_exception(error);
	}

	// Set up our BlueZ D-Bus signal handler so we can get
	// notifications when Bluetooth devices appear / vanish.

	static auto static_dbus_connection_signal_cb = [](GDBusConnection *, gchar const *sender_name, gchar const *object_path, gchar const *interface_name, gchar const *signal_name, GVariant *parameters, gpointer user_data) -> void
	{
		LOG(trace,
			"Got DBus signal \"{}\" from sender \"{}\" (object path = \"{}\" interface name = \"{}\" parameters type = \"{}\"; parameters = {})",
			signal_name,
			sender_name,
			object_path,
			interface_name,
			g_variant_get_type_string(parameters),
			to_string(parameters)
		);

		reinterpret_cast<adapter*>(user_data)->dbus_connection_signal_cb(
			object_path,
			interface_name,
			signal_name,
			parameters
		);
	};

	m_dbus_connection_signal_subscription = g_dbus_connection_signal_subscribe(
		m_dbus_connection,
		"org.bluez",
		nullptr,
		nullptr,
		nullptr,
		nullptr,
		G_DBUS_SIGNAL_FLAGS_NONE,
		static_dbus_connection_signal_cb,
		gpointer(this),
		nullptr
	);

	// Our adapter is ready. Dismiss the guard to make
	// sure it is not torn down again.

	guard.dismiss();

	LOG(trace, "Adapter set up");
}


void adapter::teardown()
{
	// Stop any ongoing discovery.
	stop_discovery();

	if (m_dbus_connection_signal_subscription != 0)
	{
		g_dbus_connection_signal_unsubscribe(m_dbus_connection, m_dbus_connection_signal_subscription);
		m_dbus_connection_signal_subscription = 0;
	}

	if (m_adapter_proxy != nullptr)
	{
		g_object_unref(G_OBJECT(m_adapter_proxy));
		m_adapter_proxy = nullptr;
	}

	m_dbus_connection = nullptr;

	// Clear the map to make sure there is no leftover stale data.
	m_bt_address_dbus_object_paths.clear();

	LOG(trace, "Adapter torn down");
}


void adapter::start_discovery(
	found_new_device_callback on_found_new_device,
	device_is_gone_callback on_device_is_gone
)
{
	GError *error = nullptr;

	// on_found_new_device must be valid.
	assert(on_found_new_device);

	// Overwrite any previously set callback.
	// Do this even if discovery is ongoing.
	// That way, the start_discovery call behaves
	// in a more intuitive manner.
	m_on_found_new_device = std::move(on_found_new_device);
	m_on_device_is_gone = std::move(on_device_is_gone);

	// Exit early if discovery is already active.
	if (m_discovery_started)
	{
		LOG(debug, "Discovery already ongoing");
		return;
	}

	// Start the discovery.
	send_discovery_call(true);

	// Look up what Bluetooth devices BlueZ already knows
	// of (that is, were discovered earlier already).
	gvariant_uptr managed_objects_gvariant = get_managed_bluez_objects();

	LOG(debug, "Got list of DBus objects currently managed by BlueZ");

	// We are ready to iterate over the enumeratd objects. Get
	// an iterator out of the retval GVariant and look at
	// each enumerated object to see if it has the relevant
	// Bluetooth device interface.
	gvariant_iter_uptr iter = get_gvariant_iter_from(managed_objects_gvariant, obj_array_gvformat_string);

	gchar *object_path;
	GVariant *interfaces_dict_variant;
	while (g_variant_iter_loop(iter.get(), "{o*}", &object_path, &interfaces_dict_variant))
		process_added_dbus_object_interfaces(object_path, interfaces_dict_variant);

	m_discovery_started = true;

	LOG(trace, "Discovery started");
}


void adapter::stop_discovery()
{
	if (!m_discovery_started)
		return;

	send_discovery_call(false);

	m_discovery_started = false;

	LOG(trace, "Discovery stopped");
}


void adapter::remove_device(bluetooth_address const &device_address)
{
	// Get the D-Bus object path for the device with this address.
	auto bt_object_path_iter = m_bt_address_dbus_object_paths.left.find(device_address);
	if (bt_object_path_iter == m_bt_address_dbus_object_paths.left.end())
	{
		LOG(debug, "No device with Bluetooth address {} known; nothing to remove", to_string(device_address));
		return;
	}

	std::string const &object_path = bt_object_path_iter->second;

	LOG(debug, "Removing device with Bluetooth address {} and DBus object path {}", to_string(device_address), object_path);

	g_dbus_proxy_call_sync(
		m_adapter_proxy,
		"RemoveDevice",
		g_variant_new("(o)", object_path.c_str()),
		G_DBUS_CALL_FLAGS_NONE,
		-1,
		nullptr,
		nullptr
	);

	m_bt_address_dbus_object_paths.left.erase(bt_object_path_iter);
}


std::string adapter::get_name() const
{
	GVariant *variant = g_dbus_proxy_get_cached_property(m_adapter_proxy, "Name");
	if (variant == nullptr)
		throw io_exception("DBus Adapter object has no Name property");

	gsize name_cstr_size = 0;
	gchar const *name_cstr = g_variant_get_string(variant, &name_cstr_size);
	if (name_cstr == nullptr)
	{
		throw io_exception("DBus Adapter object has Name property that is not a string");
	}

	std::string name(name_cstr, name_cstr_size);

	LOG(debug, "Got friendly name for Bluetooth adapter: \"{}\"", name);

	return name;
}


void adapter::send_discovery_call(bool do_start)
{
	GError *error = nullptr;

	g_dbus_proxy_call_sync(
		m_adapter_proxy,
		do_start ? "StartDiscovery" : "StopDiscovery",
		nullptr,
		G_DBUS_CALL_FLAGS_NONE,
		-1,
		nullptr,
		&error
	);
	if (error != nullptr)
	{
		LOG(error, "Could not {} discovery: {}", do_start ? "start" : "stop", error->message);
		if (do_start)
			throw gerror_exception(error);
	}
}


void adapter::process_added_dbus_object_interfaces(gchar const *object_path, GVariant *interfaces_dict_variant)
{
	GVariantIter *properties_iter;
	gchar const *interface_name;
	gchar const *property_name;
	GVariant *property_value;

	// Look through the GVariant data. Access requires type
	// information at runtime (which is what strings like "{sv}"
	// are for), and is somewhat complex, since the data is
	// made of nested structures.

	gvariant_iter_uptr interface_iter = get_gvariant_iter_from(interfaces_dict_variant, "a{sa{sv}}");

	while (g_variant_iter_loop(interface_iter.get(), "{sa{sv}}", &interface_name, &properties_iter))
	{
		// We are only interested in the org.bluez.Device1 interface.
		if (g_strcmp0(interface_name, "org.bluez.Device1") != 0)
			continue;

		std::optional<bluetooth_address> bdaddr;
		bool is_paired = false;

		// Look at the properties of the interface. We are interested
		// in the "Address" (the Bluetooth address) and the "Paired"
		// (whether or not this device is paired) properties.
		while (g_variant_iter_loop(properties_iter, "{sv}", &property_name, &property_value))
		{
			if (g_strcmp0(property_name, "Address") == 0)
			{
				gchar const *prop_str = g_variant_get_string(property_value, nullptr);

				bluetooth_address found_bdaddr;
				if (!comboctl::from_string(found_bdaddr, prop_str))
				{
					// Skip invalid Bluetooth addresses.
					LOG(error, "Invalid Bluetooth address \"{}\"", prop_str);
					continue;
				}
				bdaddr = std::move(found_bdaddr);
			}
			else if (g_strcmp0(property_name, "Paired") == 0)
			{
				is_paired = g_variant_get_boolean(property_value);
			}
		}

		if (bdaddr)
		{
			LOG(debug, "Found new Bluetooth device:  object path: {}  Bluetooth address: {}  paired: {}", object_path, to_string(*bdaddr), is_paired);

			m_bt_address_dbus_object_paths.insert(bt_address_dbus_object_paths_map::value_type(*bdaddr, std::string(object_path)));

			// Invoke m_on_found_new_device and catch any thrown
			// exceptions. It is important to do that, since we
			// reach this point after dbus_connection_signal_cb()
			// was called by GLib, and an exception traveling
			// through there results in undefined behavior.
			try
			{
				m_on_found_new_device(*bdaddr, is_paired);
			}
			catch (comboctl::exception const &exc)
			{
				LOG(error, "Caught exception: {}", exc.what());
			}
		}
	}
}


void adapter::process_removed_dbus_object_interfaces(gchar const *object_path, GVariant *interfaces_array_variant)
{
	gchar const *interface_name;

	// Look through the GVariant data. Access requires type
	// information at runtime (which is what strings like "{sv}"
	// are for), and is somewhat complex, since the data is
	// made of nested structures.

	auto bt_address_iter = m_bt_address_dbus_object_paths.right.find(object_path);
	if (bt_address_iter == m_bt_address_dbus_object_paths.right.end())
	{
		LOG(trace, "No device with D-Bus object path {} known; ignoring removed interface", object_path);
		return;
	}

	bluetooth_address bdaddr = std::move(bt_address_iter->second);

	gvariant_iter_uptr interface_iter = get_gvariant_iter_from(interfaces_array_variant, "as");

	while (g_variant_iter_loop(interface_iter.get(), "s", &interface_name))
	{
		// We are only interested in the org.bluez.Device1 interface.
		if (g_strcmp0(interface_name, "org.bluez.Device1") != 0)
			continue;

		// Remove the device from the bimap.
		if (bt_address_iter != m_bt_address_dbus_object_paths.right.end())
		{
			m_bt_address_dbus_object_paths.right.erase(bt_address_iter);
			bt_address_iter = m_bt_address_dbus_object_paths.right.end();
		}

		// Invoke m_on_device_is_gone and catch any thrown
		// exceptions. It is important to do that, since we
		// reach this point after dbus_connection_signal_cb()
		// was called by GLib, and an exception traveling
		// through there results in undefined behavior.
		if (m_on_device_is_gone)
		{
			try
			{
				m_on_device_is_gone(bdaddr);
			}
			catch (comboctl::exception const &exc)
			{
				LOG(error, "Caught exception: {}", exc.what());
			}
		}
	}
}


void adapter::process_dbus_object_interface_property_changes(gchar const *object_path, gchar const *interface_name, GVariant *property_changes_dict_variant)
{
	if (g_strcmp0(interface_name, "org.bluez.Device1") != 0)
		return;

	auto bt_address_iter = m_bt_address_dbus_object_paths.right.find(object_path);
	if (bt_address_iter == m_bt_address_dbus_object_paths.right.end())
	{
		LOG(trace, "No device with D-Bus object path {} known; not checking property modifications", object_path);
		return;
	}

	bluetooth_address const &bdaddr = bt_address_iter->second;

	GVariant *paired_value_variant = g_variant_lookup_value(property_changes_dict_variant, "Paired", nullptr);
	if (paired_value_variant == nullptr)
	{
		LOG(trace, "Property changes for D-Bus object {} contain no changes to the Paired value; ignoring changes", object_path);
		return;
	}
	if (!g_variant_is_of_type(paired_value_variant, G_VARIANT_TYPE_BOOLEAN))
	{
		LOG(trace, "Property changes for D-Bus object {} contain changes to the Paired value, but value is not a boolean; ignoring changes", object_path);
		return;
	}

	bool is_paired = g_variant_get_boolean(paired_value_variant);

	LOG(
		trace,
		"Paired status of device with Bluetooth address {} and D-Bus object path {} is now: {}",
		comboctl::to_string(bdaddr),
		object_path,
		is_paired
	);

	try
	{
		m_on_found_new_device(bdaddr, is_paired);
	}
	catch (comboctl::exception const &exc)
	{
		LOG(error, "Caught exception: {}", exc.what());
	}
}


void adapter::dbus_connection_signal_cb(gchar const *object_path, gchar const *interface_name, gchar const *signal_name, GVariant *parameters)
{
	if (g_strcmp0(interface_name, "org.freedesktop.DBus.ObjectManager") == 0)
	{
		if (g_strcmp0(signal_name, "InterfacesAdded") == 0)
		{
			// An interface was added to a D-Bus object. This is how we
			// can find devices that got detected by BlueZ. When one is
			// detected, BlueZ creates a new D-Bus object and adds an
			// org.bluez.Device1 interface to it.

			gchar *added_if_object_path;
			GVariant *interfaces_dict_variant;
			g_variant_get(parameters, "(o*)", &added_if_object_path, &interfaces_dict_variant);

			auto dict_variant_guard = make_scope_guard([&]() {
				g_free(added_if_object_path);
				g_variant_unref(interfaces_dict_variant);
			});

			process_added_dbus_object_interfaces(added_if_object_path, interfaces_dict_variant);
		}
		else if (g_strcmp0(signal_name, "InterfacesRemoved") == 0)
		{
			// An interface was removed from a D-Bus object. This happens
			// most notably when an object is removed, for example because
			// the Bluetooth device was deleted from the list of known
			// devices. In that case, we want to remove that device from
			// the m_bt_address_dbus_object_paths bimap. We also inform
			// the outside world of this by invoking m_on_device_is_gone
			// if that callback is set.

			gchar *added_if_object_path;
			GVariant *interfaces_array_variant;
			g_variant_get(parameters, "(o*)", &added_if_object_path, &interfaces_array_variant);

			auto dict_variant_guard = make_scope_guard([&]() {
				g_free(added_if_object_path);
				g_variant_unref(interfaces_array_variant);
			});

			process_removed_dbus_object_interfaces(added_if_object_path, interfaces_array_variant);
		}
	}
	else if (g_strcmp0(interface_name, "org.freedesktop.DBus.Properties") == 0)
	{
		if (g_strcmp0(signal_name, "PropertiesChanged") == 0)
		{
			// A D-Bus object's properties got changed. We check this
			// to see if the paired status changed.

			gchar *changed_interface_name;
			GVariant *property_changes_dict_variant;
			GVariantIter *removed_properties_iter;
			g_variant_get(parameters, "(s*as)", &changed_interface_name, &property_changes_dict_variant, &removed_properties_iter);

			auto dict_variant_guard = make_scope_guard([&]() {
				g_free(changed_interface_name);
				g_variant_unref(property_changes_dict_variant);
				g_variant_iter_free(removed_properties_iter);
			});

			process_dbus_object_interface_property_changes(object_path, changed_interface_name, property_changes_dict_variant);
		}
	}
}


gvariant_uptr adapter::get_managed_bluez_objects()
{
	GError *error = nullptr;

	// Look up what Bluetooth devices BlueZ already knows
	// of (that is, were discovered earlier already).
	GVariant *retval = g_dbus_connection_call_sync(
		m_dbus_connection,
		"org.bluez",
		"/",
		"org.freedesktop.DBus.ObjectManager",
		"GetManagedObjects",
		nullptr,
		G_VARIANT_TYPE("(a{oa{sa{sv}}})"),
		G_DBUS_CALL_FLAGS_NONE,
		-1,
		nullptr,
		&error
	);
	if (error != nullptr)
	{
		LOG(error, "Could not get managed objects: {}", error->message);
		send_discovery_call(false);
		throw gerror_exception(error);
	}

	return make_gvariant_uptr(retval);
}


} // namespace comboctl end
