package info.nightscout.comboctl.linux_bluez

import info.nightscout.comboctl.base.BluetoothAddress
import info.nightscout.comboctl.base.BluetoothDevice

/**
 * Class representing a Bluetooth device accessible through BlueZ.
 *
 * Users typically do not instantiate this directly. Instead,
 * [BlueZInterface]'s implementation of [BluetoothInterfaces.connect]
 * instantiates and returns this (as a [BluetoothDevice]).
 *
 * The nativeDevicePtr is an opaque pointer to the internal
 * C++ object (not to be confused with the C++ object that is
 * bound to BlueZDevice) that holds the data about the BlueZ
 * device and its RFCOMM socket.
 */
class BlueZDevice(nativeDevicePtr: Long, final override val address: BluetoothAddress) : BluetoothDevice() {
    init {
        // This calls the constructor of the native C++ class.
        initialize()
        // Passes the opaque pointer to the internal C++ object
        // to the native class.
        setNativeDevicePtr(nativeDevicePtr)
    }

    // Base class overrides.

    // These aren't directly external, since we have to convert
    // the byte lists to bytearrays first.
    final override fun blockingSend(dataToSend: List<Byte>) = sendImpl(dataToSend.toByteArray())
    final override fun blockingReceive(): List<Byte> = receiveImpl().toList()

    final external override fun connect()
    final external override fun disconnect()

    final external override fun cancelSend()
    final external override fun cancelReceive()

    // Private external C++ functions.

    private external fun sendImpl(data: ByteArray)
    private external fun receiveImpl(): ByteArray

    private external fun setNativeDevicePtr(nativeDevicePtr: Long)

    // jni.hpp specifics.

    protected external fun initialize()
    protected external fun finalize()

    private var nativePtr: Long = 0
}
