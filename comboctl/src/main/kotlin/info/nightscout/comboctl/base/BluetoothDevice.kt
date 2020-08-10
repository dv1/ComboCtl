package info.nightscout.comboctl.base

/**
 * Abstract class for operating Bluetooth devices.
 *
 * Subclasses implement blocking IO to allow for RFCOMM-based
 * IO with a Bluetooth device.
 *
 * Subclass instances are created by BluetoothInterface subclasses.
 */
abstract class BluetoothDevice : BlockingComboIO() {
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
     * Explicitely disconnect the device's RFCOMM connection now.
     *
     * After this call, this BluetoothDevice instance cannot be
     * used anymore.
     */
    abstract fun disconnect()
}
