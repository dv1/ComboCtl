#ifndef COMBOCTL_AGENT_HPP
#define COMBOCTL_AGENT_HPP

#include <glib.h>
#include <gio/gio.h>
#include <memory>
#include <string>
#include "types.hpp"


namespace comboctl
{


/**
 * BlueZ Bluetooth agent interface for authenticating pairing requests.
 *
 * This requires a running GLib mainloop in order to function properly.
 */
class agent
{
public:
	/**
	 * Constructor.
	 *
	 * Sets up internal initial states.
	 */
	agent();

	/**
	 * Destructor.
	 *
	 * Calls teardown() internally.
	 */
	~agent();

	/**
	 * Registers this agent in BlueZ as the default agent for incoming
	 * pairing requests.
	 *
	 * This also subscribes to BlueZ signals coming over D-Bus using the
	 * specified D-Bus connection.
	 *
	 * @param dbus_connection D-Bus connection to use. Must not be null.
	 * @param pairing_pin_code PIN code to use for authenticating pairing requests.
	 * @param on_filter_device Optional callback to filter devices prior to
	 *        authorizing them. Devices that get filtered out (= callback returns
	 *        false) are rejected by the agent. By default, all devices are accepted.
	 * @throws invalid_call_exception If the agent was set up already.
	 * @throws gerror_exception if something D-Bus related or GLib related fails.
	 */
	void setup(
		GDBusConnection *dbus_connection,
		std::string pairing_pin_code,
		filter_device_callback on_filter_device = filter_device_callback()
	);

	/**
	 * Unregisters this agent from BlueZ, and unsubscribes it from D-Bus.
	 *
	 * If this agent isn't registered, this function does nothing.
	 */
	void teardown();


private:
	void handle_agent_method_call(GDBusConnection *, gchar const *sender, gchar const *object_path, gchar const *interface_name, gchar const *method_name, GVariant *parameters, GDBusMethodInvocation *invocation);


	std::string m_pairing_pin_code;

	filter_device_callback m_on_filter_device;

	GDBusConnection *m_dbus_connection;
	GDBusProxy *m_agent_manager_proxy;
	guint m_agent_object_id;
	bool m_agent_registered;
};


} // namespace comboctl end


#endif // COMBOCTL_AGENT_HPP
