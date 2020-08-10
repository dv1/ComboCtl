#ifndef COMBOCTL_ADAPTER_HPP
#define COMBOCTL_ADAPTER_HPP

#include <functional>
#include <string>
#include <optional>
#include <glib.h>
#include <gio/gio.h>
#include <memory>
#include <boost/bimap.hpp>
#include "types.hpp"
#include "glib_misc.hpp"


namespace comboctl
{


/**
 * BlueZ Bluetooth adapter interface for discovery and for removing (= unpairing) devices.
 *
 * This requires a running GLib mainloop in order to function properly.
 */
class adapter
{
public:
	/**
	 * Constructor.
	 *
	 * Sets up internal initial states.
	 */
	adapter();

	/**
	 * Destructor.
	 *
	 * Calls teardown() internally.
	 */
	~adapter();

	/**
	 * Subscribes to BlueZ signals coming over D-Bus using the
	 * specified D-Bus connection.
	 *
	 * @param dbus_connection D-Bus connection to use. Must not be null.
	 * @throws invalid_call_exception if this adapter is already subscribed.
	 * @throws io_exception in case of an IO error.
	 * @throws gerror_exception if something D-Bus related or GLib related fails.
	 */
	void setup(GDBusConnection *dbus_connection);

	/**
	 * Unsubscribes this adapter from getting BlueZ signal over D-Bus.
	 *
	 * This also stops any ongoing discovery process.
	 *
	 * If this adapter isn't subscribed, this function does nothing.
	 */
	void teardown();

	/**
	 * Asynchronously starts the Bluetooth discovery process.
	 *
	 * This will invoke the on_found_new_device callback for every
	 * device that was found. This includes already paired devices.
	 *
	 * If during discovery a device is removed, on_device_is_gone
	 * is invoked.
	 *
	 * @param on_found_new_device Callback invoked whenever a new
	 *        device is found. The device's Bluetooth address and
	 *        pairing status are given to the callback.
	 *        This argument must be set to a valid function.
	 * @param on_device_is_gone Callback invoked whenever a device
	 *        is removed from BlueZ's list of known devices. The
	 *        device's Bluetooth address is given to the callback.
	 *        This argument is optional. The default value disables
	 *        this callback.
	 * @throws invalid_call_exception If the discovery is already ongoing.
	 * @throws gerror_exception if something D-Bus related or GLib related fails.
	 */
	void start_discovery(
		found_new_device_callback on_found_new_device,
		device_is_gone_callback on_device_is_gone = device_is_gone_callback()
	);

	/**
	 * Stops the discovery process.
	 *
	 * If no discovery is going on, this function does nothing.
	 *
	 * The destructor automatically calls this function.
	 *
	 * @throws gerror_exception if something D-Bus related or GLib related fails.
	 */
	void stop_discovery();

	/**
	 * Removes a device from the list of paired Bluetooth devices.
	 *
	 * @param device_address Bluetooth address of the device to remove/unpair.
	 */
	void remove_device(bluetooth_address const &device_address);


private:
	void send_discovery_call(bool do_start);

	void process_added_dbus_object_interfaces(gchar const *object_path, GVariant *interfaces_dict_variant);
	void process_removed_dbus_object_interfaces(gchar const *object_path, GVariant *interfaces_array_variant);
	void process_dbus_object_interface_property_changes(gchar const *object_path, gchar const *interface_name, GVariant *property_changes_dict_variant);

	void dbus_connection_signal_cb(gchar const *object_path, gchar const *interface_name, gchar const *signal_name, GVariant *parameters);

	gvariant_uptr get_managed_bluez_objects();


	found_new_device_callback m_on_found_new_device;
	device_is_gone_callback m_on_device_is_gone;

	GDBusConnection *m_dbus_connection;
	GDBusProxy *m_adapter_proxy;
	guint m_dbus_connection_signal_subscription;

	bool m_discovery_started;

	typedef boost::bimap<bluetooth_address, std::string> bt_address_dbus_object_paths_map;
	bt_address_dbus_object_paths_map m_bt_address_dbus_object_paths;
};


} // namespace comboctl end


#endif // COMBOCTL_ADAPTER_HPP
