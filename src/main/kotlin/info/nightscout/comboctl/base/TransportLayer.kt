package info.nightscout.comboctl.base

import java.lang.IllegalStateException

/**
 * Combo transport layer (TL) communication implementation.
 *
 * This deals with TL packets and processes. These are:
 *
 * - Generating and parsing TL packets
 * - Pairing commands
 * - Cipher management for authenticating packets
 * - Managing and incrementing the tx nonce in outgoing TL packets
 *
 * The nested State class contains the entire state of the transport layer.
 * All of its properties (except for weakCipher and currentSequenceFlag) must
 * be stored persistently once pairing with the Combo has been completed.
 * Storing the ciphers is accomplished by storing their "key" properties.
 * (See the section "What data to persistently store" in combo-comm-spec.adoc
 * (for more details about persistent storage.)
 * When the application is started again, these properties must be restored.
 */
class TransportLayer {
    class State {
        /***********
         * Ciphers *
         ***********/

        // Weak key cipher, only used for authenticating the KEY_RESPONSE
        // message and for decrypting its payload (the client-pump and
        // pump-client keys).
        var weakCipher: Cipher? = null

        /**
         * Client-pump cipher.
         *
         * This cipher is used for authenticating packets going to the Combo.
         *
         * Its 128-bit key is one of the values that must be stored persistently
         * and restored when the application is reloaded.
         */
        var clientPumpCipher: Cipher? = null

        /**
         * Pump-client cipher.
         *
         * This cipher is used for verifying packets coming from the Combo.
         *
         * Its 128-bit key is one of the values that must be stored persistently
         * and restored when the application is reloaded.
         */
        var pumpClientCipher: Cipher? = null

        /*********
         * Nonce *
         *********/

        /**
         * Current tx nonce.
         *
         * This 13-byte nonce is incremented every time a packet that goes
         * to the Combo is generated, except during the pairing process,
         * where it is initially zero, and begins to be incremented when
         * the ID_RESPONSE command is received from the Combo.
         *
         * This is one of the fields that must be stored persistently
         * and restored when the application is reloaded.
         */
        var currentTxNonce = ByteArray(NUM_NONCE_BYTES) { 0x00 }
            set(value) {
                require(value.size == NUM_NONCE_BYTES)
                field = value
            }

        /**********************************
         * Source & destination addresses *
         **********************************/

        /**
         * The source address of a previously received KEY_RESPONSE packet.
         *
         * This is one of the fields that must be stored persistently
         * and restored when the application is reloaded.
         * (This is also the reason why this property isn't private.)
         */
        var keyResponseSourceAddress: Int? = null
            set(value) {
                require(value != null)
                require((value >= 0x0) && (value <= 0xF))
                field = value
            }

        /**
         * The destination address of a previously received KEY_RESPONSE packet.
         *
         * This is one of the fields that must be stored persistently
         * and restored when the application is reloaded.
         * (This is also the reason why this property isn't private.)
         */
        var keyResponseDestinationAddress: Int? = null
            set(value) {
                require(value != null)
                require((value >= 0x0) && (value <= 0xF))
                field = value
            }

        /**
         * Current sequence flag, used in reliable data packets.
         *
         * This flag gets toggled every time a reliable packet is sent.
         */
        var currentSequenceFlag = false
    }

    /**
     * Valid command IDs for Combo packets.
     */
    enum class CommandID(val id: Int) {
        // Pairing commands
        REQUEST_PAIRING_CONNECTION(0x09), PAIRING_CONNECTION_REQUEST_ACCEPTED(0x0A), REQUEST_KEYS(0x0C),
        GET_AVAILABLE_KEYS(0x0F), KEY_RESPONSE(0x11), REQUEST_ID(0x12), ID_RESPONSE(0x14),

        // Regular commands - these require that pairing was performed
        REQUEST_REGULAR_CONNECTION(0x17), REGULAR_CONNECTION_REQUEST_ACCEPTED(0x18), DISCONNECT(0x1B),
        ACK_RESPONSE(0x05), DATA(0x03), ERROR_RESPONSE(0x06)
    }

    private fun incrementTxNonce(state: State) {
        var carry: Boolean = true

        for (i in state.currentTxNonce.indices) {
            if (carry) {
                var nonceByte = state.currentTxNonce[i]

                if (nonceByte == 0xFF.toByte()) {
                    state.currentTxNonce[i] = 0x00.toByte()
                } else {
                    nonceByte++
                    state.currentTxNonce[i] = nonceByte
                    carry = false
                }
            }

            if (!carry) break
        }
    }

    // Base function for generating CRC-verified packets.
    // These packets only have the CRC itself as payload, and
    // are only used during the pairing process.
    private fun createCRCPacket(commandID: CommandID): ComboPacket = ComboPacket().apply {
        majorVersion = 1
        minorVersion = 0
        sequenceBit = false
        reliabilityBit = false
        sourceAddress = 0xF
        destinationAddress = 0x0
        nonce = ByteArray(NUM_NONCE_BYTES) { 0x00 }
        machineAuthenticationCode = ByteArray(NUM_MAC_BYTES) { 0x00 }
        this.commandID = commandID.id
        computeCRC16Payload()
    }

    // Base function for generating MAC-authenticated packets. This
    // function assumes that the TL_KEY_RESPONSE packet has already
    // been received, because only then will keyResponseSourceAddress,
    // keyResponseDestinationAddress, and clientPumpCipher.key be set
    // to valid values.
    private fun createMACAuthenticatedPacket(
        state: State,
        commandID: CommandID,
        payload: ArrayList<Byte> = arrayListOf(),
        sequenceBit: Boolean = false,
        reliabilityBit: Boolean = false
    ): ComboPacket {
        require(state.keyResponseSourceAddress != null)
        require(state.keyResponseDestinationAddress != null)

        val packet = ComboPacket().apply {
            majorVersion = 1
            minorVersion = 0
            sourceAddress = state.keyResponseSourceAddress!!
            destinationAddress = state.keyResponseDestinationAddress!!
        }

        packet.commandID = commandID.id
        packet.sequenceBit = sequenceBit
        packet.reliabilityBit = reliabilityBit
        packet.payload = payload

        packet.nonce = state.currentTxNonce.copyOf()
        incrementTxNonce(state)

        state.clientPumpCipher?.let { packet.authenticate(it) } ?: throw IllegalStateException()

        return packet
    }

    /**
     * Creates a REQUEST_PAIRING_CONNECTION packet.
     *
     * This is exclusively used during the pairing process.
     *
     * See the combo-comm-spec.adoc file for details about this packet.
     *
     * @return The produced packet.
     */
    fun createRequestPairingConnectionPacket(): ComboPacket {
        return createCRCPacket(CommandID.REQUEST_PAIRING_CONNECTION)
    }

    /**
     * Creates a REQUEST_KEYS packet.
     *
     * This is exclusively used during the pairing process.
     *
     * See the combo-comm-spec.adoc file for details about this packet.
     *
     * @return The produced packet.
     */
    fun createRequestKeysPacket(): ComboPacket {
        return createCRCPacket(CommandID.REQUEST_KEYS)
    }

    /**
     * Creates a GET_AVAILABLE_KEYS packet.
     *
     * This is exclusively used during the pairing process.
     *
     * See the combo-comm-spec.adoc file for details about this packet.
     *
     * @return The produced packet.
     */
    fun createGetAvailableKeysPacket(): ComboPacket {
        return createCRCPacket(CommandID.GET_AVAILABLE_KEYS)
    }

    /**
     * Creates a REQUEST_ID packet.
     *
     * This is exclusively used during the pairing process.
     *
     * See the combo-comm-spec.adoc file for details about this packet.
     *
     * @param state Current transport layer state. Will be updated.
     * @param bluetoothFriendlyName Bluetooth friendly name to use for this packet.
     *        Maximum length is 13 characters.
     *        See the Bluetooth specification, Vol. 3 part C section 3.2.2
     *        for details about Bluetooth friendly names.
     * @return The produced packet.
     */
    fun createRequestIDPacket(state: State, bluetoothFriendlyName: String): ComboPacket {
        // The nonce is set to 1 in the REQUEST_ID packet, and
        // get incremented from that moment onwards.
        state.currentTxNonce = byteArrayOfInts(0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00)

        val btFriendlyNameBytes = bluetoothFriendlyName.toByteArray(Charsets.UTF_8)
        val numBTFriendlyNameBytes = kotlin.math.min(btFriendlyNameBytes.size, 13)

        val payload = ArrayList<Byte>(17)

        payload.add(((Constants.CLIENT_SOFTWARE_VERSION shr 0) and 0xFF).toByte())
        payload.add(((Constants.CLIENT_SOFTWARE_VERSION shr 8) and 0xFF).toByte())
        payload.add(((Constants.CLIENT_SOFTWARE_VERSION shr 16) and 0xFF).toByte())
        payload.add(((Constants.CLIENT_SOFTWARE_VERSION shr 24) and 0xFF).toByte())

        // If the BT friendly name is shorter than 13 bytes,
        // the rest must be set to zero.
        for (i in 0 until numBTFriendlyNameBytes) payload.add(btFriendlyNameBytes[i])
        for (i in numBTFriendlyNameBytes until 13) payload.add(0.toByte())

        return createMACAuthenticatedPacket(state, CommandID.REQUEST_ID, payload)
    }

    /**
     * Creates a REQUEST_REGULAR_CONNECTION packet.
     *
     * In spite of initiating a "regular" connection, it is also used once
     * in the latter phases of the pairing process. See the combo-comm-spec.adoc
     * file for details.
     *
     * @param state Current transport layer state. Will be updated.
     * @return The produced packet.
     */
    fun createRequestRegularConnectionPacket(state: State): ComboPacket {
        return createMACAuthenticatedPacket(state, CommandID.REQUEST_REGULAR_CONNECTION)
    }

    /**
     * Creates an ACK_RESPONSE packet.
     *
     * See the combo-comm-spec.adoc file for details about this packet.
     *
     * @param state Current transport layer state. Will be updated.
     * @param sequenceBit Sequence bit to set in the ACK_RESPONSE packet.
     * @return The produced packet.
     */
    fun createAckResponsePacket(state: State, sequenceBit: Boolean): ComboPacket {
        return createMACAuthenticatedPacket(state, CommandID.ACK_RESPONSE, sequenceBit = sequenceBit,
            reliabilityBit = true)
    }

    /**
     * Creates a DATA packet.
     *
     * See the combo-comm-spec.adoc file for details about this packet.
     *
     * One specialty of this packet is that its payload is not strictly
     * defined - it can be pretty much anything. For this reason, it is
     * passed as an argument. Also, some of these packets may have their
     * reliability bit set. In that case, their sequence bits will be
     * alternating between being set and cleared.
     *
     * @param state Current transport layer state. Will be updated.
     * @param reliabilityBit Reliability bit to set in the DATA packet.
     * @param payload Payload to assign to the DATA packet.
     * @return The produced packet.
     */
    fun createDataPacket(state: State, reliabilityBit: Boolean, payload: ArrayList<Byte>): ComboPacket {
        val sequenceBit: Boolean

        if (reliabilityBit) {
            sequenceBit = state.currentSequenceFlag
            state.currentSequenceFlag = !state.currentSequenceFlag
        } else sequenceBit = false

        return createMACAuthenticatedPacket(state, CommandID.DATA, payload = payload, sequenceBit = sequenceBit,
            reliabilityBit = reliabilityBit)
    }

    /**
     * Parses a KEY_RESPONSE packet.
     *
     * The Combo sends this during the pairing process. It contains
     * client-pump and pump-client keys, encrypted with the weak key.
     * This will modify the client-pump and pump-client keys in the
     * specified state as well as its keyResponseSourceAddress and
     * keyResponseDestinationAddress fields.
     *
     * The weak key must have been set before this can be called.
     *
     * @param state Current transport layer state. Will be updated.
     * @param packet The packet that came from the Combo.
     */
    fun parseKeyResponsePacket(state: State, packet: ComboPacket) {

        val weakCipher = state.weakCipher ?: throw IllegalStateException()

        require(packet.commandID == CommandID.KEY_RESPONSE.id)
        require(packet.payload.size == (CIPHER_KEY_SIZE * 2))
        require(packet.verifyAuthentication(weakCipher))

        val encryptedPCKey = ByteArray(CIPHER_KEY_SIZE)
        val encryptedCPKey = ByteArray(CIPHER_KEY_SIZE)

        for (i in 0 until CIPHER_KEY_SIZE) {
            encryptedPCKey[i] = packet.payload[i + 0]
            encryptedCPKey[i] = packet.payload[i + CIPHER_KEY_SIZE]
        }

        state.pumpClientCipher = Cipher(weakCipher.decrypt(encryptedPCKey))
        state.clientPumpCipher = Cipher(weakCipher.decrypt(encryptedCPKey))

        // Note: Source and destination addresses are reversed,
        // since they are set from the perspective of the pump.
        state.keyResponseSourceAddress = packet.destinationAddress
        state.keyResponseDestinationAddress = packet.sourceAddress
    }

    /**
     * Class for [parseIDResponsePacket] return values.
     *
     * It is currently unknown what the server ID actually means.
     * The Pump ID however has been observed to contain the
     * pump's serial number, like this: "PUMP_<serial number>".
     *
     * @property serverID Server ID value from the Combo.
     * @property pumpID Pump ID value from the Combo.
     */
    data class ComboIDs(val serverID: Long, val pumpID: String)

    /**
     * Parses a KEY_RESPONSE packet.
     *
     * The Combo sends this during the pairing process. It contains
     * IDs from the Combo. These are purely informational, and not
     * needed for operating the Combo, though they may be useful
     * for logging purposes.
     *
     * @param state Current transport layer state. Will be updated.
     * @param packet The packet that came from the Combo.
     * @return The parsed IDs.
     */
    fun parseIDResponsePacket(state: State, packet: ComboPacket): ComboIDs {
        require(packet.commandID == CommandID.ID_RESPONSE.id)
        require(packet.payload.size == 17)
        val pumpClientCipher = state.pumpClientCipher ?: throw IllegalStateException()
        require(packet.verifyAuthentication(pumpClientCipher))

        val serverID = ((packet.payload[0].toPosLong() shl 0) or
            (packet.payload[1].toPosLong() shl 8) or
            (packet.payload[2].toPosLong() shl 16) or
            (packet.payload[3].toPosLong() shl 24))

        val pumpIDStrBuilder = StringBuilder()
        for (i in 0 until 13) {
            val pumpIDByte = packet.payload[4 + i]
            if (pumpIDByte == 0.toByte()) break
            else pumpIDStrBuilder.append(pumpIDByte.toChar())
        }
        val pumpID = pumpIDStrBuilder.toString()

        return ComboIDs(serverID, pumpID)
    }

    /**
     * Parses an ERROR_RESPONSE packet.
     *
     * This is sent by the Combo in case of an error. Its payload
     * is a single byte containing the error ID.
     *
     * See the combo-comm-spec.adoc file for the currently known
     * list of possible error IDs.
     *
     * @param state Current transport layer state..
     * @param packet The packet that came from the Combo.
     * @return The parsed error ID.
     */
    private fun parseErrorResponsePacket(state: State, packet: ComboPacket): Int? =
        state.pumpClientCipher?.let { cipher ->
            require(packet.commandID == CommandID.ERROR_RESPONSE.id)
            require(packet.payload.size == 1)
            require(packet.verifyAuthentication(cipher))

            packet.payload[0].toInt()
        }
}
