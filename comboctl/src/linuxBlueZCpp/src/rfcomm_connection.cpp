#include <assert.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <poll.h>
#include <cerrno>
#include <bluetooth/bluetooth.h>
#include <bluetooth/rfcomm.h>
#include "scope_guard.hpp"
#include "rfcomm_connection.hpp"
#include "exception.hpp"
#include "gerror_exception.hpp"
#include "bluez_misc.hpp"
#include "log.hpp"


DEFINE_LOGGING_TAG("RfcommConnection")


namespace comboctl
{


namespace
{


void set_fd_blocking(int fd, bool blocking)
{
	int flags = fcntl(fd, F_GETFL, 0);
	assert(flags >= 0);

	flags = blocking ? (flags & ~O_NONBLOCK) : (flags | O_NONBLOCK);

	int posix_ret = fcntl(fd, F_SETFL, flags);
	assert(posix_ret == 0);
}


} // unnamed namespace end


rfcomm_connection::rfcomm_connection()
	: m_socket(nullptr)
	, m_is_connecting(false)
	, m_is_shutting_down(false)
{
	// GLib cancellables so we can abort send/receive attempts later.
	m_send_cancellable = g_cancellable_new();
	m_receive_cancellable = g_cancellable_new();

	// We create a POSIX pipe to be able to use the self-pipe trick
	// in the connect() function. See the comments there for more.
	int posix_ret = pipe(&m_connect_pipe_fds[0]);
	// We do not expect this to ever fail. If we do, we reached hard
	// system-wide resource limits, and can't really do anything.
	assert(posix_ret == 0);

	// Mark the read end of the pipe as non-blocking. We need this
	// in connect() so we can "flush" the pipe by attempting to
	// read out any stale data in it until the read() call reports
	// an EAGAIN error.
	int fd_flags = fcntl(m_connect_pipe_fds[0], F_GETFL, 0);
	fcntl(m_connect_pipe_fds[0], F_SETFL, fd_flags | O_NONBLOCK);
}


rfcomm_connection::~rfcomm_connection()
{
	// Strictly speaking, the description of this destructor isn't
	// 100% accurate. We don't call disconnect(), we call disconnect_impl().
	// This is also what disconnect() does, but we do it that way so
	// we can set the m_is_shutting_down flag in a thread safe manner.
	disconnect_impl(true);

	g_object_unref(G_OBJECT(m_send_cancellable));
	g_object_unref(G_OBJECT(m_receive_cancellable));

	::close(m_connect_pipe_fds[0]);
	::close(m_connect_pipe_fds[1]);
}


void rfcomm_connection::connect(bluetooth_address const &bt_address, unsigned int rfcomm_channel)
{
	// In here, we first set up the RFCOMM socket directly via
	// POSIX functions, then we hand over the POSIX file descriptor
	// to a GLib GSocket. Currently, GLib has no functions for
	// setting up an RFCOMM socket, so we have to do this on our
	// own. And, due to this fact, we also have to implement
	// a custom way to be able to cancel a connect attempt, since
	// GCancellable only works with GLib. To that end, we use
	// a POSIX pipe (created in the constructor), together with
	// a poll() call. The poll() call wakes up in one of these
	// cases:
	//
	// 1. The connection is established.
	// 2. An error occurs during the connection attempt.
	// 3. The receiving end of the pipe receives some data.
	// 4. A POSIX signal interrupts the poll() system call.
	//
	// In case 4, poll() returns EINTR, and we just repeat the
	// poll() call.
	// Case 3 happens when disconnect() is called while poll()
	// is waiting. This is how cancellability is implemented -
	// when one wants to abort the connection attempt by calling
	// disconnect(), a dummy message is sent through the pipe,
	// which wakes up poll(). Afterwards, we can check what
	// happened, and see that a dummy message was sent through
	// the pipe. This implies that the connection attempt is to
	// be aborted, so we exit immediately.
	//
	// To check for cases 1 and 2, we have to perform various
	// calls to see if an error occurred.
	// Also, prior to the POSIX ::connect() call (not to be
	// confused with our connect() function), the socket has to
	// be set to the non-blocking mode to be able to work with
	// poll(). In non-blocking mode, POSIX connect() returns
	// 0 or EINPROGRESS, since in that mode, the connection
	// process happens in the background (since it is not
	// supposed to block). poll() then gets notified once the
	// connection process finished, or an error occurred.


	GError *gerror = nullptr;
	GSocket *rfcomm_gsocket = nullptr;
	int socket_fd = -1;


	assert(rfcomm_channel >= 1);


	if (m_socket != nullptr)
		throw invalid_call_exception("Connection already established");


	LOG(debug, "Attempting to open RFCOMM connection to device {} on channel {}", to_string(bt_address), rfcomm_channel);


	// Establish scope guards to make sure resources are cleaned
	// up in case of an error. They are dismissed later, when
	// these resources are handed over to other entities that
	// take care of ownership.

	auto rfcomm_fd_guard = make_scope_guard([&]() {
		if (socket_fd > 0)
		{
			close(socket_fd);
			socket_fd = -1;
		}
	});

	auto rfcomm_gsocket_guard = make_scope_guard([&]() {
		if (rfcomm_gsocket != nullptr)
		{
			g_object_unref(G_OBJECT(rfcomm_gsocket));
			rfcomm_gsocket = nullptr;
		}
	});


	// We must surround the rest of this function's code with
	// a mutex to make sure it cannot run at the same time
	// when disconnect() is called. This is because we have
	// to give the dummy message that gets transmitted by
	// disconnect() a chance to actually reach the poll()
	// call below.
	//
	// Keep in mind that disconnect() _first_ sends the
	// dummy message over the pipe, and _then_ attempts
	// to lock this mutex.
	//
	// There are these possible cases:
	//
	// 1. connect() is called first, and disconnect() shortly
	//    afterwards, but right _after_ connect() locks the mutex.
	//    disconnect() sends the dummy message, then attempts
	//    to lock the mutex, meaning that the thread calling
	//    disconnect() will be suspended.
	//    Meanwhile, the while loop with the poll() call runs,
	//    notices the activity in the pipe, poll() exits, and
	//    the code below reacts to the dummy message by aborting
	//    the connection attempt.
	//    When this function finishes, m_is_connecting is set
	//    to false, and  m_connecting_condvar is notified.
	//    The mutex is then released. The previously suspended
	//    thread now continues to run disconnect(). There, we
	//    see that m_is_connecting is set to false now, so the
	//    while loop which waits using m_connecting_condvar
	//    is not entered. Instead, disconnect() finishes.
	//
	// 2. connect() is called first, and disconnect() shortly
	//    afterwards, but right _before_ connect() locks the mutex.
	//    This means that disconnect gets to lock the mutex first.
	//    disconnect() then enters its while loop that simply
	//    calls m_connecting_condvar's wait() function (which
	//    unlocks the mutex). In other words, this simply lets
	//    the thread calling disconnect() wait until a notification
	//    is sent over m_connecting_condvar. The rest continues
	//    as in case #1 above, except that here, the thread
	//    running disconnect() is woken up by m_connecting_condvar's
	//    notification.
	//
	//  3. disconnect() is called before connect(). In this
	//     case, there will be data present in the pipe at the
	//     time connect() runs (because disconnect() put data
	//     in there). disconnect() will either exit immediately
	//     if it is done before connect() locks the mutex
	//     (because m_is_connecting is still set to false then),
	//     or waits until connect() is done (because connect()
	//     then had a chance to set to m_is_connecting to true).
	//
	//  In all cases, it is ensured that disconnect() does not
	//  exit prematurely.
	//
	// There is one special case related to case #3:
	// If connect() gets called while the destructor runs, it is
	// possible that the connect() call happens right after the
	// destructor is done calling disconnect(). This leads to a
	// race condition, because the destructor removes all sockets
	// and pipes. So, in this case, disconnect() (or to be more
	// exact, disconnect_impl()) is instructed to set the
	// m_is_shutting_down flag. This prevents connect() from
	// actually doing anything; instead, it exits early.
	std::unique_lock<std::mutex> lock(m_connect_pipe_mutex);

	// Abort in case of a shutdown by simply returning, not by
	// throwing an exception. Throwing exceptions during a shutdown
	// is not only not very useful, it can lead to serious problems.
	if (m_is_shutting_down)
	{
		LOG(debug, "Aborting connection attempt since we are shutting down");
		return;
	}

	// Flush the pipe to get rid of stale data by reading it all.
	{
		char dummy_buf[1024];
		bool flush_pipe = true;
		while (flush_pipe)
		{
			if (::read(m_connect_pipe_fds[0], dummy_buf, sizeof(dummy_buf)) < 0)
			{
				switch (errno)
				{
					case 0:
					case EAGAIN:
						flush_pipe = false;
						break;

					default:
						throw io_exception(fmt::format("IO error while flushing internal pipe: {} ({})", std::strerror(errno), errno));
				}
			}
		}
	}

	// Install a special scope guard to make sure the condition
	// variable is always notified when this function finishes.
	// Otherwise, an early exit here (or a thrown exception)
	// could lead to a deadlock, because the m_connecting_condvar
	// wait() call in disconnect() would never stop waiting.
	m_is_connecting = true;
	auto is_connecting_flag_guard = make_scope_guard([&]() {
		m_is_connecting = false;
		m_connecting_condvar.notify_one();
	});

	// Create the POSIX RFCOMM socket.
	socket_fd = socket(AF_BLUETOOTH, SOCK_STREAM, BTPROTO_RFCOMM);
	if (socket_fd < 0)
		throw io_exception(fmt::format("Could not create RFCOMM socket: {} ({})", std::strerror(errno), errno));

	// Copy the Bluetooth address bytes into the bdaddr_t structure
	// that is used in the sockaddr_rc structure. Note that bdaddr_t
	// stores the bytes in opposite order.
	bdaddr_t bdaddr;
	for (int i = 0; i < 6; ++i)
		bdaddr.b[i] = bt_address[5 - i];

	// From https://stackoverflow.com/a/16010670/560774 :
	//
	//   Functions that expect a pointer to struct sockaddr probably typecast
	//   the pointer you send them to sockaddr when you send them a pointer to
	//   struct sockaddr_storage. In that way, they access it as if it was a
	//   struct sockaddr.
	//
	//   struct sockaddr_storage is designed to fit in both a struct sockaddr_in
	//   and struct sockaddr_in6
	//
	//   You don't create your own struct sockaddr, you usually create a struct
	//   sockaddr_in or a struct sockaddr_in6 depending on what IP version you're
	//   using. In order to avoid trying to know what IP version you will be using,
	//   you can use a struct sockaddr_storage which can hold either. This will in
	//   turn be typecasted to struct sockaddr by the connect(), bind(), etc
	//   functions and accessed that way.
	//
	// We use sockaddr_rc here, not sockaddr_in or sockaddr_in6, but this information
	// still applies.
	//
	// We also do a safety check to make sure sockaddr_storage is really big enough
	// to hold Bluetooth RFCOMM socket address data. This should always be true;
	// if it isn't, something is very wrong with the system this code is being built
	// for. It is a simple, one-line compile-time check, so it doesn't hurt to have it.

	static_assert(sizeof(struct sockaddr_rc) <= sizeof(struct sockaddr_storage));
	struct sockaddr_storage sock_addr = {};
	struct sockaddr_rc *rfcomm_addr = reinterpret_cast<struct sockaddr_rc *>(&sock_addr);
	rfcomm_addr->rc_family = AF_BLUETOOTH;
	rfcomm_addr->rc_bdaddr = bdaddr;
	rfcomm_addr->rc_channel = rfcomm_channel;

	// Disable blocking mode to make sure the ::connect() call below doesn't block
	// and instead starts a connection process in the background.
	set_fd_blocking(socket_fd, false);

	LOG(trace, "Performing a non-blocking connect");
	if (::connect(socket_fd, reinterpret_cast<struct sockaddr *>(rfcomm_addr), sizeof(*rfcomm_addr)) < 0)
	{
		switch (errno)
		{
			case 0:
			case EINPROGRESS:
				break;
			default:
				throw io_exception(fmt::format("Could not connect RFCOMM socket: {} ({})", std::strerror(errno), errno));
		}
	}

	// Set up the pollfd array for the poll() call.
	std::array<struct pollfd, 2> pfds = {};
	pfds[0].fd = m_connect_pipe_fds[0];
	pfds[0].events = POLLIN;
	pfds[1].fd = socket_fd;
	pfds[1].events = POLLOUT;

	// Perform the actual poll() call. This is done in a wnile loop,
	// since it is possible that a Unix signal interrupts the poll()
	// call, in which case we have to repeat it.
	while (true)
	{
		LOG(trace, "Listening to FDs with poll()");
		int posix_ret = poll(&pfds[0], pfds.size(), -1);

		if (posix_ret == -1)
		{
			switch (errno)
			{
				case EINTR:
					LOG(trace, "poll() was interrupted by a signal");
					// Try poll() call again after getting interrupted by a signal.
					break;

				default:
					// Something went wrong. Abort connection attempt.
					throw io_exception(fmt::format("Could not poll for activity: {} ({})", std::strerror(errno), errno));
			}
		}

		break;
	}

	LOG(trace, "poll() registered IO activity");

	if (pfds[0].revents & (POLLIN | POLLERR))
	{
		// A dummy message was received through the pipe. This implies
		// that disconnect() was called. Abort with a gerror_exception
		// exception informing the caller that this operation was cancelled.

		char dummy_buf[1024];
		::read(pfds[0].fd, dummy_buf, sizeof(dummy_buf));

		LOG(debug, "Aborting connection attempt due to it being cancelled by disconnect call");
		throw gerror_exception(g_error_new(G_IO_ERROR, G_IO_ERROR_CANCELLED, "Connection attempt aborted by disconnect call"));
	}

	if (pfds[1].revents & POLLOUT)
	{
		// The connection attempt finished successfully, or an error occurred.
		// Check what happened, and react accordingly.

		// The checks here are taken from various comments in
		// https://stackoverflow.com/questions/17769964/linux-sockets-non-blocking-connect :
		//
		// 1. getsockopt() checks if an error occurred. But its return value 0
		//    does not imply that the connection is established, just that
		//    no error was detected.
		// 2. getpeername() returns -1 and sets errno to ENOTCONN in case there
		//    is no connection. (We don't check for ENOTCONN, since the return
		//    value -1 already tells us that the connection is not usable.)

		int socket_error = 0;
		socklen_t socket_error_len = sizeof(socket_error);

		int posix_ret = getsockopt(pfds[1].fd, SOL_SOCKET, SO_ERROR, &socket_error, &socket_error_len);
		assert(posix_ret >= 0);

		if (socket_error != 0)
			throw io_exception(fmt::format("Connection attempt failed: {} ({})", std::strerror(socket_error), socket_error));

		{
			struct sockaddr_storage dummy_sockaddr;
			socklen_t dummy_sockaddr_len = sizeof(dummy_sockaddr);

			posix_ret = getpeername(pfds[1].fd, reinterpret_cast<struct sockaddr *>(&dummy_sockaddr), &dummy_sockaddr_len);
			if (posix_ret < 0)
				throw io_exception(fmt::format("Connection attempt failed: {} ({})", std::strerror(errno), errno));
		}

		LOG(trace, "Connection established");
	}

	// We can enable blocking mode again (we only need
	// non-blocking mode for the connection attempt).
	set_fd_blocking(socket_fd, true);

	// We set up the file descriptor. Now we can hand it over to GLib.
	rfcomm_gsocket = g_socket_new_from_fd(socket_fd, &gerror);
	if (rfcomm_gsocket == nullptr)
	{
		LOG(error, "Could not create RFCOMM GSocket: {}", gerror->message);
		throw gerror_exception(gerror);
	}

	// We are done. Dismiss the guards.
	rfcomm_fd_guard.dismiss();
	rfcomm_gsocket_guard.dismiss();

	m_socket = rfcomm_gsocket;

	LOG(info, "Opened RFCOMM connection to device {} on channel {}", to_string(bt_address), rfcomm_channel);
}


void rfcomm_connection::disconnect()
{
	disconnect_impl(false);
}


void rfcomm_connection::disconnect_impl(bool is_shutting_down)
{
	// TODO: Make sure this isn't run if there's no connection.

	LOG(trace, "Disconnecting RFCOMM connection");

	LOG(trace, "Canceling any ongoing send operation");
	g_cancellable_cancel(m_send_cancellable);

	LOG(trace, "Canceling any ongoing receive operation");
	g_cancellable_cancel(m_receive_cancellable);

	if (m_socket != nullptr)
	{
		LOG(trace, "Tearing down socket");
		g_object_unref(G_OBJECT(m_socket));
		m_socket = nullptr;
	}
 
 	LOG(trace, "Aborting any ongoing connect attempt");

 	if (m_connect_pipe_fds[1] > 0)
 	{
		int posix_ret = write(m_connect_pipe_fds[1], "1234", 4);
		assert(posix_ret >= 0);
	}

	// IMPORTANT: FIRST we send the dummy message over the pipe,
	// THEN we lock the mutex. Otherwise, aborting a connect attempt
	// would end up in a deadlock.
	//
	// If we first locked the mutex and then called write(),
	// this would happen:
	//
	// 1. connect() locks the mutex,and listens for IO activity
	//    using poll.
	// 2. disconnect() here tries to lock the mutex, gets blocked,
	//    because connect() already locked it.
	// 3. The write() call that is intended to wake up poll()
	//    inside connect is never made, because the thread running
	//    disconnect() is suspended (see step #2 above). Therefore,
	//    the poll() call inside connect() can never be woken up
	//    properly to abort the connection attempt.
	//
	// By first calling write and then locking the mutex, this is fixed:
	//
	// 1. connect() locks the mutex,and listens for IO activity
	//    using poll.
	// 2. disconnect() sends the dummy message over the pipe. This
	//    wakes up the poll() call inside connect().
	// 3. connect() handles the wakeup call, and begins aborting
	//    the attempt. Meanwhile, the mutex lock here after the
	//    write call(), together with the condition variable while loop,
	//    make sure disconnect() exits only after connect() finished
	//    aborting its attempt.

	std::unique_lock<std::mutex> lock(m_connect_pipe_mutex);

	m_is_shutting_down = is_shutting_down;

	while (m_is_connecting)
		m_connecting_condvar.wait(lock);

 	LOG(trace, "RFCOMM connection disconnected");
}


void rfcomm_connection::send(void const *src, int num_bytes)
{
	assert(src != nullptr);
	assert(num_bytes > 0);

	GError *gerror = nullptr;

	int remaining_bytes_to_send = num_bytes;

	// Reset the cancellable in case cancel_send() was called earlier.
	g_cancellable_reset(m_send_cancellable);

	do
	{
		gchar const *src_bytes = reinterpret_cast<gchar const *>(src) + (num_bytes - remaining_bytes_to_send);

		gssize num_bytes_sent = g_socket_send(
			m_socket,
			src_bytes,
			remaining_bytes_to_send,
			m_send_cancellable,
			&gerror
		);
		if (num_bytes_sent < 0)
		{
			if (g_error_matches(gerror, G_IO_ERROR, G_IO_ERROR_CANCELLED))
			{
				LOG(debug, "Send canceled");
				throw gerror_exception(gerror);
			}
			else
			{
				LOG(error, "Could not send {} byte(s): {}", num_bytes, gerror->message);
				throw gerror_exception(gerror);
			}
		}

		assert(num_bytes_sent <= remaining_bytes_to_send);
		remaining_bytes_to_send -= num_bytes_sent;

		LOG(trace, "Sent {} byte(s); remaining: {}", num_bytes_sent, remaining_bytes_to_send);
	}
	while (remaining_bytes_to_send > 0);
}


int rfcomm_connection::receive(void *dest, int num_bytes)
{
	assert(dest != nullptr);
	assert(num_bytes > 0);

	GError *gerror = nullptr;

	// Reset the cancellable in case cancel_receive() was called earlier.
	g_cancellable_reset(m_receive_cancellable);

	gssize num_bytes_received = g_socket_receive(
		m_socket,
		reinterpret_cast<gchar *>(dest),
		num_bytes,
		m_receive_cancellable,
		&gerror
	);
	if (num_bytes_received < 0)
	{
		if (g_error_matches(gerror, G_IO_ERROR, G_IO_ERROR_CANCELLED))
		{
			LOG(debug, "Receive canceled");
			throw gerror_exception(gerror);
		}
		else
		{
			LOG(error, "Could not receive {} byte(s): {}", num_bytes, gerror->message);
			throw gerror_exception(gerror);
		}
	}

	LOG(trace, "Received {} byte(s); requested: max {}", num_bytes_received, num_bytes);

	return num_bytes_received;
}


void rfcomm_connection::cancel_send()
{
	g_cancellable_cancel(m_send_cancellable);
}


void rfcomm_connection::cancel_receive()
{
	g_cancellable_cancel(m_receive_cancellable);
}


} // namespace comboctl end
