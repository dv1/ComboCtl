package info.nightscout.comboctl.base

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val logger = Logger.get("HighLevelIO")

/**
 * Exception thrown then the [HighLevelIO] background receive loop fails.
 *
 * @param cause The exception that was thrown in the loop specifying
 *        want went wrong there.
 */
class ReceiveLoopFailureException(cause: Exception) : ComboException(cause.message, cause)

/**
 * Class for high-level IO operations.
 *
 * Unlike [ComboIO], the public API of this class does not directly
 * allow for sending/receiving blocks of raw bytes. Instead, this API
 * provides high-level operations like pairing, RT button presses etc.
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
 * The Combo regularly sends new display frames in REMOTE_TERMINAL (RT)
 * mode. A loop continuously runs inside a coroutine and receives data.
 * The display frame data is parsed, display frames are assembled, and
 * once a full new display frame is available, the [onNewDisplayFrame]
 * callback is invoked. This callback is _not_ a suspending function.
 * The recommended way to communicate the display frame to the rest of
 * the application is to write a callback that transmits the new frame
 * through a [Channel]. This decouples the display frame processing
 * (done by the application) from the control flow of the coroutine
 * that invokes the callback. However, it is important to make sure
 * there's no significant backpressure that may block the callback,
 * since this would also block the data-receiving coroutine.
 *
 * IMPORTANT: If a ComboIOException or a ReceiveLoopFailureException
 * happens during a regular connection, reconnect, since the connection
 * state may be undefined, and data may not have made it through in its
 * entirety. If it happens while pairing, disconnect, reset any existing
 * persistent state associated with the device the client communicated with,
 * and do any other necessary cleanup, to revert back to the state before
 * the pairing attempt.
 *
 * @param transportLayer TransportLayer instance to use for producing
 *        and parsing transport layer packets.
 * @param applicationLayer ApplicationLayer instance to use for producing
 *        and parsing application layer packets.
 * @param io Combo IO object to use for sending/receiving data.
 * @param onNewDisplayFrame Callback that gets invoked any time the
 *        Combo has sent enough data to provide the caller with a
 *        fresh new display frame.
 */
class HighLevelIO(
    private val transportLayer: TransportLayer,
    private val applicationLayer: ApplicationLayer,
    private val io: ComboIO,
    private val onNewDisplayFrame: (displayFrame: DisplayFrame) -> Unit
) {
    // Packet reception is implemented by using a receive loop that runs in
    // a coroutine. This loop waits for incoming packets (using a suspend
    // function from ComboIO) and analyzes incoming packets. Certain packets
    // are processed by that loop directly (ACK_RESPONSE, ERROR_RESPONSE,
    // RT_DISPLAY packets). The rest are sent through the appPacketChannel
    // or tpPacketChannel (depending on the packet type). This is because
    // these packets may be sent by the Combo without any prior packet having
    // been sent by the client. In other words, these packets may not be
    // a response to a packet sent by the client, but instead are Combo
    // notifications/updates. Client code may expect a response to a sent
    // packet though, so it is important to filter out and process these
    // packets internally, otherwise they may interfere with that the client
    // wants to do. One example from the pairing process:
    //
    //     sendPacketToIO(transportLayer.createRequestRegularConnectionPacket())
    //     receiveTpLayerPacketFromChannel(TransportLayer.CommandID.REGULAR_CONNECTION_REQUEST_ACCEPTED)
    //
    // Here, the client sends a REQUEST_REGULAR_CONNECTION packet to the Combo,
    // and suspends the coroutine until a REGULAR_CONNECTION_REQUEST_ACCEPTED
    // packet arrives. If during that wait, an RT_DISPLAY packet is sent by the
    // Combo, this function would incorrectly detect this as a mismatch (since
    // it expected REGULAR_CONNECTION_REQUEST_ACCEPTED, not RT_DISPLAY), and
    // raise an exception. But by filtering out the RT_DISPLAY packets, this
    // does not happen.
    //
    // So, only one function in here actually receives data from the ComboIO,
    // and that is receiveTpPacketFromIO(). This function in turn is only
    // called by one other function, and that is runReceiveLoop(). All the
    // other functions use receiveTpLayerPacketFromChannel(). Same goes for
    // all application layer packets.

    /**
     * The mode the IO can operate in.
     */
    enum class Mode(val str: String) {
        REMOTE_TERMINAL("REMOTE_TERMINAL"),
        COMMAND("COMMAND")
    }

    /**
     * Buttons available in the RT mode.
     */
    enum class Button(val str: String) {
        UP("UP"),
        DOWN("DOWN"),
        MENU("MENU"),
        CHECK("CHECK")
    }

    private var appPacketChannel = Channel<ApplicationLayer.Packet>(Channel.UNLIMITED)
    private var tpPacketChannel = Channel<TransportLayer.Packet>(Channel.UNLIMITED)

    private val displayFrameAssembler = DisplayFrameAssembler()

    private var currentMode = Mode.REMOTE_TERMINAL

    private var receiveLoopJob: Job? = null
    private var rtKeepAliveJob: Job? = null
    private var rtKeepAliveScope: CoroutineScope? = null
    private var onBackgroundReceiveException: (e: Exception) -> Unit = { Unit }
    private var caughtBackgroundReceiveException: Exception? = null

    private var currentLongRTPressJob: Job? = null
    private var currentLongRTPressedButton = Button.CHECK

    /************************
     *** PUBLIC FUNCTIONS ***
     ************************/

    /***********
     * Pairing *
     ***********/

    /**
     * Performs a pairing procedure with a Combo.
     *
     * This performs the Combo-specific pairing. When this is called,
     * the pump must have been paired at the Bluetooth level already.
     * From Bluetooth's point of view, the pump is already paired with
     * the client at this point. But the Combo itself needs additional
     * pairing, which we perform with this function.
     *
     * Once this is done, the [PersistentState] associated with the
     * [HighLevelIO.transportLayer] will be filled with all of the
     * necessary information (ciphers etc.) for establishing regular
     * connections.
     *
     * Packets are received in a loop that runs in a background
     * coroutine that operates in the [backgroundReceiveScope].
     *
     * That scope's context needs to be associated with the same thread
     * this function is called in. Otherwise, the receive loop
     * may run in a different thread than this function, potentially
     * leading to race conditions.
     *
     * The pairingPINCallback callback has two arguments. previousAttemptFailed
     * is set to false initially, and true if this is a repeated call due
     * to a previous failure to apply the PIN. Such a failure typically
     * happens because the user mistyped the PIN, but in rare cases can also
     * happen due to corrupted packets. getPINDeferred is a CompletableDeferred
     * that will suspend this function until the callback invokes its
     * [CompletableDeferred.complete] function or the coroutine is cancelled.
     *
     * WARNING: Do not run multiple performPairing functions simultaneously
     *  on the same pump. Otherwise, undefined behavior occurs.
     *
     * @param backgroundReceiveScope [CoroutineScope] to run the background
     *        packet receive loop in.
     * @param bluetoothFriendlyName The Bluetooth friendly name to use in
     *        REQUEST_ID packets. Use [BluetoothInterface.getAdapterFriendlyName]
     *        to get the friendly name.
     * @param pairingPINCallback Callback that gets invoked as soon as
     *        the pairing process needs the 10-digit-PIN.
     * @throws IllegalStateException if this is ran while a connection
     *         is running.
     * @throws ComboIOException if connection fails due to an underlying
     *         IO issue. See the [HighLevelIO] documentation at the top
     *         for details about this.
     * @throws ReceiveLoopFailureException if the background
     *         receive loop failed.
     */
    suspend fun performPairing(
        backgroundReceiveScope: CoroutineScope,
        bluetoothFriendlyName: String,
        pairingPINCallback: (previousAttemptFailed: Boolean, getPINDeferred: CompletableDeferred<PairingPIN>) -> Unit
    ) {
        if (receiveLoopJob != null) {
            throw IllegalStateException(
                "Attempted to pair even though a receive job is running (-> we are currently connected)"
            )
        }

        // Make sure we get channels that are in their initial states.
        resetPacketChannels()

        try {
            // Start the receive loop now since we expect certain transport
            // and application layer packets during the pairing procedure.
            receiveLoopJob = backgroundReceiveScope.launch {
                runReceiveLoop()
            }

            // Initiate pairing and wait for the response.
            // (The response contains no meaningful payload.)
            logger(LogLevel.DEBUG) { "Sending pairing connection request" }
            sendPacketToIO(transportLayer.createRequestPairingConnectionPacket())
            receiveTpLayerPacketFromChannel(TransportLayer.CommandID.PAIRING_CONNECTION_REQUEST_ACCEPTED)

            // Initiate pump-client and client-pump keys request.
            // This will cause the pump to generate and show a
            // 10-digit PIN.
            logger(LogLevel.DEBUG) { "Requesting the pump to generate and show the pairing PIN" }
            sendPacketToIO(transportLayer.createRequestKeysPacket())

            // Ask the user for the 10-digit PIN, and retrieve
            // the keys from the pump. In case the user did not
            // enter the correct PIN, the KEY_RESPONSE packet
            // will not be validated correctly. The user has
            // to re-enter the PIN then.
            // TODO: Does the pump wait indefinitely while the
            // user keeps reentering the PIN? If not, handle
            // the case where the pump eventually does
            // terminate the connection. This needs further
            // experimentation.

            var keyResponsePacket: TransportLayer.Packet? = null
            var pin: PairingPIN
            var previousAttemptFailed = false

            while (true) {
                // Create a CompletableDeferred and pass it to
                // the outside world through the pairingPINCallback.
                // Then wait for it to be completed. The outside
                // world then has to complete the deferred once
                // the user entered the PIN (typically through some
                // sort of UI).
                logger(LogLevel.DEBUG) { "Waiting for the PIN to be provided" }
                val getPINDeferred = CompletableDeferred<PairingPIN>()
                pairingPINCallback.invoke(previousAttemptFailed, getPINDeferred)
                pin = getPINDeferred.await()
                logger(LogLevel.DEBUG) { "Provided PIN: $pin" }

                // Pass the PIN to the transport layer so it can internally
                // generate a weak cipher out of it.
                transportLayer.usePairingPIN(pin)

                // After the PIN was entered, send a GET_AVAILABLE_KEYS
                // request to the pump. Only then will the pump actually
                // send us the KEY_RESPONSE packet. (The REQUEST_KEYS
                // message sent earlier above only causes the pump to
                // generate the keys and show the PIN.)
                if (keyResponsePacket == null) {
                    logger(LogLevel.DEBUG) { "Requesting the available pump-client and client-pump keys from the pump" }
                    sendPacketToIO(transportLayer.createGetAvailableKeysPacket())

                    // Wait for the KEY_RESPONSE packet.
                    keyResponsePacket = receiveTpLayerPacketFromChannel(TransportLayer.CommandID.KEY_RESPONSE)
                    logger(LogLevel.DEBUG) { "KEY_RESPONSE packet with the keys inside received" }
                }

                // If the KEY_RESPONSE packet could not be verified,
                // then the user may have entered the incorrect PIN.
                // An incorrect PIN will produce an incorrect weak
                // cipher, and verification will fail. It is of course
                // also possible that the packet itself is faulty, but
                // this is unlikely. Therefore, in case of verification
                // failure, try again by letting the while loop repeat.
                // The next iteration will reuse the KEY_RESPONSE packet
                // that we got here, but will try to verify it again
                // with the newly generated weak cipher.
                if (!transportLayer.verifyIncomingPacket(keyResponsePacket)) {
                    previousAttemptFailed = true
                    logger(LogLevel.WARN) {
                        "Could not authenticate KEY_RESPONSE packet; perhaps user entered PIN incorrectly; asking again for PIN"
                    }
                } else
                    break
            }

            // KEY_RESPONSE packet was received and verified, and the
            // pairing PIN was passed to the transport layer. We can
            // now decrypt the pump-client and client-pump keys.
            logger(LogLevel.DEBUG) { "Reading keys and source/destination addresses from KEY_RESPONSE packet" }
            transportLayer.parseKeyResponsePacket(keyResponsePacket!!)

            lateinit var tpLayerPacket: TransportLayer.Packet

            // We got the keys. Next step is to ask the pump for IDs.
            // The ID_RESPONSE response packet contains purely informational
            // values that aren't necessary for operating the pump. However,
            // it seems that we still have to request these IDs, otherwise
            // the pump reports an error. (TODO: Further verify this.)
            sendPacketToIO(transportLayer.createRequestIDPacket(bluetoothFriendlyName))
            tpLayerPacket = receiveTpLayerPacketFromChannel(TransportLayer.CommandID.ID_RESPONSE)
            val comboIDs = transportLayer.parseIDResponsePacket(tpLayerPacket)
            logger(LogLevel.DEBUG) {
                "Received IDs: server ID: ${comboIDs.serverID} pump ID: ${comboIDs.pumpID}"
            }

            // Initiate a regular (= non-pairing) transport layer connection.
            // Note that we are still pairing - it just continues in the
            // application layer. For this to happen, we need a regular
            // _transport layer_ connection.
            // Wait for the response and verify it.
            logger(LogLevel.DEBUG) { "Sending regular connection request" }
            sendPacketToIO(transportLayer.createRequestRegularConnectionPacket())
            receiveTpLayerPacketFromChannel(TransportLayer.CommandID.REGULAR_CONNECTION_REQUEST_ACCEPTED)

            // Initiate application-layer connection and wait for the response.
            // (The response contains no meaningful payload.)
            logger(LogLevel.DEBUG) { "Initiating application layer connection" }
            sendPacketToIO(applicationLayer.createCTRLConnectPacket())
            receiveAppLayerPacketFromChannel(ApplicationLayer.Command.CTRL_CONNECT_RESPONSE)

            // Next, we have to query the versions of both command mode and
            // RT mode services. It is currently unknown how to interpret
            // the version numbers, but apparently we _have_ to query them,
            // otherwise the pump considers it an error.
            // TODO: Further verify this.
            logger(LogLevel.DEBUG) { "Requesting command mode service version" }
            sendPacketToIO(applicationLayer.createCTRLGetServiceVersionPacket(ApplicationLayer.ServiceID.COMMAND_MODE))
            receiveAppLayerPacketFromChannel(ApplicationLayer.Command.CTRL_SERVICE_VERSION_RESPONSE)
            // NOTE: These two steps may not be necessary. See the
            // "Application layer pairing" section in the spec.
            /*
            sendPacketToIO(applicationLayer.createCTRLGetServiceVersionPacket(ApplicationLayer.ServiceID.RT_MODE))
            receiveAppLayerPacketFromChannel(ApplicationLayer.Command.CTRL_SERVICE_VERSION_RESPONSE)
            */

            // Next, send a BIND command and wait for the response.
            // (The response contains no meaningful payload.)
            logger(LogLevel.DEBUG) { "Sending BIND command" }
            sendPacketToIO(applicationLayer.createCTRLBindPacket())
            receiveAppLayerPacketFromChannel(ApplicationLayer.Command.CTRL_BIND_RESPONSE)

            // We have to re-connect the regular connection at the
            // transport layer now. (Unclear why, but it seems this
            // is necessary for the pairing process to succeed.)
            // Wait for the response and verify it.
            logger(LogLevel.DEBUG) { "Reconnecting regular connection" }
            sendPacketToIO(transportLayer.createRequestRegularConnectionPacket())
            receiveTpLayerPacketFromChannel(TransportLayer.CommandID.REGULAR_CONNECTION_REQUEST_ACCEPTED)

            // Disconnect the application layer connection.
            logger(LogLevel.DEBUG) { "Disconnecting application layer connection" }
            sendPacketToIO(applicationLayer.createCTRLDisconnectPacket())

            // Pairing complete.
            logger(LogLevel.DEBUG) { "Pairing finished successfully" }
        } finally {
            if (receiveLoopJob != null)
                receiveLoopJob!!.cancel()
        }
    }

    /**********************
     * Connect/disconnect *
     **********************/

    /**
     * Establishes a regular connection.
     *
     * "Regular" means "not pairing". This is the type of connection
     * one uses for regular operation.
     *
     * Packets are received in a loop that runs in a background
     * coroutine that operates in the [backgroundReceiveScope].
     *
     * That scope's context needs to be associated with the same thread
     * this function is called in. Otherwise, the receive loop
     * may run in a different thread than this function, potentially
     * leading to race conditions.
     *
     * If an exception is thrown, the connection attempt is rolled
     * back. The device is in a disconnected state afterwards. This
     * applies to an exception thrown _during_ the connection setup;
     * any exception thrown in the background receive loop will cause
     * [onBackgroundReceiveException] to be called instead. Also,
     * other functions that involve IO to/from the Combo will throw
     * that exception.
     *
     * @param backgroundReceiveScope [CoroutineScope] to run the background
     *        packet receive loop in.
     * @param onBackgroundReceiveException Callback that gets invoked if
     *        an exception occurs in the background receive loop.
     * @throws ComboIOException if an IO error occurs during
     *         the connection attempts.
     * @throws IllegalStateException if no pairing was done with
     *         the device. This is indicated by the persistent
     *         state in [HighLevelIO.transportLayer] not being
     *         filled with valid data. Also thrown if this is
     *         called after a connection was already established.
     */
    suspend fun connect(
        backgroundReceiveScope: CoroutineScope,
        onBackgroundReceiveException: (e: Exception) -> Unit = { Unit }
    ) {
        if (receiveLoopJob != null) {
            throw IllegalStateException(
                "Attempted to connect even though a receive job is running (-> we are already connected)"
            )
        }

        if (!transportLayer.persistentStateIsValid()) {
            throw IllegalStateException(
                "Attempted to connect without a valid persistent state; pairing may not have been done"
            )
        }

        // Keep a reference to the coroutine scope to be able to run
        // the RT_KEEPALIVE packet send loop in the background later.
        rtKeepAliveScope = backgroundReceiveScope

        // Make sure we get channels that are in their initial states.
        resetPacketChannels()

        try {
            this.onBackgroundReceiveException = onBackgroundReceiveException
            this.caughtBackgroundReceiveException = null

            // Start the receive loop now since we expect certain transport
            // and application layer packets during the connection procedure.
            // Also, RT_DISPLAY packets, ACK_RESPONSE packets etc. may arrive
            // while we are connecting. (The former is unlikely though.)
            receiveLoopJob = backgroundReceiveScope.launch {
                runReceiveLoop()
            }

            logger(LogLevel.DEBUG) { "Sending regular connection request" }

            // Initiate connection at the transport layer.
            sendPacketToIO(transportLayer.createRequestRegularConnectionPacket())
            receiveTpLayerPacketFromChannel(TransportLayer.CommandID.REGULAR_CONNECTION_REQUEST_ACCEPTED)

            // Initiate connection at the application layer.
            logger(LogLevel.DEBUG) { "Initiating application layer connection" }
            sendPacketToIO(applicationLayer.createCTRLConnectPacket())
            receiveAppLayerPacketFromChannel(ApplicationLayer.Command.CTRL_CONNECT_RESPONSE)

            // Make sure we are in the RT mode initially.
            activateMode(Mode.REMOTE_TERMINAL)

            logger(LogLevel.INFO) { "Application layer connected" }
        } catch (e: Exception) {
            // Make sure we are in the disconnected state if an error occurred.
            disconnect()
            throw e
        }
    }

    /**
     * Terminates a previously established connection.
     *
     * If no connection is running, this does nothing.
     *
     * Other than the usual [CancellationException] that is present
     * in all suspending functions, this throws no exceptions.
     */
    suspend fun disconnect() {
        if (receiveLoopJob == null) {
            logger(LogLevel.DEBUG) {
                "Attempted to connect even though no receive job is running (-> we are already disconnected); ignoring call"
            }
            return
        }

        // Cancel any ongoing jobs

        stopRTKeepAliveBackgroundLoop()

        receiveLoopJob!!.cancel()
        receiveLoopJob = null

        if (currentLongRTPressJob != null) {
            currentLongRTPressJob!!.cancel()
            currentLongRTPressJob = null
        }

        rtKeepAliveScope = null

        try {
            // First, deactivate all application layer services.
            logger(LogLevel.DEBUG) { "Deactivating all services" }
            sendPacketToIO(applicationLayer.createCTRLDeactivateAllServicesPacket())
            receiveAppLayerPacketFromChannel(ApplicationLayer.Command.CTRL_ALL_SERVICES_DEACTIVATED)

            // Next, send the disconnect application layer packet.
            logger(LogLevel.DEBUG) { "Sending disconnect packet" }
            sendPacketToIO(applicationLayer.createCTRLDisconnectPacket())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger(LogLevel.ERROR) {
                // Catch exceptions during disconnect. We are anyway
                // shutting down the connection, so propagating
                // exceptions here would only complicate matters,
                // because disconnect() gets called in catch blocks.
                // CancellationException are propagated though to
                // make sure the coroutine is properly canceled.
                "Caught exception during disconnect; not propagating; exception: $e"
            }
        }

        logger(LogLevel.INFO) { "Application layer disconnected" }
    }

    /******************
     * Mode switching *
     ******************/

    /**
     * Switches the Combo to a different mode.
     *
     * The two valid modes are the remote terminal (RT) mode
     * and the command mode.
     *
     * If an exception occurs, either disconnect,
     * or try to repeat the mode switch. This is important
     * to make sure the pump is in a known mode.
     *
     * TODO: This is experimental. Switching modes has not been attempted yet.
     *
     * @throws ComboIOException if an IO error occurs during
     *         the mode switch.
     * @throws ReceiveLoopFailureException if the background
     *         receive loop failed.
     */
    suspend fun switchToMode(newMode: Mode) {
        if (caughtBackgroundReceiveException != null)
            throw caughtBackgroundReceiveException!!

        if (currentMode == newMode) {
            logger(LogLevel.DEBUG) { "Ignoring redundant mode change since the ${currentMode.str} is already active" }
            return
        }

        logger(LogLevel.DEBUG) { "Deactivating all services before activating the ${currentMode.str} mode" }
        sendPacketToIO(applicationLayer.createCTRLDeactivateAllServicesPacket())
        receiveAppLayerPacketFromChannel(ApplicationLayer.Command.CTRL_ALL_SERVICES_DEACTIVATED)

        activateMode(newMode)
    }

    /****************************************
     * Single/long RT button press handling *
     ****************************************/

    /**
     * Performs a short button press.
     *
     * This mimics the physical press of a button for a short
     * moment, followed by that button being released.
     *
     * This may not be called while a long button press is ongoing.
     * It can only be called in the remote terminal (RT) mode.
     *
     * @param button What button to press.
     * @throws ComboIOException if an IO error occurs during
     *         the button press.
     * @throws IllegalStateException if a long button press is
     *         ongoing or the pump is not in the RT mode.
     * @throws ReceiveLoopFailureException if the background
     *         receive loop failed.
     */
    suspend fun sendSingleRTButtonPress(button: Button) {
        // A single RT button press is performed by sending the button code,
        // waiting 100ms, and then sending the NO_BUTTON code to inform
        // the Combo that the user released the button.

        if (currentLongRTPressJob != null)
            throw IllegalStateException("Cannot send single RT button press while a long RT button press is ongoing")

        if (currentMode != Mode.REMOTE_TERMINAL)
            throw IllegalStateException("Cannot send single RT button press while being in ${currentMode.str} mode")

        if (caughtBackgroundReceiveException != null)
            throw caughtBackgroundReceiveException!!

        val buttonCode = when (button) {
            Button.UP -> ApplicationLayer.RTButtonCode.UP
            Button.DOWN -> ApplicationLayer.RTButtonCode.DOWN
            Button.MENU -> ApplicationLayer.RTButtonCode.MENU
            Button.CHECK -> ApplicationLayer.RTButtonCode.CHECK
        }

        try {
            sendPacketToIO(applicationLayer.createRTButtonStatusPacket(buttonCode, true))
            delay(100L)
        } finally {
            // Make sure we always attempt to send the NO_BUTTON
            // code to finish the short button press, even if
            // an exception is thrown.
            sendPacketToIO(applicationLayer.createRTButtonStatusPacket(ApplicationLayer.RTButtonCode.NO_BUTTON, true))
        }
    }

    /**
     * Starts a long RT button press, imitating a button being held down.
     *
     * This can only be called in the remote terminal (RT) mode.
     *
     * If a long button press is already ongoing, this function
     * does nothing.
     *
     * The [scope] is where a loop will run, periodically transmitting
     * codes to the Combo, until [stopLongRTButtonPress] is called,
     * [disconnect] is called, or an error occurs.
     *
     * That scope's context needs to be associated with the same thread
     * this function is called in. Otherwise, the press loop may run
     * in a different thread than this function, potentially leading
     * to race conditions.
     *
     * @param button What button to press.
     * @param scope CoroutineScope to run the press loop in.
     * @throws ComboIOException if an IO error occurs during
     *         the button press.
     * @throws IllegalStateException if the pump is not in the RT mode.
     * @throws ReceiveLoopFailureException if the background
     *         receive loop failed.
     */
    suspend fun startLongRTButtonPress(button: Button, scope: CoroutineScope) {
        if (currentMode != Mode.REMOTE_TERMINAL)
            throw IllegalStateException("Cannot send single RT button press while being in ${currentMode.str} mode")

        if (currentLongRTPressJob != null) {
            logger(LogLevel.DEBUG) {
                "Long RT button press job already running, and button press state is PRESSED; ignoring redundant call"
            }
            return
        }

        if (caughtBackgroundReceiveException != null)
            throw caughtBackgroundReceiveException!!

        try {
            sendLongRTButtonPress(button, true, scope)
        } catch (e: Exception) {
            // In case of an exception, we abort by stopping
            // the long RT button press attempt, then rethrow.
            stopLongRTButtonPress()
            throw e
        }
    }

    /**
     * Stops an ongoing RT button press, imitating a button being released.
     *
     * This is the counterpart to [startLongRTButtonPress]. It stops
     * long button presses that were started by that function.
     *
     * If no long button press is ongoing, this function does nothing.
     *
     * @throws ComboIOException if an IO error occurs during
     *         the button press.
     * @throws IllegalStateException if the pump is not in the RT mode.
     * @throws ReceiveLoopFailureException if the background
     *         receive loop failed.
     */
    suspend fun stopLongRTButtonPress() {
        if (currentMode != Mode.REMOTE_TERMINAL)
            throw IllegalStateException("Cannot send single RT button press while being in ${currentMode.str} mode")

        if (currentLongRTPressJob == null) {
            logger(LogLevel.DEBUG) {
                "No long RT button press job running, and button press state is RELEASED; ignoring redundant call"
            }
            return
        }

        if (caughtBackgroundReceiveException != null)
            throw caughtBackgroundReceiveException!!

        // We use the Button.CHECK button code here, but it
        // actually does not matter what code we use, since
        // the "pressing" argument is set to false, in which
        // case the function will always transmit the NO_BUTTON
        // RT button code to the Combo.
        sendLongRTButtonPress(Button.CHECK, false, null)
    }

    /*************************
     *** PRIVATE FUNCTIONS ***
     *************************/

    /***********************************
     * ComboIO packet receive handling *
     ***********************************/

    // Other functions should only use receiveTpLayerPacketFromChannel()
    // and receiveAppLayerPacketFromChannel(), and run runReceiveLoop()
    // in a coroutine scope. The other receive functions are handled by
    // runReceiveLoop(). This is important to properly handle RT_DISPLAY,
    // ACK_RESPONSE etc. packets that the Combo may send in between
    // other packets.

    private suspend fun receiveTpPacketFromIO(): TransportLayer.Packet {
        lateinit var tpLayerPacket: TransportLayer.Packet

        receivingPacket@ while (true) {
            tpLayerPacket = TransportLayer.Packet(io.receive())

            // Check that the packet is OK. Note that at this point,
            // the necessary ciphers must have been set. Otherwise,
            // this verification will fail. If the pairing is performed
            // correctly, this should not be a concern, since these
            // ciphers will be populated before they are needed here.
            //
            // However, we do _not_ verify KEY_RESPONSE packets here,
            // since they are verified with the weak cipher, and the
            // weak cipher is generated from the PIN that is supplied
            // by the user. Since the user could make a mistake when
            // entering the PIN, the weak cipher may be wrong. The
            // "fix" is to ask the user to try again to enter the
            // PIN. This means that the KEY_RESPONSE packet needs to
            // be verified seperately. performPairing() does this
            // in its PIN enter loop.
            if ((tpLayerPacket.commandID != TransportLayer.CommandID.KEY_RESPONSE) && !transportLayer.verifyIncomingPacket(tpLayerPacket))
                throw TransportLayer.PacketVerificationException(tpLayerPacket)

            // Packets with the reliability flag set must be immediately
            // responded to with an ACK_RESPONSE packet whose sequence bit
            // must match that of the received packet.
            if (tpLayerPacket.reliabilityBit) {
                logger(LogLevel.DEBUG) {
                    "Got a transport layer ${tpLayerPacket.commandID.name} packet with its reliability bit set; " +
                    "responding with ACK_RESPONSE packet; sequence bit: ${tpLayerPacket.sequenceBit}"
                }
                val ackResponsePacket = transportLayer.createAckResponsePacket(tpLayerPacket.sequenceBit)

                try {
                    io.send(ackResponsePacket.toByteList())
                } catch (e: Exception) {
                    logger(LogLevel.ERROR) { "Error while sending ACK_RESPONSE transport layer packet: $e" }
                    throw e
                }
            }

            when (tpLayerPacket.commandID) {
                TransportLayer.CommandID.ACK_RESPONSE -> logger(LogLevel.DEBUG) { "Got ACK_RESPONSE packet; ignoring" }
                TransportLayer.CommandID.ERROR_RESPONSE -> processErrorResponse(transportLayer.parseErrorResponsePacket(tpLayerPacket))
                else -> break@receivingPacket
            }
        }

        return tpLayerPacket
    }

    private suspend fun runReceiveLoop() {
        // This runs a loop that constantly receives packets.
        // receiveTpPacketFromIO() is a suspend function, which
        // suspends the coroutine when waiting for incoming data.
        // NOTE: It is important to make sure that this coroutine
        // does not run in a different thread than the thread that
        // calls connect() or performPairing(), since this would
        // require synchronization that is not in place here.

        try {
            while (true) {
                val tpLayerPacket = receiveTpPacketFromIO()

                // Process the packet according to its command ID. If we
                // don't recognize the packet, log and discard it.
                // NOTE: This is not about packets with unknown IDs. These
                // are sorted out by the TransportLayer.Packet constructor,
                // which throws an InvalidCommandIDException in that case.
                // This check here is about valid command IDs that we don't
                // handle in this when statement.
                when (tpLayerPacket.commandID) {
                    TransportLayer.CommandID.DATA -> processTpLayerDataPacket(tpLayerPacket)
                    TransportLayer.CommandID.PAIRING_CONNECTION_REQUEST_ACCEPTED,
                    TransportLayer.CommandID.KEY_RESPONSE,
                    TransportLayer.CommandID.ID_RESPONSE,
                    TransportLayer.CommandID.REGULAR_CONNECTION_REQUEST_ACCEPTED -> tpPacketChannel.send(tpLayerPacket)
                    else -> logger(LogLevel.WARN) {
                        "Cannot process ${tpLayerPacket.commandID.name} packet coming from the Combo; ignoring packet"
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger(LogLevel.DEBUG) { "Caught exception in receive loop: $e" }

            // Record the exception.
            val recvloopException = ReceiveLoopFailureException(e)
            caughtBackgroundReceiveException = recvloopException

            // Notify about the exception.
            onBackgroundReceiveException(recvloopException)

            // Close the channels, citing the exception as the reason why.
            appPacketChannel.close(recvloopException)
            tpPacketChannel.close(recvloopException)

            // Stop the RT_KEEPALIVE background loop, since it is
            // pointless to keep sending these packets after the
            // receive loop got stopped by the exception.
            stopRTKeepAliveBackgroundLoop()
        }
    }

    private fun processErrorResponse(errorResponse: Int) {
        // TODO: Throw exception here
    }

    private suspend fun processTpLayerDataPacket(tpLayerPacket: TransportLayer.Packet) {
        lateinit var appLayerPacket: ApplicationLayer.Packet

        // Parse the transport layer DATA packet as an application layer packet.
        try {
            logger(LogLevel.DEBUG) { "Parsing DATA packet as application layer packet" }
            appLayerPacket = ApplicationLayer.Packet(tpLayerPacket)
            logger(LogLevel.DEBUG) { "This is an application layer packet with command ${appLayerPacket.command}" }
        } catch (e: ApplicationLayer.ExceptionBase) {
            logger(LogLevel.ERROR) { "Could not parse DATA packet as application layer packet: $e" }
            throw e
        }

        // RT_DISPLAY packets regularly come from the Combo, and contains new display
        // frame updates. We must pass these new updates on right away, and not just
        // push it into the channel, because the frames may contain notifications.
        // For this reason, we handle RT_DISPLAY packets explicitely.
        //
        // CTRL_SERVICE_ERROR packets are errors that must be handled immediately.
        // Unlike the ERROR_RESPONSE packet, these packets report errors at the
        // application layer.
        //
        // As for the RT_KEY_CONFIRMATION and RT_KEEP_ALIVE packets, they are
        // notifications that we don't need here, so we ignore them. (Note that
        // RT_KEEP_ALIVE serves a dual purpose; we _do_ have to also send such
        // packets _to_ the Combo. The rtKeepAliveJob takes care of that.)
        when (appLayerPacket.command) {
            ApplicationLayer.Command.RT_DISPLAY -> processRTDisplayPayload(applicationLayer.parseRTDisplayPacket(appLayerPacket))
            ApplicationLayer.Command.RT_KEEP_ALIVE -> { logger(LogLevel.DEBUG) { "Got RT_KEEP_ALIVE packet from the Combo; ignoring" } }
            ApplicationLayer.Command.CTRL_SERVICE_ERROR ->
                processCTRLServiceError(appLayerPacket, applicationLayer.parseCTRLServiceErrorPacket(appLayerPacket))
            else -> appPacketChannel.send(appLayerPacket)
        }
    }

    private fun processRTDisplayPayload(rtDisplayPayload: ApplicationLayer.RTDisplayPayload) {
        // Feed the payload to the display frame assembler to let it piece together
        // frames and output them via the callback.

        try {
            val displayFrame = displayFrameAssembler.processRTDisplayPayload(rtDisplayPayload)
            if (displayFrame != null)
                onNewDisplayFrame(displayFrame)
        } catch (e: Exception) {
            logger(LogLevel.ERROR) { "Could not process RT_DISPLAY payload: $e" }
            throw e
        }
    }

    private fun processCTRLServiceError(appLayerPacket: ApplicationLayer.Packet, ctrlServiceError: ApplicationLayer.CTRLServiceError) {
        logger(LogLevel.ERROR) { "Got CTRL_SERVICE_ERROR packet from the Combo; throwing exception" }
        throw ApplicationLayer.ServiceErrorException(appLayerPacket, ctrlServiceError)
    }

    private suspend fun receiveTpLayerPacketFromChannel(expectedCommandID: TransportLayer.CommandID? = null): TransportLayer.Packet {
        logger(LogLevel.DEBUG) {
            if (expectedCommandID == null)
                "Waiting for transport layer packet"
            else
                "Waiting for transport layer ${expectedCommandID.name} packet"
        }

        val tpLayerPacket = tpPacketChannel.receive()
        if ((expectedCommandID != null) && (tpLayerPacket.commandID != expectedCommandID))
            throw TransportLayer.IncorrectPacketException(tpLayerPacket, expectedCommandID)

        return tpLayerPacket
    }

    private suspend fun receiveAppLayerPacketFromChannel(expectedCommand: ApplicationLayer.Command? = null): ApplicationLayer.Packet {
        logger(LogLevel.DEBUG) {
            if (expectedCommand == null)
                "Waiting for application layer packet (will arrive in a transport layer DATA packet)"
            else
                "Waiting for application layer ${expectedCommand.name} packet (will arrive in a transport layer DATA packet)"
        }

        val appLayerPacket = appPacketChannel.receive()
        if ((expectedCommand != null) && (appLayerPacket.command != expectedCommand))
            throw ApplicationLayer.IncorrectPacketException(appLayerPacket, expectedCommand)

        return appLayerPacket
    }

    /********************************
     * ComboIO packet send handling *
     ********************************/

    private suspend fun sendPacketToIO(packet: TransportLayer.Packet) {
        logger(LogLevel.DEBUG) { "Sending transport layer ${packet.commandID.name} packet" }
        io.send(packet.toByteList())
    }

    private suspend fun sendPacketToIO(packet: ApplicationLayer.Packet) {
        logger(LogLevel.DEBUG) { "Sending application layer ${packet.command.name} packet" }
        io.send(packet.toTransportLayerPacket(transportLayer).toByteList())
    }

    /*****************
     * Miscellaneous *
     *****************/

    private fun resetPacketChannels() {
        // Reset channels in case they were closed in a previous call
        // or still contain stale data from a previous run. Do this
        // by closing any existing channels and creating new ones.

        appPacketChannel.close()
        tpPacketChannel.close()

        appPacketChannel = Channel<ApplicationLayer.Packet>(Channel.UNLIMITED)
        tpPacketChannel = Channel<TransportLayer.Packet>(Channel.UNLIMITED)
    }

    private suspend fun activateMode(newMode: Mode) {
        logger(LogLevel.DEBUG) { "Activating ${newMode.str} mode" }

        // Send the command to switch the mode.

        sendPacketToIO(
            applicationLayer.createCTRLActivateServicePacket(
                when (newMode) {
                    Mode.REMOTE_TERMINAL -> ApplicationLayer.ServiceID.RT_MODE
                    Mode.COMMAND -> ApplicationLayer.ServiceID.COMMAND_MODE
                }
            )
        )
        receiveAppLayerPacketFromChannel(ApplicationLayer.Command.CTRL_ACTIVATE_SERVICE_RESPONSE)

        currentMode = newMode

        // If we just switched to the RT mode, enable the RT_KEEPALIVE
        // background loop. Disable it if we switched to the command mode.

        val rtKeepAliveRunning = isRTKeepAliveBackgroundLoopRunning()

        if ((newMode == Mode.REMOTE_TERMINAL) && !rtKeepAliveRunning)
            startRTKeepAliveBackgroundLoop()
        else if ((newMode != Mode.REMOTE_TERMINAL) && rtKeepAliveRunning)
            stopRTKeepAliveBackgroundLoop()
    }

    private fun startRTKeepAliveBackgroundLoop() {
        if (isRTKeepAliveBackgroundLoopRunning())
            return

        logger(LogLevel.DEBUG) { "Starting background RT keep-alive loop" }

        require(rtKeepAliveScope != null)

        rtKeepAliveJob = rtKeepAliveScope!!.launch {
            // We have to send an RT_KEEPALIVE packet to the Combo
            // every second to let the Combo know that we are still
            // there. Otherwise, it will terminate the connection,
            // since it then assumes that we are no longer connected
            // (for example due to a system crash).
            while (true) {
                logger(LogLevel.DEBUG) { "Transmitting RT keep-alive packet" }
                sendPacketToIO(applicationLayer.createRTKeepAlivePacket())
                delay(1000)
            }
        }
    }

    private fun stopRTKeepAliveBackgroundLoop() {
        if (!isRTKeepAliveBackgroundLoopRunning())
            return

        logger(LogLevel.DEBUG) { "Stopping background RT keep-alive loop" }

        rtKeepAliveJob!!.cancel()
        rtKeepAliveJob = null
    }

    private fun isRTKeepAliveBackgroundLoopRunning() = (rtKeepAliveJob != null)

    private suspend fun sendLongRTButtonPress(button: Button, pressing: Boolean, scope: CoroutineScope?) {
        val currentJob = currentLongRTPressJob

        if (!pressing) {
            logger(LogLevel.DEBUG) { "Releasing ${currentLongRTPressedButton.str} RT button" }
            currentJob!!.cancel()
            currentLongRTPressJob = null
            return
        }

        currentLongRTPressedButton = button

        currentLongRTPressJob = scope!!.launch {
            // Using two nested try-finally blocks, since in
            // the inner finally block, sendPacketToIO() is called,
            // which itself may throw, and we must make sure that
            // currentLongRTPressJob is set to null in all cases
            // where we exit the scope of launch(), otherwise it
            // might not be possible to start long presses again.
            try {
                try {
                    var buttonStatusChanged = true

                    val buttonCode = when (button) {
                        Button.UP -> ApplicationLayer.RTButtonCode.UP
                        Button.DOWN -> ApplicationLayer.RTButtonCode.DOWN
                        Button.MENU -> ApplicationLayer.RTButtonCode.MENU
                        Button.CHECK -> ApplicationLayer.RTButtonCode.CHECK
                    }

                    while (true) {
                        logger(LogLevel.DEBUG) {
                            "Sending long RT ${button.str} button press; status changed = $buttonStatusChanged"
                        }

                        sendPacketToIO(applicationLayer.createRTButtonStatusPacket(buttonCode, buttonStatusChanged))

                        delay(200L)

                        buttonStatusChanged = false
                    }
                } finally {
                    logger(LogLevel.DEBUG) { "Long RT ${button.str} button press canceled" }
                    sendPacketToIO(applicationLayer.createRTButtonStatusPacket(ApplicationLayer.RTButtonCode.NO_BUTTON, true))
                }
            } finally {
                currentLongRTPressJob = null
            }
        }
    }
}
