#ifndef COMBOCTL_RFCOMM_LISTENER_HPP
#define COMBOCTL_RFCOMM_LISTENER_HPP

#include <glib.h>
#include <gio/gio.h>


namespace comboctl
{


/**
 * Class for initializin and operating an RFCOMM listner socket.
 *
 * This is needed for setting up an SDP service the Combo looks for.
 * Beyond that, the socket isn't used; no send/receive operations
 * are performed through it.
 *
 * Of particular importance is its ability to pick an unused RFCOMM
 * channel automatically for the SDP service (which needs to have
 * an RFCOMM channel assigned).
 */
class rfcomm_listener
{
public:
	/**
	 * Constructor.
	 *
	 * Sets up internal states. To actually start listening, use
	 * the listen() function.
	 */
	rfcomm_listener();

	/**
	 * Destructor.
	 *
	 * Cleans up internal states and calls stop_listening().
	 */
	~rfcomm_listener();

	/**
	 * Starts listening by setting up an RFCOMM listener socket.
	 *
	 * Optionally, a specific RFCOMm channel for the listener
	 * socket to listen to can be used. The default channel is
	 * the special value 0, which instructs the function to
	 * pick the next available RFCOMM channel in the system.
	 * If 0 is used, get_channel() returns the channel that
	 * was picked (not 0).
	 *
	 * @param rfcomm_channel RFCOMM channel to use for listening,
	 *        or 0 to automatically pick the next unused one.
	 * @throws invalid_call_exception if there is already a listening socket.
	 * @throws io_exception in case of an IO error.
	 * @throws gerror_exception in case of a GLib/GIO error.
	 */
	void listen(unsigned int rfcomm_channel = 0);

	/**
	 * Stops listening for incoming RFCOMM connections and shuts
	 * down the listener socket.
	 *
	 * If listening isn't active, this call does nothing.
	 */
	void stop_listening();

	/**
	 * Returns the RFCOMM channel that the listener socket is listening to.
	 *
	 * This value has no meaning if there is currently no listening socket.
	 *
	 * If the special channel 0 was specified in the listen() call, this
	 * returns the actual channel that was picked by listen().
	 */
	unsigned int get_channel() const;


private:
	void close_socket_listener();

	GSocketListener *m_socket_listener;
	GCancellable *m_socket_listener_accept_cancellable;

	unsigned int m_rfcomm_channel;
};


} // namespace comboctl end


#endif // COMBOCTL_RFCOMM_LISTENER_HPP
