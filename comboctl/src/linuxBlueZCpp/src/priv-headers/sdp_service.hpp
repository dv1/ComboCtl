#ifndef COMBOCTL_BLUEZ_SDP_SERVICE_HPP
#define COMBOCTL_BLUEZ_SDP_SERVICE_HPP

#include <string>
#include <glib.h>
#include <gio/gio.h>


namespace comboctl
{


/**
 * Sets up an SDP service record with the provided details.
 *
 * The Combo looks for a service record with a specific name
 * and of the SerialPort service class. This class sets up such
 * a service record with attributes containing these details.
 *
 * An RFCOMM listener channel number is required for valid
 * SerialPort services. The rfcomm_listener class is used
 * for establishing such a listener.
 */
class sdp_service
{
public:
	/**
	 * Constructor.
	 *
	 * Sets up internal states. To actually set up the service
	 * use the setup() function.
	 */
	sdp_service();

	/**
	 * Destructor.
	 *
	 * Cleans up internal states and calls teardown().
	 */
	~sdp_service();

	/**
	 * Sets up the SDP service record and subscribes this object to D-Bus.
	 *
	 * Once the SDP service record is set up, the Combo can find
	 * this platform while it scans for Bluetooth devices.
	 *
	 * This sets up a service record of the SerialPort class. This
	 * class requires an RFCOMM channel number. To have a valid
	 * RFCOMM channel, use the rfcomm_listener class.
	 *
	 * @param dbus_connection D-Bus connection to use. Must not be null.
	 * @param service_name Name the SDP service record shall use.
	 *        Must not be empty.
	 * @param service_provider Name of the provider that shall be added
	 *        to this service record. Must not be empty.
	 * @param service_description Additional human-readable description of
	 *        the service. Will be added to the record. Must not be empty.
	 * @param rfcomm_channel RFCOMM channel number to add to this SerialPort
	 *        SDP service record. Must not be 0.
	 * @throws invalid_call_exception if this SDP service record is already
	 *         set up.
	 */
	void setup(GDBusConnection *dbus_connection, std::string service_name, std::string service_provider, std::string service_description, unsigned int rfcomm_channel);

	/**
	 * Tears down the SDP service record, and unsubscribes this object from D-Bus.
	 */
	void teardown();


private:
	GDBusConnection *m_dbus_connection;
	GDBusProxy *m_profile_manager_proxy;
	guint m_profile_object_id;
	bool m_profile_registered;
};


} // namespace comboctl end


#endif // COMBOCTL_BLUEZ_SDP_SERVICE_HPP
