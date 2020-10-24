package info.nightscout.comboctl.base

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

private val logger = Logger.get("HighLevelIO")

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
 */
class HighLevelIO(
    public val transportLayer: TransportLayer,
    public val applicationLayer: ApplicationLayer,
    private val io: ComboIO,
    private val onNewDisplayFrame: (displayFrame: DisplayFrame) -> Unit
) {
    enum class Mode(val str: String) {
        REMOTE_TERMINAL("REMOTE_TERMINAL"),
        COMMAND("COMMAND")
    }

    enum class Button(val str: String) {
        UP("UP"),
        DOWN("DOWN"),
        MENU("MENU"),
        CHECK("CHECK")
    }

    private val packetChannel = Channel<ApplicationLayer.Packet>(Channel.UNLIMITED)
    private val displayFrameAssembler = DisplayFrameAssembler()
    private var currentMode = Mode.REMOTE_TERMINAL

    private var receiveLoopJob: Job? = null

    private var currentLongRTPressJob: Job? = null
    private var currentLongRTPressedButton = Button.CHECK

    /* suspend fun processPacketsReceivedInLoop(onNewPacket: (packet: ApplicationLayer.Packet) -> Boolean) {
        while (true) {
            val packet = packetChannel.receive()
            if (!onNewPacket(packet))
                break
        }
    } */

    suspend fun performPairing(
        bluetoothFriendlyName: String,
        pairingPINCallback: (getPINDeferred: CompletableDeferred<PairingPIN>) -> Unit
    ) {
        // Initiate pairing and wait for the response.
        // (The response contains no meaningful payload.)
        logger(LogLevel.DEBUG) { "Sending pairing connection request" }
        sendPacket(transportLayer.createRequestPairingConnectionPacket())
        receivePacket(TransportLayer.CommandID.PAIRING_CONNECTION_REQUEST_ACCEPTED)

        // Initiate pump-client and client-pump keys request.
        // This will cause the pump to generate and show a
        // 10-digit PIN.
        logger(LogLevel.DEBUG) { "Requesting the pump to generate and show the pairing PIN" }
        sendPacket(transportLayer.createRequestKeysPacket())

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

        while (true) {
            // Create a CompletableDeferred and pass it to
            // the outside world through the pairingPINCallback.
            // Then wait for it to be completed. The outside
            // world then has to complete the deferred once
            // the user entered the PIN (typically through some
            // sort of UI).
            logger(LogLevel.DEBUG) { "Waiting for the PIN to be provided" }
            val getPINDeferred = CompletableDeferred<PairingPIN>()
            pairingPINCallback.invoke(getPINDeferred)
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
                sendPacket(transportLayer.createGetAvailableKeysPacket())

                // Wait for the KEY_RESPONSE packet.
                keyResponsePacket = receivePacket(TransportLayer.CommandID.KEY_RESPONSE)
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
        sendPacket(transportLayer.createRequestIDPacket(bluetoothFriendlyName))
        tpLayerPacket = receivePacket(TransportLayer.CommandID.ID_RESPONSE)
        if (!transportLayer.verifyIncomingPacket(tpLayerPacket))
            throw TransportLayer.PacketVerificationException(tpLayerPacket)
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
        sendPacket(transportLayer.createRequestRegularConnectionPacket())
        tpLayerPacket = receivePacket(TransportLayer.CommandID.REGULAR_CONNECTION_REQUEST_ACCEPTED)
        if (!transportLayer.verifyIncomingPacket(tpLayerPacket))
            throw TransportLayer.PacketVerificationException(tpLayerPacket)

        // Initiate application-layer connection and wait for the response.
        // (The response contains no meaningful payload.)
        logger(LogLevel.DEBUG) { "Initiating application layer connection" }
        sendPacket(applicationLayer.createCTRLConnectPacket())
        receivePacket(ApplicationLayer.Command.CTRL_CONNECT_RESPONSE)

        // Next, we have to query the versions of both command mode and
        // RT mode services. It is currently unknown how to interpret
        // the version numbers, but apparently we _have_ to query them,
        // otherwise the pump considers it an error.
        // TODO: Further verify this.
        logger(LogLevel.DEBUG) { "Requesting command mode service version" }
        sendPacket(applicationLayer.createCTRLGetServiceVersionPacket(ApplicationLayer.ServiceID.COMMAND_MODE))
        receivePacket(ApplicationLayer.Command.CTRL_SERVICE_VERSION_RESPONSE)
        // NOTE: These two steps may not be necessary. See the
        // "Application layer pairing" section in the spec.
        /*
        sendPacket(applicationLayer.createCTRLGetServiceVersionPacket(ApplicationLayer.ServiceID.RT_MODE))
        receivePacket(ApplicationLayer.Command.CTRL_SERVICE_VERSION_RESPONSE)
        */

        // Next, send a BIND command and wait for the response.
        // (The response contains no meaningful payload.)
        logger(LogLevel.DEBUG) { "Sending BIND command" }
        sendPacket(applicationLayer.createCTRLBindPacket())
        receivePacket(ApplicationLayer.Command.CTRL_BIND_RESPONSE)

        // We have to re-connect the regular connection at the
        // transport layer now. (Unclear why, but it seems this
        // is necessary for the pairing process to succeed.)
        // Wait for the response and verify it.
        logger(LogLevel.DEBUG) { "Reconnecting regular connection" }
        sendPacket(transportLayer.createRequestRegularConnectionPacket())
        tpLayerPacket = receivePacket(TransportLayer.CommandID.REGULAR_CONNECTION_REQUEST_ACCEPTED)
        if (!transportLayer.verifyIncomingPacket(tpLayerPacket))
            throw TransportLayer.PacketVerificationException(tpLayerPacket)

        // Disconnect the application layer connection.
        logger(LogLevel.DEBUG) { "Disconnecting application layer connection" }
        sendPacket(applicationLayer.createCTRLDisconnectPacket())

        // Pairing complete.
        logger(LogLevel.DEBUG) { "Pairing finished successfully" }
    }

    suspend fun connect(backgroundReceiveScope: CoroutineScope) {
        if (receiveLoopJob != null) {
            throw IllegalStateException(
                "Attempted to connect even though a receive job is running (-> we are already connected)"
            )
        }

        try {
            lateinit var tpLayerPacket: TransportLayer.Packet

            receiveLoopJob = backgroundReceiveScope.launch {
                runReceiveLoop()
            }

            logger(LogLevel.DEBUG) { "Sending regular connection request" }

            sendPacket(transportLayer.createRequestRegularConnectionPacket())
            tpLayerPacket = receivePacket(TransportLayer.CommandID.REGULAR_CONNECTION_REQUEST_ACCEPTED)
            if (!transportLayer.verifyIncomingPacket(tpLayerPacket))
                throw TransportLayer.PacketVerificationException(tpLayerPacket)

            logger(LogLevel.DEBUG) { "Initiating application layer connection" }
            sendPacket(applicationLayer.createCTRLConnectPacket())
            receivePacket(ApplicationLayer.Command.CTRL_CONNECT_RESPONSE)

            activateMode(Mode.REMOTE_TERMINAL)

            logger(LogLevel.INFO) { "Application layer connected" }
        } catch (e: Exception) {
            disconnect()
        }
    }

    suspend fun disconnect() {
        if (receiveLoopJob == null) {
            logger(LogLevel.DEBUG) {
                "Attempted to connect even though a receive job is running (-> we are already disconnected); ignoring call"
            }
            return
        }

        receiveLoopJob!!.cancel()
        receiveLoopJob = null

        logger(LogLevel.DEBUG) { "Deactivating all services" }
        sendPacket(applicationLayer.createCTRLDeactivateAllServicesPacket())
        receivePacket(ApplicationLayer.Command.CTRL_ALL_SERVICES_DEACTIVATED)

        logger(LogLevel.DEBUG) { "Sending disconnect packet" }
        sendPacket(applicationLayer.createCTRLDisconnectPacket())

        logger(LogLevel.INFO) { "Application layer disconnected" }
    }

    suspend fun switchToMode(newMode: Mode) {
        // TODO: This is experimental. Switching modes has not been attempted yet.

        if (currentMode == newMode) {
            logger(LogLevel.DEBUG) { "Ignoring redundant mode change since the ${currentMode.str} is already active" }
            return
        }

        logger(LogLevel.DEBUG) { "Deactivating all services before activating the ${currentMode.str} mode" }
        sendPacket(applicationLayer.createCTRLDeactivateAllServicesPacket())
        receivePacket(ApplicationLayer.Command.CTRL_ALL_SERVICES_DEACTIVATED)

        activateMode(newMode)
    }

    suspend fun sendSingleRTButtonPress(button: Button) {
        if (currentMode != Mode.REMOTE_TERMINAL)
            throw IllegalStateException("Cannot send RT button press while being in ${currentMode.str} mode")

        try {
            val buttonCode = when (button) {
                Button.UP -> ApplicationLayer.RTButtonCode.UP
                Button.DOWN -> ApplicationLayer.RTButtonCode.DOWN
                Button.MENU -> ApplicationLayer.RTButtonCode.MENU
                Button.CHECK -> ApplicationLayer.RTButtonCode.CHECK
            }

            sendPacket(applicationLayer.createRTButtonStatusPacket(buttonCode, true))

            delay(100L)

            sendPacket(applicationLayer.createRTButtonStatusPacket(ApplicationLayer.RTButtonCode.NO_BUTTON, true))
        } catch (e: Exception) {
            disconnect()
            throw e
        }
    }

    suspend fun startLongRTButtonPress(button: Button, scope: CoroutineScope) {
        if (currentLongRTPressJob != null) {
            logger(LogLevel.DEBUG) {
                "Long RT button press job already running, and button press state is PRESSED; ignoring redundant call"
            }
            return
        }

        sendLongRTButtonPress(button, true, scope)
    }

    suspend fun stopLongRTButtonPress() {
        if (currentLongRTPressJob == null) {
            logger(LogLevel.DEBUG) {
                "No long RT button press job running, and button press state is RELEASED; ignoring redundant call"
            }
            return
        }

        sendLongRTButtonPress(Button.CHECK, false, null)
    }

    // Private functions

    private suspend fun sendPacket(packet: TransportLayer.Packet) {
        logger(LogLevel.DEBUG) { "Sending transport layer ${packet.commandID.name} packet" }
        io.send(packet.toByteList())
    }

    private suspend fun sendPacket(packet: ApplicationLayer.Packet) {
        logger(LogLevel.DEBUG) { "Sending application layer ${packet.command.name} packet" }
        io.send(packet.toTransportLayerPacket(transportLayer).toByteList())
    }

    private suspend fun receivePacket(expectedCommandID: TransportLayer.CommandID? = null): TransportLayer.Packet {
        logger(LogLevel.DEBUG) {
            if (expectedCommandID == null)
                "Waiting for transport layer packet"
            else
                "Waiting for transport layer ${expectedCommandID.name} packet"
        }

        lateinit var tpLayerPacket: TransportLayer.Packet

        receivingPacket@ while (true) {
            tpLayerPacket = TransportLayer.Packet(io.receive())

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
                else -> {
                    if ((expectedCommandID != null) && (tpLayerPacket.commandID != expectedCommandID))
                        throw TransportLayer.IncorrectPacketException(tpLayerPacket, expectedCommandID)
                    else
                        break@receivingPacket
                }
            }
        }

        return tpLayerPacket
    }

    suspend fun receivePacket(expectedCommand: ApplicationLayer.Command): ApplicationLayer.Packet {
        logger(LogLevel.DEBUG) {
            "Waiting for application layer ${expectedCommand.name} packet (will arrive in a transport layer DATA packet)"
        }
        val tpLayerPacket = receivePacket(TransportLayer.CommandID.DATA)

        logger(LogLevel.DEBUG) { "Parsing DATA packet as application layer packet" }
        return ApplicationLayer.Packet(tpLayerPacket)
    }

    private suspend fun runReceiveLoop() {
        while (true) {
            val tpLayerPacket = receivePacket()

            // Process the packet according to its command ID. If we
            // don't recognize the packet, log and discard it.
            // NOTE: This is not about packets with unknown IDs. These
            // are sorted out by the TransportLayer.Packet constructor,
            // which throws an InvalidCommandIDException in that case.
            // This check here is about valid command IDs that we don't
            // handle in this when statement.
            when (tpLayerPacket.commandID) {
                TransportLayer.CommandID.DATA -> processTpLayerDataPacket(tpLayerPacket)
                else -> logger(LogLevel.WARN) {
                    "Cannot process ${tpLayerPacket.commandID.name} packet coming from the Combo; ignoring packet"
                }
            }
        }
    }

    private suspend fun processTpLayerDataPacket(tpLayerPacket: TransportLayer.Packet) {
        lateinit var appLayerPacket: ApplicationLayer.Packet

        // Parse the transport layer DATA packet as an application layer packet.
        try {
            logger(LogLevel.DEBUG) { "Parsing DATA packet as application layer packet" }
            appLayerPacket = ApplicationLayer.Packet(tpLayerPacket)
        } catch (e: ApplicationLayer.ExceptionBase) {
            logger(LogLevel.ERROR) { "Could not parse DATA packet as application layer packet: $e" }
            throw e
        }

        // RT_DISPLAY packets regularly come from the Combo, and contains new display
        // frame updates. We must pass these new updates on right away, and not just
        // push it into the channel, because the frames may contain notifications.
        // For this reason, we handle RT_DISPLAY packets explicitely.
        when (appLayerPacket.command) {
            ApplicationLayer.Command.RT_DISPLAY -> processRTDisplayPayload(applicationLayer.parseRTDisplayPacket(appLayerPacket))
            else -> packetChannel.send(appLayerPacket)
        }
    }

    private fun processErrorResponse(errorResponse: Int) {
        // TODO: Throw exception here
    }

    private fun processRTDisplayPayload(rtDisplayPayload: ApplicationLayer.RTDisplayPayload) {
        val displayFrame = displayFrameAssembler.processRTDisplayPayload(rtDisplayPayload)
        if (displayFrame != null)
            onNewDisplayFrame(displayFrame)
    }

    private suspend fun activateMode(newMode: Mode) {
        logger(LogLevel.DEBUG) { "Activating ${newMode.str} mode" }

        sendPacket(
            applicationLayer.createCTRLActivateServicePacket(
                when (newMode) {
                    Mode.REMOTE_TERMINAL -> ApplicationLayer.ServiceID.RT_MODE
                    Mode.COMMAND -> ApplicationLayer.ServiceID.COMMAND_MODE
                }
            )
        )
        receivePacket(ApplicationLayer.Command.CTRL_ACTIVATE_SERVICE_RESPONSE)

        currentMode = newMode
    }

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
            // the inner finally block, sendPacket() is called,
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

                        sendPacket(applicationLayer.createRTButtonStatusPacket(buttonCode, buttonStatusChanged))

                        delay(200L)

                        buttonStatusChanged = false
                    }
                } finally {
                    logger(LogLevel.DEBUG) { "Long RT ${button.str} button press canceled" }
                    sendPacket(applicationLayer.createRTButtonStatusPacket(ApplicationLayer.RTButtonCode.NO_BUTTON, true))
                }
            } finally {
                currentLongRTPressJob = null
            }
        }
    }
}
