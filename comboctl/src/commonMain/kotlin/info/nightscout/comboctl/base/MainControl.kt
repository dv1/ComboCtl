package info.nightscout.comboctl.base

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

private val logger = Logger.get("MainControl")

/**
 * Main interface for controlling Combo pumps via ComboCtl.
 *
 * This is the high level interface for using ComboCtl.
 * Programs primarily use this class, along with [Pump].
 *
 * This class takes care of managing pump discovery via
 * Bluetooth and performing the pairing once a pump is
 * discovered, along with connecting to pumps.
 *
 * First step after creating an instance of this class
 * is to call [startBackgroundEventHandlingLoop]. Without
 * it, discovery won't work, and the caller won't be able
 * to be notified when a paired Combo got unpaired.
 * Also make sure to call [stopBackgroundEventHandlingLoop]
 * when this instance is no longer needed.
 *
 * The [pairingPINCallback] is called when the control
 * needs the 10-digit pairing PIN that is shown on the
 * Combo's LCD. This typically leads to a dialog box
 * shown on screen with a widget for entering the PIN.
 * If the user cancels that entry (for example by pressing
 * a "Cancel" button in that dialog box), the callback
 * should throw an exception to have the pairing process
 * aborted.
 *
 * This class launches an internal background coroutine
 * for handling certain events (like "new pump discovered").
 * Any notifications coming from [bluetoothInterface]'s
 * callbacks will be serialized and pushed through a
 * [Channel] to that background coroutine. In there, a
 * loop runs that handles incoming events. This is done
 * that way because [bluetoothInterface]'s callbacks may
 * be called by a different internal thread (depending
 * on the [BluetoothInterface] implementation), so if
 * the events were handled directly in the callback,
 * we'd risk getting race conditions. By serializing
 * the events and handling them in that coroutine, we
 * avoid that.
 *
 * Continuing with threads, note that this API is _not_
 * thread safe. In particular, if one thread called
 * an API function, and [backgroundEventHandlingScope]
 * is running in a different thread, undefined behavior
 * will occur. Easiest - and the recommended - approach
 * is to make sure the one specific thread is the one
 * that calls the API functions.
 *
 * @param backgroundEventHandlingScope [CoroutineScope]
 *        to handle events in.
 * @param bluetoothInterface Bluetooth interface to use
 *        for discovery, pairing, and connection setup.
 * @param persistentPumpStateStoreBackend Backend used
 *        for retrieving pump state stores whenever
 *        [getPump] is called or a pump is paired during
 *        the discovery process.
 * @param pairingPINCallback Callback that gets invoked
 *        as soon as a pump pairing process needs the
 *        10-digit-PIN.
 */
class MainControl(
    private val backgroundEventHandlingScope: CoroutineScope,
    private val bluetoothInterface: BluetoothInterface,
    private val persistentPumpStateStoreBackend: PersistentPumpStateStoreBackend,
    private val pairingPINCallback: (
        newPumpAddress: BluetoothAddress,
        previousAttemptFailed: Boolean,
        getPINDeferred: CompletableDeferred<PairingPIN>
    ) -> Unit
) {
    private enum class EventType {
        // Newly Bluetooth-paired pump detected.
        NEW_PAIRED_PUMP,
        // Bluetooth-paired pump is now unpaired.
        PUMP_UNPAIRED
    }

    private var backgroundEventHandlingLoopJob: Job? = null
    private val backgroundEventChannel = Channel<Pair<BluetoothAddress, EventType>>(Channel.UNLIMITED)

    private var discoveryRunning = false

    /* *
     * Callback to notify callers about a newly paired pump.
     *
     * It is not required to set this for a successful pairing;
     * this is purely for notification.
     *
     * This will be called during discovery, so [startDiscovery] must
     * be called in order for this callback to actually be used.
     */
    var onNewPairedPump: (pumpAddress: BluetoothAddress) -> Unit = { Unit }

    /**
     * Callback for when a previously paired pump got unpaired.
     *
     * This will be called in the background event handling loop,
     * so [startBackgroundEventHandlingLoop] must be called in
     * order for this callback to actually be used.
     */
    var onPumpUnpaired: (pumpAddress: BluetoothAddress) -> Unit = { Unit }

    /**
     * Callback for when an exception is thrown in the background loop.
     *
     * Since that loop runs in a separate coroutine, a simple try-catch
     * block won't work, hence this callback.
     *
     * The cause for the failure is supplied along with the Bluetooth
     * address of the pump associated with the failure.
     *
     * This will be called in the background event handling loop,
     * so [startBackgroundEventHandlingLoop] must be called in
     * order for this callback to actually be used.
     *
     * IMPORTANT: This callback must _not_ throw exceptions,
     * since it is called as part of an ongoing error handling.
     */
    var onBackgroundLoopException: (pumpAddress: BluetoothAddress, e: Exception) -> Unit = { _, _ -> Unit }

    init {
        logger(LogLevel.INFO) { "Main control started" }

        // We get the addresses of the currently paired Bluetooth
        // devices (which pass the device filter) and the addresses
        // of the available pump state stores. The goal is to see
        // if there are stores that have no corresponding paired
        // Bluetooth device. If so, then this store is stale. This
        // may happen if the user unpaired the device while ComboCtl
        // was not running. Then, when ComboCtl is started, the
        // store of the unpaired device is still around. To fix this,
        // we check the stores here.

        // First, log the paired device addresses.
        val pairedDeviceAddresses = bluetoothInterface.getPairedDeviceAddresses()
        logger(LogLevel.DEBUG) { "${pairedDeviceAddresses.size} known device(s)" }
        for (deviceAddress in pairedDeviceAddresses) {
            logger(LogLevel.DEBUG) { "Known device: $deviceAddress" }
        }

        // Now go through each store and check if its address is also
        // present in the pairedDeviceAddresses set. If not, the store
        // is stale, and needs to be erased.
        val availableStoreAddresses = persistentPumpStateStoreBackend.getAvailableStoreAddresses()
        logger(LogLevel.DEBUG) { "${availableStoreAddresses.size} available store(s)" }
        for (storeAddress in availableStoreAddresses) {
            logger(LogLevel.DEBUG) { "Available store: $storeAddress" }

            val pairedDevicePresent = pairedDeviceAddresses.contains(storeAddress)
            if (!pairedDevicePresent) {
                logger(LogLevel.DEBUG) { "There is no paired device for this store; erasing store" }
                // TODO: What if an exception is thrown here?
                val store = persistentPumpStateStoreBackend.requestStore(storeAddress)
                store.reset()
            }
        }

        bluetoothInterface.onDeviceUnpaired = {
            deviceAddress -> backgroundEventHandlingScope.launch {
                backgroundEventChannel.send(Pair(deviceAddress, EventType.PUMP_UNPAIRED))
            }
        }

        bluetoothInterface.deviceFilter = { deviceAddress -> isCombo(deviceAddress) }
    }

    /**
     * Starts a loop in a coroutine that handles incoming events.
     *
     * It is recommended to set the [onNewPairedPump], [onPumpUnpaired],
     * and [onBackgroundLoopException] callbacks before starting
     * the loop to make sure no events are missed.
     *
     * @throws IllegalStateException if the loop was already started.
     */
    fun startBackgroundEventHandlingLoop() {
        if (backgroundEventHandlingLoopJob != null)
            throw IllegalStateException("Background event handling loop already running")

        backgroundEventHandlingLoopJob = backgroundEventHandlingScope.launch {
            logger(LogLevel.DEBUG) { "Background event handling loop started" }

            while (true) {
                val (pumpAddress, eventType) = backgroundEventChannel.receive()

                when (eventType) {
                    EventType.NEW_PAIRED_PUMP -> {
                        // Note that we do _not_ rethrow exceptions here,
                        // unlike in the try-catch block further below.
                        // This is because this try-catch block runs in
                        // its separate coroutine that runs in the
                        // background. The exception would not be propagated
                        // in a way that is useful here. For this reason,
                        // instead, we notify the caller by invoking the
                        // onBackgroundLoopException() callback.
                        try {
                            logger(LogLevel.DEBUG) { "Found device with address $pumpAddress" }

                            if (persistentPumpStateStoreBackend.hasValidStore(pumpAddress)) {
                                logger(LogLevel.DEBUG) { "Skipping added device since it has already been paired" }
                            } else {
                                performPairing(backgroundEventHandlingScope, pumpAddress)
                                onNewPairedPump(pumpAddress)
                            }
                        } catch (e: CancellationException) {
                            logger(LogLevel.ERROR) { "Aborting pairing since this coroutine got cancelled" }
                            abortDiscovery(e)
                            onBackgroundLoopException(pumpAddress, e)
                            throw e
                        } catch (e: Exception) {
                            logger(LogLevel.ERROR) { "Caught exception while pairing to pump with address $pumpAddress: $e" }
                            abortDiscovery(e)
                            onBackgroundLoopException(pumpAddress, e)
                        }
                    }
                    EventType.PUMP_UNPAIRED -> {
                        // NOTE: Not performing a Bluetooth unpairing here,
                        // since we reach this location _because_ a device
                        // was removed (= unpaired) from the system's
                        // Bluetooth stack (for example because the user
                        // unpaired the pump via the Bluetooth settings).

                        logger(LogLevel.DEBUG) { "Previously paired device with address $pumpAddress removed" }

                        if (persistentPumpStateStoreBackend.hasValidStore(pumpAddress)) {
                            // Reset the pump state store for the removed pump.
                            try {
                                val persistentPumpStateStore = persistentPumpStateStoreBackend.requestStore(pumpAddress)
                                persistentPumpStateStore.reset()
                            } catch (e: PumpStateStoreAccessException) {
                                logger(LogLevel.ERROR) { "Caught exception while resetting store of removed pump $pumpAddress: $e" }
                                abortDiscovery(e)
                                onBackgroundLoopException(pumpAddress, e)
                            }

                            // Now run the user supplied callback, and catch
                            // any exception it might throw.
                            try {
                                onPumpUnpaired(pumpAddress)
                            } catch (e: Exception) {
                                logger(LogLevel.ERROR) {
                                    "Caught exception while running onPumpUnpaired callback for pump with address $pumpAddress: $e"
                                }
                                abortDiscovery(e)
                                onBackgroundLoopException(pumpAddress, e)
                            }
                        } else {
                            logger(LogLevel.DEBUG) { "Skipping removed device since it has not been paired or already was unpaired" }
                        }
                    }
                }
            }
        }
    }

    /**
     * Stops a running event handling loop and its coroutine.
     *
     * If there is no loop running, this does nothing.
     *
     * If discovery is running, it is automatically stopped by this function.
     */
    fun stopBackgroundEventHandlingLoop() {
        if (discoveryRunning)
            stopDiscovery()

        if (backgroundEventHandlingLoopJob != null) {
            backgroundEventHandlingLoopJob!!.cancel()
            backgroundEventHandlingLoopJob = null
        }
    }

    /**
     * Starts Bluetooth discovery to look for unpaired pumps.
     *
     * This manages the Bluetooth device discovery and the pairing
     * process with new pumps. Once an unpaired pump is discovered,
     * the Bluetooth implementation pairs with it, using the
     * [Constants.BT_PAIRING_PIN] PIN code (not to be confused with
     * the 10-digit Combo PIN). The discovery process runs in the
     * background in the supplied [backgroundEventHandlingScope].
     * When the Bluetooth pairing is done, the Combo specific pairing
     * is performed using [Pump.performPairing]. The pairing process
     * fills a [PersistentState] instance specific to the discovered
     * pump. Said state is retrieved by calling [requestPersistentState].
     * Once pairing is done, that state will have been filled with
     * pairing data (cipher keys etc.), and a regular connection can
     * be established with the pump.
     *
     * The background event handling loop must have been started via
     * [startBackgroundEventHandlingLoop] prior to starting discovery.
     *
     * In case of a failure during discovery, the [onBackgroundLoopException]
     * callback is called, and the discovery is aborted, meaning that even
     * if the failure is specific to the pump itself, the entire discovery
     * process is aborted. This is because it is not possible to guarantee
     * that the failure didn't affect other parts of the discovery
     * process. This can for example happen if there is something wrong
     * with the Bluetooth stack. So, in case of failure during discovery,
     * it is aborted. It can be restarted by calling [startDiscovery]
     * again, however.
     *
     * @param backgroundEventHandlingScope [CoroutineScope] to run the
     *        background pairing activities in.
     * @throws IllegalStateException if a discovery is already ongoing
     *         or if the background event handling loop is not running.
     * @throws BluetoothException if discovery fails due to an underlying
     *         Bluetooth issue.
     */
    fun startDiscovery() {
        if (backgroundEventHandlingLoopJob == null)
            throw IllegalStateException("Background event handling loop is not running")

        if (discoveryRunning)
            throw IllegalStateException("Discovery already ongoing")

        logger(LogLevel.DEBUG) { "Starting discovery" }

        this.onNewPairedPump = onNewPairedPump

        try {
            bluetoothInterface.startDiscovery(
                Constants.BT_SDP_SERVICE_NAME,
                "ComboCtl SDP service",
                "ComboCtl",
                Constants.BT_PAIRING_PIN,
                {
                    deviceAddress -> backgroundEventHandlingScope.launch {
                        backgroundEventChannel.send(Pair(deviceAddress, EventType.NEW_PAIRED_PUMP))
                    }
                }
            )

            discoveryRunning = true

            logger(LogLevel.DEBUG) { "Discovery started" }
        } catch (e: Exception) {
            abortDiscovery(e)
            throw e
        }
    }

    /**
     * Stops any ongoing discovery.
     *
     * This also aborts any ongoing pairing process.
     *
     * If no discovery is ongoing, this function does nothing.
     */
    fun stopDiscovery() {
        if (!discoveryRunning) {
            logger(LogLevel.DEBUG) { "Attempted to stop discovery even though none is ongoing; ignoring call" }
            return
        }

        logger(LogLevel.DEBUG) { "Stopping discovery" }

        bluetoothInterface.stopDiscovery()

        onNewPairedPump = { Unit }

        discoveryRunning = false

        logger(LogLevel.DEBUG) { "Discovery stopped" }
    }

    /**
     * Returns a [Pump] instance for a previously paired pump with the given address.
     *
     * The returned instance is set up for interaction with the pump.
     * However, it is not connected yet. Call [Pump.connect] afterwards
     * to establish a connection.
     *
     * @param pumpAddress Bluetooth address of the pump to access.
     * @param onNewDisplayFrame Callback invoked every time the pump
     *        receives a new complete remote terminal frame.
     *        Note that this callback will not be invoked until
     *        the pump is connected.
     * @returns [Pump] instance.
     * @throws PumpStateStoreRequestException if the request for
     *         a pump state store for this pump failed.
     *         (See [requestPersistentPumpStateStore] in the constructor.)
     * @throws IllegalStateException if the pump was not paired. This
     *         is indicated by a pump state store that isn't valid.
     *         (See [PersistentPumpStateStore] for details about this.)
     */
    fun getPump(
        pumpAddress: BluetoothAddress,
        onNewDisplayFrame: (displayFrame: DisplayFrame) -> Unit
    ): Pump {
        logger(LogLevel.DEBUG) { "About to connect to pump $pumpAddress" }

        val persistentPumpStateStore = persistentPumpStateStoreBackend.requestStore(pumpAddress)

        // Check that this pump is paired. If it is paired, then
        // the store is valid.
        require(persistentPumpStateStore.isValid())

        val bluetoothDevice = bluetoothInterface.getDevice(pumpAddress)
        logger(LogLevel.DEBUG) { "Got Bluetooth device instance for pump" }

        val pump = Pump(
            bluetoothDevice,
            persistentPumpStateStore,
            onNewDisplayFrame
        )

        return pump
    }

    // Filter for Combo devices based on their address.
    // The first 3 bytes of a Combo are always the same.
    private fun isCombo(deviceAddress: BluetoothAddress) =
        (deviceAddress[0] == 0x00.toByte()) &&
        (deviceAddress[1] == 0x0E.toByte()) &&
        (deviceAddress[2] == 0x2F.toByte())

    private suspend fun performPairing(backgroundReceiveScope: CoroutineScope, pumpAddress: BluetoothAddress) {
        // NOTE: Pairing can be aborted either by calling stopDiscovery()
        // or by throwing an exception in the pairing PIN callback.

        logger(LogLevel.DEBUG) { "About to perform pairing with pump $pumpAddress" }

        val persistentPumpStateStore = persistentPumpStateStoreBackend.requestStore(pumpAddress)

        val bluetoothDevice = bluetoothInterface.getDevice(pumpAddress)
        logger(LogLevel.DEBUG) { "Got Bluetooth device instance for pump" }

        val pump = Pump(
            bluetoothDevice,
            persistentPumpStateStore,
            { Unit } // We don't need the onNewDisplayFrame callback while pairing.
        )

        if (pump.isPaired()) {
            logger(LogLevel.INFO) { "Not pairing discovered pump $pumpAddress since it is already paired" }
            return
        }

        logger(LogLevel.DEBUG) { "Pump instance ready for pairing" }

        pump.performPairing(backgroundReceiveScope, bluetoothInterface.getAdapterFriendlyName()) {
            previousAttemptFailed, getPINDeferred -> pairingPINCallback(pumpAddress, previousAttemptFailed, getPINDeferred)
        }

        logger(LogLevel.DEBUG) { "Successfully paired with pump $pumpAddress" }
    }

    private fun abortDiscovery(e: Exception) {
        // This function is almost identical to stopDiscovery(),
        // except that the log messages are different (the Exception
        // argument is there for logging purposes), and stopDiscovery()
        // has an additional check to make sure redundant calls are
        // avoided (this check is unnecessary here).

        logger(LogLevel.DEBUG) { "Aborting discovery due to exception: $e" }

        bluetoothInterface.stopDiscovery()

        discoveryRunning = false

        onNewPairedPump = { Unit }

        logger(LogLevel.DEBUG) { "Discovery aborted" }
    }
}
