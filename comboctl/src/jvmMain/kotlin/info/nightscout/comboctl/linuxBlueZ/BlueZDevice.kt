package info.nightscout.comboctl.linuxBlueZ

import info.nightscout.comboctl.base.BluetoothAddress
import info.nightscout.comboctl.base.BluetoothDevice
import info.nightscout.comboctl.base.BluetoothInterface

/**
 * Class representing a Bluetooth device accessible through BlueZ.
 *
 * Users typically do not instantiate this directly. Instead,
 * [BlueZInterface]'s implementation of [BluetoothInterface.getDevice]
 * instantiates and returns this (as a [BluetoothDevice]).
 *
 * The nativeDevicePtr is an opaque pointer to the internal
 * C++ object (not to be confused with the C++ object that is
 * bound to BlueZDevice) that holds the data about the BlueZ
 * device and its RFCOMM socket.
 */
class BlueZDevice(
    bluetoothInterface: BluetoothInterface,
    nativeDevicePtr: Long,
    override val address: BluetoothAddress
) : BluetoothDevice(bluetoothInterface) {
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
    override fun blockingSend(dataToSend: List<Byte>) = sendImpl(dataToSend.toByteArray())
    override fun blockingReceive(): List<Byte> = receiveImpl().toList()

    external override fun connect()
    external override fun disconnect()

    external override fun cancelSend()
    external override fun cancelReceive()

    // Private external C++ functions.

    private external fun sendImpl(data: ByteArray)
    private external fun receiveImpl(): ByteArray

    private external fun setNativeDevicePtr(nativeDevicePtr: Long)

    // jni.hpp specifics.

    private external fun initialize()
    private external fun finalize()

    // NOTE: This is never used in Kotlin code
    // but it is needed by jni.hpp for the C++
    // bindings, so don't remove nativePtr.
    private var nativePtr: Long = 0
}
