package info.nightscout.comboctl.base

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val logger = Logger.get("MainControl")

typealias PumpPairingPINCallback =
    suspend (newPumpAddress: BluetoothAddress, previousAttemptFailed: Boolean) -> PairingPIN

class MainControl(
    private val bluetoothInterface: BluetoothInterface,
    private val pumpStateStore: PumpStateStore
) {
    private var pumpPairingPINCallback: PumpPairingPINCallback = { _, _ -> nullPairingPIN() }

    // Coroutine mutex. This is used to prevent race conditions while
    // accessing acquiredPumps and the pumpStateStore. The mutex is needed
    // when acquiring pumps (accesses the store and the acquiredPumps map),
    // releasing pumps (accesses the acquiredPumps map), when a new pump
    // is found during discovery (accesses the store), and when a pump is
    // unpaired (accesses the store).
    // Note that a coroutine mutex is rather slow. But since the calls
    // that use it aren't used very often, this is not an issue.
    private val pumpStateAccessMutex = Mutex()

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

    /**
     * Possible results from a [pairWithNewPump] call.
     */
    sealed class PairingResult {
        data class Success(
            val BluetoothAddress: BluetoothAddress,
            val pumpID: String
        ) : PairingResult()

        class ExceptionDuringPairing(val exception: Exception) : PairingResult()
        object PairingAbortedByUser : PairingResult()
        object DiscoveryManuallyStopped : PairingResult()
        object DiscoveryError : PairingResult()
        object DiscoveryTimeout : PairingResult()
    }

    init {
        logger(LogLevel.INFO) { "Main control started" }

        // Install a filter to make sure we only ever get notified about Combo pumps.
        bluetoothInterface.deviceFilter = { deviceAddress -> isCombo(deviceAddress) }
    }

    /**
     * Sets up this MainControl instance.
     *
     * Once this is called, the [onPumpUnpaired] callback will be invoked
     * whenever a pump is unpaired (this includes unpairing via the system's
     * Bluetooth settings). Once this is invoked, the states associated with
     * the unpaired pump will already have been wiped from the pump state store.
     *
     * This also checks the available states in the pump state store and compares
     * this with the list of paired device addresses returned by the
     * [BluetoothInterface.getPairedDeviceAddresses] function to check for pumps
     * that may have been unpaired while ComboCtl was not running. This makes sure
     * that there are no stale states inside the store which otherwise would impact
     * the event handling and cause other IO issues (especially in future pairing
     * attempts).
     *
     * This must be called before using [pairWithNewPump] or [acquirePump].
     *
     * @param onPumpUnpaired Callback for when a previously paired pump is unpaired.
     *        This is called from within the [miscEventHandlingScope].
     */
    fun setup(onPumpUnpaired: (pumpAddress: BluetoothAddress) -> Unit = { }) {
        // TODO: Actually wipe the states from the pump state store.
        bluetoothInterface.onDeviceUnpaired = { deviceAddress -> onPumpUnpaired(deviceAddress) }

        val pairedDeviceAddresses = bluetoothInterface.getPairedDeviceAddresses()
        logger(LogLevel.DEBUG) { "${pairedDeviceAddresses.size} known device(s)" }
        for (deviceAddress in pairedDeviceAddresses) {
            logger(LogLevel.DEBUG) { "Known device: $deviceAddress" }
        }

        val availablePumpStates = pumpStateStore.getAvailablePumpStateAddresses()
        logger(LogLevel.DEBUG) { "${availablePumpStates.size} available state(s)" }
        for (pumpStateAddress in availablePumpStates) {
            logger(LogLevel.DEBUG) { "Got state for pump with address $pumpStateAddress" }

            val pairedDevicePresent = pairedDeviceAddresses.contains(pumpStateAddress)
            if (!pairedDevicePresent) {
                logger(LogLevel.DEBUG) { "There is no paired device for this pump state; erasing state" }
                pumpStateStore.deletePumpState(pumpStateAddress)
            }
        }
    }

    /**
     * Starts device discovery and pairs with a pump once one is discovered.
     *
     * This function suspends the calling coroutine until a device is found,
     * the coroutine is cancelled, an error happens during discovery, or
     * discovery timeouts.
     *
     * This manages the Bluetooth device discovery and the pairing
     * process with new pumps. Once an unpaired pump is discovered,
     * the Bluetooth implementation pairs with it, using the
     * [Constants.BT_PAIRING_PIN] PIN code (not to be confused with
     * the 10-digit Combo PIN).
     *
     * When the Bluetooth-level pairing is done, additional processing is
     * necessary: The Combo-level pairing must be performed, which also
     * sets up a state in the [PumpStateStore] for the discovered pump.
     * These steps are done by background coroutines that run in the
     * [discoveryEventHandlingScope]. The [pumpPairingPINCallback] is
     * called when the Combo-level pairing process reaches a point where
     * the user must be asked for the 10-digit PIN.
     *
     * @param discoveryDuration How long the discovery shall go on,
     *        in seconds. Must be a value between 1 and 300.
     * @param pumpPairingPINCallback Callback to ask the user for
     *        the 10-digit pairing PIN during the pairing process.
     * @throws BluetoothException if discovery fails due to an underlying
     *         Bluetooth issue.
     */
    suspend fun pairWithNewPump(
        discoveryDuration: Int,
        pumpPairingPINCallback: PumpPairingPINCallback
    ): PairingResult {
        this.pumpPairingPINCallback = pumpPairingPINCallback

        val deferred = CompletableDeferred<PairingResult>()

        lateinit var result: PairingResult

        coroutineScope {
            val thisScope = this
            try {
                bluetoothInterface.startDiscovery(
                    sdpServiceName = Constants.BT_SDP_SERVICE_NAME,
                    sdpServiceProvider = "ComboCtl SDP service",
                    sdpServiceDescription = "ComboCtl",
                    btPairingPin = Constants.BT_PAIRING_PIN,
                    discoveryDuration = discoveryDuration,
                    discoveryStopped = { reason ->
                        when (reason) {
                            BluetoothInterface.DiscoveryStoppedReason.MANUALLY_STOPPED ->
                                deferred.complete(PairingResult.DiscoveryManuallyStopped)
                            BluetoothInterface.DiscoveryStoppedReason.DISCOVERY_ERROR ->
                                deferred.complete(PairingResult.DiscoveryError)
                            BluetoothInterface.DiscoveryStoppedReason.DISCOVERY_TIMEOUT ->
                                deferred.complete(PairingResult.DiscoveryTimeout)
                        }
                    },
                    foundNewPairedDevice = { deviceAddress ->
                        thisScope.launch {
                            pumpStateAccessMutex.withLock {
                                try {
                                    logger(LogLevel.DEBUG) { "Found pump with address $deviceAddress" }

                                    if (pumpStateStore.hasPumpState(deviceAddress)) {
                                        logger(LogLevel.DEBUG) { "Skipping added pump since it has already been paired" }
                                    } else {
                                        performPairing(deviceAddress)

                                        val pumpID = pumpStateStore.getInvariantPumpData(deviceAddress).pumpID
                                        logger(LogLevel.DEBUG) { "Paired pump with address $deviceAddress ; pump ID = $pumpID" }

                                        deferred.complete(PairingResult.Success(deviceAddress, pumpID))
                                    }
                                } catch (e: Exception) {
                                    logger(LogLevel.ERROR) { "Caught exception while pairing to pump with address $deviceAddress: $e" }
                                    deferred.completeExceptionally(e)
                                    throw e
                                }
                            }
                        }
                    }
                )

                result = deferred.await()
            } catch (e: TransportLayerIO.PairingAbortedException) {
                logger(LogLevel.DEBUG) { "User aborted pairing" }
                result = PairingResult.PairingAbortedByUser
            } catch (e: CancellationException) {
                logger(LogLevel.DEBUG) { "Pairing cancelled" }
                throw e
            } finally {
                bluetoothInterface.stopDiscovery()
            }
        }

        return result
    }

    /**
     * Returns a set of Bluetooth addresses of the paired pumps.
     *
     * This equals the list of addresses of all the pump states in the
     * [PumpStateStore] assigned to this MainControl instance.
     */
    fun getPairedPumpAddresses() = pumpStateStore.getAvailablePumpStateAddresses()

    /**
     * Returns the ID of the paired pump with the given address.
     *
     * @return String with the pump ID.
     * @throws PumpStateDoesNotExistException if no pump state associated with
     *         the given address exists in the store.
     * @throws PumpStateStoreAccessException if accessing the data fails
     *         due to an error that occurred in the underlying implementation.
     */
    fun getPumpID(pumpAddress: BluetoothAddress) =
        pumpStateStore.getInvariantPumpData(pumpAddress).pumpID

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
        pumpStateAccessMutex.withLock {
            if (acquiredPumps.contains(pumpAddress))
                throw PumpAlreadyAcquiredException(pumpAddress)

            logger(LogLevel.DEBUG) { "Getting Pump instance for pump $pumpAddress" }

            if (!pumpStateStore.hasPumpState(pumpAddress))
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
        pumpStateAccessMutex.withLock {
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

    private suspend fun performPairing(pumpAddress: BluetoothAddress) {
        // NOTE: Pairing can be aborted either by calling stopDiscovery()
        // or by throwing an exception in the pairing PIN callback.

        logger(LogLevel.DEBUG) { "About to perform pairing with pump $pumpAddress" }

        val bluetoothDevice = bluetoothInterface.getDevice(pumpAddress)
        logger(LogLevel.DEBUG) { "Got Bluetooth device instance for pump" }

        val pump = Pump(bluetoothDevice, pumpStateStore)

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
}
