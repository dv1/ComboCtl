package info.nightscout.comboctl.base

import kotlin.coroutines.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.*

// Utility functions.

private suspend fun sendTransportLayerPacket(
    outgoingDataChannel: SendChannel<List<Byte>>,
    logger: Logger,
    packet: TransportLayer.Packet
) {
    logger.log(LogLevel.DEBUG) { "Sending transport layer ${packet.commandID.name} packet" }
    outgoingDataChannel.send(packet.toByteList())
}

private suspend fun sendApplicationLayerPacket(
    outgoingDataChannel: SendChannel<List<Byte>>,
    transportLayer: TransportLayer,
    logger: Logger,
    packet: ApplicationLayer.Packet
) {
    logger.log(LogLevel.DEBUG) { "Sending application layer ${packet.command.name} packet" }
    outgoingDataChannel.send(packet.toTransportLayerPacket(transportLayer).toByteList())
}

private suspend fun receiveTransportLayerPacket(
    incomingDataChannel: ReceiveChannel<List<Byte>>,
    outgoingDataChannel: SendChannel<List<Byte>>,
    transportLayer: TransportLayer,
    logger: Logger,
    expectedCommandID: TransportLayer.CommandID
): TransportLayer.Packet {
    logger.log(LogLevel.DEBUG) { "Waiting for transport layer ${expectedCommandID.name} packet" }

    lateinit var tpLayerPacket: TransportLayer.Packet

    receivingPacket@ while (true) {
        tpLayerPacket = TransportLayer.Packet(incomingDataChannel.receive())

        when (tpLayerPacket.commandID) {
            expectedCommandID -> break@receivingPacket
            TransportLayer.CommandID.ACK_RESPONSE -> logger.log(LogLevel.DEBUG) { "Got ACK_RESPONSE packet; ignoring" }
            else -> throw TransportLayer.IncorrectPacketException(tpLayerPacket, expectedCommandID)
        }
    }

    // Packets with the reliability flag set must be immediately
    // responded to with an ACK_RESPONSE packet whose sequence bit
    // must match that of the received packet.
    // TODO: Extract this ACK_RESPONSE somehow so it does not have to be
    // implemented twice (once for pairing, and once for regular sessions)
    if (tpLayerPacket.reliabilityBit) {
        logger.log(LogLevel.DEBUG) {
            "Got a transport layer ${tpLayerPacket.commandID.name} packet with its reliability bit set; " +
            "responding with ACK_RESPONSE packet; sequence bit: ${tpLayerPacket.sequenceBit}"
        }
        val ackResponsePacket = transportLayer.createAckResponsePacket(tpLayerPacket.sequenceBit)
        outgoingDataChannel.send(ackResponsePacket.toByteList())
    }

    return tpLayerPacket
}

private suspend fun receiveApplicationLayerPacket(
    incomingDataChannel: ReceiveChannel<List<Byte>>,
    outgoingDataChannel: SendChannel<List<Byte>>,
    transportLayer: TransportLayer,
    logger: Logger,
    expectedCommand: ApplicationLayer.Command
): ApplicationLayer.Packet {
    logger.log(LogLevel.DEBUG) { "Waiting for application layer ${expectedCommand.name} packet" }
    val tpLayerPacket = receiveTransportLayerPacket(
        incomingDataChannel,
        outgoingDataChannel,
        transportLayer,
        logger,
        TransportLayer.CommandID.DATA
    )
    val appLayerPacket = ApplicationLayer.Packet(tpLayerPacket)

    if (appLayerPacket.command != expectedCommand)
        throw ApplicationLayer.IncorrectPacketException(appLayerPacket, expectedCommand)

    return appLayerPacket
}

suspend fun performPairing(
    appLayer: ApplicationLayer,
    transportLayer: TransportLayer,
    bluetoothFriendlyName: String,
    logger: Logger,
    pairingPINCallback: (getPINDeferred: CompletableDeferred<PairingPIN>) -> Unit,
    incomingDataChannel: ReceiveChannel<List<Byte>>,
    outgoingDataChannel: SendChannel<List<Byte>>
) {
    // Initiate pairing and wait for the response.
    // (The response contains no meaningful payload.)
    logger.log(LogLevel.DEBUG) { "Sending pairing connection request" }
    sendTransportLayerPacket(outgoingDataChannel, logger, transportLayer.createRequestPairingConnectionPacket())
    receiveTransportLayerPacket(
        incomingDataChannel,
        outgoingDataChannel,
        transportLayer,
        logger,
        TransportLayer.CommandID.PAIRING_CONNECTION_REQUEST_ACCEPTED
    )

    // Initiate pump-client and client-pump keys request.
    // This will cause the pump to generate and show a
    // 10-digit PIN.
    logger.log(LogLevel.DEBUG) { "Requesting the pump to generate and show the pairing PIN" }
    sendTransportLayerPacket(outgoingDataChannel, logger, transportLayer.createRequestKeysPacket())

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
        logger.log(LogLevel.DEBUG) { "Waiting for the PIN to be provided" }
        val getPINDeferred = CompletableDeferred<PairingPIN>()
        pairingPINCallback.invoke(getPINDeferred)
        pin = getPINDeferred.await()
        logger.log(LogLevel.DEBUG) { "Provided PIN: $pin" }

        // Pass the PIN to the transport layer so it can internally
        // generate a weak cipher out of it.
        transportLayer.usePairingPIN(pin)

        // After the PIN was entered, send a GET_AVAILABLE_KEYS
        // request to the pump. Only then will the pump actually
        // send us the KEY_RESPONSE packet. (The REQUEST_KEYS
        // message sent earlier above only causes the pump to
        // generate the keys and show the PIN.)
        if (keyResponsePacket == null) {
            logger.log(LogLevel.DEBUG) { "Requesting the available pump-client and client-pump keys from the pump" }
            sendTransportLayerPacket(outgoingDataChannel, logger, transportLayer.createGetAvailableKeysPacket())

            // Wait for the KEY_RESPONSE packet.
            keyResponsePacket = receiveTransportLayerPacket(
                incomingDataChannel,
                outgoingDataChannel,
                transportLayer,
                logger,
                TransportLayer.CommandID.KEY_RESPONSE
            )
            logger.log(LogLevel.DEBUG) { "KEY_RESPONSE packet with the keys inside received" }
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
            logger.log(LogLevel.WARN) {
                "Could not authenticate KEY_RESPONSE packet; perhaps user entered PIN incorrectly; asking again for PIN"
            }
        } else
            break
    }

    // KEY_RESPONSE packet was received and verified, and the
    // pairing PIN was passed to the transport layer. We can
    // now decrypt the pump-client and client-pump keys.
    logger.log(LogLevel.DEBUG) { "Reading keys and source/destination addresses from KEY_RESPONSE packet" }
    transportLayer.parseKeyResponsePacket(keyResponsePacket!!)

    // We got the keys. Next step is to ask the pump for IDs.
    // The ID_RESPONSE response packet contains purely informational
    // values that aren't necessary for operating the pump. However,
    // it seems that we still have to request these IDs, otherwise
    // the pump reports an error. (TODO: Further verify this.)
    sendTransportLayerPacket(outgoingDataChannel, logger, transportLayer.createRequestIDPacket(bluetoothFriendlyName))
    val tpLayerPacket: TransportLayer.Packet = receiveTransportLayerPacket(
        incomingDataChannel,
        outgoingDataChannel,
        transportLayer,
        logger,
        TransportLayer.CommandID.ID_RESPONSE
    )
    if (!transportLayer.verifyIncomingPacket(tpLayerPacket))
        throw TransportLayer.PacketVerificationException(tpLayerPacket)
    val comboIDs = transportLayer.parseIDResponsePacket(tpLayerPacket)
    logger.log(LogLevel.DEBUG) {
        "Received IDs: server ID: ${comboIDs.serverID} pump ID: ${comboIDs.pumpID}"
    }

    // Initiate a regular (= non-pairing) transport layer connection.
    // Note that we are still pairing - it just continues in the
    // application layer. For this to happen, we need a regular
    // _transport layer_ connection.
    // Wait for the response and verify it.
    logger.log(LogLevel.DEBUG) { "Sending regular connection request" }
    sendTransportLayerPacket(outgoingDataChannel, logger, transportLayer.createRequestRegularConnectionPacket())
    receiveTransportLayerPacket(
        incomingDataChannel,
        outgoingDataChannel,
        transportLayer,
        logger,
        TransportLayer.CommandID.REGULAR_CONNECTION_REQUEST_ACCEPTED
    )
    if (!transportLayer.verifyIncomingPacket(tpLayerPacket))
        throw TransportLayer.PacketVerificationException(tpLayerPacket)

    // Initiate application-layer connection and wait for the response.
    // (The response contains no meaningful payload.)
    logger.log(LogLevel.DEBUG) { "Initiating application layer connection" }
    sendApplicationLayerPacket(outgoingDataChannel, transportLayer, logger, appLayer.createCTRLConnectPacket())
    receiveApplicationLayerPacket(
        incomingDataChannel,
        outgoingDataChannel,
        transportLayer,
        logger,
        ApplicationLayer.Command.CTRL_CONNECT_RESPONSE
    )

    // Next, we have to query the versions of both command mode and
    // RT mode services. It is currently unknown how to interpret
    // the version numbers, but apparently we _have_ to query them,
    // otherwise the pump considers it an error.
    // TODO: Further verify this.
    logger.log(LogLevel.DEBUG) { "Requesting command mode service version" }
    sendApplicationLayerPacket(
        outgoingDataChannel,
        transportLayer,
        logger,
        appLayer.createCTRLGetServiceVersionPacket(ApplicationLayer.ServiceID.COMMAND_MODE)
    )
    receiveApplicationLayerPacket(
        incomingDataChannel,
        outgoingDataChannel,
        transportLayer,
        logger,
        ApplicationLayer.Command.CTRL_SERVICE_VERSION_RESPONSE
    )
    // NOTE: These two steps may not be necessary. See the
    // "Application layer pairing" section in the spec.
    /*
    sendApplicationLayerPacket(
        outgoingDataChannel,
        appLayerState,
        logger,
        appLayer.createCTRLGetServiceVersionPacket(ApplicationLayer.ServiceID.RT_MODE)
    )
    receiveApplicationLayerPacket(
        incomingDataChannel,
        outgoingDataChannel,
        appLayerState,
        logger,
        ApplicationLayer.Command.CTRL_SERVICE_VERSION_RESPONSE
    )
    */

    // Next, send a BIND command and wait for the response.
    // (The response contains no meaningful payload.)
    logger.log(LogLevel.DEBUG) { "Sending BIND command" }
    sendApplicationLayerPacket(outgoingDataChannel, transportLayer, logger, appLayer.createCTRLBindPacket())
    receiveApplicationLayerPacket(
        incomingDataChannel,
        outgoingDataChannel,
        transportLayer,
        logger,
        ApplicationLayer.Command.CTRL_BIND_RESPONSE
    )

    // We have to re-connect the regular connection at the
    // transport layer now. (Unclear why, but it seems this
    // is necessary for the pairing process to succeed.)
    // Wait for the response and verify it.
    logger.log(LogLevel.DEBUG) { "Reconnecting regular connection" }
    sendTransportLayerPacket(outgoingDataChannel, logger, transportLayer.createRequestRegularConnectionPacket())
    receiveTransportLayerPacket(
        incomingDataChannel,
        outgoingDataChannel,
        transportLayer,
        logger,
        TransportLayer.CommandID.REGULAR_CONNECTION_REQUEST_ACCEPTED
    )
    if (!transportLayer.verifyIncomingPacket(tpLayerPacket))
        throw TransportLayer.PacketVerificationException(tpLayerPacket)

    // Disconnect the application layer connection.
    logger.log(LogLevel.DEBUG) { "Disconnecting application layer connection" }
    sendApplicationLayerPacket(outgoingDataChannel, transportLayer, logger, appLayer.createCTRLDisconnectPacket())

    // Pairing complete. Close the send channel and cancel reception.
    logger.log(LogLevel.DEBUG) { "Pairing finished successfully" }
    incomingDataChannel.cancel()
    outgoingDataChannel.close()
}
