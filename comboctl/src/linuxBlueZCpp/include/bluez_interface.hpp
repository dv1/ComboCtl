#ifndef COMBOCTL_BLUEZ_INTERFACE_HPP
#define COMBOCTL_BLUEZ_INTERFACE_HPP

#include <memory>
#include <array>
#include <functional>
#include "types.hpp"


namespace comboctl
{


class bluez_interface;
class rfcomm_connection;
struct bluez_interface_priv;


/**
 * Class representing and allowing access to a Bluetooth device via BlueZ.
 *
 * Instances of this class are created by bluez_interface::get_device().
 *
 * It provides function to send and receive data through an RFCOMM channel.
 * The send() and receive() functions block. To cancel them, corresponding
 * cancel_send() and cancel_receive() functions are availabl. disconnect()
 * implicitely calls these two functions.
 *
 * Instantiating this class does not automatically connect it. connect()
 * has to be called for that purpose. This is done that way to be able to
 * cancel a connect attempt, since connect() blocks. disconnect() cancels
 * any ongoing connect attempt, and is called by the destructor.
 */
class bluez_bluetooth_device
{
friend class bluez_interface;
public:
	/**
	 * Destructor.
	 *
	 * Cleans up internal states and calls disconnect().
	 */
	~bluez_bluetooth_device();

	/**
	 * Sets up an RFCOMM connection to the Bluetooth device.
	 *
	 * This blocks until an error occurs, disconnect() is called, or the connection
	 * is established.
	 *
	 * @throws invalid_call_exception if the connection was already established.
	 * @throws io_exception in case of an IO error.
	 */
	void connect();

	/**
	 * Terminates an existing RFCOMM connection.
	 *
	 * It is safe to call this from another thread. Doing so aborts an ongoing
	 * connect() call. In fact, this is the proper way to cancel the connection
	 * operation.
	 *
	 * If there is no connection, this call does nothing.
	 */
	void disconnect();

	/**
	 * Sends a sequence of bytes over RFCOMM.
	 *
	 * This blocks until all of the bytes were sent, cancel_send() was called,
	 * disconnect() was called, or an error occurs.
	 *
	 * @param src Source to get the bytes from. Must be a valid pointer,
	 *        and the region this points to must contain the specified
	 *        amount of bytes, otherwise crashes can occur.
	 * @param num_bytes Number of bytes to send. Must not be zero.
	 * @throws gerror_exception in case of a GLib/GIO error (including when the
	 *         operation is canceled due to a disconnect() or cancel_send() call;
	 *         check if the GError category is G_IO_ERROR and the error ID is
	 *         G_IO_ERROR_CANCELLED).
	 */
	void send(void const *src, int num_bytes);

	/**
	 * Receives a sequence of bytes over RFCOMM.
	 *
	 * This blocks until some the bytes were received (up to the amount specified
	 * by num_bytes), cancel_receive() was called, disconnect() was called, or an
	 * error occurs.
	 *
	 * @param dest Destination to put the received bytes into. Must be a valid
	 *        pointer, and the region this points to must have enough capacity
	 *        to contain at least num_bytes bytes, otherwise buffer overflow
	 *        errors can occur.
	 * @param num_bytes Maximum number of bytes to receive. Must not be zero.
	 * @return Actual number of bytes received. This is always <= num_bytes.
	 * @throws gerror_exception in case of a GLib/GIO error (including when the
	 *         operation is canceled due to a disconnect() or cancel_send() call;
	 *         check if the GError category is G_IO_ERROR and the error ID is
	 *         G_IO_ERROR_CANCELLED).
	 */
	int receive(void *dest, int num_bytes);

	/**
	 * Cancels any ongoing send operation.
	 *
	 * If no send operation is ongoing, this effectively does nothing.
	 */
	void cancel_send();

	/**
	 * Cancels any ongoing receive operation.
	 *
	 * If no receive operation is ongoing, this effectively does nothing.
	 */
	void cancel_receive();


private:
	explicit bluez_bluetooth_device(bluetooth_address const &bt_address, unsigned int rfcomm_channel);

	bluetooth_address const m_bt_address;
	unsigned int const m_rfcomm_channel;
	std::unique_ptr<rfcomm_connection> m_connection;
};

typedef std::unique_ptr<bluez_bluetooth_device> bluez_bluetooth_device_uptr;


/**
 * Simple high level interface to BlueZ.
 *
 * This provides functionality for discovery, pairing,
 * and for getting access to Bluetooth devices.
 */
class bluez_interface
{
public:
	/**
	 * Constructor.
	 *
	 * Establishes a D-Bus connection to BlueZ and starts
	 * an internal thread to handle notifications and events.
	 *
     * @throws io_exception in case of an IO error.
     * @throws gerror_exception in case of a GLib/GIO error,
     *         particularly if BlueZ cannot be connected to.
	 */
	bluez_interface();

	/**
	 * Destructor.
	 *
	 * Calls teardown().
	 */
	~bluez_interface();

	/**
	 * Tears down and previously set up states and D-Bus connection.
	 *
	 * After this was called, this object cannot be used anymore.
	 *
	 * This function mainly exists as part of the public API since
	 * Kotlin does not have deterministic destructors, and relying
	 * on finalizers is generally not recommended, partially because
	 * they aren't deterministic.
	 */
	void teardown();

	typedef std::function<void()> thread_func;

	/**
	 * Runs the specified function in the internal thread.
	 *
	 * This is mainly useful if some thread specific function needs
	 * to be run for JNI bindings.
	 *
	 * @param func Function to run in the internal thread. Must be valid.
	 */
	void run_in_thread(thread_func func);

	/**
	 * Sets a function to run when the internal thread finishes.
	 *
	 * This is mainly useful if some thread specific function needs
	 * to be run for JNI bindings when a thread is finished.
	 *
	 * @param func Function to run in when the internal thread finishes.
	 *        A default-constructed thread_func instance can be used to
	 *        turn off this callback.
	 */
	void on_thread_stopping(thread_func func);

	/**
	 * Asynchronously starts the Bluetooth discovery process, sets up
	 * an SDP service record so the Combo can find the BlueZ adapter,
	 * and sets up a BlueZ agent for pairing and authentication.
	 *
	 * This is essentially a combination of what the agent, adapter,
	 * rfcomm_listener and sdp_service classes do.
	 *
	 * Once the SDP service record is set up, the Combo can find
	 * this platform while it scans for Bluetooth devices.
	 *
	 * This sets up a service record of the SerialPort class. Internally,
	 * an RFCOMM listener channel will be set up to be able to assign its
	 * channel number to the SerialPort SDP service record.
	 *
	 * This will also invoke the on_found_new_device callback for every
	 * device that was found and is paired already. Unpaired devices
	 * are ignored until they get paired.
	 *
	 * If during discovery a device is removed, on_device_is_gone
	 * is invoked.
	 *
	 * @param service_name Name the SDP service record shall use.
	 *        Must not be empty.
	 * @param service_provider Name of the provider that shall be added
	 *        to the SDP service record. Must not be empty.
	 * @param service_description Additional human-readable description of
	 *        the SDP service record. Will be added to the record.
	 *        Must not be empty.
	 * @param pairing_pin_code PIN code to use for authenticating pairing requests.
	 * @param on_discovery_started Callback to be invoked as soon as the
	 *        discovery started. Useful for setting up resources that are
	 *        used during discovery.
	 * @param on_discovery_stopped Callback to be invoked as soon as the
	 *        discovery stopped. Useful for tearing down resources that are
	 *        used during discovery.
	 * @param on_found_new_device Callback invoked whenever a new
	 *        paired device is found. The device's Bluetooth address
	 *        is given to the callback.
	 *        This argument must be set to a valid function.
	 * @param on_device_is_gone Callback invoked whenever a device
	 *        is removed from BlueZ's list of known devices. The
	 *        device's Bluetooth address is given to the callback.
	 *        This argument is optional. The default value disables
	 *        this callback.
	 * @param on_filter_device Callback for filtering out devices
	 *        based on their address. See the filter_device_callback
	 *        documentation for more.
	 * @throws invalid_call_exception If the discovery is already ongoing.
	 * @throws io_exception in case of an IO error.
	 * @throws gerror_exception if something D-Bus related or GLib related fails.
	 */
	void start_discovery(
		std::string sdp_service_name,
		std::string sdp_service_provider,
		std::string sdp_service_description,
		std::string bt_pairing_pin_code,
		thread_func on_discovery_started,
		thread_func on_discovery_stopped,
		found_new_paired_device_callback on_found_new_device,
		device_is_gone_callback on_device_is_gone,
		filter_device_callback on_filter_device
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
	 * Removes any existing pairing between BlueZ and the specified device.
	 *
	 * If the device isn't paired, or if no device with such an address
	 * is known to BlueZ, this does nothing.
	 *
	 * @param device_address Address of the Bluetooth device to unpair.
	 */
	void unpair_device(bluetooth_address device_address);

	/**
	 * Creates and returns a BlueZ Bluetooth device instance.
	 *
	 * The instance is returned as the bluez_bluetooth_device_uptr smart pointer.
	 *
	 * This creates the instance internally, and sets up its states, but does
	 * not immediately connect to the device. Use bluez_bluetooth_device::connect()
	 * for that purpose.
	 *
	 * NOTE: Creating multiple instances to the same device is possible, but untested.
	 *
	 * @param device_address Address of the Bluetooth device to create the instanc for.
	 */
	bluez_bluetooth_device_uptr get_device(bluetooth_address device_address);

    /**
     * Returns the friendly (= human-readable) name for the adapter.
     */
	std::string get_adapter_friendly_name() const;


private:
	void setup();

	std::unique_ptr<bluez_interface_priv> m_priv;
};



} // namespace comboctl end


#endif // COMBOCTL_BLUEZ_INTERFACE_HPP
