#include <jni/jni.hpp>
#include <stdexcept>
#include <cstddef>
#include <cstdlib>
#include <string>
#include <optional>
#include <type_traits>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <deque>
#include <fmt/format.h>
#include <glib.h>
#include <gio/gio.h>
#include "bluez_interface.hpp"
#include "exception.hpp"
#include "gerror_exception.hpp"
#include "scope_guard.hpp"
#include "log.hpp"


DEFINE_LOGGING_TAG("BlueZJNIBindings")


namespace
{


// TODO: Throw IllegalStateException when the interface is shut down.


//////////////////
// Utility code //
//////////////////


// Utility function to convert a jni.hpp array to a bluetooth_address.
// This also checks for the correct length of the byte array (6 bytes).
comboctl::bluetooth_address to_bt_address(jni::JNIEnv &env, jni::Array<jni::jbyte> const &byte_array)
{
	jni::jsize array_size = byte_array.Length(env);
	if (array_size != comboctl::bluetooth_address().size())
		throw std::invalid_argument("Invalid bluetooth address bytearray size");

	comboctl::bluetooth_address address;

	for (jni::jsize i = 0; i < array_size; ++i)
		address[i] = byte_array.Get(env, i);

	return address;
}

// Helper functions to catch exceptions coming from
// a function and translate these functions to JNI
// ThrowNew calls to make sure Java/Kotlin get
// exceptions that they can understand.
// One special case is the G_IO_ERROR_CANCELLED
// GError exception. This occurs when a GIO
// operation was cancelled. We want to map this
// to coroutines so that a G_IO_ERROR_CANCELLED
// properly cancels a coroutine. So, if the
// GError's code is set to G_IO_ERROR_CANCELLED,
// we throw CancellationException. Otherwise,
// we throw BluetoothException.

template<typename Func, typename ReturnType = decltype(std::declval<Func>()())>
auto call_with_jni_rethrow(jni::JNIEnv &env, Func &&func) -> typename std::enable_if<std::is_void<ReturnType>::value, ReturnType>::type
{
	try
	{
		func();
	}
	catch (comboctl::invalid_call_exception const &exc)
	{
		jni::ThrowNew(env, jni::FindClass(env, "java/lang/IllegalStateException"), exc.what());
	}
	catch (comboctl::io_exception const &exc)
	{
		jni::ThrowNew(env, jni::FindClass(env, "info/nightscout/comboctl/base/ComboIOException"), exc.what());
	}
	catch (comboctl::gerror_exception const &exc)
	{
		if (g_error_matches(exc.get_gerror(), G_IO_ERROR, G_IO_ERROR_CANCELLED))
			jni::ThrowNew(env, jni::FindClass(env, "java/util/concurrent/CancellationException"), exc.what());
		else
			jni::ThrowNew(env, jni::FindClass(env, "info/nightscout/comboctl/base/BluetoothException"), exc.what());
	}
	catch (comboctl::exception const &exc)
	{
		jni::ThrowNew(env, jni::FindClass(env, "info/nightscout/comboctl/base/ComboException"), exc.what());
	}
}

template<typename Func, typename ReturnType = decltype(std::declval<Func>()())>
auto call_with_jni_rethrow(jni::JNIEnv &env, Func &&func) -> typename std::enable_if<!std::is_void<ReturnType>::value, ReturnType>::type
{
	try
	{
		return func();
	}
	catch (comboctl::invalid_call_exception const &exc)
	{
		jni::ThrowNew(env, jni::FindClass(env, "java/lang/IllegalStateException"), exc.what());
	}
	catch (comboctl::io_exception const &exc)
	{
		jni::ThrowNew(env, jni::FindClass(env, "info/nightscout/comboctl/base/ComboIOException"), exc.what());
	}
	catch (comboctl::gerror_exception const &exc)
	{
		if (g_error_matches(exc.get_gerror(), G_IO_ERROR, G_IO_ERROR_CANCELLED))
			jni::ThrowNew(env, jni::FindClass(env, "java/util/concurrent/CancellationException"), exc.what());
		else
			jni::ThrowNew(env, jni::FindClass(env, "info/nightscout/comboctl/base/BluetoothException"), exc.what());
	}
	catch (comboctl::exception const &exc)
	{
		jni::ThrowNew(env, jni::FindClass(env, "info/nightscout/comboctl/base/ComboException"), exc.what());
	}
}


///////////////////////////////////
// bluetooth_device JNI bindings //
///////////////////////////////////


// Instantiating a JNI object from C++, accessing underlying C++ methods,
// and passing it back to the JNI is tricky and requires more boilerplate
// code. For this reason, we use a trick: This class actually just wraps
// a bluetooth_device instance. Said instance is heap-allocated by the
// bluetooth_interface_jni class, and passed to bluetooth_device_jni by
// its pointer (through set_native_device_ptr()). This makes it possible
// to create the bluetooth_device_jni instance inside Java/Kotlin, which
// simplifies the code. Immediately after creating an instance, the
// Java/Kotlin code must call its setNativeDevicePtr function, which
// in turn calls this class' set_native_device_ptr() function.
//
// (Note that we refer to bluetooth_device here, not a "bluez_device".
// This is because bluez_device exists only as an internal class in
// the bluez_interface implementation. bluez_interface::connect() returns
// an object of type bluetooth_device. That is, the fact that this is
// a BlueZ backed device is not exposed at all. It is not needed to do
// that, since everything we need for the JNI bindings is already
// accessible via the bluetooth_device base class.)

class bluetooth_device_jni
{
public:
	explicit bluetooth_device_jni(JNIEnv &)
	{
		// We use a fixed-size read buffer (max 512 bytes per read).
		m_intermediate_receive_buffer.resize(512);
	}

	~bluetooth_device_jni()
	{
		delete m_device;
	}

	// Disable copy semantics, since copying won't work with this type.
	bluetooth_device_jni(bluetooth_device_jni const &) = delete;
	bluetooth_device_jni& operator = (bluetooth_device_jni const &) = delete;

	void connect(jni::JNIEnv &env)
	{
		assert(m_device != nullptr);
		call_with_jni_rethrow(env, [&]() { m_device->connect(); });
	}

	void disconnect(jni::JNIEnv &)
	{
		assert(m_device != nullptr);
		m_device->disconnect();
	}

	void cancel_send(jni::JNIEnv &)
	{
		assert(m_device != nullptr);
		m_device->cancel_send();
	}

	void cancel_receive(jni::JNIEnv &)
	{
		assert(m_device != nullptr);
		m_device->cancel_receive();
	}

	void send_impl(jni::JNIEnv &env, jni::Array<jni::jbyte> const &data)
	{
		assert(m_device != nullptr);

		jni::jsize length = data.Length(env);

		// Expand the send buffer as needed.
		if (m_intermediate_send_buffer.size() < length)
			m_intermediate_send_buffer.resize(length);

		// This copies the bytes from the JNI array into our send buffer.
		// Note that we don't just pass m_intermediate_send_buffer
		// to GetRegion(). Instead, we pass a pointer & length. This is
		// because the code above only expands m_intermediate_send_buffer.
		// If length is shorter than the existing size of that buffer,
		// it won't be resized. (This is intentional, to avoid unnecessary
		// reallocations.) If we passed m_intermediate_send_buffer to
		// GetRegion(), it may end up being instructed to copy more bytes
		// than there are available.
		data.GetRegion(env, 0, length, reinterpret_cast<jni::jbyte *>(m_intermediate_send_buffer.data()));

		// Now send the bytes over RFCOMM.
		call_with_jni_rethrow(env, [&]() { m_device->send(&m_intermediate_send_buffer[0], length); });
	}

	jni::Local<jni::Array<jni::jbyte>> receive_impl(jni::JNIEnv &env)
	{
		assert(m_device != nullptr);

		return call_with_jni_rethrow(env, [&]() {
			// Receive bytes over RFCOMM.
			int num_received_bytes = m_device->receive(&m_intermediate_receive_buffer[0], m_intermediate_receive_buffer.size());

			// Create a new JNI array and copy the receivd bytes into it.
			auto array = jni::Array<jni::jbyte>::New(env, num_received_bytes);
			array.SetRegion(env, 0, num_received_bytes, &m_intermediate_receive_buffer[0]);

			// Hand over the newly created and filled array.
			return array;
		});
	}

	void set_native_device_ptr(jni::JNIEnv &, jni::jlong native_device_ptr)
	{
		m_device = reinterpret_cast<comboctl::bluez_bluetooth_device *>(native_device_ptr);
		assert(m_device != nullptr);
	}

	static constexpr auto Name() { return "info/nightscout/comboctl/linux_bluez/BlueZDevice"; }


private:
	std::vector<jni::jbyte> m_intermediate_send_buffer;
	std::vector<jni::jbyte> m_intermediate_receive_buffer;
	comboctl::bluez_bluetooth_device *m_device = nullptr;
};


//////////////////////////////////
// bluez_interface JNI bindings //
//////////////////////////////////


struct bluetooth_device_no_return_callback_tag { static constexpr auto Name() { return "info/nightscout/comboctl/linux_bluez/BluetoothDeviceNoReturnCallback"; } };
struct bluetooth_device_no_return_callback_wrapper
{
	using jni_object = jni::Object<bluetooth_device_no_return_callback_tag>;
	using jni_class = jni::Class<bluetooth_device_no_return_callback_tag>;
};

struct bluetooth_device_boolean_return_callback_tag { static constexpr auto Name() { return "info/nightscout/comboctl/linux_bluez/BluetoothDeviceBooleanReturnCallback"; } };
struct bluetooth_device_boolean_return_callback_wrapper
{
	using jni_object = jni::Object<bluetooth_device_boolean_return_callback_tag>;
	using jni_class = jni::Class<bluetooth_device_boolean_return_callback_tag>;
};

class bluez_interface_jni
{
public:
	explicit bluez_interface_jni(JNIEnv &env)
		: m_java_vm(jni::GetJavaVM(env))
	{
		m_jni_no_return_klass = jni::NewGlobal(env, bluetooth_device_no_return_callback_wrapper::jni_class::Find(env));
		m_jni_boolean_return_klass = jni::NewGlobal(env, bluetooth_device_boolean_return_callback_wrapper::jni_class::Find(env));

		m_iface.run_in_thread([&]() {
			LOG(trace, "Attaching JVM to internal BlueZ interface thread");
			m_thread_env = jni::AttachCurrentThread(m_java_vm);
		});

		m_iface.on_thread_stopping([&]() {
			LOG(trace, "Detaching JVM from internal BlueZ interface thread");
			jni::DetachCurrentThread(m_java_vm, std::move(m_thread_env));
		});
	}

	~bluez_interface_jni()
	{
	}

	bluez_interface_jni(bluez_interface_jni const &) = delete;
	bluez_interface_jni& operator = (bluez_interface_jni const &) = delete;

	void shutdown(jni::JNIEnv &)
	{
		m_iface.teardown();
	}

	void start_discovery_impl(
		jni::JNIEnv &env,
		jni::String const &sdp_service_name,
		jni::String const &sdp_service_provider,
		jni::String const &sdp_service_description,
		jni::String const &bt_pairing_pin_code,
		bluetooth_device_no_return_callback_wrapper::jni_object &found_new_paired_device,
		bluetooth_device_no_return_callback_wrapper::jni_object &device_is_gone,
		bluetooth_device_boolean_return_callback_wrapper::jni_object &filter_device
	)
	{
		call_with_jni_rethrow(env, [&]() mutable {
			m_iface.start_discovery(
				jni::Make<std::string>(env, sdp_service_name),
				jni::Make<std::string>(env, sdp_service_provider),
				jni::Make<std::string>(env, sdp_service_description),
				jni::Make<std::string>(env, bt_pairing_pin_code),
				[&]() {
					m_jni_found_new_paired_device_object = jni::NewGlobal(*m_thread_env, found_new_paired_device);
					m_jni_device_is_gone_object = jni::NewGlobal(*m_thread_env, device_is_gone);
					m_jni_filter_device_object = jni::NewGlobal(*m_thread_env, filter_device);
				},
				[this]() {
					m_jni_found_new_paired_device_object.reset();
					m_jni_device_is_gone_object.reset();
					m_jni_filter_device_object.reset();
				},
				[this](comboctl::bluetooth_address paired_device_address) {
					auto jni_array = jni::Make<jni::Array<jni::jbyte>>(*m_thread_env, reinterpret_cast<jni::jbyte const *>(paired_device_address.data()), paired_device_address.size());

					auto method = m_jni_no_return_klass.GetMethod<void(jni::Array<jni::jbyte>)>(*m_thread_env, "invoke");
					m_jni_found_new_paired_device_object.Call(*m_thread_env, method, jni_array);
				},
				[this](comboctl::bluetooth_address removed_device_address) {
					auto jni_array = jni::Make<jni::Array<jni::jbyte>>(*m_thread_env, reinterpret_cast<jni::jbyte const *>(removed_device_address.data()), removed_device_address.size());

					auto method = m_jni_no_return_klass.GetMethod<void(jni::Array<jni::jbyte>)>(*m_thread_env, "invoke");
					m_jni_device_is_gone_object.Call(*m_thread_env, method, jni_array);
				},
				[this](comboctl::bluetooth_address device_address) -> bool {
					auto jni_array = jni::Make<jni::Array<jni::jbyte>>(*m_thread_env, reinterpret_cast<jni::jbyte const *>(device_address.data()), device_address.size());

					auto method = m_jni_boolean_return_klass.GetMethod<jni::jboolean(jni::Array<jni::jbyte>)>(*m_thread_env, "invoke");
					return m_jni_filter_device_object.Call(*m_thread_env, method, jni_array);
				}
			);
		});
	}

	void stop_discovery(jni::JNIEnv &env)
	{
		call_with_jni_rethrow(env, [&]() { m_iface.stop_discovery(); });
	}

	jni::Local<jni::String> get_adapter_friendly_name(jni::JNIEnv &env)
	{
		return jni::Make<jni::String>(env, m_iface.get_adapter_friendly_name());
	}

	void unpair_device_impl(jni::JNIEnv &env, jni::Array<jni::jbyte> const &device_address)
	{
		comboctl::bluetooth_address address = to_bt_address(env, device_address);
		m_iface.unpair_device(std::move(address));
	}

	jni::jlong get_device_impl(jni::JNIEnv &env, jni::Array<jni::jbyte> const &device_address)
	{
		return call_with_jni_rethrow(env, [&]() {
			comboctl::bluetooth_address address = to_bt_address(env, device_address);
			auto new_device_uptr = m_iface.get_device(address);

			// The caller takes ownership over the heap-allocated
			// device object, so it is safe to release ownership
			// from the unique_ptr by calling release().
			// (See the bluetooth_device_jni destructor to understand
			// this note about ownership.)
			return reinterpret_cast<jni::jlong>(new_device_uptr.release());
		});
	}

	static constexpr auto Name() { return "info/nightscout/comboctl/linux_bluez/BlueZInterface"; }


private:
	comboctl::bluez_interface m_iface;

	jni::JavaVM &m_java_vm;

	jni::UniqueEnv m_thread_env;

	jni::Global<bluetooth_device_no_return_callback_wrapper::jni_class> m_jni_no_return_klass;
	jni::Global<bluetooth_device_boolean_return_callback_wrapper::jni_class> m_jni_boolean_return_klass;

	jni::Global<bluetooth_device_no_return_callback_wrapper::jni_object> m_jni_found_new_paired_device_object;
	jni::Global<bluetooth_device_no_return_callback_wrapper::jni_object> m_jni_device_is_gone_object;
	jni::Global<bluetooth_device_boolean_return_callback_wrapper::jni_object> m_jni_filter_device_object;
};


} // unnamed namespace end


extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*)
{
	try
	{
		jni::JNIEnv &env { jni::GetEnv(*vm) };

		#define METHOD(MethodPtr, name) jni::MakeNativePeerMethod<decltype(MethodPtr), (MethodPtr)>(name)

		jni::RegisterNativePeer<bluez_interface_jni>(
			env,
			jni::Class<bluez_interface_jni>::Find(env),
			"nativePtr",
			jni::MakePeer<bluez_interface_jni>,
			"initialize",
			"finalize",
			METHOD(&bluez_interface_jni::shutdown, "shutdown"),
			METHOD(&bluez_interface_jni::stop_discovery, "stopDiscovery"),
			METHOD(&bluez_interface_jni::get_adapter_friendly_name, "getAdapterFriendlyName"),
			METHOD(&bluez_interface_jni::start_discovery_impl, "startDiscoveryImpl"),
			METHOD(&bluez_interface_jni::unpair_device_impl, "unpairDeviceImpl"),
			METHOD(&bluez_interface_jni::get_device_impl, "getDeviceImpl")
		);

		jni::RegisterNativePeer<bluetooth_device_jni>(
			env,
			jni::Class<bluetooth_device_jni>::Find(env),
			"nativePtr",
			jni::MakePeer<bluetooth_device_jni>,
			"initialize",
			"finalize",
			METHOD(&bluetooth_device_jni::connect, "connect"),
			METHOD(&bluetooth_device_jni::disconnect, "disconnect"),
			METHOD(&bluetooth_device_jni::cancel_send, "cancelSend"),
			METHOD(&bluetooth_device_jni::cancel_receive, "cancelReceive"),
			METHOD(&bluetooth_device_jni::send_impl, "sendImpl"),
			METHOD(&bluetooth_device_jni::receive_impl, "receiveImpl"),
			METHOD(&bluetooth_device_jni::set_native_device_ptr, "setNativeDevicePtr")
		);

		return jni::Unwrap(jni::jni_version_1_2);
	}
	catch (jni::PendingJavaException const &e)
	{
		LOG(fatal, "Caught PendingJavaException while setting up ComboCtl linux_bluez JNI bindings");
		std::terminate();
	}
	catch (std::exception const &e)
	{
		LOG(fatal, "Caught exception while setting up ComboCtl linux_bluez JNI bindings: {}", e.what());
		std::terminate();
	}
}
