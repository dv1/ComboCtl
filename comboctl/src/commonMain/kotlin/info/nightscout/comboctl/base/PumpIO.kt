package info.nightscout.comboctl.base

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val logger = Logger.get("PumpIO")

/**
 * Callback used during pairing for asking the user for the 10-digit PIN.
 *
 * This is passed to [PumpIO.performPairing] when pairing.
 *
 * [previousAttemptFailed] is useful for showing in a GUI that the
 * previously entered PIN seems to be wrong and that the user needs
 * to try again.
 *
 * If the user wants to cancel the pairing instead of entering the
 * PIN, cancelling the coroutine where [PumpIO.performPairing] runs
 * is sufficient.
 *
 * @param previousAttemptFailed true if the user was already asked for
 *        the PIN and the KEY_RESPONSE authentication failed.
 */
typealias PairingPINCallback = suspend (previousAttemptFailed: Boolean) -> PairingPIN

/**
 * Class for high-level Combo pump IO.
 *
 * This implements high level IO actions on top of [TransportLayer.IO].
 * It takes care of pairing, connection setup, remote terminal (RT)
 * commands and RT display reception, and also supports the Combo's
 * command mode. Basically, this class' public API reflects what the
 * user can directly do with the pump (press RT buttons like UP or
 * DOWN, send a command mode bolus etc.).
 *
 * For initiating the Combo pairing, the [performPairing] function is
 * available. This must not be used if a connection was already established
 * via [connectAsync] - pairing is only possible in the disconnected state.
 *
 * For initiating a regular connection, use [connectAsync]. Do not call
 * [connectAsync] again until after disconnecting with [disconnect]. Also
 * see the remarks above about pairing and connecting at the same time.
 *
 * Note that [connectAsync], [disconnect], [performPairing] do not establish
 * anything related to Bluetooth connection. Those are beyond the scope
 * of this class.
 *
 * The Combo regularly sends new display frames when running in the
 * REMOTE_TERMINAL (RT) mode. These frames come as the payload of
 * RT_DISPLAY packets. This class reads those packets and extracts the
 * partial frames (the packets only contain portions of a frame, not
 * a full frame). Once enough parts were gathered to assemble a full
 * frame, the frame is emitted via the [displayFrameFlow].
 *
 * To handle IO at the transport layer, this uses [TransportLayer.IO]
 * internally.
 *
 * In regular connections, the Combo needs "heartbeats" to periodically
 * let it know that the client still exists. If too much time passes since
 * the last heartbeat, the Combo terminates the connection. Each mode has a
 * different type of heartbeat: RT mode has RT_KEEP_ALIVE commands, command
 * mode has CMD_PING commands. To periodically send these, this class runs
 * separate coroutines with loops inside that send these commands. Only one
 * of these two heartbeats are active, depending on the current mode of the
 * Combo.
 * Note that other commands sent to the Combo _also_ count as heartbeats,
 * so RT_KEEP_ALIVE / CMD_PING only need to be sent if no other command
 * has been sent for a while now.
 * In some cases (typically unit tests), a regular connection without
 * a heartbeat is needed. [connectAsync] accepts an argument to start
 * without one for this purpose.
 *
 * The supplied [pumpStateStore] is used during pairing and regular
 * connections. During pairing, a new pump state is set up for the
 * pump that is being paired. It is during pairing that the invariant
 * portion of the pump state is written. During regular (= not pairing)
 * connections, the invariant part is read, not written. Only the nonce
 * gets updated regularly during regular IO.
 *
 * This class accesses the pump state in a thread safe manner, ensuring
 * that no two threads access the pump state at the same time. See
 * [PumpStateStore] for details about thread safety.
 *
 * @param pumpStateStore Pump state store to use.
 * @param pumpAddress Bluetooth address of the pump. Used for
 *   accessing the pump state store.
 * @param comboIO Combo IO object to use for sending/receiving data.
 * @param disconnectDeviceCallback Callback to be invoked during the
 *   shutdown process to disconnect the device that is used for IO. See
 *   the [TransportLayer.IO.stop] documentation for details. This callback
 *   is used both when pairing finishes and when a [disconnect] call
 *   terminates a regular connection.
 */
class PumpIO(
    private val pumpStateStore: PumpStateStore,
    private val pumpAddress: BluetoothAddress,
    comboIO: ComboIO,
    private val disconnectDeviceCallback: suspend () -> Unit = { }
) {
    // Mutex to synchronize sendPacketWithResponse and sendPacketWithoutResponse calls.
    private val sendPacketMutex = Mutex()

    // RT sequence number. Used in outgoing RT packets.
    private var currentRTSequence: Int = 0

    private var transportLayerIO = TransportLayer.IO(
        pumpStateStore, pumpAddress, comboIO
    ) { packetReceiverException ->
        // If the packet receiver fails, close the barrier to wake
        // up any caller that is waiting on it.
        rtButtonConfirmationBarrier.close(packetReceiverException)
    }

    private var sequencedDispatcherScope: CoroutineScope? = null

    // Job representing the coroutine that runs the CMD ping heartbeat.
    private var cmdPingHeartbeatJob: Job? = null
    // Job representing the coroutine that runs the RT keep-alive heartbeat.
    private var rtKeepAliveHeartbeatJob: Job? = null

    // Members associated with long-pressing buttons in RT mode.
    // Long-pressing is implemented by repeatedly sending RT_BUTTON_STATUS
    // messages until the user "releases" the buttons.
    // (We use a list of Button values in case multiple buttons are being
    // held down at the same time.)
    // We use a Deferred instance instead of Job to be able to catch
    // and store exceptions & rethrow them later.
    private var currentLongRTPressJob: Deferred<Unit>? = null
    private var currentLongRTPressedButtons = listOf<ApplicationLayer.RTButton>()
    private var longRTPressLoopRunning = true

    // A Channel that is used as a "barrier" of sorts to block button
    // pressing functions from continuing until the Combo sends
    // a confirmation for the key press. Up until that confirmation
    // is received, the client must not send any other button press
    // commands to the Combo. To ensure that, this barrier exists.
    // Its payload is a Boolean to let waiting coroutines know whether
    // to finish or to continue any internal loops. The former happens
    // during disconnect. It is set up as a conflated channel. That
    // way, if a confirmation is received before button press commands
    // call receive(), information about the confirmation is not lost
    // (which would happen with a rendezvous channel). And, in case
    // disconnect() is called, it is important to overwrite any other
    // existing value with "false" to stop button pressing commands
    // (hence a conflated channel instead of DROP_OLDEST buffer
    // overflow behavior).
    private var rtButtonConfirmationBarrier = newRtButtonConfirmationBarrier()

    // Members associated with display frame generation.
    // The mutable version of the displayFrameFlow is used internally
    // when a new frame is available. This is a SharedFlow, since
    // the display frame emission is independent of the presence of
    // collectors. This means that flow collection never ends. (See
    // the Kotlin SharedFlow documentation for details.) It is set
    // up to not suspend emission calls, instead dropping the oldest
    // frames. That way, the display frame producer is not blocked
    // by backpressure. To allow subscribers to immediately get data
    // (the last received display frame), the replay cache is set to 1.
    private var _displayFrameFlow = MutableSharedFlow<DisplayFrame>(
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        replay = 1
    )
    private val displayFrameAssembler = DisplayFrameAssembler()

    // Whether we are in RT or COMMAND mode, or null at startup
    // before an initial mode was set.
    private val _currentModeFlow = MutableStateFlow<Mode?>(null)

    /************************************
     *** PUBLIC FUNCTIONS AND CLASSES ***
     ************************************/

    /**
     * Read-only [SharedFlow] property that delivers newly assembled display frames.
     *
     * See [DisplayFrame] for details about these frames.
     */
    val displayFrameFlow = _displayFrameFlow.asSharedFlow()

    /**
     * Read-only [StateFlow] property that announces when the current [Mode] changes.
     */
    val currentModeFlow = _currentModeFlow.asStateFlow()

    /**
     * The mode the pump can operate in.
     */
    enum class Mode(val str: String) {
        REMOTE_TERMINAL("REMOTE_TERMINAL"),
        COMMAND("COMMAND");

        override fun toString() = str
    }

    /**
     * Performs a pairing procedure with a Combo.
     *
     * This performs the Combo-specific pairing. When this is called,
     * the pump must have been paired at the Bluetooth level already.
     * From Bluetooth's point of view, the pump is already paired with
     * the client at this point. But the Combo itself needs an additional
     * custom pairing. As part of this extra pairing, this function sets
     * up a special temporary pairing connection to the Combo, and terminates
     * that connection before finishing. Manually setting up such a connection
     * is not necessary and not supported by the public API.
     *
     * Cancelling the coroutine this function runs in will abort the pairing
     * process in an orderly fashion.
     *
     * Pairing will initialize a new state for this pump [PumpStateStore] that
     * was passed to the constructor of this class. This state will contain
     * new pairing data, a new pump ID string, and a new initial nonce.
     *
     * The pairingPINCallback callback has two arguments. previousAttemptFailed
     * is set to false initially, and true if this is a repeated call due
     * to a previous failure to apply the PIN. Such a failure typically
     * happens because the user mistyped the PIN, but in rare cases can also
     * happen due to corrupted packets.
     *
     * @param bluetoothFriendlyName The Bluetooth friendly name to use in
     *   REQUEST_ID packets. Use [BluetoothInterface.getAdapterFriendlyName]
     *   to get the friendly name.
     * @param progressReporter [ProgressReporter] for tracking pairing progress.
     * @param pairingPINCallback Callback that gets invoked as soon as
     *   the pairing process needs the 10-digit-PIN.
     * @throws IllegalStateException if this is ran while a connection
     *   is running.
     * @throws PumpStateAlreadyExistsException if the pump was already
     *   fully paired before.
     * @throws TransportLayer.PacketReceiverException if an exception
     *   is thrown while this function is waiting for a packet.
     */
    suspend fun performPairing(
        bluetoothFriendlyName: String,
        progressReporter: ProgressReporter<Unit>?,
        pairingPINCallback: PairingPINCallback
    ) {
        check(!isConnected()) { "Attempted to perform pairing while pump is connected" }

        // Set up a custom coroutine scope to run the packet receiver in.
        coroutineScope {
            try {
                transportLayerIO.start(packetReceiverScope = this) { tpLayerPacket -> processReceivedPacket(tpLayerPacket) }

                progressReporter?.setCurrentProgressStage(BasicProgressStage.PerformingConnectionHandshake)

                // Initiate pairing and wait for the response.
                // (The response contains no meaningful payload.)
                logger(LogLevel.DEBUG) { "Sending pairing connection request" }
                sendPacketWithResponse(
                    TransportLayer.createRequestPairingConnectionPacketInfo(),
                    TransportLayer.Command.PAIRING_CONNECTION_REQUEST_ACCEPTED
                )

                // Initiate pump-client and client-pump keys request.
                // This will cause the pump to generate and show a
                // 10-digit PIN.
                logger(LogLevel.DEBUG) { "Requesting the pump to generate and show the pairing PIN" }
                sendPacketWithoutResponse(TransportLayer.createRequestKeysPacketInfo())

                progressReporter?.setCurrentProgressStage(BasicProgressStage.ComboPairingKeyAndPinRequested)

                logger(LogLevel.DEBUG) { "Requesting the keys from the pump" }
                val keyResponsePacket = sendPacketWithResponse(
                    TransportLayer.createGetAvailableKeysPacketInfo(),
                    TransportLayer.Command.KEY_RESPONSE
                )

                logger(LogLevel.DEBUG) { "Will ask for pairing PIN" }
                var previousPINAttemptFailed = false

                lateinit var keyResponseInfo: KeyResponseInfo
                while (true) {
                    logger(LogLevel.DEBUG) { "Waiting for the PIN to be provided" }

                    // Request the PIN. If canceled, PairingAbortedException is
                    // thrown by the callback.
                    val pin = pairingPINCallback.invoke(previousPINAttemptFailed)

                    logger(LogLevel.DEBUG) { "Provided PIN: $pin" }

                    val weakCipher = Cipher(generateWeakKeyFromPIN(pin))
                    logger(LogLevel.DEBUG) { "Generated weak cipher key ${weakCipher.key.toHexString()} out of pairing PIN" }

                    if (keyResponsePacket.verifyAuthentication(weakCipher)) {
                        logger(LogLevel.DEBUG) { "KEY_RESPONSE packet verified" }
                        keyResponseInfo = processKeyResponsePacket(keyResponsePacket, weakCipher)
                        // Exit the loop since we successfully verified the packet.
                        break
                    } else {
                        logger(LogLevel.DEBUG) { "Could not verify KEY_RESPONSE packet; user may have entered PIN incorrectly; asking again for PIN" }
                        previousPINAttemptFailed = true
                    }
                }

                // Manually set the cached invariant pump data inside transportLayerIO,
                // otherwise the next outgoing packets will not be properly authenticated
                // (and their address bytes won't be valid). We'll update this later on
                // with the final version of the invariant data. That's also the one
                // that will be written into the pump state store.
                transportLayerIO.setManualInvariantPumpData(
                    InvariantPumpData(
                        clientPumpCipher = keyResponseInfo.clientPumpCipher,
                        pumpClientCipher = keyResponseInfo.pumpClientCipher,
                        keyResponseAddress = keyResponseInfo.keyResponseAddress,
                        pumpID = ""
                    )
                )

                logger(LogLevel.DEBUG) { "Requesting the pump ID from the pump" }
                val idResponsePacket = sendPacketWithResponse(
                    TransportLayer.createRequestIDPacketInfo(bluetoothFriendlyName),
                    TransportLayer.Command.ID_RESPONSE
                )
                val pumpID = processIDResponsePacket(idResponsePacket)

                val newPumpData = InvariantPumpData(
                    clientPumpCipher = keyResponseInfo.clientPumpCipher,
                    pumpClientCipher = keyResponseInfo.pumpClientCipher,
                    keyResponseAddress = keyResponseInfo.keyResponseAddress,
                    pumpID = pumpID
                )
                transportLayerIO.setManualInvariantPumpData(newPumpData)
                pumpStateStore.createPumpState(pumpAddress, newPumpData)

                val firstTxNonce = Nonce(
                    byteArrayListOfInts(
                        0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
                    )
                )
                pumpStateStore.setCurrentTxNonce(pumpAddress, firstTxNonce)

                progressReporter?.setCurrentProgressStage(BasicProgressStage.ComboPairingFinishing)

                // Initiate a regular (= non-pairing) transport layer connection.
                // Note that we are still pairing - it just continues in the
                // application layer. For this to happen, we need a regular
                // _transport layer_ connection.
                // Wait for the response and verify it.
                logger(LogLevel.DEBUG) { "Sending regular connection request" }
                sendPacketWithResponse(
                    TransportLayer.createRequestRegularConnectionPacketInfo(),
                    TransportLayer.Command.REGULAR_CONNECTION_REQUEST_ACCEPTED
                )

                // Initiate application-layer connection and wait for the response.
                // (The response contains no meaningful payload.)
                logger(LogLevel.DEBUG) { "Initiating application layer connection" }
                sendPacketWithResponse(
                    ApplicationLayer.createCTRLConnectPacket(),
                    ApplicationLayer.Command.CTRL_CONNECT_RESPONSE
                )

                // Next, we have to query the versions of both command mode and
                // RT mode services. It is currently unknown how to interpret
                // the version numbers, but apparently we _have_ to query them,
                // otherwise the pump considers it an error.
                // TODO: Further verify this.
                logger(LogLevel.DEBUG) { "Requesting command mode service version" }
                sendPacketWithResponse(
                    ApplicationLayer.createCTRLGetServiceVersionPacket(ApplicationLayer.ServiceID.COMMAND_MODE),
                    ApplicationLayer.Command.CTRL_GET_SERVICE_VERSION_RESPONSE
                )
                // NOTE: These two steps may not be necessary. See the
                // "Application layer pairing" section in the spec.
                /*
                sendPacketWithResponse(
                    ApplicationLayer.ApplicationLayer.createCTRLGetServiceVersionPacket(ApplicationLayer.ServiceID.RT_MODE),
                    ApplicationLayer.Command.CTRL_GET_SERVICE_VERSION_RESPONSE
                )
                */

                // Next, send a BIND command and wait for the response.
                // (The response contains no meaningful payload.)
                logger(LogLevel.DEBUG) { "Sending BIND command" }
                sendPacketWithResponse(
                    ApplicationLayer.createCTRLBindPacket(),
                    ApplicationLayer.Command.CTRL_BIND_RESPONSE
                )

                // We have to re-connect the regular connection at the
                // transport layer now. (Unclear why, but it seems this
                // is necessary for the pairing process to succeed.)
                // Wait for the response and verify it.
                logger(LogLevel.DEBUG) { "Reconnecting regular connection" }
                sendPacketWithResponse(
                    TransportLayer.createRequestRegularConnectionPacketInfo(),
                    TransportLayer.Command.REGULAR_CONNECTION_REQUEST_ACCEPTED
                )

                // Pairing complete.
                logger(LogLevel.DEBUG) { "Pairing finished successfully - sending CTRL_DISCONNECT to Combo" }
            } catch (e: CancellationException) {
                logger(LogLevel.DEBUG) { "Pairing cancelled - sending CTRL_DISCONNECT to Combo" }
                throw e
            } catch (t: Throwable) {
                logger(LogLevel.ERROR) {
                    "Pairing aborted due to throwable - sending CTRL_DISCONNECT to Combo; " +
                    "throwable details: ${t.stackTraceToString()}"
                }
                throw t
            } finally {
                val disconnectPacketInfo = ApplicationLayer.createCTRLDisconnectPacket()
                transportLayerIO.stop(
                    disconnectPacketInfo.toTransportLayerPacketInfo(),
                    disconnectDeviceCallback
                )
            }
        }
    }

    /**
     * Establishes a regular connection.
     *
     * A "regular connection" is a connection that is used for
     * regular Combo operation. The client must have been paired with
     * the Combo before such connections can be established.
     *
     * This must be called before [switchMode] and any RT / command mode
     * function are used.
     *
     * The [packetReceiverScope] is used as the scope to run the transport
     * layer packet receiver in (handled by [TransportLayer.IO]). Some functions
     * like [startLongRTButtonPress] also launch coroutines. Any function that
     * launches coroutines does that in that scope.
     *
     * The actual connection procedure also happens in that scope, in its
     * own coroutine. That coroutine is represented by the [Deferred] that
     * is returned by this function.
     *
     * If an exception occurs _during_ connection setup, the connection
     * attempt is aborted, this PumpIO instance reverts to the disconnected
     * state, and the exception is caught by the returned Deferred value.
     * Users call [Deferred.await] to wait for the connection procedure to
     * finish; if an exception occurred during that procedure, it is re-thrown
     * by that function.
     *
     * [isConnected] will return true if the connection was established.
     *
     * [disconnect] is the counterpart to this function. It terminates
     * an existing connection and stops the worker.
     *
     * This also starts the "heartbeat" (unless explicitly requested not to).
     * See the [PumpIO] documentation above for details.
     *
     * @param packetReceiverScope Scope to run the packet receiver and other
     *   internal coroutines in.
     * @param progressReporter [ProgressReporter] for tracking connect progress.
     * @param initialMode What mode to initially switch to.
     * @param runHeartbeat True if the heartbeat shall be started.
     * @return [Deferred] representing the coroutine that finishes the connection
     *   setup procedure.
     * @throws IllegalStateException if IO was already started by a previous
     *   [connectAsync] call or if the [PumpStateStore] that was passed to the
     *   class constructor has no pairing data for this pump (most likely
     *   because the pump isn't paired yet).
     */
    fun connectAsync(
        packetReceiverScope: CoroutineScope,
        progressReporter: ProgressReporter<Unit>?,
        initialMode: Mode = Mode.REMOTE_TERMINAL,
        runHeartbeat: Boolean = true
    ): Deferred<Unit> {
        // Prerequisites.

        check(pumpStateStore.hasPumpState(pumpAddress)) {
            "Attempted to connect without a valid persistent state; pairing may not have been done"
        }
        check(!isConnected()) { "Attempted to connect even though a connection is already ongoing or established" }

        // Reset the display frame assembler in case it contains
        // partial frames from an earlier connection.
        displayFrameAssembler.reset()

        // Get rid of any existing frame in the replay cache.
        // Otherwise, subscribers will always be one frame
        // behind what the Combo is _actually_ currently showing,
        // which can cause serious errors while programmatically
        // navigating through RT screens.
        @Suppress("EXPERIMENTAL_API_USAGE")
        _displayFrameFlow.resetReplayCache()

        // Reinitialize the barrier, since it may have been closed
        // earlier due to an exception in the packet receiver.
        // (See the transportLayerIO initialization code.)
        rtButtonConfirmationBarrier = newRtButtonConfirmationBarrier()

        // Start the actual IO activity.
        transportLayerIO.start(packetReceiverScope) { tpLayerPacket -> processReceivedPacket(tpLayerPacket) }

        // Store the scope to allow other functions such as
        // startLongRTButtonPress to start coroutines in this scope.
        // We use our own sequenced dispatcher with it to disallow
        // parallelism in our internal coroutines.
        this.sequencedDispatcherScope = packetReceiverScope + transportLayerIO.sequencedDispatcher()

        logger(LogLevel.DEBUG) { "Pump IO connecting asynchronously" }

        progressReporter?.setCurrentProgressStage(BasicProgressStage.PerformingConnectionHandshake)

        // Launch the coroutine that sets up the connection.
        return this.sequencedDispatcherScope!!.async {
            try {
                logger(LogLevel.DEBUG) { "Sending regular connection request" }

                // Initiate connection at the transport layer.
                sendPacketWithResponse(
                    TransportLayer.createRequestRegularConnectionPacketInfo(),
                    TransportLayer.Command.REGULAR_CONNECTION_REQUEST_ACCEPTED
                )

                // Initiate connection at the application layer.
                logger(LogLevel.DEBUG) { "Initiating application layer connection" }
                sendPacketWithResponse(
                    ApplicationLayer.createCTRLConnectPacket(),
                    ApplicationLayer.Command.CTRL_CONNECT_RESPONSE
                )

                // Explicitly switch to the initial mode.
                switchMode(initialMode, runHeartbeat)

                logger(LogLevel.INFO) { "Pump IO connected" }
            } catch (t: Throwable) {
                disconnect()
                throw t
            }
        }
    }

    suspend fun disconnect() {
        // Make sure that any function that is suspended by this
        // barrier is woken up. Pass "false" to these functions
        // to let them know that they need to abort any loop
        // they might be running.
        rtButtonConfirmationBarrier.trySend(false)

        stopCMDPingHeartbeat()
        stopRTKeepAliveHeartbeat()

        val disconnectPacketInfo = ApplicationLayer.createCTRLDisconnectPacket()
        logger(LogLevel.VERBOSE) { "Will send application layer disconnect packet:  $disconnectPacketInfo" }

        transportLayerIO.stop(
            disconnectPacketInfo.toTransportLayerPacketInfo(),
            disconnectDeviceCallback
        )

        sequencedDispatcherScope = null
        _currentModeFlow.value = null

        logger(LogLevel.DEBUG) { "Pump IO disconnected" }
    }

    /** Returns true if IO is ongoing (due to a [connectAsync] call), false otherwise. */
    fun isConnected() = transportLayerIO.isIORunning()

    /**
     * Reads the current datetime of the pump in COMMAND (CMD) mode.
     *
     * The current datetime is always given in localtime.
     *
     * @return The current datetime.
     * @throws IllegalStateException if the pump is not in the comand
     *   mode or the pump is not connected.
     * @throws TransportLayer.PacketReceiverException if an exception is thrown
     *   in the packet receiver while this call is waiting for a packet or if
     *   an exception was thrown in the packet receiver prior to this call.
     * @throws ApplicationLayer.InvalidPayloadException if the size
     *   of a packet's payload does not match the expected size.
     * @throws ComboIOException if IO with the pump fails.
     */
    suspend fun readCMDDateTime(): DateTime {
        check(isConnected()) { "Cannot get current pump datetime because the pump is not connected" }
        check(_currentModeFlow.value == Mode.COMMAND) { "Cannot get current pump datetime while being in ${_currentModeFlow.value} mode" }

        val packet = sendPacketWithResponse(
            ApplicationLayer.createCMDReadDateTimePacket(),
            ApplicationLayer.Command.CMD_READ_DATE_TIME_RESPONSE
        )
        return ApplicationLayer.parseCMDReadDateTimeResponsePacket(packet)
    }

    /**
     * Reads the current status of the pump in COMMAND (CMD) mode.
     *
     * The pump can be either in the stopped or in the running status.
     *
     * @return The current status.
     * @throws IllegalStateException if the pump is not in the command
     *   mode or the pump is not connected.
     * @throws TransportLayer.PacketReceiverException if an exception is thrown
     *   in the packet receiver while this call is waiting for a packet or if
     *   an exception was thrown in the packet receiver prior to this call.
     * @throws ApplicationLayer.InvalidPayloadException if the size
     *   of a packet's payload does not match the expected size.
     * @throws ComboIOException if IO with the pump fails.
     */
    suspend fun readCMDPumpStatus(): ApplicationLayer.CMDPumpStatus {
        check(isConnected()) { "Cannot get pump status because the pump is not connected" }
        check(_currentModeFlow.value == Mode.COMMAND) { "Cannot get pump status while being in ${_currentModeFlow.value} mode" }

        val packet = sendPacketWithResponse(
            ApplicationLayer.createCMDReadPumpStatusPacket(),
            ApplicationLayer.Command.CMD_READ_PUMP_STATUS_RESPONSE
        )
        return ApplicationLayer.parseCMDReadPumpStatusResponsePacket(packet)
    }

    /**
     * Reads the current error/warning status of the pump in COMMAND (CMD) mode.
     *
     * @return The current status.
     * @throws IllegalStateException if the pump is not in the comand
     *   mode or the pump is not connected.
     * @throws TransportLayer.PacketReceiverException if an exception is thrown
     *   in the packet receiver while this call is waiting for a packet or if
     *   an exception was thrown in the packet receiver prior to this call.
     * @throws ApplicationLayer.InvalidPayloadException if the size
     *   of a packet's payload does not match the expected size.
     * @throws ComboIOException if IO with the pump fails.
     */
    suspend fun readCMDErrorWarningStatus(): ApplicationLayer.CMDErrorWarningStatus {
        check(isConnected()) { "Cannot get error/warning status because the pump is not connected" }
        check(_currentModeFlow.value == Mode.COMMAND) { "Cannot get error/warning status while being in ${_currentModeFlow.value} mode" }

        val packet = sendPacketWithResponse(
            ApplicationLayer.createCMDReadErrorWarningStatusPacket(),
            ApplicationLayer.Command.CMD_READ_ERROR_WARNING_STATUS_RESPONSE
        )
        return ApplicationLayer.parseCMDReadErrorWarningStatusResponsePacket(packet)
    }

    /**
     * Requests a CMD history delta.
     *
     * In the command mode, the Combo can provide a "history delta".
     * This means that the user can get what events occurred since the
     * last time a request was sent. Because this is essentially the
     * difference between the current history state and the history
     * state when the last request was sent, it is called a "delta".
     * This also means that if a request is sent again, and no new
     * event occurred in the meantime, the history delta will be empty
     * (= it will have zero events recorded). It is _not_ possible
     * to get the entire history with this function.
     *
     * The maximum amount of history block request is limited by the
     * maxRequests argument. This is a safeguard in case the data
     * keeps getting corrupted for some reason. Having a maximum
     * guarantees that we can't get stuck in an infinite loop.
     *
     * @param maxRequests How many history block request we can
     *        maximally send. This must be at least 10.
     * @return The history delta.
     * @throws IllegalArgumentException if maxRequests is less than 10.
     * @throws IllegalStateException if the pump is not in the comand
     *   mode or the pump is not connected.
     * @throws TransportLayer.PacketReceiverException if an exception is thrown
     *   in the packet receiver while this call is waiting for a packet or if
     *   an exception was thrown in the packet receiver prior to this call.
     * @throws ApplicationLayer.InvalidPayloadException if the size
     *   of a packet's payload does not match the expected size.
     * @throws ApplicationLayer.PayloadDataCorruptionException if
     *   packet data integrity is compromised.
     * @throws ApplicationLayer.InfiniteHistoryDataException if the
     *   call did not ever get a history block that marked an end
     *   to the history.
     * @throws ComboIOException if IO with the pump fails.
     */
    suspend fun getCMDHistoryDelta(maxRequests: Int): List<ApplicationLayer.CMDHistoryEvent> {
        require(maxRequests >= 10) { "Maximum amount of requests must be at least 10; caller specified $maxRequests" }
        check(isConnected()) { "Cannot get history delta because the pump is not connected" }
        check(_currentModeFlow.value == Mode.COMMAND) { "Cannot get history delta while being in ${_currentModeFlow.value} mode" }

        val historyDelta = mutableListOf<ApplicationLayer.CMDHistoryEvent>()
        var reachedEnd = false

        // Keep requesting history blocks until we reach the end,
        // and fill historyDelta with the events from each block,
        // skipping those events whose IDs are unknown (this is
        // taken care of by parseCMDReadHistoryBlockResponsePacket()).
        for (requestNr in 1 until maxRequests) {
            // Request the current history block from the Combo.
            val packet = sendPacketWithResponse(
                ApplicationLayer.createCMDReadHistoryBlockPacket(),
                ApplicationLayer.Command.CMD_READ_HISTORY_BLOCK_RESPONSE
            )

            // Try to parse and validate the packet data.
            val historyBlock = try {
                ApplicationLayer.parseCMDReadHistoryBlockResponsePacket(packet)
            } catch (t: Throwable) {
                logger(LogLevel.ERROR) {
                    "Could not parse history block; data may have been corrupted; requesting the block again (throwable: $t)"
                }
                continue
            }

            // Confirm this history block to let the Combo consider
            // it processed. The Combo can then move on to the next
            // history block.
            sendPacketWithResponse(
                ApplicationLayer.createCMDConfirmHistoryBlockPacket(),
                ApplicationLayer.Command.CMD_CONFIRM_HISTORY_BLOCK_RESPONSE
            )

            historyDelta.addAll(historyBlock.events)

            // Check if there is a next history block to get.
            // If not, we are done, and need to exit this loop.
            if (!historyBlock.moreEventsAvailable ||
                (historyBlock.numRemainingEvents <= historyBlock.events.size)) {
                reachedEnd = true
                break
            }
        }

        if (!reachedEnd)
            throw ApplicationLayer.InfiniteHistoryDataException(
                "Did not reach an end of the history event list even after $maxRequests request(s)"
            )

        return historyDelta
    }

    /**
     * Requests the current status of an ongoing bolus delivery.
     *
     * This is used for keeping track of the status of an ongoing bolus.
     * If no bolus is ongoing, the return value's bolusType field is
     * set to [ApplicationLayer.CMDBolusDeliveryState.NOT_DELIVERING].
     *
     * @return The current status.
     * @throws IllegalStateException if the pump is not in the comand
     *   mode or the pump is not connected.
     * @throws TransportLayer.PacketReceiverException if an exception is thrown
     *   in the packet receiver while this call is waiting for a packet or if
     *   an exception was thrown in the packet receiver prior to this call.
     * @throws ApplicationLayer.InvalidPayloadException if the size
     *   of a packet's payload does not match the expected size.
     * @throws ApplicationLayer.DataCorruptionException if some of
     *   the fields in the status data received from the pump
     *   contain invalid values.
     * @throws ComboIOException if IO with the pump fails.
     */
    suspend fun getCMDCurrentBolusDeliveryStatus(): ApplicationLayer.CMDBolusDeliveryStatus {
        check(isConnected()) { "Cannot get current bolus delivery status because the pump is not connected" }
        check(_currentModeFlow.value == Mode.COMMAND) {
            "Cannot get current bolus delivery status while being in ${_currentModeFlow.value} mode"
        }

        val packet = sendPacketWithResponse(
            ApplicationLayer.createCMDGetBolusStatusPacket(),
            ApplicationLayer.Command.CMD_GET_BOLUS_STATUS_RESPONSE
        )

        return ApplicationLayer.parseCMDGetBolusStatusResponsePacket(packet)
    }

    /**
     * Instructs the pump to deliver the specified standard bolus amount.
     *
     * As the name suggests, this function can only deliver a standard bolus,
     * and no multi-wave or extended ones. In the future, additional functions
     * may be written that can deliver those.
     *
     * The return value indicates whether or not the delivery was actually
     * done. The delivery may not happen if for example the pump is currently
     * stopped, or if it is already administering another bolus. It is
     * recommended to keep track of the current bolus status by periodically
     * calling [getCMDCurrentBolusDeliveryStatus].
     *
     * @param bolusAmount Bolus amount to deliver. Note that this is given
     *        in 0.1 IU units, so for example, "57" means 5.7 IU.
     * @return true if the bolus could be delivered, false otherwise.
     * @throws IllegalStateException if the pump is not in the comand
     *   mode or the pump is not connected.
     * @throws TransportLayer.PacketReceiverException if an exception is thrown
     *   in the packet receiver while this call is waiting for a packet or if
     *   an exception was thrown in the packet receiver prior to this call.
     * @throws ApplicationLayer.InvalidPayloadException if the size
     *   of a packet's payload does not match the expected size.
     * @throws ComboIOException if IO with the pump fails.
     */
    suspend fun deliverCMDStandardBolus(bolusAmount: Int): Boolean {
        check(isConnected()) { "Cannot deliver standard bolus because the pump is not connected" }
        check(_currentModeFlow.value == Mode.COMMAND) { "Cannot deliver standard bolus while being in ${_currentModeFlow.value} mode" }

        val packet = sendPacketWithResponse(
            ApplicationLayer.createCMDDeliverBolusPacket(bolusAmount),
            ApplicationLayer.Command.CMD_DELIVER_BOLUS_RESPONSE
        )

        return ApplicationLayer.parseCMDDeliverBolusResponsePacket(packet)
    }

    /**
     * Cancels an ongoing bolus.
     *
     * @return true if the bolus was cancelled, false otherwise.
     *   If no bolus is ongoing, this returns false as well.
     * @throws IllegalStateException if the pump is not in the command
     *   mode or the pump is not connected.
     * @throws TransportLayer.PacketReceiverException if an exception is thrown
     *   in the packet receiver while this call is waiting for a packet or if
     *   an exception was thrown in the packet receiver prior to this call.
     */
    suspend fun cancelCMDStandardBolus(): Boolean {
        // TODO: Test that this function does the expected thing
        // when no bolus is actually ongoing.
        check(isConnected()) { "Cannot cancel bolus because the pump is not connected" }
        check(_currentModeFlow.value == Mode.COMMAND) { "Cannot cancel bolus while being in ${_currentModeFlow.value} mode" }

        val packet = sendPacketWithResponse(
            ApplicationLayer.createCMDCancelBolusPacket(ApplicationLayer.CMDBolusType.STANDARD),
            ApplicationLayer.Command.CMD_CANCEL_BOLUS_RESPONSE
        )

        return ApplicationLayer.parseCMDCancelBolusResponsePacket(packet)
    }

    /**
     * Performs a short button press.
     *
     * This mimics the physical pressing of buttons for a short
     * moment, followed by those buttons being released.
     *
     * This may not be called while a long button press is ongoing.
     * It can only be called in the remote terminal (RT) mode.
     *
     * It is possible to short-press multiple buttons at the same
     * time. This is necessary for moving back in the Combo's menu
     * example. The buttons in the specified list are combined to
     * form a code that is transmitted to the pump.
     *
     * @param buttons What button(s) to short-press.
     * @throws IllegalArgumentException If the buttons list is empty.
     * @throws IllegalStateException if a long button press is
     *   ongoing, the pump is not in the RT mode, or the
     *   pump is not connected.
     * @throws TransportLayer.PacketReceiverException if an exception is thrown
     *   in the packet receiver while this call is waiting for a packet or if
     *   an exception was thrown in the packet receiver prior to this call.
     */
    suspend fun sendShortRTButtonPress(buttons: List<ApplicationLayer.RTButton>) {
        require(buttons.isNotEmpty()) { "Cannot send short RT button press since the specified buttons list is empty" }
        check(isConnected()) { "Cannot send short RT button press because the pump is not connected" }
        check(_currentModeFlow.value == Mode.REMOTE_TERMINAL) {
            "Cannot send short RT button press while being in ${_currentModeFlow.value} mode"
        }
        check(currentLongRTPressJob == null) { "Cannot send short RT button press while a long RT button press is ongoing" }

        val buttonCodes = getCombinedButtonCodes(buttons)
        var delayBeforeNoButton = false

        try {
            withContext(transportLayerIO.sequencedDispatcher()) {
                sendPacketWithoutResponse(ApplicationLayer.createRTButtonStatusPacket(buttonCodes, true))
                // Wait by "receiving" a value. We aren't actually interested
                // in that value, just in receive() suspending this coroutine
                // until the RT button was confirmed by the Combo.
                rtButtonConfirmationBarrier.receive()
            }
        } catch (e: CancellationException) {
            delayBeforeNoButton = true
            throw e
        } catch (t: Throwable) {
            delayBeforeNoButton = true
            logger(LogLevel.ERROR) { "Error thrown during short RT button press: ${t.stackTraceToString()}" }
            throw t
        } finally {
            // Wait 200 milliseconds before sending NO_BUTTON if we reached this
            // location due to an exception. That's because in that case we cannot
            // know if the button confirmation barrier' receive() call was
            // cancelled or not, and we shouldn't send button status packets
            // to the Combo too quickly.
            if (delayBeforeNoButton)
                delay(TransportLayer.PACKET_SEND_INTERVAL_IN_MS)

            // Make sure we always attempt to send the NO_BUTTON
            // code to finish the short button press, even if
            // an exception is thrown.
            try {
                sendPacketWithoutResponse(
                    ApplicationLayer.createRTButtonStatusPacket(ApplicationLayer.RTButton.NO_BUTTON.id, true)
                )
            } catch (t: Throwable) {
                logger(LogLevel.DEBUG) { "Swallowing error that was thrown while sending NO_BUTTON; exception: $t" }
            }
        }
    }

    /**
     * Performs a short button press.
     *
     * This overload is for convenience in case exactly one button
     * is to be pressed.
     */
    suspend fun sendShortRTButtonPress(button: ApplicationLayer.RTButton) =
        sendShortRTButtonPress(listOf(button))

    /**
     * Starts a long RT button press, imitating buttons being held down.
     *
     * This can only be called in the remote terminal (RT) mode.
     *
     * If a long button press is already ongoing, this function
     * does nothing.
     *
     * It is possible to long-press multiple buttons at the same
     * time. This is necessary for moving back in the Combo's menu
     * example. The buttons in the specified list are combined to
     * form a code that is transmitted to the pump.
     *
     * Internally, a coroutine is launched that repeatedly transmits
     * a confirmation command to the Combo that the buttons are still
     * being held down. This loop continues until either the keepGoing
     * predicate returns true (if that predicate isn't null) or until
     * [stopLongRTButtonPress] is called. In both cases, a command is
     * sent to the Combo to signal that the user "released" the buttons.
     *
     * If the keepGoing predicate is set, it is called before sending
     * each confirmation command. This is particularly useful for
     * aborting the loop at just the right time. In the Combo, this
     * command triggers updates associated with the button(s) and the
     * current screen. For example, in the bolus screen, if the UP
     * button is pressed, such a command will cause the bolus amount
     * to be incremented. Therefore, if the code in keepGoing waits
     * for received [DisplayFrame] instances to check their contents
     * before deciding whether to return true or false, it becomes
     * possible to stop the bolus increment at precisely the correct
     * moment (= when the target amount is reached). If however the
     * confirmation commands were sent _too_ quickly, the user would
     * see that the bolus amount is incremented even after "releasing"
     * the button.
     *
     * @param buttons What button(s) to long-press.
     * @param keepGoing Predicate for deciding whether to continue
     *        the internal loop. If this is set to null, the loop
     *        behaves as if this returned true all the time.
     * @throws IllegalArgumentException If the buttons list is empty.
     * @throws IllegalStateException if the pump is not in the RT mode
     *   or the pump is not connected.
     * @throws TransportLayer.PacketReceiverException if an exception is thrown
     *   in the packet receiver while this call is waiting for a packet or if
     *   an exception was thrown in the packet receiver prior to this call.
     */
    suspend fun startLongRTButtonPress(buttons: List<ApplicationLayer.RTButton>, keepGoing: (suspend () -> Boolean)? = null) {
        check(isConnected()) {
            "Cannot send long RT button press because the pump is not connected"
        }

        require(buttons.isNotEmpty()) {
            "Cannot send long RT button press since the specified buttons list is empty"
        }

        if (currentLongRTPressJob != null) {
            logger(LogLevel.DEBUG) { "Long RT button press job already running; ignoring redundant call" }
            return
        }

        check(_currentModeFlow.value == Mode.REMOTE_TERMINAL) {
            "Cannot send long RT button press while being in ${_currentModeFlow.value} mode"
        }

        try {
            issueLongRTButtonPressUpdate(buttons, keepGoing, pressing = true)
        } catch (t: Throwable) {
            stopLongRTButtonPress()
            throw t
        }
    }

    /**
     * Performs a long button press.
     *
     * This overload is for convenience in case exactly one button
     * is to be pressed.
     */
    suspend fun startLongRTButtonPress(button: ApplicationLayer.RTButton, keepGoing: (suspend () -> Boolean)? = null) =
        startLongRTButtonPress(listOf(button), keepGoing)

    suspend fun stopLongRTButtonPress() {
        check(isConnected()) { "Cannot stop long RT button press because the pump is not connected" }

        if (currentLongRTPressJob == null) {
            logger(LogLevel.DEBUG) {
                "No long RT button press job running, and button press state is RELEASED; ignoring redundant call"
            }
            return
        }

        // If a long RT button press job is running, we must
        // be in the RT mode, otherwise something is wrong.
        check(_currentModeFlow.value == Mode.REMOTE_TERMINAL)

        issueLongRTButtonPressUpdate(listOf(ApplicationLayer.RTButton.NO_BUTTON), keepGoing = null, pressing = false)
    }

    /**
     * Waits for the coroutine that drives the long RT button press loop to finish.
     *
     * This finishes when either the keepAlive predicate in [startLongRTButtonPress]
     * returns false or [stopLongRTButtonPress] is called. The former is the more
     * common use case for this function.
     *
     * If no long RT button press is ongoing, this function does nothing,
     * and just exits immediately.
     *
     * @throws Exception Exceptions that were thrown in the keepGoing callback
     *   that was passed to [startLongRTButtonPress].
     */
    suspend fun waitForLongRTButtonPressToFinish() =
        // currentLongRTPressJob is set to null automatically when the job finishes
        currentLongRTPressJob?.await()

    /**
     * Switches the Combo to a different mode.
     *
     * The two valid modes are the remote terminal (RT) mode and the command mode.
     *
     * If an exception occurs, either disconnect, or try to repeat the mode switch.
     * This is important to make sure the pump is in a known mode.
     *
     * The runHeartbeat argument functions just like the one in [connectAsync].
     * It is necessary because mode switching stops any currently ongoing heartbeat.
     *
     * If the mode specified by newMode is the same as the current mode,
     * this function does nothing.
     *
     * @param newMode Mode to switch to.
     * @param runHeartbeat Whether or not to run the "heartbeat".
     * @throws IllegalStateException if the pump is not connected.
     * @throws TransportLayer.PacketReceiverException if an exception is thrown
     *   inside the packet receiver while this call is waiting for a packet
     *   or if an exception was thrown inside the receiver prior to this call.
     * @throws ComboIOException if IO with the pump fails.
     */
    suspend fun switchMode(newMode: Mode, runHeartbeat: Boolean = true) = withContext(NonCancellable) {
        // This function is in a NonCancellable context to avoid undefined behavior
        // if cancellation occurs during mode change.

        check(isConnected()) { "Cannot switch mode because the pump is not connected" }

        if (_currentModeFlow.value == newMode)
            return@withContext

        logger(LogLevel.DEBUG) { "Switching mode from ${_currentModeFlow.value} to $newMode" }

        stopCMDPingHeartbeat()
        stopRTKeepAliveHeartbeat()

        // Get rid of any existing frame in the replay cache. The displayFrameFlow
        // contains the last frame that was received in the remote terminal mode
        // if the pump had been running in the remote terminal mode earlier and
        // was then switched to command mode. If we are now switching back to
        // remote terminal mode, then that last frame is still lingering around,
        // and we must flush it out of the flow to prevent mismatches between
        // what the flow thinks the current screen is and what the current screen
        // actually is.
        @Suppress("EXPERIMENTAL_API_USAGE")
        _displayFrameFlow.resetReplayCache()

        // Send the command to switch the mode.

        _currentModeFlow.value?.let { modeToDeactivate ->
            logger(LogLevel.DEBUG) { "Deactivating current service" }
            sendPacketWithResponse(
                ApplicationLayer.createCTRLDeactivateServicePacket(
                    when (modeToDeactivate) {
                        Mode.REMOTE_TERMINAL -> ApplicationLayer.ServiceID.RT_MODE
                        Mode.COMMAND -> ApplicationLayer.ServiceID.COMMAND_MODE
                    }
                ),
                ApplicationLayer.Command.CTRL_DEACTIVATE_SERVICE_RESPONSE
            )
        }

        logger(LogLevel.DEBUG) { "Activating new service" }
        sendPacketWithResponse(
            ApplicationLayer.createCTRLActivateServicePacket(
                when (newMode) {
                    Mode.REMOTE_TERMINAL -> ApplicationLayer.ServiceID.RT_MODE
                    Mode.COMMAND -> ApplicationLayer.ServiceID.COMMAND_MODE
                }
            ),
            ApplicationLayer.Command.CTRL_ACTIVATE_SERVICE_RESPONSE
        )

        _currentModeFlow.value = newMode

        if (runHeartbeat) {
            logger(LogLevel.DEBUG) { "Resetting heartbeat" }
            when (newMode) {
                Mode.COMMAND -> startCMDPingHeartbeat()
                Mode.REMOTE_TERMINAL -> startRTKeepAliveHeartbeat()
            }
        }
    }

    /*************************************
     *** PRIVATE FUNCTIONS AND CLASSES ***
     *************************************/

    private fun newRtButtonConfirmationBarrier() =
        Channel<Boolean>(capacity = Channel.CONFLATED)

    private fun getCombinedButtonCodes(buttons: List<ApplicationLayer.RTButton>) =
        buttons.fold(0) { codes, button -> codes or button.id }

    private fun toString(buttons: List<ApplicationLayer.RTButton>) = buttons.joinToString(" ") { it.str }

    // The sendPacketWithResponse and sendPacketWithoutResponse calls
    // are surrounded by a sendPacketMutex lock to prevent these functions
    // from being called concurrently. This is essential, since the Combo
    // cannot handle such concurrent calls. In particular, if a command
    // that is sent to the Combo will cause the pump to respond with
    // another command, we must make sure that we receive the response
    // _before_ sending another command to the pump. (The main potential
    // cause of concurrent send calls are the heartbeat coroutines.)
    //
    // Note that these functions use a coroutine mutex, not a "classical",
    // thread level mutex. See kotlinx.coroutines.sync.Mutex for details.
    //
    // Furthermore, these use the NonCancellable context to prevent the
    // prompt cancellation guarantee from cancelling any send attempts.

    private suspend fun sendPacketWithResponse(
        tpLayerPacketInfo: TransportLayer.OutgoingPacketInfo,
        expectedResponseCommand: TransportLayer.Command? = null
    ): TransportLayer.Packet = sendPacketMutex.withLock {
        return withContext(NonCancellable) {
            transportLayerIO.send(tpLayerPacketInfo)
            transportLayerIO.receive(expectedResponseCommand)
        }
    }

    private suspend fun sendPacketWithResponse(
        appLayerPacketToSend: ApplicationLayer.Packet,
        expectedResponseCommand: ApplicationLayer.Command? = null
    ): ApplicationLayer.Packet = sendPacketMutex.withLock {
        return withContext(NonCancellable) {
            restartHeartbeat()

            sendAppLayerPacket(appLayerPacketToSend)

            logger(LogLevel.VERBOSE) {
                if (expectedResponseCommand == null)
                    "Waiting for application layer packet (will arrive in a transport layer DATA packet)"
                else
                    "Waiting for application layer ${expectedResponseCommand.name} " +
                    "packet (will arrive in a transport layer DATA packet)"
            }

            val receivedAppLayerPacket = transportLayerIO.receive(TransportLayer.Command.DATA).toAppLayerPacket()

            if ((expectedResponseCommand != null) && (receivedAppLayerPacket.command != expectedResponseCommand))
                throw ApplicationLayer.IncorrectPacketException(receivedAppLayerPacket, expectedResponseCommand)

            receivedAppLayerPacket
        }
    }

    private suspend fun sendPacketWithoutResponse(
        tpLayerPacketInfo: TransportLayer.OutgoingPacketInfo
    ) = sendPacketMutex.withLock {
        withContext(NonCancellable) {
            transportLayerIO.send(tpLayerPacketInfo)
        }
    }

    private suspend fun sendPacketWithoutResponse(
        appLayerPacketToSend: ApplicationLayer.Packet
    ) = sendPacketMutex.withLock {
        withContext(NonCancellable) {
            restartHeartbeat()
            sendAppLayerPacket(appLayerPacketToSend)
        }
    }

    private suspend fun sendAppLayerPacket(appLayerPacket: ApplicationLayer.Packet) {
        // NOTE: This function does NOT lock a mutex and does NOT use
        // NonCancellable. Make sure to set these up before calling this.

        logger(LogLevel.VERBOSE) {
            "Sending application layer packet via transport layer:  $appLayerPacket"
        }

        val outgoingPacketInfo = appLayerPacket.toTransportLayerPacketInfo()

        if (appLayerPacket.command.serviceID == ApplicationLayer.ServiceID.RT_MODE) {
            if (outgoingPacketInfo.payload.size < (ApplicationLayer.PAYLOAD_BYTES_OFFSET + 2)) {
                throw ApplicationLayer.InvalidPayloadException(
                    appLayerPacket,
                    "Cannot send application layer RT packet since there's no room in the payload for the RT sequence number"
                )
            }

            logger(LogLevel.VERBOSE) { "Writing current RT sequence number $currentRTSequence into packet" }

            // The RT sequence is always stored in the
            // first 2 bytes  of an RT packet's payload.
            //
            // Also, we set the RT sequence in the outgoingPacketInfo,
            // and not in appLayerPacket's payload, since the latter
            // is a function argument, and modifying the payload of
            // an outside value may lead to confusing behavior.
            // By writing the RT sequence into outgoingPacketInfo
            // instead, that change stays contained in here.
            outgoingPacketInfo.payload[ApplicationLayer.PAYLOAD_BYTES_OFFSET + 0] =
                ((currentRTSequence shr 0) and 0xFF).toByte()
            outgoingPacketInfo.payload[ApplicationLayer.PAYLOAD_BYTES_OFFSET + 1] =
                ((currentRTSequence shr 8) and 0xFF).toByte()

            // After using the RT sequence, increment it to
            // make sure the next RT packet doesn't use the
            // same RT sequence.
            currentRTSequence++
            if (currentRTSequence > 65535)
                currentRTSequence = 0
        }

        transportLayerIO.send(outgoingPacketInfo)
    }

    private fun processReceivedPacket(tpLayerPacket: TransportLayer.Packet) =
        if (tpLayerPacket.command == TransportLayer.Command.DATA) {
            when (ApplicationLayer.extractAppLayerPacketCommand(tpLayerPacket)) {
                ApplicationLayer.Command.CTRL_ACTIVATE_SERVICE_RESPONSE -> {
                    logger(LogLevel.DEBUG) { "New service was activated; resetting RT sequence number" }
                    currentRTSequence = 0
                    TransportLayer.IO.ReceiverBehavior.FORWARD_PACKET
                }

                ApplicationLayer.Command.RT_DISPLAY -> {
                    processRTDisplayPayload(
                        ApplicationLayer.parseRTDisplayPacket(tpLayerPacket.toAppLayerPacket())
                    )
                    // Signal the arrival of the button confirmation.
                    // (Either RT_BUTTON_CONFIRMATION or RT_DISPLAY
                    // function as confirmations.) Transmit "true"
                    // to let the receivers know that everything
                    // is OK and that they don't need to abort.
                    rtButtonConfirmationBarrier.trySend(true)
                    TransportLayer.IO.ReceiverBehavior.DROP_PACKET
                }

                ApplicationLayer.Command.RT_BUTTON_CONFIRMATION -> {
                    logger(LogLevel.VERBOSE) { "Got RT_BUTTON_CONFIRMATION packet from the Combo" }
                    // Signal the arrival of the button confirmation.
                    // (Either RT_BUTTON_CONFIRMATION or RT_DISPLAY
                    // function as confirmations.) Transmit "true"
                    // to let the receivers know that everything
                    // is OK and that they don't need to abort.
                    rtButtonConfirmationBarrier.trySend(true)
                    TransportLayer.IO.ReceiverBehavior.DROP_PACKET
                }

                // We do not care about keep-alive packets from the Combo.
                ApplicationLayer.Command.RT_KEEP_ALIVE -> {
                    logger(LogLevel.VERBOSE) { "Got RT_KEEP_ALIVE packet from the Combo; ignoring" }
                    TransportLayer.IO.ReceiverBehavior.DROP_PACKET
                }

                // RT_AUDIO, RT_PAUSE, RT_RELEASE, RT_VIBRATION packets
                // are purely for information. We just log them and
                // otherwise ignore them.

                ApplicationLayer.Command.RT_AUDIO -> {
                    logger(LogLevel.VERBOSE) {
                        val audioType = ApplicationLayer.parseRTAudioPacket(tpLayerPacket.toAppLayerPacket())
                        "Got RT_AUDIO packet with audio type ${audioType.toHexString(8)}; ignoring"
                    }
                    TransportLayer.IO.ReceiverBehavior.DROP_PACKET
                }

                ApplicationLayer.Command.RT_PAUSE,
                ApplicationLayer.Command.RT_RELEASE -> {
                    logger(LogLevel.VERBOSE) {
                        "Got ${ApplicationLayer.Command} packet with payload " +
                                "${tpLayerPacket.toAppLayerPacket().payload.toHexString()}; ignoring"
                    }
                    TransportLayer.IO.ReceiverBehavior.DROP_PACKET
                }

                ApplicationLayer.Command.RT_VIBRATION -> {
                    logger(LogLevel.VERBOSE) {
                        val vibrationType = ApplicationLayer.parseRTVibrationPacket(
                            tpLayerPacket.toAppLayerPacket()
                        )
                        "Got RT_VIBRATION packet with vibration type ${vibrationType.toHexString(8)}; ignoring"
                    }
                    TransportLayer.IO.ReceiverBehavior.DROP_PACKET
                }

                // This is an information by the pump that something is wrong
                // with the connection / with the service. This error is
                // not recoverable. Throw an exception here to let the
                // packet receiver fail. It will forward the exception to
                // any ongoing send and receive calls.
                ApplicationLayer.Command.CTRL_SERVICE_ERROR -> {
                    val appLayerPacket = tpLayerPacket.toAppLayerPacket()
                    val ctrlServiceError = ApplicationLayer.parseCTRLServiceErrorPacket(appLayerPacket)
                    logger(LogLevel.ERROR) { "Got CTRL_SERVICE_ERROR packet from the Combo; throwing exception" }
                    throw ApplicationLayer.ServiceErrorException(appLayerPacket, ctrlServiceError)
                }

                else -> TransportLayer.IO.ReceiverBehavior.FORWARD_PACKET
            }
        } else
            TransportLayer.IO.ReceiverBehavior.FORWARD_PACKET

    private fun processRTDisplayPayload(rtDisplayPayload: ApplicationLayer.RTDisplayPayload) {
        // Feed the payload to the display frame assembler to let it piece together
        // frames and output them via the callback.

        try {
            val displayFrame = displayFrameAssembler.processRTDisplayPayload(
                rtDisplayPayload.index,
                rtDisplayPayload.row,
                rtDisplayPayload.rowBytes
            )
            if (displayFrame != null)
                _displayFrameFlow.tryEmit(displayFrame)
        } catch (t: Throwable) {
            logger(LogLevel.ERROR) { "Could not process RT_DISPLAY payload: $t" }
            throw t
        }
    }

    private fun isCMDPingHeartbeatRunning() = (cmdPingHeartbeatJob != null)

    private fun startCMDPingHeartbeat() {
        if (isCMDPingHeartbeatRunning())
            return

        logger(LogLevel.VERBOSE) { "Starting background CMD ping heartbeat" }

        require(sequencedDispatcherScope != null)

        cmdPingHeartbeatJob = sequencedDispatcherScope!!.launch {
            // We have to send a CMD_PING packet to the Combo
            // every second to let the Combo know that we are still
            // there. Otherwise, it will terminate the connection,
            // since it then assumes that we are no longer connected
            // (for example due to a system crash).
            while (true) {
                logger(LogLevel.VERBOSE) { "Transmitting CMD ping packet" }
                try {
                    sendPacketWithResponse(
                        ApplicationLayer.createCMDPingPacket(),
                        ApplicationLayer.Command.CMD_PING_RESPONSE
                    )
                } catch (e: CancellationException) {
                    cmdPingHeartbeatJob = null
                    throw e
                } catch (e: TransportLayer.PacketReceiverException) {
                    logger(LogLevel.ERROR) {
                        "Could not send CMD ping packet because packet receiver failed - stopping CMD ping heartbeat"
                    }
                    cmdPingHeartbeatJob = null
                    break
                } catch (t: Throwable) {
                    logger(LogLevel.ERROR) {
                        "Error caught when attempting to transmit CMD ping packet - stopping CMD ping heartbeat"
                    }
                    logger(LogLevel.ERROR) {
                        "Error: ${t.stackTraceToString()}"
                    }
                    cmdPingHeartbeatJob = null
                    break
                }
                delay(1000)
            }
        }
    }

    private suspend fun stopCMDPingHeartbeat() {
        if (!isCMDPingHeartbeatRunning())
            return

        logger(LogLevel.VERBOSE) { "Stopping background CMD ping heartbeat" }

        cmdPingHeartbeatJob?.cancelAndJoin()
        cmdPingHeartbeatJob = null

        logger(LogLevel.VERBOSE) { "Background CMD ping heartbeat stopped" }
    }

    private fun isRTKeepAliveHeartbeatRunning() = (rtKeepAliveHeartbeatJob != null)

    private fun startRTKeepAliveHeartbeat() {
        if (isRTKeepAliveHeartbeatRunning())
            return

        logger(LogLevel.VERBOSE) { "Starting background RT keep-alive heartbeat" }

        require(sequencedDispatcherScope != null)

        rtKeepAliveHeartbeatJob = sequencedDispatcherScope!!.launch {
            // In RT mode, if no RT command has been sent to the Combo
            // within about 1-1.5 seconds, the Combo terminates the
            // connection, assuming that the client is gone. As a
            // consequence, we have to send an RT_KEEP_ALIVE packet
            // to the Combo after a second.
            //
            // It is possible to prevent these packets from being
            // sent when other RT commands were sent. To that end, if
            // an RT command is to be sent, restartKeepAliveIfRequired()
            // can be called to effectively reset this timeout back to
            // one second. If RT commands are sent frequently, this
            // causes the timeout to be constantly reset, and the
            // RT_KEEP_ALIVE packet isn't sent until no more RT
            // commands are sent.
            //
            // Also note that in here, we use sendAppLayerPacket()
            // directly, and not sendPacketWithoutResponse(). The reason
            // for this is that the sendPacketWithoutResponse() function
            // internally resets the timeout using restartHeartbeat(),
            // and doing that here would cause an infinite loop
            // (and makes no sense).
            while (true) {
                // *First* wait, and only *afterwards* send the
                // RT_KEEP_ALIVE packet. This order is important, since
                // otherwise, an RT_KEEP_ALIVE packet would be sent out
                // immediately, and thus we would not have the timeout
                // behavior described above.
                delay(1000)
                logger(LogLevel.VERBOSE) { "Transmitting RT keep-alive packet" }
                try {
                    sendAppLayerPacket(ApplicationLayer.createRTKeepAlivePacket())
                } catch (e: CancellationException) {
                    rtKeepAliveHeartbeatJob = null
                    throw e
                } catch (e: TransportLayer.PacketReceiverException) {
                    logger(LogLevel.ERROR) {
                        "Could not send RT keep-alive packet because packet receiver failed - stopping RT keep-alive heartbeat"
                    }
                    rtKeepAliveHeartbeatJob = null
                    break
                } catch (t: Throwable) {
                    logger(LogLevel.ERROR) {
                        "Error caught when attempting to transmit RT keep-alive packet - stopping RT keep-alive heartbeat"
                    }
                    logger(LogLevel.ERROR) {
                        "Error: ${t.stackTraceToString()}"
                    }
                    rtKeepAliveHeartbeatJob = null
                    break
                }
            }
        }
    }

    private suspend fun stopRTKeepAliveHeartbeat() {
        if (!isRTKeepAliveHeartbeatRunning())
            return

        logger(LogLevel.VERBOSE) { "Stopping background RT keep-alive heartbeat" }

        rtKeepAliveHeartbeatJob!!.cancelAndJoin()
        rtKeepAliveHeartbeatJob = null

        logger(LogLevel.VERBOSE) { "Background RT keep-alive heartbeat stopped" }
    }

    private suspend fun restartHeartbeat() {
        when (currentModeFlow.value) {
            Mode.REMOTE_TERMINAL -> {
                if (isRTKeepAliveHeartbeatRunning()) {
                    stopRTKeepAliveHeartbeat()
                    startRTKeepAliveHeartbeat()
                }
            }

            Mode.COMMAND -> {
                if (isCMDPingHeartbeatRunning()) {
                    stopCMDPingHeartbeat()
                    startCMDPingHeartbeat()
                }
            }
        }
    }

    private suspend fun issueLongRTButtonPressUpdate(
        buttons: List<ApplicationLayer.RTButton>,
        keepGoing: (suspend () -> Boolean)?,
        pressing: Boolean
    ) {
        if (!pressing) {
            logger(LogLevel.DEBUG) {
                "Releasing RTs button(s) ${toString(currentLongRTPressedButtons)}"
            }

            // Set this to false to stop the long RT button press.
            longRTPressLoopRunning = false

            // Wait for job completion by using await(). This will
            // also re-throw any exceptions caught in that coroutine.
            // In cases where connection to the pump fails, and no
            // confirmation can be received anymore, this is still
            // woken up, because in tha case, this channel is closed.
            // See the transportLayerIO initialization above.
            currentLongRTPressJob?.await()

            return
        }

        currentLongRTPressedButtons = buttons
        val buttonCodes = getCombinedButtonCodes(buttons)
        longRTPressLoopRunning = true

        var delayBeforeNoButton = false

        currentLongRTPressJob = sequencedDispatcherScope!!.async {
            try {
                // First time, we send the button status with
                // the CHANGED status and with the codes for
                // the pressed buttons.
                var buttonStatusChanged = true

                while (longRTPressLoopRunning) {
                    // If there is a keepGoing predicate, call it _before_ sending
                    // a button status packet in case keepGoing() wishes to abort
                    // this loop already in its first iteration (for example, because
                    // a quantity that is shown on-screen is already correct).
                    if (keepGoing != null) {
                        try {
                            if (!keepGoing()) {
                                logger(LogLevel.DEBUG) { "Aborting long RT button press flow" }
                                break
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            logger(LogLevel.DEBUG) { "keepGoing callback threw error: $t" }
                            throw t
                        }
                    }

                    // Dummy tryReceive() call to clear out the barrier in case it isn't empty.
                    rtButtonConfirmationBarrier.tryReceive()

                    logger(LogLevel.DEBUG) {
                        "Sending long RT button press; button(s) = ${toString(buttons)} status changed = $buttonStatusChanged"
                    }

                    // Send the button status. This triggers an update on the Combo's
                    // remote terminal screen. For example, when pressing UP to
                    // increment a quantity, said quantity is incremented only
                    // after the Combo receives this status.
                    sendPacketWithoutResponse(
                        ApplicationLayer.createRTButtonStatusPacket(buttonCodes, buttonStatusChanged)
                    )

                    // Wait for the Combo to send us a button
                    // confirmation. We cannot send more button
                    // status commands until then.
                    logger(LogLevel.DEBUG) { "Waiting for button confirmation" }
                    val canContinue = rtButtonConfirmationBarrier.receive()
                    logger(LogLevel.DEBUG) { "Got button confirmation; canContinue = $canContinue" }

                    if (!canContinue)
                        break

                    // The next time we send the button status, we must
                    // send NOT_CHANGED to the Combo.
                    buttonStatusChanged = false
                }
            } catch (e: CancellationException) {
                delayBeforeNoButton = true
                throw e
            } catch (t: Throwable) {
                delayBeforeNoButton = true
                logger(LogLevel.ERROR) { "Error thrown during long RT button press: ${t.stackTraceToString()}" }
                throw t
            } finally {
                logger(LogLevel.DEBUG) { "Ending long RT button press by sending NO_BUTTON" }
                try {
                    // Call sendPacketWithoutResponse() and delay() in a NonCancellable
                    // context to circumvent the prompt cancellation guarantee (it is
                    // undesirable here because we need to let the Combo know that we
                    // want to stop the long RT button press).
                    withContext(NonCancellable) {
                        // Wait 200 milliseconds before sending NO_BUTTON if we reached this
                        // location due to an exception. That's because in that case we cannot
                        // know if the button confirmation barrier' receive() call was
                        // cancelled or not, and we shouldn't send button status packets
                        // to the Combo too quickly.
                        if (delayBeforeNoButton)
                            delay(200L)

                        sendPacketWithoutResponse(
                            ApplicationLayer.createRTButtonStatusPacket(
                                ApplicationLayer.RTButton.NO_BUTTON.id,
                                buttonStatusChanged = true
                            )
                        )
                    }
                } catch (t: Throwable) {
                    logger(LogLevel.DEBUG) { "Swallowing error that was thrown while sending NO_BUTTON; exception: $t" }
                }

                currentLongRTPressJob = null
            }
        }
    }

    private data class KeyResponseInfo(val pumpClientCipher: Cipher, val clientPumpCipher: Cipher, val keyResponseAddress: Byte)

    private fun processKeyResponsePacket(packet: TransportLayer.Packet, weakCipher: Cipher): KeyResponseInfo {
        if (packet.payload.size != (CIPHER_KEY_SIZE * 2))
            throw TransportLayer.InvalidPayloadException(packet, "Expected ${CIPHER_KEY_SIZE * 2} bytes, got ${packet.payload.size}")

        val encryptedPCKey = ByteArray(CIPHER_KEY_SIZE)
        val encryptedCPKey = ByteArray(CIPHER_KEY_SIZE)

        for (i in 0 until CIPHER_KEY_SIZE) {
            encryptedPCKey[i] = packet.payload[i + 0]
            encryptedCPKey[i] = packet.payload[i + CIPHER_KEY_SIZE]
        }

        val pumpClientCipher = Cipher(weakCipher.decrypt(encryptedPCKey))
        val clientPumpCipher = Cipher(weakCipher.decrypt(encryptedCPKey))

        // Note: Source and destination addresses are reversed,
        // since they are set from the perspective of the pump.
        val addressInt = packet.address.toPosInt()
        val sourceAddress = addressInt and 0xF
        val destinationAddress = (addressInt shr 4) and 0xF
        val keyResponseAddress = ((sourceAddress shl 4) or destinationAddress).toByte()

        // We begin setting up the invariant pump data here. However,
        // the pump state store cannot be initialized yet, because
        // we do not yet know the pump ID. This initialization continues
        // in processIDResponsePacket(). We fill cachedInvariantPumpData
        // with the data we currently know. Later, it is filled again,
        // and the remaining unknown data is also added.

        return KeyResponseInfo(
            pumpClientCipher = pumpClientCipher,
            clientPumpCipher = clientPumpCipher,
            keyResponseAddress = keyResponseAddress
        )
    }

    private fun processIDResponsePacket(packet: TransportLayer.Packet): String {
        if (packet.payload.size != 17)
            throw TransportLayer.InvalidPayloadException(packet, "Expected 17 bytes, got ${packet.payload.size}")

        val serverID = ((packet.payload[0].toPosLong() shl 0) or
                (packet.payload[1].toPosLong() shl 8) or
                (packet.payload[2].toPosLong() shl 16) or
                (packet.payload[3].toPosLong() shl 24))

        // The pump ID string can be up to 13 bytes long. If it
        // is shorter, the unused bytes are filled with nullbytes.
        val pumpIDStrBuilder = StringBuilder()
        for (i in 0 until 13) {
            val pumpIDByte = packet.payload[4 + i]
            if (pumpIDByte == 0.toByte()) break
            else pumpIDStrBuilder.append(pumpIDByte.toInt().toChar())
        }
        val pumpID = pumpIDStrBuilder.toString()

        logger(LogLevel.DEBUG) {
            "Received IDs: server ID: $serverID pump ID: $pumpID"
        }

        return pumpID
    }
}
