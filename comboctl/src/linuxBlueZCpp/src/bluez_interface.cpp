#include <glib.h>
#include <gio/gio.h>
#include <thread>
#include <future>
#include <set>
#include <assert.h>
#include "bluez_interface.hpp"
#include "agent.hpp"
#include "adapter.hpp"
#include "sdp_service.hpp"
#include "gerror_exception.hpp"
#include "rfcomm_listener.hpp"
#include "rfcomm_connection.hpp"
#include "scope_guard.hpp"
#include "log.hpp"


DEFINE_LOGGING_TAG("BlueZInterface")


namespace comboctl
{


namespace
{


template<typename T>
void try_set_promise_value(std::promise<T> &promise, T value)
{
	// set_value() itself can throw an exception,
	// so we surround that call with a try-catch block.
	// We ignore promise_already_satisfied errors, but
	// consider all the others as fatal.

	try
	{
		promise.set_value(std::move(value));
	}
	catch (std::future_error const &error)
	{
		if (error.code() == std::make_error_condition(std::future_errc::promise_already_satisfied))
		{
			// no-op
		}
		else
		{
			LOG(fatal, "Caught future_error exception that isn't promise_already_satisfied");
			std::terminate();
		}
	}
	catch (...)
	{
		LOG(fatal, "Caught unknown exception while trying to set the std::promise's value");
		std::terminate();
	}
}


} // unnamed namespace end




struct bluez_interface_priv
{
	std::thread m_thread;
	bool m_thread_started = false;

	GMainLoop *m_mainloop = nullptr;
	GDBusConnection *m_gdbus_connection = nullptr;

	rfcomm_listener m_rfcomm_listener;
	sdp_service m_sdp_service;
	agent m_agent;
	adapter m_adapter;

	found_new_paired_device_callback m_on_found_new_device;
	device_is_gone_callback m_on_device_is_gone;
	filter_device_callback m_on_filter_device;
	bluez_interface::thread_func m_on_discovery_stopped;

	bluez_interface::thread_func m_on_thread_starting;
	bluez_interface::thread_func m_on_thread_stopping;

	bool m_discovery_started = false;

	typedef std::set<comboctl::bluetooth_address> bluetooth_address_set;
	bluetooth_address_set m_discovered_paired_device_addresses;


	bluez_interface_priv()
	{
		m_mainloop = g_main_loop_new(nullptr, TRUE);
		assert(m_mainloop != nullptr);
	}


	~bluez_interface_priv()
	{
		g_main_loop_unref(m_mainloop);
	}


	void thread_func()
	{
		LOG(trace, "Starting internal BlueZ thread");

		if (m_on_thread_starting)
			m_on_thread_starting();

		g_main_loop_run(m_mainloop);

		if (m_on_thread_stopping)
			m_on_thread_stopping();
	}


	void stop_glib_mainloop()
	{
		if (m_mainloop != nullptr)
			g_main_loop_quit(m_mainloop);
	}


	void run_in_thread(bluez_interface::thread_func func)
	{
		// This runs the given function object in the GLib
		// mainloop thread (m_thread). This makes things
		// easier, since otherwise, many mutex locks would
		// potentially be required.
		//
		// We use idle GSources for this purpose. This
		// type of GSource is run when the mainloop has nothing
		// else to do. In that GSource's callback, the function
		// object is executed.
		//
		// We also block here until that function is executed.
		// This simplifies bluez_interface's API considerably.
		// In particular, passing exceptions is much easier.
		//
		// To that end, we make use of an std::future. An
		// std::promise instance is created, the corresponding
		// future is retrieved, and the promise is passed to
		// the GSource along with the function object itself.
		// Once the function object was executed, that promise's
		// value is set to the default-constructed exception_ptr().
		// Should an exception occur, that exception is captured
		// using std::current_exception(), and used as the promise's
		// value. Meanwhile, the future's get() function blocks
		// until the promise's value is set.

		struct function_data
		{
			bluez_interface::thread_func m_function;
			std::promise<std::exception_ptr> m_promise;
		};

		// The supplied function must be valid.
		assert(func);

		// This is the callback that is executed when the
		// idle GSsource is run by the GLib mainloop..
		static auto callback = [](gpointer data) -> gboolean {
			function_data *func_data = reinterpret_cast<function_data*>(data);

			std::exception_ptr eptr;

			// Call the actual function object.
			// Capture any exception here so we can propagate
			// it properly later via future.get().
			try
			{
				func_data->m_function();
			}
			catch (...)
			{
				eptr = std::current_exception();
			}

			try_set_promise_value(func_data->m_promise, eptr);

			return G_SOURCE_REMOVE;
		};

		// Set up the promise and future objects.
		std::promise<std::exception_ptr> promise;
		std::future<std::exception_ptr> future = promise.get_future();

		// Set up the data for the GSource. We have
		// to allocate this in the heap, since the
		// GSource only accepts a userdata pointer
		// as context information.
		function_data *func_data = new function_data{std::move(func), std::move(promise)};

		GSource *idle_source = g_idle_source_new();
		g_source_set_callback(
			idle_source,
			GSourceFunc(callback),
			gpointer(func_data),
			[](gpointer data) {
				// This is run when the GSource is discarded.
				// With this, we make sure that the previously
				// heap-allocated function_data context is
				// freed, and that the promise is always set
				// to a value, even if the GSource is never run.

				function_data *func_data = reinterpret_cast<function_data*>(data);

				try_set_promise_value(func_data->m_promise, std::exception_ptr());

				delete func_data;
			}
		);
		g_source_attach(idle_source, g_main_loop_get_context(m_mainloop));
		g_source_unref(idle_source);

		// Wait for the GSource to run, and get any resulting
		// captured exception. If one was captured, rethrow it
		// here, in the thread that called run_in_thread().
		// That way, exceptions are propagated across threads.
		std::exception_ptr eptr = future.get();
		if (eptr)
			std::rethrow_exception(eptr);
	}


	void start_discovery_impl(
		std::string sdp_service_name,
		std::string sdp_service_provider,
		std::string sdp_service_description,
		std::string bt_pairing_pin_code,
		bluez_interface::thread_func on_discovery_started,
		bluez_interface::thread_func on_discovery_stopped,
		found_new_paired_device_callback on_found_new_device,
		device_is_gone_callback on_device_is_gone,
		filter_device_callback on_filter_device
	)
	{
		if (m_discovery_started)
			throw invalid_call_exception("Discovery already started");

		on_discovery_started();

		// Install a scope guard which calls on_discovery_stopped.
		// The reason for this is that in case of an exception
		// or other early return, we want to roll back any partial
		// discovery start we performed here. This includes the
		// on_discovery_started call earlier.
		auto discovery_started_guard = make_scope_guard([&]() {
			if (on_discovery_stopped)
				on_discovery_stopped();
		});

		// Store the callbacks for later use.
		m_on_found_new_device = std::move(on_found_new_device);
		m_on_device_is_gone = std::move(on_device_is_gone);
		m_on_filter_device = std::move(on_filter_device);
		m_on_discovery_stopped = std::move(on_discovery_stopped);

		// Set up all components.

		m_agent.setup(
			m_gdbus_connection,
			std::move(bt_pairing_pin_code),
			m_on_filter_device
		);
		m_adapter.setup(m_gdbus_connection);
		m_sdp_service.setup(
			m_gdbus_connection,
			std::move(sdp_service_name),
			std::move(sdp_service_provider),
			std::move(sdp_service_description),
			m_rfcomm_listener.get_channel()
		);

		// Start the discovery process. Note that the
		// supplied callbacks will not be invoked until
		// the mainloop got a chance to iterate.
		m_adapter.start_discovery(
			[this](bluetooth_address device_address, bool paired) {
				// In here, we get notified about newly found
				// devices, which may be paired or unpaired.
				// We are only interested in paired devices,
				// since this gives our agent the chance to
				// provide authorization first. In other words,
				// any device that shows up and is paired already
				// got authorized successfully, either by our
				// agent, or by other means.

				if (!filter_device(device_address))
				{
					LOG(trace, "Filtered out newly discovered device {}", to_string(device_address));
					return;
				}

				if (!paired)
				{
					auto device_iter = m_discovered_paired_device_addresses.find(device_address);

					if (device_iter == m_discovered_paired_device_addresses.end())
					{
						LOG(trace, "Ignoring newly discovered device {} because it is not paired (yet)", to_string(device_address));
						return;
					}
					else
					{
						// The device is in the set of paired device addresses,
						// and now is unpaired. Calling device_is_gone callback
						// since for us, this device effectively is "gone" due
						// to it now being unpaired.
						LOG(trace, "Previously paired device {} is now unpaired; calling device_is_gone", to_string(device_address));
						invoke_on_device_is_gone(device_address);
						return;
					}
				}

				if (m_discovered_paired_device_addresses.find(device_address) != m_discovered_paired_device_addresses.end())
				{
					LOG(trace, "Ignoring newly discovered device {} because it was seen already", to_string(device_address));
					return;
				}

				try
				{
					m_discovered_paired_device_addresses.insert(device_address);
					m_on_found_new_device(device_address);
				}
				catch (std::exception const &exc)
				{
					LOG(error, "Exception thrown while handling newly discovered paired device: {}", exc.what());
					m_discovered_paired_device_addresses.erase(device_address);
					// TODO: What should be done in this case?
				}
			},
			[this](bluetooth_address device_address) {
				// In here, we deal with devices that were removed from
				// BlueZ' list of discovered devices (because they are gone).

				auto device_iter = m_discovered_paired_device_addresses.find(device_address);
				if (device_iter == m_discovered_paired_device_addresses.end())
				{
					LOG(trace, "Ignoring removed device {} because it wasn't seen before or wasn't paired", to_string(device_address));
					return;
				}

				m_discovered_paired_device_addresses.erase(device_iter);

				invoke_on_device_is_gone(device_address);
			}
		);

		// Start successful. Dismiss the guard so it does not
		// call on_discovery_stopped when we exit this scope.
		discovery_started_guard.dismiss();

		m_discovery_started = true;
	}


	void invoke_on_device_is_gone(bluetooth_address device_address)
	{
		try
		{
			if (m_on_device_is_gone)
				m_on_device_is_gone(device_address);
		}
		catch (std::exception const &exc)
		{
			LOG(error, "Exception thrown while handling device that's gone: {}", exc.what());
			// TODO: What should be done in this case?
		}
	}


	void stop_discovery_impl()
	{
		if (!m_discovery_started)
			return;

		m_discovery_started = false;

		m_on_discovery_stopped();

		m_adapter.teardown();
		m_agent.teardown();
		m_sdp_service.teardown();

		m_discovered_paired_device_addresses.clear();
	}


	void unpair_device_impl(bluetooth_address device_address)
	{
		// Perform the actual removal.
		m_adapter.remove_device(device_address);

		LOG(trace, "Unpaired device {} by removing it from the BlueZ adapter", to_string(device_address));
	}

	bool filter_device(bluetooth_address device_address)
	{
		if (m_on_filter_device)
			return m_on_filter_device(device_address);
		else
			return true;
	}
};




// NOTE: Currently, bluez_bluetooth_device does not need anything
// from the bluez_interface instance that created it. Should this
// change, make sure that that instance stays alive at least until
// all bluez_bluetooth_device instances it created are gone,
// otherwise they might try to access the bluez_interface instance
// after it has been destroyed.


bluez_bluetooth_device::bluez_bluetooth_device(bluetooth_address const &bt_address, unsigned int rfcomm_channel)
	: m_bt_address(bt_address)
	, m_rfcomm_channel(rfcomm_channel)
{
	m_connection = std::make_unique<rfcomm_connection>();
}

void bluez_bluetooth_device::connect()
{
	m_connection->connect(m_bt_address, m_rfcomm_channel);
}

bluez_bluetooth_device::~bluez_bluetooth_device()
{
	disconnect();
}

void bluez_bluetooth_device::disconnect()
{
	// NOTE: rfcomm_connection::disconnect() implitely cancels
	// send and receive operations that may currently be ongoing.
	// So, we do not need to call cancel_send() and cancel_receive()
	// explicitely.
	m_connection->disconnect();
}

void bluez_bluetooth_device::send(void const *src, int num_bytes)
{
	m_connection->send(src, num_bytes);
}

int bluez_bluetooth_device::receive(void *dest, int num_bytes)
{
	return m_connection->receive(dest, num_bytes);
}

void bluez_bluetooth_device::cancel_send()
{
	m_connection->cancel_send();
}

void bluez_bluetooth_device::cancel_receive()
{
	m_connection->cancel_receive();
}




bluez_interface::bluez_interface()
{
	m_priv = std::make_unique<bluez_interface_priv>();
	setup();
}


bluez_interface::~bluez_interface()
{
	teardown();
}


void bluez_interface::setup()
{
	// Catch redundant calls.
	if (m_priv->m_thread_started)
		return;

	LOG(trace, "Getting GLib D-Bus connection");

	// Get GLib D-Bus connection for D-Bus calls.
	GError *gerror = nullptr;
	m_priv->m_gdbus_connection = g_bus_get_sync(G_BUS_TYPE_SYSTEM, nullptr, &gerror);
	if (gerror != nullptr)
	{
		LOG(error, "Could not get GLib DBus connection: {}", gerror->message);
		throw gerror_exception(gerror);
	}

	// Start the RFCOMM listener. We only need it
	// so we can provide the SDP service with an
	// RFCOMM channel number. By specifying channel
	// #0 we instruct the listener to automatically
	// pick any free channel (its get_channel() function
	// will then return that picked channel).
	LOG(trace, "Starting RFCOMM listener");
	m_priv->m_rfcomm_listener.listen(0);

	// Finally, start the GLib mainloop thread.
	LOG(trace, "Starting GLib mainloop thread");
	m_priv->m_thread = std::thread([this]() { m_priv->thread_func(); });
	m_priv->m_thread_started = true;

	LOG(trace, "BlueZ interface set up");
}


void bluez_interface::teardown()
{
	// Catch redundant calls.
	if (!m_priv->m_thread_started)
		return;

	stop_discovery();

	// Stop the GLib mainloop, otherwise its thread
	// will never finish.
	LOG(trace, "Stopping GLib mainloop");
	m_priv->stop_glib_mainloop();

	// Now that we instructed the GLib mainloop
	// to stop, wait until its thread finishes.
	LOG(trace, "Stopping GLib mainloop thread");
	m_priv->m_thread.join();

	// Reset the RFCOMM listener for future setup() calls.
	LOG(trace, "Resetting RFCOMM listener");
	m_priv->m_rfcomm_listener = rfcomm_listener();

	// Discard the GLib D-Bus connection.
	if (m_priv->m_gdbus_connection != nullptr)
	{
		LOG(trace, "Discarding GLib D-Bus connection");
		g_object_unref(G_OBJECT(m_priv->m_gdbus_connection));
		m_priv->m_gdbus_connection = nullptr;
	}

	// We are done.
	LOG(trace, "BlueZ interface torn down");
	m_priv->m_thread_started = false;
}


void bluez_interface::run_in_thread(thread_func func)
{
	assert(func);
	m_priv->run_in_thread(std::move(func));
}


void bluez_interface::on_thread_stopping(thread_func func)
{
	m_priv->m_on_thread_stopping = std::move(func);
}


void bluez_interface::start_discovery(
	std::string sdp_service_name,
	std::string sdp_service_provider,
	std::string sdp_service_description,
	std::string bt_pairing_pin_code,
	thread_func on_discovery_started,
	thread_func on_discovery_stopped,
	found_new_paired_device_callback on_found_new_device,
	device_is_gone_callback on_device_is_gone,
	filter_device_callback on_filter_device
)
{
	assert(on_found_new_device);
	assert(m_priv->m_thread_started);

	m_priv->run_in_thread([=]() mutable {
		m_priv->start_discovery_impl(
			std::move(sdp_service_name),
			std::move(sdp_service_provider),
			std::move(sdp_service_description),
			std::move(bt_pairing_pin_code),
			std::move(on_discovery_started),
			std::move(on_discovery_stopped),
			std::move(on_found_new_device),
			std::move(on_device_is_gone),
			std::move(on_filter_device)
		);
	});
}


void bluez_interface::stop_discovery()
{
	if (!m_priv->m_thread_started)
		return;

	m_priv->run_in_thread([this]() { m_priv->stop_discovery_impl(); });
}


void bluez_interface::unpair_device(bluetooth_address device_address)
{
	assert(m_priv->m_thread_started);
	m_priv->run_in_thread([=]() mutable { m_priv->unpair_device_impl(device_address); });
}


bluez_bluetooth_device_uptr bluez_interface::get_device(bluetooth_address device_address)
{
	// TODO: Currently, the RFCOMM channel is hardcoded to be channel 1.
	// See if this is OK.

	// NOTE: Not using make_unique() here because the constructor
	// of bluez_bluetooth_device is private. bluez_interface is
	// marked as a friend class, but make_unique() doesn't have
	// the same privileges.
	return bluez_bluetooth_device_uptr(new bluez_bluetooth_device(device_address, 1));
}


std::string bluez_interface::get_adapter_friendly_name() const
{
	return m_priv->m_adapter.get_name();
}


} // namespace comboctl end
