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

	GMainContext *m_mainloop_context = nullptr;
	GMainLoop *m_mainloop = nullptr;
	GDBusConnection *m_gdbus_connection = nullptr;

	rfcomm_listener m_rfcomm_listener;
	sdp_service m_sdp_service;
	agent m_agent;
	adapter m_adapter;

	found_new_paired_device_callback m_on_found_new_device;
	bluez_interface::discovery_stopped_callback m_on_discovery_stopped;

	bluez_interface::thread_func m_on_thread_starting;
	bluez_interface::thread_func m_on_thread_stopping;

	bool m_discovery_started = false;

	GSource *m_discovery_timeout_gsource = nullptr;


	bluez_interface_priv()
	{
		// Start our own GLib mainloop where all GDBus activities
		// shall take place, along with any extra calls we serialize
		// to the mainloop via the run_in_thread() call.
		// We use a custom GMainContext to make sure we don't
		// pollute the default context (which is selected by
		// passing null to the first g_main_loop_new() argument).
		// This allows for using this code with other components
		// that also use a GLib mainloop. If we used the default
		// GMainContext, there could be a collision if another
		// component also uses that context. One prominent example
		// is the GTK backend of JavaFX on Linux, which uses a
		// GLib mainloop, and apparently uses the default context
		// instead of a custom one.
		m_mainloop_context = g_main_context_new();
		m_mainloop = g_main_loop_new(m_mainloop_context, TRUE);
		assert(m_mainloop != nullptr);
	}


	~bluez_interface_priv()
	{
		if (m_discovery_timeout_gsource != nullptr)
		{
			g_source_destroy(m_discovery_timeout_gsource);
			g_source_unref(m_discovery_timeout_gsource);
		}

		g_main_loop_unref(m_mainloop);
		g_main_context_unref(m_mainloop_context);
	}


	void thread_func()
	{
		LOG(trace, "Starting internal BlueZ thread");

		// Set our custom context as the new default one in
		// this thread (-> this is a thread-local configuration).
		// Any calls that always take the default context (like
		// the GDBus functions) will now use our own context.
		// In this code, all calls that touch the GLib mainloop
		// happen in this same thread, so we don't have to worry
		// about calls in other threads not using our context.
		g_main_context_push_thread_default(m_mainloop_context);

		if (m_on_thread_starting)
			m_on_thread_starting();

		// Unlike the agent and the SDP service, we start
		// the adapter here. This is because we only need
		// the agent and SDP service during discovery, while
		// we do need the adapter all the time (to be able to
		// detect unpaired devices).
		m_adapter.setup(m_gdbus_connection);

		g_main_loop_run(m_mainloop);

		LOG(trace, "Stopping internal BlueZ thread");

		m_adapter.teardown();

		if (m_on_thread_stopping)
			m_on_thread_stopping();

		// Unset our custom context as the default one
		// as part of our cleanup here.
		g_main_context_pop_thread_default(m_mainloop_context);
	}


	void stop_glib_mainloop()
	{
		if (m_mainloop != nullptr)
			g_main_loop_quit(m_mainloop);
	}


	std::future<std::exception_ptr> run_thread_func_in_gsource(GSource *gsource, bluez_interface::thread_func func)
	{
		// This runs the given function object in the GLib
		// mainloop thread (m_thread). This makes things
		// easier, since otherwise, many mutex locks would
		// potentially be required. The function is assigned
		// to the given GSource, which is then executed by
		// the GLib mainloop in a manner depending on the
		// particular type of the GSource.
		//
		// In case callers want to wait until the function is
		// executed, an std::future is used. An std::promise
		// instance is created, the corresponding future is
		// retrieved, and the promise is passed to the GSource
		// along with the function object itself Once the function
		// object was executed, that promise's value is set to
		// the default-constructed exception_ptr(). Should an
		// exception occur, that exception is captured using
		// std::current_exception(), and used as the promise's
		// value. Meanwhile, the future's get() function blocks
		// until the promise's value is set.

		struct function_data
		{
			bluez_interface::thread_func m_function;
			std::promise<std::exception_ptr> m_promise;
		};

		// The supplied function must be valid.
		assert(func);

		// This is the callback that is executed when
		// the GSsource is run by the GLib mainloop.
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

		g_source_set_callback(
			gsource,
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
		g_source_attach(gsource, g_main_loop_get_context(m_mainloop));

		return future;
	}


	void run_in_thread(bluez_interface::thread_func func)
	{
		// Run the function object in the GLib mainloop as soon
		// as the loop has no other tasks to take care of.
		// We use idle GSources for this purpose.

		GSource *idle_source = g_idle_source_new();
		auto future = run_thread_func_in_gsource(idle_source, func);
		g_source_unref(idle_source);

		// Wait for the GSource to run, and get any resulting
		// captured exception. If one was captured, rethrow it
		// here, in the thread that called run_in_thread().
		// That way, exceptions are propagated across threads.
		std::exception_ptr eptr = future.get();
		if (eptr)
			std::rethrow_exception(eptr);
	}


	GSource* run_in_thread(guint timeout, bluez_interface::thread_func func)
	{
		// Run the function object in a timeout GSource and
		// run that GSource in the GLib mainloop. Unlike in
		// the variant above, we do not care about the future
		// object here, just about the GSource itself, so we
		// can destroy and unref it in case we want to cancel
		// that timeout.

		GSource *timeout_source = g_timeout_source_new_seconds(timeout);
		run_thread_func_in_gsource(timeout_source, func);
		return timeout_source;
	}


	void start_discovery_impl(
		std::string sdp_service_name,
		std::string sdp_service_provider,
		std::string sdp_service_description,
		std::string bt_pairing_pin_code,
		int discovery_duration,
		bluez_interface::discovery_started_callback on_discovery_started,
		bluez_interface::discovery_stopped_callback on_discovery_stopped,
		found_new_paired_device_callback on_found_new_device
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
				on_discovery_stopped(discovery_stopped_reason::discovery_error);
		});

		m_discovery_timeout_gsource = run_in_thread(discovery_duration, [&]() {
			LOG(debug, "discovery timeout reached; stopping discovery");
			stop_discovery_impl(discovery_stopped_reason::discovery_timeout);
		});

		// Store the callbacks for later use.
		m_on_found_new_device = std::move(on_found_new_device);
		m_on_discovery_stopped = std::move(on_discovery_stopped);

		// Set up all components.

		m_agent.setup(
			m_gdbus_connection,
			std::move(bt_pairing_pin_code)
		);
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
			[this](bluetooth_address device_address) {
				// In here, we get notified about newly found
				// paired devices.
				// We are only interested in paired devices,
				// since this gives our agent the chance to
				// provide authorization first. In other words,
				// any device that shows up and is paired already
				// got authorized successfully, either by our
				// agent, or by other means. The adapter applies
				// a filter if one is defined, so we only get
				// devices here that passed that filter.

				try
				{
					m_on_found_new_device(device_address);
				}
				catch (std::exception const &exc)
				{
					LOG(error, "Exception thrown while handling newly discovered paired device: {}", exc.what());
					// TODO: What should be done in this case?
				}
			}
		);

		// Start successful. Dismiss the guard so it does not
		// call on_discovery_stopped when we exit this scope.
		discovery_started_guard.dismiss();

		m_discovery_started = true;
	}


	void stop_discovery_impl(discovery_stopped_reason reason)
	{
		if (!m_discovery_started)
			return;

		m_discovery_started = false;

		m_on_discovery_stopped(reason);

		m_agent.teardown();
		m_sdp_service.teardown();

		if (m_discovery_timeout_gsource != nullptr)
		{
			g_source_destroy(m_discovery_timeout_gsource);
			g_source_unref(m_discovery_timeout_gsource);
			m_discovery_timeout_gsource = nullptr;
		}
	}


	void unpair_device_impl(bluetooth_address device_address)
	{
		// Perform the actual removal.
		m_adapter.remove_device(device_address);

		LOG(trace, "Unpaired device {} by removing it from the BlueZ adapter", to_string(device_address));
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
	LOG(trace, "Starting teardown");

	// Catch redundant calls.
	if (!m_priv->m_thread_started)
	{
		LOG(trace, "GLib mainloop thread is not running; nothing to tear down");
		return;
	}

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
	int discovery_duration,
	discovery_started_callback on_discovery_started,
	discovery_stopped_callback on_discovery_stopped,
	found_new_paired_device_callback on_found_new_device
)
{
	assert(on_found_new_device);
	assert(m_priv->m_thread_started);
	assert((discovery_duration >= 1) && (discovery_duration <= 300));

	m_priv->run_in_thread([=]() mutable {
		m_priv->start_discovery_impl(
			std::move(sdp_service_name),
			std::move(sdp_service_provider),
			std::move(sdp_service_description),
			std::move(bt_pairing_pin_code),
			discovery_duration,
			std::move(on_discovery_started),
			std::move(on_discovery_stopped),
			std::move(on_found_new_device)
		);
	});
}


void bluez_interface::stop_discovery()
{
	if (!m_priv->m_thread_started)
		return;

	m_priv->run_in_thread([this]() { m_priv->stop_discovery_impl(discovery_stopped_reason::manually_stopped); });
}


void bluez_interface::on_device_unpaired(device_unpaired_callback callback)
{
	assert(m_priv->m_thread_started);

	m_priv->run_in_thread([this, callback = std::move(callback)]() mutable {
		m_priv->m_adapter.on_device_unpaired(callback);
	});
}


void bluez_interface::set_device_filter(filter_device_callback callback)
{
	assert(m_priv->m_thread_started);

	m_priv->run_in_thread([this, callback = std::move(callback)]() mutable {
		m_priv->m_adapter.set_device_filter(callback);
		m_priv->m_agent.set_device_filter(callback);
	});
}


void bluez_interface::unpair_device(bluetooth_address device_address)
{
	assert(m_priv->m_thread_started);
	m_priv->run_in_thread([=]() mutable { m_priv->unpair_device_impl(device_address); });
}


bluez_bluetooth_device_uptr bluez_interface::get_device(bluetooth_address device_address)
{
	// Connect to the Combo using RFCOMM channel #1. This is the
	// channel that worked reliably during tests.

	// NOTE: Not using make_unique() here because the constructor
	// of bluez_bluetooth_device is private. bluez_interface is
	// marked as a friend class, but make_unique() doesn't have
	// the same privileges.
	return bluez_bluetooth_device_uptr(new bluez_bluetooth_device(device_address, 1));
}


std::string bluez_interface::get_adapter_friendly_name() const
{
	assert(m_priv->m_thread_started);

	std::string name;
	m_priv->run_in_thread([&]() mutable { name = m_priv->m_adapter.get_name(); });

	return name;
}


bluetooth_address_set bluez_interface::get_paired_device_addresses() const
{
	assert(m_priv->m_thread_started);

	bluetooth_address_set addresses;
	m_priv->run_in_thread([&]() mutable { addresses = m_priv->m_adapter.get_paired_device_addresses(); });

	return addresses;
}


} // namespace comboctl end
