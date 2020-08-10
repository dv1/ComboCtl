package info.nightscout.comboctl.linux_bluez

import info.nightscout.comboctl.base.*
import kotlinx.coroutines.*

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

    // Base class overrides.

    final external override fun shutdown()

    // This isn't directly external, since we have to wrap
    // the function literals in the wrapper classes first.
    final override fun startDiscovery(
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

    final external override fun stopDiscovery()

    // This isn't directly external, since we have to convert
    // the Bluetooth address to a bytearray first.
    final override fun unpairDevice(deviceAddress: BluetoothAddress) = unpairDeviceImpl(deviceAddress.toByteArray())

    final override fun getDevice(deviceAddress: BluetoothAddress): BluetoothDevice {
        val nativeDevicePtr = getDeviceImpl(deviceAddress.toByteArray())
        return BlueZDevice(nativeDevicePtr, deviceAddress)
    }

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

    external fun unpairDeviceImpl(deviceAddress: ByteArray)

    private external fun getDeviceImpl(deviceAddress: ByteArray): Long

    // jni.hpp specifics.

    protected external fun initialize()
    protected external fun finalize()

    private var nativePtr: Long = 0
}
