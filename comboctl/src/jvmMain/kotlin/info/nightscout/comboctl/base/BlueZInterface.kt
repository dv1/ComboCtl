package info.nightscout.comboctl.base

// Callback wrappers. We need these to be able to invoke
// our callbacks from C++. We can't invoke the function
// literals directly, because there is no stable, known
// way of accessing said function literals through JNI.

private class BluetoothDeviceNoReturnCallback(val func: (deviceAddress: BluetoothAddress) -> Unit) {
    fun invoke(deviceAddressBytes: ByteArray) {
        func(deviceAddressBytes.toBluetoothAddress())
    }
}

private class BluetoothDeviceBooleanReturnCallback(val func: (deviceAddress: BluetoothAddress) -> Boolean) {
    fun invoke(deviceAddressBytes: ByteArray): Boolean {
        return func(deviceAddressBytes.toBluetoothAddress())
    }
}

class BlueZInterface : BluetoothInterface {
    init {
        // This loads the .so file with the C++ code inside.
        // That's where the implementation of the external
        // functions are located.
        System.loadLibrary("linux-bluez-cpp")
        // This calls the constructor of the native C++ class.
        initialize()
    }

    /**
     * Immediately shuts down the interface.
     *
     * This function is necessary to make sure BlueZ is shut down properly.
     * It removes the custom BlueZ authentication agent and cleans up
     * any DBus objects and connections that might have been created.
     *
     * Previously connected devices can be used even after shutting down,
     * but only to a limited degree. Their own functions for connecting,
     * disconnecting, sending, and receiving still work. However, any
     * calls to this interface's functions will either cause a no-op
     * (if it is a function that shuts down or stops or disconnects from
     * from something) or throw an [IllegalStateException].
     *
     * If discovery is ongoing, this implicitly calls stopDiscovery().
     *
     * A repeated call will do nothing.
     */
    external fun shutdown()

    // Base class overrides.

    // This isn't directly external, since we have to wrap
    // the function literals in the wrapper classes first.
    override fun startDiscovery(
        sdpServiceName: String,
        sdpServiceProvider: String,
        sdpServiceDescription: String,
        btPairingPin: String,
        foundNewPairedDevice: (deviceAddress: BluetoothAddress) -> Unit,
        deviceIsGone: (deviceAddress: BluetoothAddress) -> Unit,
        filterDevice: (deviceAddress: BluetoothAddress) -> Boolean
    ) {
        startDiscoveryImpl(
            sdpServiceName,
            sdpServiceProvider,
            sdpServiceDescription,
            btPairingPin,
            BluetoothDeviceNoReturnCallback(foundNewPairedDevice),
            BluetoothDeviceNoReturnCallback(deviceIsGone),
            BluetoothDeviceBooleanReturnCallback(filterDevice)
        )
    }

    external override fun stopDiscovery()

    // This isn't directly external, since we have to convert
    // the Bluetooth address to a bytearray first.
    override fun unpairDevice(deviceAddress: BluetoothAddress) = unpairDeviceImpl(deviceAddress.toByteArray())

    override fun getDevice(deviceAddress: BluetoothAddress): BluetoothDevice {
        val nativeDevicePtr = getDeviceImpl(deviceAddress.toByteArray())
        return BlueZDevice(this, nativeDevicePtr, deviceAddress)
    }

    external override fun getAdapterFriendlyName(): String

    // Private external C++ functions.

    private external fun startDiscoveryImpl(
        sdpServiceName: String,
        sdpServiceProvider: String,
        sdpServiceDescription: String,
        btPairingPin: String,
        foundNewPairedDevice: BluetoothDeviceNoReturnCallback,
        deviceIsGone: BluetoothDeviceNoReturnCallback,
        filterDevice: BluetoothDeviceBooleanReturnCallback
    )

    private external fun unpairDeviceImpl(deviceAddress: ByteArray)

    private external fun getDeviceImpl(deviceAddress: ByteArray): Long

    // jni.hpp specifics.

    private external fun initialize()
    private external fun finalize()

    // NOTE: This is never used in Kotlin code
    // but it is needed by jni.hpp for the C++
    // bindings, so don't remove nativePtr.
    private var nativePtr: Long = 0
}

internal fun nativeLoggerCall(tag: String, cppLogLevel: Int, message: String) {
    val logLevel = when (cppLogLevel) {
        0 -> LogLevel.VERBOSE
        1 -> LogLevel.DEBUG
        2 -> LogLevel.INFO
        3 -> LogLevel.WARN
        4 -> LogLevel.ERROR
        5 -> LogLevel.ERROR
        else -> return
    }

    Logger.backend.log(tag, logLevel, null, message)
}
