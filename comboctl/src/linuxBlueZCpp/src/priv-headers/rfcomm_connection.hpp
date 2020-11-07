#ifndef COMBOCTL_RFCOMM_CONNECTION_HPP
#define COMBOCTL_RFCOMM_CONNECTION_HPP

#include <glib.h>
#include <gio/gio.h>
#include <array>
#include <vector>
#include <cstdint>
#include <mutex>
#include <condition_variable>
#include "types.hpp"


namespace comboctl
{


/**
 * Class for initializing and operating a Bluetooth RFCOMM client connection.
 *
 * Establish the connection using connect(). This function blocks; to abort
 * a connection attempt, call disconnect(). send() and receive() too block
 * and have cancel_send() / cancel_receive() functions to cancel ongoing
 * send / receive operations.
 */
class rfcomm_connection
{
public:
	/**
	 * Constructor.
	 *
	 * Sets up internal states. To actually connect, use the connect() function.
	 */
	rfcomm_connection();

	/**
	 * Destructor.
	 *
	 * Cleans up internal states and calls disconnect().
	 */
	~rfcomm_connection();

	// Disable copy semantics for this class.
	rfcomm_connection(rfcomm_connection const &) = delete;
	rfcomm_connection& operator = (rfcomm_connection const &) = delete;

	/**
	 * Perform a blocking connect.
	 *
	 * This blocks until an error occurs, disconnect() is called, or the connection
	 * is established.
	 *
	 * @param device_address Bluetooth address of device to connect to.
	 * @param rfcomm_channel RFCOMM channel to use for the connection. Must be at least 1.
	 * @throws invalid_call_exception if the connection was already established.
	 * @throws io_exception in case of an IO error.
	 */
	void connect(bluetooth_address const &device_address, unsigned int rfcomm_channel);

	/**
	 * Terminates an existing connection.
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
	void disconnect_impl(bool is_shutting_down);

	GSocket *m_socket;
	GCancellable *m_send_cancellable;
	GCancellable *m_receive_cancellable;
	std::array<int, 2> m_connect_pipe_fds;
	std::mutex m_connect_pipe_mutex;
	std::condition_variable m_connecting_condvar;
	bool m_is_connecting;
	bool m_is_shutting_down;
};


} // namespace comboctl end


#endif // COMBOCTL_RFCOMM_CONNECTION_HPP
