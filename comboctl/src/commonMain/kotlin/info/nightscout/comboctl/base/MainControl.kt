package info.nightscout.comboctl.base

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val logger = Logger.get("MainControl")

typealias PumpPairingPINCallback =
    suspend (newPumpAddress: BluetoothAddress, previousAttemptFailed: Boolean) -> PairingPIN

class MainControl(
    private val bluetoothInterface: BluetoothInterface,
    private val pumpStateStoreProvider: PumpStateStoreProvider
) {
    // Event handling related properties.
    private var eventHandlingStarted = false
    private var onNewPairedPump: suspend (pumpAddress: BluetoothAddress) -> Unit = { }
    private var onPumpUnpaired: suspend (pumpAddress: BluetoothAddress) -> Unit = { }
    private var onEventHandlingException: (e: Exception) -> Boolean = { true }
    private val eventHandlingMutex = Mutex()

    // Device discovery related properties.
    private var discoveryRunning = false
    private var pumpPairingPINCallback: PumpPairingPINCallback = { _, _ -> nullPairingPIN() }

    // Coroutine mutex. This is used to prevent race conditions while
    // accessing acquiredPumps and the pumpStateStoreProvider. The
    // mutex is needed when acquiring pumps (accesses the store provider and
    // the acquiredPumps map), releasing pumps (accesses the acquiredPumps
    // list), when a new pump is found during discovery (accesses the store
    // provider), and when a pump is unpaired (accesses the store provider).
    // These occasions are uncommon, and both the store provider & the list
    // of acquired pumps are accessed in acquirePumps(), which is why one
    // mutex for both is used.
    // Note that a coroutine mutex is rather slow. But since, as said, the
    // calls that use it aren't used very often, this is not an issue.
    private val mutex = Mutex()

    // List of Pump instances acquired by calling acquirePump().
    private val acquiredPumps = mutableMapOf<BluetoothAddress, Pump>()

    /**
     * Exception thrown when an attempt is made to acquire an already acquired pump.
     *
     * Pumps can only be acquired once at a time. This is a safety measure to
     * prevent multiple [Pump] instances from accessing the same pump, which
     * would lead to undefined behavior. See [MainControl.acquirePump] for more.
     *
     * @param pumpAddress Bluetooth address of the pump that was already acquired.
     */
    class PumpAlreadyAcquiredException(val pumpAddress: BluetoothAddress) :
        ComboException("Pump with address $pumpAddress was already acquired")

    /**
     * Exception thrown when a pump has not been paired and a function requires this.
     *
     * @param pumpAddress Bluetooth address of the pump that's not paired.
     */
    class PumpNotPairedException(val pumpAddress: BluetoothAddress) :
        ComboException("Pump with address $pumpAddress has not been paired")

    init {
        logger(LogLevel.INFO) { "Main control started" }

        // Install a filter to make sure we only ever get notified about Combo pumps.
        bluetoothInterface.deviceFilter = { deviceAddress -> isCombo(deviceAddress) }
    }

    /**
     * Sets up the necessary states for handling incoming events.
     *
     * Currently, two events are handled: when a new pump is discovered (after it
     * was paired), and when a previously paired pump is unpaired. The former happens
     * during discovery (see [startDiscovery]), while the latter can happen at any
     * moment (since the user could always unpair via the Bluetooth system settings
     * on their desktop or their mobile device).
     *
     * This also checks the available stores in the [pumpStateStoreProvider]
     * and compares this with the list of paired device addresses returned by the
     * [BluetoothInterface.getPairedDeviceAddresses] function to check for pumps
     * that may have been unpaired while ComboCtl was not running. This makes sure
     * that there are no stale states inside the store provider which otherwise would
     * impact the event handling and cause other IO issues.
     *
     * Event handling must be started before calling [startDiscovery].
     *
     * If during an event an exception is thrown, the [onEventHandlingException]
     * callback is invoked. If it returns true, event handling continues. If it
     * returns false, the event handling (including any ongoing discovery) is
     * stopped as if [stopEventHandling] had been called.
     *
     * @param miscEventHandlingScope CoroutineScope for handling events that are
     *        not specific to other operations. Pumps being unpaired is currently
     *        the single event that falls under this category. An event is handled
     *        in a background coroutine, which is what this scope is needed for.
     * @param onNewPairedPump Callback for when a newly paired pump is detected.
     * @param onPumpUnpaired Callback for when a previously paired pump is unpaired.
     *        This is called from within the [miscEventHandlingScope].
     * @throws IllegalStateException if event handling was already started.
     * @throws PumpStateStoreRequestException if while checking for stale store
     *         states the [PumpStateStoreProvider.requestStore] call fails.
     *         Look up that function's documentation for notes about error handling.
     */
    fun startEventHandling(
        miscEventHandlingScope: CoroutineScope,
        onNewPairedPump: suspend (pumpAddress: BluetoothAddress) -> Unit = { },
        onPumpUnpaired: suspend (pumpAddress: BluetoothAddress) -> Unit = { },
        onEventHandlingException: (e: Exception) -> Boolean = { true }
    ) {
        if (eventHandlingStarted)
            throw IllegalStateException(
                "Attempting to start event handling even though it was already started")

        try {
            this.onNewPairedPump = onNewPairedPump
            this.onPumpUnpaired = onPumpUnpaired
            this.onEventHandlingException = onEventHandlingException

            // Install our callback to get notified about unpaired
            // devices. Note that we get such notifications even
            // when discovery is not running.
            bluetoothInterface.onDeviceUnpaired = {
                deviceAddress -> miscEventHandlingScope.launch {
                    runEventHandler {
                        handleUnpairedPump(deviceAddress)
                    }
                }
            }

            // We get the addresses of the currently paired Bluetooth
            // devices (which pass the device filter) and the addresses
            // of the available pump state stores. The goal is to see
            // if there are stores that have no corresponding paired
            // Bluetooth device. If so, then this store is stale. This
            // may happen if the user unpaired the device while ComboCtl
            // was not running. Then, when ComboCtl is started, the
            // store of the unpaired device is still around. To fix
            // this, we check the stores here.

            // First, log the paired device addresses.
            val pairedDeviceAddresses = bluetoothInterface.getPairedDeviceAddresses()
            logger(LogLevel.DEBUG) { "${pairedDeviceAddresses.size} known device(s)" }
            for (deviceAddress in pairedDeviceAddresses) {
                logger(LogLevel.DEBUG) { "Known device: $deviceAddress" }
            }

            // Now go through each store and check if its address is also
            // present in the pairedDeviceAddresses set. If not, the store
            // is stale, and needs to be erased.
            val availableStoreAddresses = pumpStateStoreProvider.getAvailableStoreAddresses()
            logger(LogLevel.DEBUG) { "${availableStoreAddresses.size} available store(s)" }
            for (storeAddress in availableStoreAddresses) {
                logger(LogLevel.DEBUG) { "Available store: $storeAddress" }

                val pairedDevicePresent = pairedDeviceAddresses.contains(storeAddress)
                if (!pairedDevicePresent) {
                    logger(LogLevel.DEBUG) { "There is no paired device for this store; erasing store" }
                    val store = pumpStateStoreProvider.requestStore(storeAddress)
                    store.reset()
                }
            }

            eventHandlingStarted = true
        } catch (e: Exception) {
            // Roll back any changes in case of an exception
            stopEventHandling()
            throw e
        }
    }

    /**
     * Stops any ongoing event handling.
     *
     * If discovery is also running, this automatically stops that by calling
     * [stopDiscovery].
     *
     * If event handling has not been started, this function does nothing.
     */
    fun stopEventHandling() {
        if (discoveryRunning)
            stopDiscovery()

        // Reset this one first, otherwise the onPumpUnpaired
        // callback would be called after that one got reset.
        // By resetting the onDeviceUnpaired first instead,
        // this cannot happen.
        bluetoothInterface.onDeviceUnpaired = { }

        onNewPairedPump = { }
        onPumpUnpaired = { }
        onEventHandlingException = { true }

        eventHandlingStarted = false
    }

    /**
     * Starts Bluetooth discovery to look for unpaired pumps.
     *
     * This manages the Bluetooth device discovery and the pairing
     * process with new pumps. Once an unpaired pump is discovered,
     * the Bluetooth implementation pairs with it, using the
     * [Constants.BT_PAIRING_PIN] PIN code (not to be confused with
     * the 10-digit Combo PIN).
     *
     * When the Bluetooth-level pairing is done, additional processing
     * is necessary: A new [PumpStateStore] must be created, and the
     * Combo-level pairing must be performed. These are done by background
     * coroutines that run in the [discoveryEventHandlingScope].
     * The [pumpPairingPINCallback] is called when the Combo-level pairing
     * process reaches a point where the user must be asked for the
     * 10-digit PIN.
     *
     * If an exception is thrown while it is being started, any (partially)
     * started discovery is aborted.
     *
     * If during a discovery event an exception is thrown, the
     * onEventHandlingException callback that was passed to [startEventHandling]
     * gets invoked. If it returns true, event handling continues. If it
     * returns false, the event handling (including any ongoing discovery)
     * is stopped as if [stopEventHandling] had been called.
     *
     * @param discoveryEventHandlingScope [CoroutineScope] to run the
     *        discovery-specific event handling in.
     * @param pumpPairingPINCallback Callback to ask the user for
     *        the 10-digit pairing PIN during the pairing process.
     * @throws IllegalStateException if a discovery is already ongoing
     *         or if the overall event handling wasn't started yet.
     * @throws BluetoothException if discovery fails due to an underlying
     *         Bluetooth issue.
     */
    fun startDiscovery(
        discoveryEventHandlingScope: CoroutineScope,
        pumpPairingPINCallback: PumpPairingPINCallback
    ) {
        if (!eventHandlingStarted)
            throw IllegalStateException("Background event handling loop is not running")

        if (discoveryRunning)
            throw IllegalStateException("Discovery already ongoing")

        this.pumpPairingPINCallback = pumpPairingPINCallback

        try {
            bluetoothInterface.startDiscovery(
                Constants.BT_SDP_SERVICE_NAME,
                "ComboCtl SDP service",
                "ComboCtl",
                Constants.BT_PAIRING_PIN,
                { deviceAddress ->
                    discoveryEventHandlingScope.launch {
                        runEventHandler {
                            handleNewlyPairedPump(deviceAddress)
                        }
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

        discoveryRunning = false

        logger(LogLevel.DEBUG) { "Discovery stopped" }
    }

    /**
     * Acquires a Pump instance for a pump with the given Bluetooth address.
     *
     * Pumps can only be acquired once at a time. This is a safety measure to
     * prevent multiple [Pump] instances from accessing the same pump, which
     * would lead to undefined behavior. An acquired pump must be un-acquired
     * by calling [releasePump]. Attempting to acquire an already acquired
     * pump is an error and will cause this function to throw an exception
     * ([PumpAlreadyAcquiredException]).
     *
     * The pump must have been paired before it can be acquired. If this is
     * not done, an [PumpNotPairedException] is thrown.
     *
     * @param pumpAddress Bluetooth address of the pump to acquire.
     * @throws PumpAlreadyAcquiredException if the pump was already acquired.
     * @throws PumpNotPairedException if the pump was not yet paired.
     * @throws BluetoothException if getting a [BluetoothDevice] for this
     *         pump fails.
     */
    suspend fun acquirePump(pumpAddress: BluetoothAddress) =
        mutex.withLock {
            if (acquiredPumps.contains(pumpAddress))
                throw PumpAlreadyAcquiredException(pumpAddress)

            logger(LogLevel.DEBUG) { "Getting Pump instance for pump $pumpAddress" }

            val pumpStateStore = pumpStateStoreProvider.requestStore(pumpAddress)

            if (!pumpStateStore.isValid())
                throw PumpNotPairedException(pumpAddress)

            val bluetoothDevice = bluetoothInterface.getDevice(pumpAddress)

            val pump = Pump(bluetoothDevice, pumpStateStore)

            acquiredPumps[pumpAddress] = pump

            pump // Return the Pump instance
        }

    /**
     * Releases (= un-acquires) a previously acquired pump with the given address.
     *
     * If no such pump was previously acquired, this function does nothing.
     *
     * @param acquiredPumpAddress Bluetooth address of the pump to release.
     */
    suspend fun releasePump(acquiredPumpAddress: BluetoothAddress) {
        mutex.withLock {
            if (!acquiredPumps.contains(acquiredPumpAddress)) {
                logger(LogLevel.DEBUG) { "A pump with address $acquiredPumpAddress wasn't previously acquired; ignoring call" }
                return@withLock
            }

            acquiredPumps.remove(acquiredPumpAddress)
        }
    }

    // Filter for Combo devices based on their address.
    // The first 3 bytes of a Combo are always the same.
    private fun isCombo(deviceAddress: BluetoothAddress) =
        (deviceAddress[0] == 0x00.toByte()) &&
        (deviceAddress[1] == 0x0E.toByte()) &&
        (deviceAddress[2] == 0x2F.toByte())

    private suspend fun handleNewlyPairedPump(pumpAddress: BluetoothAddress) {
        mutex.withLock {
            try {
                logger(LogLevel.DEBUG) { "Found pump with address $pumpAddress" }

                if (pumpStateStoreProvider.hasValidStore(pumpAddress)) {
                    logger(LogLevel.DEBUG) { "Skipping added pump since it has already been paired" }
                } else {
                    performPairing(pumpAddress)
                    onNewPairedPump(pumpAddress)
                }
            } catch (e: Exception) {
                logger(LogLevel.ERROR) { "Caught exception while pairing to pump with address $pumpAddress: $e" }
                throw e
            }
        }
    }

    private suspend fun performPairing(pumpAddress: BluetoothAddress) {
        // NOTE: Pairing can be aborted either by calling stopDiscovery()
        // or by throwing an exception in the pairing PIN callback.

        logger(LogLevel.DEBUG) { "About to perform pairing with pump $pumpAddress" }

        val pumpStateStore = pumpStateStoreProvider.requestStore(pumpAddress)

        val bluetoothDevice = bluetoothInterface.getDevice(pumpAddress)
        logger(LogLevel.DEBUG) { "Got Bluetooth device instance for pump" }

        val pump = Pump(
            bluetoothDevice,
            pumpStateStore
        )

        if (pump.isPaired()) {
            logger(LogLevel.INFO) { "Not pairing discovered pump $pumpAddress since it is already paired" }
            return
        }

        logger(LogLevel.DEBUG) { "Pump instance ready for pairing" }

        pump.performPairing(bluetoothInterface.getAdapterFriendlyName()) {
            previousAttemptFailed -> pumpPairingPINCallback(pumpAddress, previousAttemptFailed)
        }

        logger(LogLevel.DEBUG) { "Successfully paired with pump $pumpAddress" }
    }

    private suspend fun handleUnpairedPump(pumpAddress: BluetoothAddress) {
        // NOTE: Not performing a Bluetooth unpairing here,
        // since we reach this location _because_ a device
        // was removed (= unpaired) from the system's
        // Bluetooth stack (for example because the user
        // unpaired the pump via the Bluetooth settings).

        mutex.withLock {
            // TODO: Define what to do if exceptions occur here.
            // Should the code fail hard? If reset() or requestStore()
            // fail, it is unclear what the state of the store is.

            logger(LogLevel.DEBUG) { "Previously paired pump with address $pumpAddress removed" }

            if (pumpStateStoreProvider.hasValidStore(pumpAddress)) {
                // Reset the pump state store for the removed pump.
                try {
                    val pumpStateStore = pumpStateStoreProvider.requestStore(pumpAddress)
                    pumpStateStore.reset()
                } catch (e: PumpStateStoreAccessException) {
                    logger(LogLevel.ERROR) { "Caught exception while resetting store of removed pump $pumpAddress: $e" }
                }

                // Now run the user supplied callback, and catch
                // any exception it might throw.
                try {
                    onPumpUnpaired(pumpAddress)
                } catch (e: Exception) {
                    logger(LogLevel.ERROR) {
                        "Caught exception while running onPumpUnpaired callback for pump with address $pumpAddress: $e"
                    }
                    throw e
                }
            } else {
                logger(LogLevel.DEBUG) { "Skipping removed pump since it has not been paired or already was unpaired" }
            }
        }
    }

    private fun abortDiscovery(e: Exception) {
        // This function is almost identical to stopDiscovery(),
        // except that the log messages are different (the Exception
        // argument is there for logging purposes), and stopDiscovery()
        // has an additional check to make sure redundant calls are
        // avoided (this check is unnecessary here).

        if (!discoveryRunning)
            return

        logger(LogLevel.DEBUG) { "Aborting discovery due to exception: $e" }

        bluetoothInterface.stopDiscovery()

        discoveryRunning = false

        logger(LogLevel.DEBUG) { "Discovery aborted" }
    }

    private suspend fun runEventHandler(eventHandler: suspend () -> Unit) {
        // Event handlers are called from within Bluetooth callbacks.
        // Therefore, it is necessary to add safeguards:
        //
        // 1. No two handlers must run simultaneously. That's because
        //    if one handler fails, it shuts down event handling overall,
        //    which would create data races if another handler ran at
        //    the same time.
        // 2. Exceptions must be handled in a particular way. If one
        //    is thrown, discovery must be aborted, event handling must
        //    stop, and the supplied onEventHandlingException callback
        //    must be called to let the outside world know about the
        //    exception. Propagating the exception is not possible since
        //    the event handler is called by the Bluetooth stack, which
        //    is why the exception is not rethrown here.

        eventHandlingMutex.withLock {
            if (!eventHandlingStarted)
                return

            try {
                eventHandler()
            } catch (e: Exception) {
                if (!onEventHandlingException(e)) {
                    abortDiscovery(e)
                    stopEventHandling()
                }
            }
        }
    }
}
