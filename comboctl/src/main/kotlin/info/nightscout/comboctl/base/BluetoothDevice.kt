package info.nightscout.comboctl.base

/**
 * Abstract class for operating Bluetooth devices.
 *
 * Subclasses implement blocking IO to allow for RFCOMM-based
 * IO with a Bluetooth device.
 *
 * Subclass instances are created by BluetoothInterface subclasses.
 */
abstract class BluetoothDevice(val bluetoothInterface: BluetoothInterface) : BlockingComboIO() {
    /**
     * The device's Bluetooth address.
     */
    abstract val address: BluetoothAddress

    /**
     * Set up the device's RFCOMM connection.
     *
     * @throws BluetoothException if connection fails due to an underlying
     *         Bluetooth issue.
     * @throws ComboIOException if connection fails due to an underlying
     *         IO issue.
     */
    abstract fun connect()

    /**
     * Explicitly disconnect the device's RFCOMM connection now.
     *
     * After this call, this BluetoothDevice instance cannot be
     * used anymore.
     */
    abstract fun disconnect()

    /**
     * Unpairs this device.
     *
     * This is functionally equivalent to calling [BluetoothInterface.unpairDevice]
     * and passing this device's address to that function. [unpair] is provided
     * to be able to unpair this very device without having to carry around a
     * reference to a [BluetoothInterface].
     *
     * Once this was called, this [BluetoothDevice] instance must not be used anymore.
     * [disconnect] may be called, but will be a no-op. [connect], [send] and [receive]
     * will throw an [IllegalStateException].
     *
     * If the device is connected when this is called, [disconnect] is implicitely
     * called before unpairing.
     */
    fun unpair() {
        bluetoothInterface.unpairDevice(address)
    }
}
