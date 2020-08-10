#include <assert.h>
#include <unistd.h>
#include <sys/socket.h>
#include <bluetooth/bluetooth.h>
#include <bluetooth/rfcomm.h>
#include "scope_guard.hpp"
#include "rfcomm_listener.hpp"
#include "exception.hpp"
#include "gerror_exception.hpp"
#include "bluez_misc.hpp"
#include "log.hpp"


namespace comboctl
{


rfcomm_listener::rfcomm_listener()
	: m_socket_listener(nullptr)
	, m_socket_listener_accept_cancellable(nullptr)
{
	m_socket_listener_accept_cancellable = g_cancellable_new();
}


rfcomm_listener::~rfcomm_listener()
{
	stop_listening();

	g_object_unref(G_OBJECT(m_socket_listener_accept_cancellable));
}


void rfcomm_listener::listen(unsigned int rfcomm_channel)
{
	// In here, we first set up the RFCOMM socket directly via
	// POSIX functions, then we hand over the POSIX file descriptor
	// to a GLib GSocket. Currently, GLib has no functions for
	// setting up an RFCOMM socket, so we have to do this on our
	// own.


	GError *gerror = nullptr;
	GSocket *rfcomm_gsocket = nullptr;
	int socket_fd = -1;


	if (m_socket_listener != nullptr)
		throw invalid_call_exception("Listener socket already set up");


	// Establish scope guards to make sure resources are cleaned
	// up in case of an error. They are dismissed later, when
	// these resources are handed over to other entities that
	// take care of ownership.

	auto rfcomm_fd_guard = make_scope_guard([&]() {
		if (socket_fd < 0)
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

		close_socket_listener();
	});


	// Create the POSIX RFCOMM socket.
	socket_fd = socket(AF_BLUETOOTH, SOCK_STREAM, BTPROTO_RFCOMM);
	if (socket_fd < 0)
		throw io_exception(fmt::format("Could not create RFCOMM socket: {} ({})", std::strerror(errno), errno));

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
	rfcomm_addr->rc_bdaddr = bdaddr_any; // We allow for incoming connections from any Bluetooth address.
	rfcomm_addr->rc_channel = rfcomm_channel;

	if (::bind(socket_fd, reinterpret_cast<struct sockaddr *>(rfcomm_addr), sizeof(*rfcomm_addr)) < 0)
		throw io_exception(fmt::format("Could not bind RFCOMM listener socket: {} ({})", std::strerror(errno), errno));


	// Set up the GLib GSocket.

	rfcomm_gsocket = g_socket_new_from_fd(socket_fd, &gerror);
	if (rfcomm_gsocket == nullptr)
	{
		LOG(error, "Could not create RFCOMM GSocket: {}", gerror->message);
		throw gerror_exception(gerror);
	}

	// Dismiss the FD guard, since the rfcomm_gsocket now
	// took ownership over the socket_fd file descriptor.
	rfcomm_fd_guard.dismiss();


	// Set up listener for incoming RFCOMM connections.

	if (!g_socket_listen(rfcomm_gsocket, &gerror))
	{
		LOG(error, "Could not setting RFCOMM GSocket to listen: {}", gerror->message);
		throw gerror_exception(gerror);
	}

	m_socket_listener = g_socket_listener_new();
	assert(m_socket_listener != nullptr);

	if (!g_socket_listener_add_socket(m_socket_listener, rfcomm_gsocket, nullptr, &gerror))
	{
		LOG(error, "Could not add RFCOMM GSocket to socket listener: {}", gerror->message);
		throw gerror_exception(gerror);
	}

	// Dismiss the GSocket guard, since m_socket_listener
	// took ownership over the GSocket.
	rfcomm_gsocket_guard.dismiss();


	// Start accepting incoming connections. Since we only use the
	// listener to be able to assign an RFCOMM channel number to
	// our SDP service record, we don't actually care about these
	// incoming connections. In the callback, close these immediately.

	static auto static_async_accept_ready_cb = [](GObject *, GAsyncResult *res, gpointer user_data) -> void {
		rfcomm_listener *self = reinterpret_cast<rfcomm_listener*>(user_data);
		GError *gerror = nullptr;

		LOG(debug, "Closing accepted RFCOMM GSocket (since we don't use client connections)");

		GSocketConnection *socket_connection = g_socket_listener_accept_finish(
			self->m_socket_listener,
			res,
			nullptr,
			&gerror
		);

		if (socket_connection == nullptr)
		{
			auto error_guard = make_scope_guard([&]() { g_error_free(gerror); });

			if (g_error_matches(gerror, G_IO_ERROR, G_IO_ERROR_CANCELLED))
			{
				LOG(debug, "Listener accept call cancelled");
				return;
			}
			else
			{
				// Not throwing an exception here, since doing that from within a callback
				// of a C library leads to undefined behavior. Also, this is a fatal error,
				// since without a working RFCOMM listener, we can't do any Combo pairing.
				LOG(error, "Could not get accepted RFCOMM GSocket: {}", gerror->message);
				std::terminate();
			}
		}

		if (!g_io_stream_close(G_IO_STREAM(socket_connection), nullptr, &gerror))
		{
			LOG(error, "Could not close accepted RFCOMM GSocket: {}", gerror->message);
			g_error_free(gerror);
		}
	};

	g_socket_listener_accept_async(
		m_socket_listener,
		m_socket_listener_accept_cancellable,
		static_async_accept_ready_cb,
		gpointer(this)
	);


	// Get the RFCOMM channel that is actually used. If we set channel #0, Linux will
	// pick a currently unused channel. We use getsockname() to retrieve the number of
	// the channel that Linux picked.
	// NOTE: We retrieve the channel number _after_ g_socket_listener_accept_async(),
	// since Linux won't assign the channel until the listener socket is set to accept
	// incoming connections.

	if (rfcomm_channel == 0)
	{
		socklen_t rfcomm_addr_length = sizeof(*rfcomm_addr);

		if (::getsockname(socket_fd, reinterpret_cast<struct sockaddr *>(rfcomm_addr), &rfcomm_addr_length) < 0)
			throw io_exception(fmt::format("Could not get dynamically picked channel because getsockname() failed: {} ({})", std::strerror(errno), errno));

		m_rfcomm_channel = rfcomm_addr->rc_channel;
		LOG(info, "Using dynamically picked RFCOMM channel {}", m_rfcomm_channel);
	}
	else
	{
		m_rfcomm_channel = rfcomm_channel;
		LOG(info, "Using specified RFCOMM channel {}", m_rfcomm_channel);
	}


	// We are done.

	LOG(info, "Listening to incoming RFCOMM connections on channel {}", m_rfcomm_channel);
}


void rfcomm_listener::stop_listening()
{
	g_cancellable_cancel(m_socket_listener_accept_cancellable);

	if (m_socket_listener != nullptr)
	{
		g_socket_listener_close(m_socket_listener);
		g_object_unref(G_OBJECT(m_socket_listener));
		m_socket_listener = nullptr;
	}
}


unsigned int rfcomm_listener::get_channel() const
{
	return m_rfcomm_channel;
}


} // namespace comboctl end
