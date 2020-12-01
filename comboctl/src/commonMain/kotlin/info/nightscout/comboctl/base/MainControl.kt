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
 * The [pairingPINCallback] is called when the control
 * needs the 10-digit pairing PIN that is shown on the
 * Combo's LCD. This typically leads to a dialog box
 * shown on screen with a widget for entering the PIN.
 * If the user cancels that entry (for example by pressing
 * a "Cancel" button in that dialog box), the callback
 * should throw an exception to have the pairing process
 * aborted.
 *
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
    private val bluetoothInterface: BluetoothInterface,
    private val persistentPumpStateStoreBackend: PersistentPumpStateStoreBackend,
    private val pairingPINCallback: (
        newPumpAddress: BluetoothAddress,
        previousAttemptFailed: Boolean,
        getPINDeferred: CompletableDeferred<PairingPIN>
    ) -> Unit
) {
    private var discoveryEventLoopJob: Job? = null

    private enum class EventType {
        // Newly Bluetooth-paired pump detected.
        NEW_PUMP,
        // Bluetooth-paired pump is now gone (= unpaired).
        PUMP_GONE
    }

    private val discoveryEventChannel = Channel<Pair<BluetoothAddress, EventType>>(Channel.UNLIMITED)

    init {
        logger(LogLevel.INFO) { "Main control started" }
    }

    /**
     * Starts Bluetooth discovery to look for unpaired pumps.
     *
     * This manages the Bluetooth device discovery and the pairing
     * process with new pumps. Once an unpaired pump is discovered,
     * the Bluetooth implementation pairs with it, using the
     * [Constants.BT_PAIRING_PIN] PIN code (not to be confused with
     * the 10-digit Combo PIN). The discovery process runs in the
     * background in the supplied [backgroundDiscoveryEventScope].
     * When the Bluetooth pairing is done, the Combo specific pairing
     * is performed using [Pump.performPairing]. The pairing process
     * fills a [PersistentState] instance specific to the discovered
     * pump. Said state is retrieved by calling [requestPersistentState].
     * Once pairing is done, that state will have been filled with
     * pairing data (cipher keys etc.), and a regular connection can
     * be established with the pump.
     *
     * [backgroundDiscoveryEventScope]'s context needs to be associated
     * with the same thread this function is called in. Otherwise, the
     * receive loop may run in a different thread than this function,
     * potentially leading to race conditions.
     *
     * In case of a failure during discovery, [onDiscoveryFailure] is
     * called, and the discovery is aborted. This means that even if
     * the failure is specific to the pump itself, the entire discovery
     * process is aborted. This is because it is not possible to guarantee
     * that the failure didn't affect other parts of the discovery
     * process. For example, IO errors may persist even if the logic
     * excluded the particular pump where IO errors occurred. This
     * can for example happen if there is something wrong with the
     * Bluetooth stack. So, in case of failure during discovery, it
     * is aborted. It can be restarted by calling [startDiscovery]
     * again, however.
     *
     * @param backgroundDiscoveryEventScope [CoroutineScope] to run the
     *        background pairing activities in.
     * @param onNewPump Optional callback to notify callers about a new
     *        pump. It is not required to set this for a successful
     *        pairing; this is purely for notification.
     * @param onPumpGone Optional callback to notify callers that a
     *        previously Bluetooth-level paired pump is now gone.
     *        Typically, this happens because the pump is out of range,
     *        or because the user unpaired it in the Bluetooth settings,
     *        or when the pump is turned off.
     * @param onDiscoveryFailure Optional callback to notify the caller
     *        that discovery failed / got aborted. The cause for the
     *        failure is supplied along with the Bluetooth address of
     *        the pump associated with the failure.
     *        IMPORTANT: This callback must _not_ throw exceptions,
     *        since it is called as part of an ongoing error handling.
     * @throws IllegalStateException if a discovery is already ongoing.
     * @throws BluetoothException if discovery fails due to an underlying
     *         Bluetooth issue.
     */
    fun startDiscovery(
        backgroundDiscoveryEventScope: CoroutineScope,
        onNewPump: (pumpAddress: BluetoothAddress) -> Unit = { Unit },
        onPumpGone: (pumpAddress: BluetoothAddress) -> Unit = { Unit },
        onDiscoveryFailure: (pumpAddress: BluetoothAddress, e: Exception) -> Unit = { _, _ -> Unit }
    ) {
        if (discoveryEventLoopJob != null)
            throw IllegalStateException("Discovery already ongoing")

        logger(LogLevel.DEBUG) { "Starting discovery" }

        // Discovery events may be generated by a separate background
        // thread depending on the Bluetooth implementation. For this
        // reason, the event callbacks are not called directly when
        // an event occurs. Instead, they are serialized into the
        // discoveryEventChannel. In the loop here, we listen for
        // events coming through the channel, and process them here.
        // That way, race conditions are avoided, since this loop
        // does not run in that separate background thread.
        discoveryEventLoopJob = backgroundDiscoveryEventScope.launch {
            logger(LogLevel.DEBUG) { "Discovery event loop started" }

            while (true) {
                val (pumpAddress, eventType) = discoveryEventChannel.receive()

                when (eventType) {
                    EventType.NEW_PUMP -> {
                        // Note that we do _not_ rethrow exceptions here,
                        // unlike in the try-catch block further below.
                        // This is because this try-catch block runs in
                        // its separate coroutine that runs in the
                        // background. The exception would not be propagated
                        // in a way that is useful here. For this reason,
                        // instead, we notify the caller by invoking the
                        // onDiscoveryFailure() callback.
                        try {
                            logger(LogLevel.DEBUG) { "Found device with address $pumpAddress" }

                            if (persistentPumpStateStoreBackend.hasValidStore(pumpAddress)) {
                                logger(LogLevel.DEBUG) { "Skipping added device since it has already been paired" }
                            } else {
                                performPairing(backgroundDiscoveryEventScope, pumpAddress)
                                onNewPump(pumpAddress)
                            }
                        } catch (e: CancellationException) {
                            logger(LogLevel.ERROR) { "Aborting pairing since this coroutine got cancelled" }
                            abortDiscovery(e)
                            onDiscoveryFailure(pumpAddress, e)
                            throw e
                        } catch (e: Exception) {
                            logger(LogLevel.ERROR) { "Caught exception while pairing to pump with address $pumpAddress: $e" }
                            abortDiscovery(e)
                            onDiscoveryFailure(pumpAddress, e)
                        }
                    }
                    EventType.PUMP_GONE -> {
                        // NOTE: Not performing a Bluetooth unpairing here,
                        // since we reach this location _because_ a device
                        // was removed (= unpaired) from the system's
                        // Bluetooth stack (for example because the user
                        // unpaired the pump via the Bluetooth settings).

                        logger(LogLevel.DEBUG) { "Previously paired device with address $pumpAddress removed" }

                        if (persistentPumpStateStoreBackend.hasValidStore(pumpAddress)) {
                            logger(LogLevel.DEBUG) { "Skipping removed device since it has not been paired or already was unpaired" }
                        } else {
                            // Reset the pump state store for the removed pump.
                            try {
                                val persistentPumpStateStore = persistentPumpStateStoreBackend.requestStore(pumpAddress)
                                persistentPumpStateStore.reset()
                            } catch (e: PumpStateStoreAccessException) {
                                logger(LogLevel.ERROR) { "Caught exception while resetting store of removed pump $pumpAddress: $e" }
                                abortDiscovery(e)
                                onDiscoveryFailure(pumpAddress, e)
                            }

                            // Now run the user supplied callback, and catch
                            // any exception it might throw.
                            try {
                                onPumpGone(pumpAddress)
                            } catch (e: Exception) {
                                logger(LogLevel.ERROR) { "Caught exception while running onPumpGone callback for pump with address $pumpAddress: $e" }
                                abortDiscovery(e)
                                onDiscoveryFailure(pumpAddress, e)
                            }
                        }
                    }
                }
            }
        }

        try {
            bluetoothInterface.startDiscovery(
                Constants.BT_SDP_SERVICE_NAME,
                "ComboCtl SDP service",
                "ComboCtl",
                Constants.BT_PAIRING_PIN,
                {
                    deviceAddress -> backgroundDiscoveryEventScope.launch {
                        discoveryEventChannel.send(Pair(deviceAddress, EventType.NEW_PUMP))
                    }
                },
                {
                    deviceAddress -> backgroundDiscoveryEventScope.launch {
                        discoveryEventChannel.send(Pair(deviceAddress, EventType.PUMP_GONE))
                    }
                },
                {
                    // Filter for Combo devices based on their address.
                    // The first 3 bytes of a Combo are always the same.
                    deviceAddress ->
                    (deviceAddress[0] == 0x00.toByte()) &&
                    (deviceAddress[1] == 0x0E.toByte()) &&
                    (deviceAddress[2] == 0x2F.toByte())
                }
            )

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
        if (discoveryEventLoopJob == null) {
            logger(LogLevel.DEBUG) { "Attempted to stop discovery even though none is ongoing; ignoring call" }
            return
        }

        logger(LogLevel.DEBUG) { "Stopping discovery" }

        bluetoothInterface.stopDiscovery()

        discoveryEventLoopJob!!.cancel()
        discoveryEventLoopJob = null

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

        if (discoveryEventLoopJob != null) {
            discoveryEventLoopJob!!.cancel()
            discoveryEventLoopJob = null
        }

        logger(LogLevel.DEBUG) { "Discovery aborted" }
    }
}
