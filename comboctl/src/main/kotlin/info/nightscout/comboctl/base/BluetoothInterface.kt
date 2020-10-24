package info.nightscout.comboctl.base

/**
 * Simple high-level interface to the system's Bluetooth stack.
 *
 * This interface offers the bare minimum to accomplish the following tasks:
 *
 * 1. Discover and pair Bluetooth devices with the given pairing PIN.
 *    (An SDP service is temporarily set up during discovery.)
 * 2. Connect to a Bluetooth device and enable RFCOMM-based blocking IO with it.
 *
 * The constructor must set up all necessary platform specific resources.
 */
interface BluetoothInterface {
    /**
     * Starts discovery of Bluetooth devices that haven't been paired yet.
     *
     * Discovery is actually a process that involves multiple parts:
     *
     * 1. An SDP service is set up. This service is then announced to
     *    Bluetooth devices. Each SDP device has a record with multiple
     *    attributes, three of which are defined by the sdp* arguments.
     * 2. Pairing is set up so that when a device tries to pair with the
     *    interface, it is authenticated using the given PIN.
     * 3. Each detected device is filtered via its address by calling
     *    the filterDevice callback. Only those devices whose addresses
     *    pass this filter are forwarded to the pairing authorization
     *    (see step 2 above). As a result, only the filtered devices
     *    can eventually have their address passed to the
     *    foundNewPairedDevice callback. This allows for filtering for
     *    specific device types by checking their address. (Typically,
     *    device that can be filtered this way have a common address
     *    prefix for example):
     *
     * The filtering step is optional, and disabled by default, that
     * is, the default filter lets everything through.
     *
     * Filtering is also applied to devices that were detected by the
     * Bluetooth stack as being gone. A device may be gone if for example
     * it was turned off, or is now out of range. If a device is detected
     * as gone, but gets filtered out, it is not reported as gone.
     *
     * Note that the callbacks typically are called from a different
     * thread, so make sure that thread synchronization primitives like
     * mutexes are used.
     *
     * This function may only be called after creating the interface
     * and after having called [stopDiscovery].
     *
     * @param sdpServiceName Name for the SDP service record.
     *        Must not be empty.
     * @param sdpServiceProvider Human-readable name of the provider of
     *        this SDP service record. Must not be empty.
     * @param sdpServiceDescription Human-readable description of
     *        this SDP service record. Must not be empty.
     * @param btPairingPin Bluetooth PIN code to use for pairing.
     *        Not to be confused with the Combo's 10-digit pairing PIN.
     *        This PIN is a sequence of characters used by the Bluetooth
     *        stack for its pairing/authorization.
     * @param foundNewPairedDevice Callback that gets invoked when
     *        a device was found that passed the filter (see filterDevice)
     *        and is paired.
     * @param deviceIsGone Callback that gets invoked when a device
     *        that previously was discovered by the Bluetooth stack
     *        is now gone and said device has been filtered.
     *        Default callback does nothing.
     * @param filterDevice Callback that gets invoked every time a device
     *        is discovered or detected as gone by the stack. If it
     *        returns true, then foundNewPairedDevice or deviceIsGone
     *        is called, depending on whether the device has been discovered
     *        or detected as gone. If filterDevice returns false, the
     *        activity is ignored; discovered devices are skipped, devices
     *        detected as gone are not reported as such.
     *        Default callback just lets everything pass through.
     * @throws IllegalStateException if this is called again after
     *         discovery has been started already, or if the interface
     *         is in a state in which discovery is not possible, such as
     *         a Bluetooth subsystem that has been shut down.
     * @throws BluetoothException if discovery fails due to an underlying
     *         Bluetooth issue.
     */
    fun startDiscovery(
        sdpServiceName: String,
        sdpServiceProvider: String,
        sdpServiceDescription: String,
        btPairingPin: String,
        foundNewPairedDevice: (deviceAddress: BluetoothAddress) -> Unit,
        deviceIsGone: (deviceAddress: BluetoothAddress) -> Unit = { Unit },
        filterDevice: (deviceAddress: BluetoothAddress) -> Boolean = { true }
    )

    /**
     * Stops any ongoing discovery.
     *
     * If no discovery is going on, this does nothing.
     */
    fun stopDiscovery()

    /**
     * Unpairs a previously paired device.
     *
     * If the given device wasn't already paired, this does nothing.
     *
     * @param deviceAddress Address of Bluetooth device to unpair.
     */
    fun unpairDevice(deviceAddress: BluetoothAddress)

    /**
     * Creates and returns a BluetoothDevice for the given address.
     *
     * This merely creates a new BluetoothDevice instance. It does
     * not connect to the device. Use [BluetoothDevice.connect]
     * for that purpose.
     *
     * NOTE: Creating multiple instances to the same device is
     * possible, but untested.
     *
     * @return BluetoothDevice instance for the device with the
     *         given address
     * @throws IllegalStateException if the interface is in a state
     *         in which accessing devices is not possible, such as
     *         a Bluetooth subsystem that has been shut down.
     */
    fun getDevice(deviceAddress: BluetoothAddress): BluetoothDevice
}
