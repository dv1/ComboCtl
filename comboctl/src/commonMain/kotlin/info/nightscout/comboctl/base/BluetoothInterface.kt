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
     * Callback for when a previously paired device is unpaired.
     *
     * This is independent of the device discovery. That is, this callback
     * can be invoked by the implementation even when discovery is inactive.
     *
     * The unpairing may have been done via [unpairDevice] or via some
     * sort of system settings.
     *
     * Note that this callback may be called from another thread. Using
     * synchronization primitives to avoid race conditions is recommended.
     * Also, implementations must make sure that setting the callback
     * can not cause data races; that is, it must not happen that a new
     * callback is set while the existing callback is invoked due to an
     * unpaired device.
     *
     * Do not spend too much time in this callback, since it may block
     * internal threads.
     *
     * Exceptions thrown by this callback are logged, but not propagated.
     *
     * See the note at [getPairedDeviceAddresses] about using this callback
     * and that function in the correct order.
     */
    var onDeviceUnpaired: (deviceAddress: BluetoothAddress) -> Unit

    /**
     * Callback for filtering devices based on their Bluetooth addresses.
     *
     * This is used for checking if a device shall be processed or ignored.
     * When a newly paired device is discovered, or a paired device is
     * unpaired, this callback is invoked. If it returns false, then
     * the device is ignored, and those callbacks don't get called.
     *
     * Note that this callback may be called from another thread. Using
     * synchronization primitives to avoid race conditions is recommended.
     * Also, implementations must make sure that setting the callback
     * can not cause data races.
     *
     * Do not spend too much time in this callback, since it may block
     * internal threads.
     *
     * IMPORTANT: This callback must not throw.
     *
     * The default callback always returns true.
     */
    var deviceFilter: (deviceAddress: BluetoothAddress) -> Boolean

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
     *    the [deviceFilter] callback. Only those devices whose addresses
     *    pass this filter are forwarded to the pairing authorization
     *    (see step 2 above). As a result, only the filtered devices
     *    can eventually have their address passed to the
     *    foundNewPairedDevice callback.
     *
     * Note that the callbacks typically are called from a different
     * thread, so make sure that thread synchronization primitives like
     * mutexes are used.
     *
     * Do not spend too much time in the [foundNewPairedDevice], since it
     * may block internal threads.
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
     *        a device was found that passed the filter (see [deviceFilter])
     *        and is paired. Exceptions thrown by this callback are logged,
     *        but not propagated.
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
        foundNewPairedDevice: (deviceAddress: BluetoothAddress) -> Unit
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

    /**
     * Returns the friendly (= human-readable) name for the adapter.
     */
    fun getAdapterFriendlyName(): String

    /**
     * Returns a set of addresses of paired Bluetooth devices.
     *
     * The [deviceFilter] is applied here. That is, the returned set
     * only contains addresses of devices which passed that filter.
     *
     * The return value is a new set, not a reference to an internal
     * one, so it is safe to use even if devices get paired/unpaired
     * in the meantime.
     *
     * To avoid a race condition where an unpaired device is missed
     * when an application is starting, it is recommended to first
     * assign the [onDeviceUnpaired] callback, and then retrieve the
     * list of paired addresses here. If it is done the other way
     * round, it is possible that between the [getPairedDeviceAddresses]
     * call and the [onDeviceUnpaired] assignment, a device is
     * unpaired, and thus does not get noticed.
     */
    fun getPairedDeviceAddresses(): Set<BluetoothAddress>
}
