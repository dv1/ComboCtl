package info.nightscout.comboctl.base

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val logger = Logger.get("PumpIO")

/**
 * Class for high-level Combo pump IO.
 *
 * This implements IO operations on top of the application layer IO.
 * Unlike [ApplicationLayerIO] and [TransportLayerIO], this class
 * does not deal with packets in its public API. Instead, a few high
 * level functions are implemented using these two other IO classes.
 * These are functions like pairing or RT button press actions.
 *
 * For initiating the Combo pairing, the [performPairing] function is
 * available. This must not be used if a connection was already established
 * via [connect] - pairing is only possible in the disconnected state.
 *
 * For initiating a regular connection, use [connect]. Do not call
 * [connect] again until after disconnecting with [disconnect]. Also
 * see the remarks above about pairing and connecting at the same time.
 *
 * Note that [connect], [disconnect], [performPairing] do not establish
 * anything related to Bluetooth connection. Those are beyond the scope
 * of this class. See [Pump] for a class that combines both this class
 * and Bluetooth classes.
 *
 * The Combo regularly sends new display frames when running in the
 * REMOTE_TERMINAL (RT) mode. These frames come as the payload of
 * RT_DISPLAY packets. This class reads those packets and extracts the
 * partial frames (the packets only contain portions of a frame, not
 * a full frame). Once enough parts were gathered to assemble a full
 * frame, the frame is emitted via the [displayFrameFlow].
 *
 * Internally, this class runs a "background worker", which is the
 * sum of coroutines started by [connect] and [performPairing]. These
 * coroutines are run by a special internal dispatcher that is single
 * threaded. Internal states are updated in these coroutines. Since they
 * run on the same thread, race conditions are prevented, and thread
 * safety is established.
 *
 * Likewise, access to the persistentPumpStateStore is done in a thread
 * safe manner, since updates to the store happen inside those coroutines.
 * This implies that [PersistentPumpStateStore] functions do not have to
 * be thread safe themselves.
 *
 * [disconnect] cancels the coroutines, and thus "stops" the worker.
 * [performPairing] starts and stops the worker internally, since during
 * pairing, the worker is only needed for communicating the pairing
 * packets with the Combo.
 *
 * @param persistentPumpStateStore Persistent state store to use.
 * @param comboIO Combo IO object to use for sending/receiving data.
 */
class PumpIO(private val persistentPumpStateStore: PersistentPumpStateStore, private val comboIO: ComboIO) {
    private val applicationLayerIO: ApplicationLayerIO

    // The coroutine scope passed to connect() as an argument.
    // Used for starting other coroutines such as the RT keep-alive loop.
    private var backgroundIOScope: CoroutineScope? = null

    // Job representing the coroutine that runs the RT keep-alive loop.
    private var rtKeepAliveJob: Job? = null

    // Members associated with long-pressing buttons in RT mode.
    // Long-pressing is implemented by repeatedly sending RT_BUTTON_STATUS
    // messages until the user "releases" the buttons.
    // (We use a list of Button values in case multiple buttons are being
    // held down at the same time.)
    private var currentLongRTPressJob: Job? = null
    private var currentLongRTPressedButtons = listOf<Button>()

    // Members associated with display frame generation.
    // The mutable version of the displayFrameFlow is used internally
    // when a new frame is available.
    private var mutableDisplayFrameFlow = MutableStateFlow(NullDisplayFrame)
    private val displayFrameAssembler = DisplayFrameAssembler()

    // Whether we are in RT or COMMAND mode, or null at startup
    // before an initial mode was set.
    private var currentMode: Mode? = null

    /************************************
     *** PUBLIC FUNCTIONS AND CLASSES ***
     ************************************/

    /**
     * Read-only [StateFlow] property that delivers newly assembled display frames.
     *
     * Note that, unlike most other flow types, a [StateFlow] is a _hot_ flow.
     * This means that its emitter runs independently of any collector.
     *
     * See [DisplayFrame] for details about these frames.
     */
    val displayFrameFlow = mutableDisplayFrameFlow.asStateFlow()

    /**
     * The mode the IO can operate in.
     */
    enum class Mode(val str: String) {
        REMOTE_TERMINAL("REMOTE_TERMINAL"),
        COMMAND("COMMAND");

        override fun toString() = str
    }

    /**
     * Buttons available in the RT mode.
     */
    enum class Button(val str: String) {
        UP("UP"),
        DOWN("DOWN"),
        MENU("MENU"),
        CHECK("CHECK");

        override fun toString() = str
    }

    init {
        // Set up ApplicationLayerIO subclass that processes incoming
        // packets in order to handle notifications from the Combo.
        applicationLayerIO = object : ApplicationLayerIO(persistentPumpStateStore, comboIO) {
            override fun processIncomingPacket(appLayerPacket: Packet): Boolean {
                // RT_DISPLAY packets regularly come from the Combo (when running in RT mode),
                // and contains new display frame updates.
                //
                // CTRL_SERVICE_ERROR packets are errors that must be handled immediately.
                // Unlike the transport layer ERROR_RESPONSE packet, these packets report
                // errors at the application layer.
                //
                // RT_AUDIO and RT_VIBRATION packets inform us about simulated notification
                // sounds and vibrations, respectively. We do not need those here.
                //
                // As for the RT_BUTTON_CONFIRMATION and RT_KEEP_ALIVE packets, they are
                // notifications that we don't need here, so we ignore them. (Note that
                // RT_KEEP_ALIVE serves a dual purpose; we _do_ have to also send such
                // packets _to_ the Combo. The rtKeepAliveJob takes care of that.)

                when (appLayerPacket.command) {
                    ApplicationLayerIO.Command.RT_DISPLAY ->
                        processRTDisplayPayload(ApplicationLayerIO.parseRTDisplayPacket(appLayerPacket))
                    ApplicationLayerIO.Command.RT_BUTTON_CONFIRMATION -> {
                        logger(LogLevel.VERBOSE) { "Got RT_BUTTON_CONFIRMATION packet from the Combo; ignoring" }
                    }
                    ApplicationLayerIO.Command.RT_KEEP_ALIVE -> {
                        logger(LogLevel.VERBOSE) { "Got RT_KEEP_ALIVE packet from the Combo; ignoring" }
                    }
                    ApplicationLayerIO.Command.RT_AUDIO -> {
                        logger(LogLevel.VERBOSE) {
                            val audioType = ApplicationLayerIO.parseRTAudioPacket(appLayerPacket)
                            "Got RT_AUDIO packet with audio type ${audioType.toHexString(8)}; ignoring"
                        }
                    }
                    ApplicationLayerIO.Command.RT_VIBRATION -> {
                        logger(LogLevel.VERBOSE) {
                            val vibrationType = ApplicationLayerIO.parseRTVibrationPacket(appLayerPacket)
                            "Got RT_VIBRATION packet with vibration type ${vibrationType.toHexString(8)}; ignoring"
                        }
                    }
                    ApplicationLayerIO.Command.CTRL_SERVICE_ERROR -> {
                        val ctrlServiceError = ApplicationLayerIO.parseCTRLServiceErrorPacket(appLayerPacket)
                        logger(LogLevel.ERROR) { "Got CTRL_SERVICE_ERROR packet from the Combo; throwing exception" }
                        throw ApplicationLayerIO.ServiceErrorException(appLayerPacket, ctrlServiceError)
                    }
                    else -> return true
                }

                return false
            }
        }
    }

    /**
     * Performs a pairing procedure with a Combo.
     *
     * This performs the Combo-specific pairing. When this is called,
     * the pump must have been paired at the Bluetooth level already.
     * From Bluetooth's point of view, the pump is already paired with
     * the client at this point. But the Combo itself needs an additional
     * custom pairing.
     *
     * Pairing will initialize the [PersistentPumpStateStore] that was
     * passed to the constructor of this class. The store will then contain
     * new pairing data, a new pump ID string, and a new initial nonce.
     *
     * This function starts the background worker to be able to receive
     * packets from the Combo. Once pairing is done (or an error occurs),
     * the worker is stopped again.
     *
     * The pairingPINCallback callback has two arguments. previousAttemptFailed
     * is set to false initially, and true if this is a repeated call due
     * to a previous failure to apply the PIN. Such a failure typically
     * happens because the user mistyped the PIN, but in rare cases can also
     * happen due to corrupted packets.
     *
     * WARNING: Do not run multiple performPairing functions simultaneously
     *  on the same pump. Otherwise, undefined behavior occurs.
     *
     * @param bluetoothFriendlyName The Bluetooth friendly name to use in
     *        REQUEST_ID packets. Use [BluetoothInterface.getAdapterFriendlyName]
     *        to get the friendly name.
     * @param pairingPINCallback Callback that gets invoked as soon as
     *        the pairing process needs the 10-digit-PIN.
     * @throws IllegalStateException if this is ran while a connection
     *         is running.
     * @throws TransportLayerIO.BackgroundIOException if an exception is
     *         thrown inside the worker while an IO call is waiting for
     *         completion.
     */
    suspend fun performPairing(
        bluetoothFriendlyName: String,
        pairingPINCallback: PairingPINCallback
    ) {
        if (isConnected())
            throw IllegalStateException("Attempted to perform pairing while a connection is ongoing")

        coroutineScope {
            try {
                applicationLayerIO.startIO(backgroundIOScope = this, pairingPINCallback = pairingPINCallback)

                // Initiate pairing and wait for the response.
                // (The response contains no meaningful payload.)
                logger(LogLevel.DEBUG) { "Sending pairing connection request" }
                applicationLayerIO.sendPacket(TransportLayerIO.createRequestPairingConnectionPacketInfo())
                applicationLayerIO.receiveTpLayerPacket(TransportLayerIO.Command.PAIRING_CONNECTION_REQUEST_ACCEPTED)

                // Initiate pump-client and client-pump keys request.
                // This will cause the pump to generate and show a
                // 10-digit PIN.
                logger(LogLevel.DEBUG) { "Requesting the pump to generate and show the pairing PIN" }
                applicationLayerIO.sendPacket(TransportLayerIO.createRequestKeysPacketInfo())

                logger(LogLevel.DEBUG) { "Requesting the keys and IDs from the pump" }
                applicationLayerIO.sendPacket(TransportLayerIO.createGetAvailableKeysPacketInfo())
                applicationLayerIO.receiveTpLayerPacket(TransportLayerIO.Command.KEY_RESPONSE)
                applicationLayerIO.sendPacket(TransportLayerIO.createRequestIDPacketInfo(bluetoothFriendlyName))
                applicationLayerIO.receiveTpLayerPacket(TransportLayerIO.Command.ID_RESPONSE)

                // Initiate a regular (= non-pairing) transport layer connection.
                // Note that we are still pairing - it just continues in the
                // application layer. For this to happen, we need a regular
                // _transport layer_ connection.
                // Wait for the response and verify it.
                logger(LogLevel.DEBUG) { "Sending regular connection request" }
                applicationLayerIO.sendPacket(TransportLayerIO.createRequestRegularConnectionPacketInfo())
                applicationLayerIO.receiveTpLayerPacket(TransportLayerIO.Command.REGULAR_CONNECTION_REQUEST_ACCEPTED)

                // Initiate application-layer connection and wait for the response.
                // (The response contains no meaningful payload.)
                logger(LogLevel.DEBUG) { "Initiating application layer connection" }
                applicationLayerIO.sendPacket(ApplicationLayerIO.createCTRLConnectPacket())
                applicationLayerIO.receiveAppLayerPacket(ApplicationLayerIO.Command.CTRL_CONNECT_RESPONSE)

                // Next, we have to query the versions of both command mode and
                // RT mode services. It is currently unknown how to interpret
                // the version numbers, but apparently we _have_ to query them,
                // otherwise the pump considers it an error.
                // TODO: Further verify this.
                logger(LogLevel.DEBUG) { "Requesting command mode service version" }
                applicationLayerIO.sendPacket(ApplicationLayerIO.createCTRLGetServiceVersionPacket(ApplicationLayerIO.ServiceID.COMMAND_MODE))
                applicationLayerIO.receiveAppLayerPacket(ApplicationLayerIO.Command.CTRL_SERVICE_VERSION_RESPONSE)
                // NOTE: These two steps may not be necessary. See the
                // "Application layer pairing" section in the spec.
                /*
                applicationLayerIO.sendPacket(ApplicationLayerIO.createCTRLGetServiceVersionPacket(ApplicationLayerIO.ServiceID.RT_MODE))
                applicationLayerIO.receiveAppLayerPacket(ApplicationLayerIO.Command.CTRL_SERVICE_VERSION_RESPONSE)
                */

                // Next, send a BIND command and wait for the response.
                // (The response contains no meaningful payload.)
                logger(LogLevel.DEBUG) { "Sending BIND command" }
                applicationLayerIO.sendPacket(ApplicationLayerIO.createCTRLBindPacket())
                applicationLayerIO.receiveAppLayerPacket(ApplicationLayerIO.Command.CTRL_BIND_RESPONSE)

                // We have to re-connect the regular connection at the
                // transport layer now. (Unclear why, but it seems this
                // is necessary for the pairing process to succeed.)
                // Wait for the response and verify it.
                logger(LogLevel.DEBUG) { "Reconnecting regular connection" }
                applicationLayerIO.sendPacket(TransportLayerIO.createRequestRegularConnectionPacketInfo())
                applicationLayerIO.receiveTpLayerPacket(TransportLayerIO.Command.REGULAR_CONNECTION_REQUEST_ACCEPTED)

                // Pairing complete.
                logger(LogLevel.DEBUG) { "Pairing finished successfully" }

                // Disconnect packet is automatically sent by stopIO().
            } finally {
                applicationLayerIO.stopIO()
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
     * This must be called before [sendShortRTButtonPress], [startLongRTButtonPress],
     * [stopLongRTButtonPress], and [switchMode] can be used.
     *
     * This function starts the background worker, using the
     * [backgroundIOScope] as the scope for the coroutines that make up
     * the worker. Some functions such as [startLongRTButtonPress] also
     * launch coroutines. They use this same coroutine scope.
     *
     * The actual connection procedure also happens in that scope. If
     * during the connection setup an exception is thrown, then the
     * connection is rolled back to the disconnected state, and
     * [onBackgroundIOException] is called. If an exception happens
     * inside the worker _after_ the connection is established, then
     * [onBackgroundIOException] will be called. However, unlike with
     * exceptions during the connection setup, exceptions from inside
     * the worker cause the worker to "fail". In that failed state, any
     * of the functions mentioned above will immediately fail with an
     * [IllegalStateException]. The user has to call [disconnect] to
     * change the worker from a failed to a disconnected state.
     * Then, the user can attempt to connect again.
     *
     * The coroutine that runs connection setup procedure is represented
     * by the [kotlinx.coroutines.Job] instance that is returned by this
     * function. This makes it possible to wait until the connection is
     * established or an error occurs. To do that, the user calls
     * [kotlinx.coroutines.Job.join].
     *
     * [isConnected] will return true if the connection was established.
     *
     * [disconnect] is the counterpart to this function. It terminates
     * an existing connection and stops the worker.
     *
     * This also laucnhes a loop inside the worker that keeps sending
     * out RT_KEEP_ALIVE packets if the pump is operating in the
     * REMOTE_TERMINAL (RT) mode. This is necessary to keep the connection
     * alive (if the pump does not get these in RT mode it closes the
     * connection). This is enabled by default, but can be disabled if
     * needed. This is useful for unit tests for example.
     *
     * @param backgroundIOScope Coroutine scope to start the background
     *        worker in.
     * @param onBackgroundIOException Optional callback for notifying
     *        about exceptions that get thrown inside the worker.
     * @param initialMode What mode to initially switch to.
     * @param runKeepAliveLoop Whether or not to run a loop in the worker
     *        that repeatedly sends out RT_KEEP_ALIVE packets if the
     *        pump is running in the REMOTE_TERMINAL mode.
     * @return [kotlinx.coroutines.Job] representing the coroutine that
     *         runs the connection setup procedure.
     * @throws IllegalStateException if IO was already started by a
     *         previous [startIO] call or if the [PersistentPumpStateStore]
     *         that was passed to the class constructor isn't initialized
     *         (= [PersistentPumpStateStore.isValid] returns false).
     */
    fun connect(
        backgroundIOScope: CoroutineScope,
        onBackgroundIOException: (e: Exception) -> Unit = { },
        initialMode: Mode = Mode.REMOTE_TERMINAL,
        runKeepAliveLoop: Boolean = true
    ): Job {
        // Prerequisites.

        if (!persistentPumpStateStore.isValid()) {
            throw IllegalStateException(
                "Attempted to connect without a valid persistent state; pairing may not have been done"
            )
        }

        if (isConnected())
            throw IllegalStateException("Attempted to connect even though a connection is already ongoing")

        // Keep a reference to the scope around to be able to launch
        // corooutines in that same scope in other functions.
        this.backgroundIOScope = backgroundIOScope

        // Reset the display frame assembler in case it contains
        // partial frames from an earlier connection.
        displayFrameAssembler.reset()

        // Start the actual IO activity.
        applicationLayerIO.startIO(backgroundIOScope, onBackgroundIOException)

        logger(LogLevel.DEBUG) { "Pump IO connecting asynchronously" }

        // Launch the coroutine that sets up the connection.
        return backgroundIOScope.launch {
            try {
                logger(LogLevel.DEBUG) { "Sending regular connection request" }

                // Initiate connection at the transport layer.
                applicationLayerIO.sendPacket(TransportLayerIO.createRequestRegularConnectionPacketInfo())
                applicationLayerIO.receiveTpLayerPacket(TransportLayerIO.Command.REGULAR_CONNECTION_REQUEST_ACCEPTED)

                // Initiate connection at the application layer.
                logger(LogLevel.DEBUG) { "Initiating application layer connection" }
                applicationLayerIO.sendPacket(ApplicationLayerIO.createCTRLConnectPacket())
                applicationLayerIO.receiveAppLayerPacket(ApplicationLayerIO.Command.CTRL_CONNECT_RESPONSE)

                // Explicitely switch to the initial mode.
                switchMode(initialMode, runKeepAliveLoop)

                logger(LogLevel.INFO) { "Pump IO connected" }
            } catch (e: Exception) {
                disconnect()
                onBackgroundIOException(e)
                // TODO: Should this throw here? It would make it possible
                // to check the returned Job instance for failure, but what
                // would this throw do to the coroutine scope?
                throw e
            }
        }
    }

    /**
     * Closes a regular connection to the Combo.
     *
     * This terminates the connection and stops the background worker that
     * was started by [connect].
     *
     * If there is no connection, this does nothing.
     *
     * Calling this ensures an orderly IO shutdown and should
     * not be omitted when shutting down an application.
     * This also clears the "failed" mark on a failed worker.
     *
     * After this call, [isConnected] will return false.
     */
    suspend fun disconnect() {
        stopRTKeepAliveBackgroundLoop()
        applicationLayerIO.stopIO()

        backgroundIOScope = null
        currentMode = null

        logger(LogLevel.DEBUG) { "Pump IO disconnected" }
    }

    /** Returns true if IO is ongoing (due to a [connect] call), false otherwise. */
    fun isConnected() = applicationLayerIO.isIORunning()

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
     *         ongoing, the pump is not in the RT mode, or the
     *         worker has failed (see [connect]).
     */
    suspend fun sendShortRTButtonPress(buttons: List<Button>) {
        if (!isConnected())
            throw IllegalStateException("Cannot send short RT button press because the background worker is not running")

        if (currentLongRTPressJob != null)
            throw IllegalStateException("Cannot send short RT button press while a long RT button press is ongoing")

        if (buttons.isEmpty())
            throw IllegalArgumentException("Cannot send short RT button press since the specified buttons list is empty")

        if (currentMode != Mode.REMOTE_TERMINAL)
            throw IllegalStateException("Cannot send short RT button press while being in $currentMode mode")

        val buttonCodes = getCombinedButtonCodes(buttons)

        try {
            applicationLayerIO.sendPacket(ApplicationLayerIO.createRTButtonStatusPacket(buttonCodes, true))
            // Wait 100 ms to mimic a physical short button press.
            delay(100L)
        } finally {
            // Make sure we always attempt to send the NO_BUTTON
            // code to finish the short button press, even if
            // an exception is thrown.
            try {
                applicationLayerIO.sendPacket(
                    ApplicationLayerIO.createRTButtonStatusPacket(ApplicationLayerIO.RTButtonCode.NO_BUTTON.id, true)
                )
            } catch (ignore: Exception) {
            }
        }
    }

    /**
     * Performs a short button press.
     *
     * This overload is for convenience in case exactly one button
     * is to be pressed.
     */
    suspend fun sendShortRTButtonPress(button: Button) =
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
     * @param buttons What button(s) to long-press.
     * @throws IllegalArgumentException If the buttons list is empty.
     * @throws IllegalStateException if the pump is not in the RT mode
     *         or the worker has failed (see [connect]).
     */
    suspend fun startLongRTButtonPress(buttons: List<Button>) {
        if (!isConnected())
            throw IllegalStateException("Cannot send long RT button press because the background worker is not running")

        if (buttons.isEmpty())
            throw IllegalArgumentException("Cannot send long RT button press since the specified buttons list is empty")

        if (currentLongRTPressJob != null) {
            logger(LogLevel.DEBUG) {
                "Long RT button press job already running, and button press state is PRESSED; ignoring redundant call"
            }
            return
        }

        if (currentMode != Mode.REMOTE_TERMINAL)
            throw IllegalStateException("Cannot send long RT button press while being in $currentMode mode")

        try {
            sendLongRTButtonPress(buttons, true)
        } catch (e: Exception) {
            // In case of an exception, we abort by stopping
            // the long RT button press attempt, then rethrow.
            stopLongRTButtonPress()
            throw e
        }
    }

    /**
     * Performs a long button press.
     *
     * This overload is for convenience in case exactly one button
     * is to be pressed.
     */
    suspend fun startLongRTButtonPress(button: Button) =
        startLongRTButtonPress(listOf(button))

    /**
     * Stops an ongoing RT button press, imitating buttons being released.
     *
     * This is the counterpart to [startLongRTButtonPress]. It stops
     * long button presses that were started by that function.
     *
     * If no long button press is ongoing, this function does nothing.
     *
     * @throws IllegalStateException if the pump is not in the RT mode
     *         or the worker has failed (see [connect]).
     */
    suspend fun stopLongRTButtonPress() {
        if (!isConnected())
            throw IllegalStateException("Cannot stop long RT button press because the background worker is not running")

        if (currentLongRTPressJob == null) {
            logger(LogLevel.DEBUG) {
                "No long RT button press job running, and button press state is RELEASED; ignoring redundant call"
            }
            return
        }

        // If a long RT button press job is running, we must
        // be in the RT mode, otherwise something is wrong.
        require(currentMode == Mode.REMOTE_TERMINAL)

        // We use the Button.CHECK button code here, but it
        // actually does not matter what code we use, since
        // the "pressing" argument is set to false, in which
        // case the function will always transmit the NO_BUTTON
        // RT button code to the Combo.
        sendLongRTButtonPress(listOf(Button.CHECK), false)
    }

    /**
     * Switches the Combo to a different mode.
     *
     * The two valid modes are the remote terminal (RT) mode and the command mode.
     *
     * If an exception occurs, either disconnect, or try to repeat the mode switch.
     * This is important to make sure the pump is in a known mode.
     *
     * The runKeepAliveLoop argument functions just like the one in [connect].
     *
     * If the mode specified by newMode is the same as the current mode,
     * this function does nothing.
     *
     * TODO: This is experimental. Switching modes has not been attempted yet.
     *
     * @param newMode Mode to switch to.
     * @param runKeepAliveLoop Whether or not to run a loop in the worker
     *        that repeatedly sends out RT_KEEP_ALIVE packets.
     * @throws IllegalStateException if the pump is not in the RT mode
     *         or the worker has failed (see [connect]).
     */
    suspend fun switchMode(newMode: Mode, runKeepAliveLoop: Boolean = true) {
        if (!isConnected())
            throw IllegalStateException("Cannot switch mode because the background worker is not running")

        if (currentMode == newMode)
            return

        logger(LogLevel.DEBUG) { "Switching mode from $currentMode to $newMode" }

        // Send the command to switch the mode.

        applicationLayerIO.sendPacket(
            ApplicationLayerIO.createCTRLActivateServicePacket(
                when (newMode) {
                    Mode.REMOTE_TERMINAL -> ApplicationLayerIO.ServiceID.RT_MODE
                    Mode.COMMAND -> ApplicationLayerIO.ServiceID.COMMAND_MODE
                }
            )
        )
        applicationLayerIO.receiveAppLayerPacket(ApplicationLayerIO.Command.CTRL_ACTIVATE_SERVICE_RESPONSE)

        currentMode = newMode

        val rtKeepAliveRunning = isRTKeepAliveBackgroundLoopRunning()

        if (runKeepAliveLoop) {
            // If we just switched to the RT mode, enable the RT_KEEP_ALIVE
            // background loop. Disable it if we switched to the command mode.

            if ((newMode == Mode.REMOTE_TERMINAL) && !rtKeepAliveRunning)
                startRTKeepAliveBackgroundLoop()
            else if ((newMode != Mode.REMOTE_TERMINAL) && rtKeepAliveRunning)
                stopRTKeepAliveBackgroundLoop()
        } else {
            if (rtKeepAliveRunning)
                stopRTKeepAliveBackgroundLoop()
        }
    }

    /*************************************
     *** PRIVATE FUNCTIONS AND CLASSES ***
     *************************************/

    private fun getCombinedButtonCodes(buttons: List<Button>) =
        buttons.fold(0) { codes, button -> codes or toInternalButtonCode(button).id }

    private fun toString(buttons: List<Button>) = buttons.joinToString(" ") { it.str }

    private fun toInternalButtonCode(button: Button) =
        when (button) {
            Button.UP -> ApplicationLayerIO.RTButtonCode.UP
            Button.DOWN -> ApplicationLayerIO.RTButtonCode.DOWN
            Button.MENU -> ApplicationLayerIO.RTButtonCode.MENU
            Button.CHECK -> ApplicationLayerIO.RTButtonCode.CHECK
        }

    private fun startRTKeepAliveBackgroundLoop() {
        if (isRTKeepAliveBackgroundLoopRunning())
            return

        logger(LogLevel.DEBUG) { "Starting background RT keep-alive loop" }

        require(backgroundIOScope != null)

        rtKeepAliveJob = backgroundIOScope!!.launch {
            // We have to send an RT_KEEP_ALIVE packet to the Combo
            // every second to let the Combo know that we are still
            // there. Otherwise, it will terminate the connection,
            // since it then assumes that we are no longer connected
            // (for example due to a system crash).
            while (true) {
                logger(LogLevel.VERBOSE) { "Transmitting RT keep-alive packet" }
                applicationLayerIO.sendPacket(ApplicationLayerIO.createRTKeepAlivePacket())
                delay(1000)
            }
        }
    }

    private suspend fun stopRTKeepAliveBackgroundLoop() {
        if (!isRTKeepAliveBackgroundLoopRunning())
            return

        logger(LogLevel.DEBUG) { "Stopping background RT keep-alive loop" }

        rtKeepAliveJob!!.cancel()
        rtKeepAliveJob!!.join()
        rtKeepAliveJob = null

        logger(LogLevel.DEBUG) { "Background RT keep-alive loop stopped" }
    }

    private fun isRTKeepAliveBackgroundLoopRunning() = (rtKeepAliveJob != null)

    private suspend fun sendLongRTButtonPress(buttons: List<Button>, pressing: Boolean) {
        val currentJob = currentLongRTPressJob

        if (!pressing) {
            logger(LogLevel.DEBUG) {
                "Releasing RTs button(s) ${toString(currentLongRTPressedButtons)}"
            }
            if (currentJob != null) {
                currentJob.cancel()
                currentJob.join()
            }
            currentLongRTPressJob = null
            return
        }

        currentLongRTPressedButtons = buttons
        val buttonCodes = getCombinedButtonCodes(buttons)

        currentLongRTPressJob = backgroundIOScope!!.launch {
            try {
                var buttonStatusChanged = true

                // Long RT button presses require their own kind
                // of keep-alive mechanism. This consists of an
                // RT_BUTTON_STATUS packet that has to be sent
                // repeatedly every 200 ms until the long button
                // press is stopped.

                while (true) {
                    logger(LogLevel.DEBUG) {
                        "Sending long RT button press; button(s) = ${toString(buttons)} status changed = $buttonStatusChanged"
                    }

                    applicationLayerIO.sendPacket(
                        ApplicationLayerIO.createRTButtonStatusPacket(buttonCodes, buttonStatusChanged)
                    )

                    delay(200L)

                    buttonStatusChanged = false
                }
            } finally {
                logger(LogLevel.DEBUG) { "Long RT button press canceled" }
                // Need to call sendPacket() in a NonCancellable context, since
                // we may have reached that point due to a cancellation. Without
                // that context, the sendPacket() call would be cancelled, and
                // we would not send out the terminating NO_BUTTON packet.
                // TODO: This is not a good solution. Find a better way than
                // cancellation to end a long-press sequence cleanly. Then,
                // this workaround would not be needed anymore.
                withContext(NonCancellable) {
                    applicationLayerIO.sendPacket(
                        ApplicationLayerIO.createRTButtonStatusPacket(ApplicationLayerIO.RTButtonCode.NO_BUTTON.id, true)
                    )
                }
                currentLongRTPressJob = null
            }
        }
    }

    private fun processRTDisplayPayload(rtDisplayPayload: ApplicationLayerIO.RTDisplayPayload) {
        // Feed the payload to the display frame assembler to let it piece together
        // frames and output them via the callback.

        try {
            val displayFrame = displayFrameAssembler.processRTDisplayPayload(rtDisplayPayload)
            if (displayFrame != null)
                mutableDisplayFrameFlow.value = displayFrame
        } catch (e: Exception) {
            logger(LogLevel.ERROR) { "Could not process RT_DISPLAY payload: $e" }
            throw e
        }
    }
}
