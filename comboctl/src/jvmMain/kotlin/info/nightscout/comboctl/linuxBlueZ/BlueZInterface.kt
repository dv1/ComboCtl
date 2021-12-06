package info.nightscout.comboctl.linuxBlueZ

import info.nightscout.comboctl.base.BluetoothAddress
import info.nightscout.comboctl.base.BluetoothDevice
import info.nightscout.comboctl.base.BluetoothInterface
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.Logger
import info.nightscout.comboctl.base.NUM_BLUETOOTH_ADDRESS_BYTES
import info.nightscout.comboctl.base.toBluetoothAddress
import info.nightscout.comboctl.base.toHexString

private val logger = Logger.get("BlueZInterface")

// Callback wrappers. We need these to be able to invoke
// our callbacks from C++. We can't invoke the function
// literals directly, because there is no stable, known
// way of accessing said function literals through JNI.

private class IntArgumentNoReturnCallback(val func: (argument: Int) -> Unit) {
    fun invoke(argument: Int) {
        func(argument)
    }
}

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
        System.loadLibrary("linuxBlueZCppJNI")
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

    // Some of the overrides aren't directly external, since they may
    // require one of the Callback wrappers to be applied first, or they
    // may require conversion to/from ByteArrays due to JNI restrictions.

    override var onDeviceUnpaired: (deviceAddress: BluetoothAddress) -> Unit = { }
        set(value) { onDeviceUnpairedImpl(BluetoothDeviceNoReturnCallback(value)) }

    override var deviceFilterCallback: (deviceAddress: BluetoothAddress) -> Boolean = { true }
        set(value) { setDeviceFilterImpl(BluetoothDeviceBooleanReturnCallback(value)) }

    override fun startDiscovery(
        sdpServiceName: String,
        sdpServiceProvider: String,
        sdpServiceDescription: String,
        btPairingPin: String,
        discoveryDuration: Int,
        onDiscoveryStopped: (reason: BluetoothInterface.DiscoveryStoppedReason) -> Unit,
        onFoundNewPairedDevice: (deviceAddress: BluetoothAddress) -> Unit
    ) {
        startDiscoveryImpl(
            sdpServiceName,
            sdpServiceProvider,
            sdpServiceDescription,
            btPairingPin,
            discoveryDuration,
            IntArgumentNoReturnCallback {
                onDiscoveryStopped(
                    when (it) {
                        0 -> BluetoothInterface.DiscoveryStoppedReason.MANUALLY_STOPPED
                        1 -> BluetoothInterface.DiscoveryStoppedReason.DISCOVERY_ERROR
                        2 -> BluetoothInterface.DiscoveryStoppedReason.DISCOVERY_TIMEOUT
                        else -> throw Error("Invalid discovery stop reason")
                    }
                )
            },
            BluetoothDeviceNoReturnCallback(onFoundNewPairedDevice)
        )
    }

    external override fun stopDiscovery()

    fun unpairDevice(deviceAddress: BluetoothAddress) = unpairDeviceImpl(deviceAddress.toByteArray())

    override fun getDevice(deviceAddress: BluetoothAddress): BluetoothDevice {
        val nativeDevicePtr = getDeviceImpl(deviceAddress.toByteArray())
        return BlueZDevice(this, nativeDevicePtr, deviceAddress)
    }

    external override fun getAdapterFriendlyName(): String

    override fun getPairedDeviceAddresses(): Set<BluetoothAddress> {
        val result = mutableSetOf<BluetoothAddress>()

        // Passing a collection of ByteArrays from C+++ to
        // the JVM is difficult and error prone, so we use
        // a trick. ONE ByteArray is transferred, with the
        // bytes of ALL Bluetooth addresses inside. So, to
        // get these addresses, we split up this ByteArray
        // and produce a Set out of this.

        val addressBytes = getPairedDeviceAddressesImpl()
        val numAddresses = addressBytes.size / NUM_BLUETOOTH_ADDRESS_BYTES

        for (index in 0 until numAddresses) {
            try {
                val range = IntRange(index * NUM_BLUETOOTH_ADDRESS_BYTES, (index + 1) * NUM_BLUETOOTH_ADDRESS_BYTES - 1)
                val bluetoothAddress = BluetoothAddress(addressBytes.slice(range))
                result.add(bluetoothAddress)
            } catch (e: Exception) {
                // XXX: This should never happen, since an
                // address is considered invalid if it
                // doesn't consist of exactly 6 bytes.
                logger(LogLevel.WARN) { "Got invalid Bluetooth address ${addressBytes.toHexString()}; skipping" }
            }
        }

        return result
    }

    // Private external C++ functions.

    private external fun startDiscoveryImpl(
        sdpServiceName: String,
        sdpServiceProvider: String,
        sdpServiceDescription: String,
        btPairingPin: String,
        discoveryDuration: Int,
        discoveryStopped: IntArgumentNoReturnCallback,
        foundNewPairedDevice: BluetoothDeviceNoReturnCallback
    )

    private external fun onDeviceUnpairedImpl(callback: BluetoothDeviceNoReturnCallback)

    private external fun setDeviceFilterImpl(callback: BluetoothDeviceBooleanReturnCallback)

    private external fun unpairDeviceImpl(deviceAddress: ByteArray)

    private external fun getDeviceImpl(deviceAddress: ByteArray): Long

    private external fun getPairedDeviceAddressesImpl(): ByteArray

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

    if (logLevel.numericLevel <= Logger.threshold.numericLevel)
        Logger.backend.log(tag, logLevel, null, message)
}
