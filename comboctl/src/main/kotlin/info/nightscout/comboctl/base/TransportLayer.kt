package info.nightscout.comboctl.base

import java.lang.IllegalStateException

// Transport layer packet structure:
//
//   1. 4 bits    : Packet major version (always set to 0x01)
//   2. 4 bits    : Packet minor version (always set to 0x00)
//   3. 1 bit     : Sequence bit
//   4. 1 bit     : Unused (referred to as "Res1")
//   5. 1 bit     : Data reliability bit
//   6. 5 bits    : Command ID
//   7. 16 bits   : Payload length (in bytes), stored as a 16-bit little endian integer
//   8. 4 bits    : Source address
//   9. 4 bits    : Destination address
//  10. 13 bytes  : Nonce
//  11. n bytes   : Payload
//  12. 8 bytes   : Message authentication code

const val NUM_NONCE_BYTES = 13
const val NUM_MAC_BYTES = 8

// 1 byte with major & minor version
// 1 byte with sequence bit & "Res1" & reliability bit & command ID
// 2 bytes with payload length
// 1 byte with source and destination addresses
private const val PACKET_HEADER_SIZE = 1 + 1 + 2 + 1 + NUM_NONCE_BYTES

private const val VERSION_BYTE_OFFSET = 0
private const val SEQ_REL_CMD_BYTE_OFFSET = 1
private const val PAYLOAD_LENGTH_BYTES_OFFSET = 2
private const val ADDRESS_BYTE_OFFSET = 4
private const val NONCE_BYTES_OFFSET = 5
private const val PAYLOAD_BYTES_OFFSET = NONCE_BYTES_OFFSET + NUM_NONCE_BYTES

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
 * All of its properties (except for currentSequenceFlag) must be stored
 * persistently once pairing with the Combo has been completed.
 * Storing the ciphers is accomplished by storing their "key" properties.
 * (See the section "What data to persistently store" in combo-comm-spec.adoc
 * (for more details about persistent storage.)
 * When the application is started again, these properties must be restored.
 */
class TransportLayer(private val logger: Logger) {
    /**
     * Valid command IDs for Combo packets.
     */
    enum class CommandID(val id: Int) {
        // Pairing commands
        REQUEST_PAIRING_CONNECTION(0x09), PAIRING_CONNECTION_REQUEST_ACCEPTED(0x0A), REQUEST_KEYS(0x0C),
        GET_AVAILABLE_KEYS(0x0F), KEY_RESPONSE(0x11), REQUEST_ID(0x12), ID_RESPONSE(0x14),

        // Regular commands - these require that pairing was performed
        REQUEST_REGULAR_CONNECTION(0x17), REGULAR_CONNECTION_REQUEST_ACCEPTED(0x18), DISCONNECT(0x1B),
        ACK_RESPONSE(0x05), DATA(0x03), ERROR_RESPONSE(0x06);

        companion object {
            private val values = CommandID.values()
            fun fromInt(value: Int) = values.firstOrNull { it.id == value }
        }
    }

    /**
     * Base class for transport layer exceptions.
     *
     * @param message The detail message.
     */
    open class ExceptionBase(message: String) : ComboException(message)

    class InvalidCommandIDException(
        val commandID: Int,
        val packetBytes: List<Byte>
    ) : ExceptionBase("Invalid/unknown transport layer packet command ID $commandID")

    class IncorrectPacketException(
        val packet: TransportLayer.Packet,
        val expectedCommandID: CommandID
    ) : ExceptionBase("Incorrect packet: expected ${expectedCommandID.name} packet, got ${packet.commandID?.name ?: "<invalid>"} one")

    class PacketVerificationException(
        val packet: TransportLayer.Packet
    ) : ExceptionBase("Packet verification failed")

    /**
     * Class containing data of a Combo transport layer packet.
     *
     * Communication with the Combo uses packets as the basic unit. Each packet
     * has a header, payload, and a machine authentication code (MAC). (Some initial
     * pairing packets have a MAC made of nullbytes.) This class provides all
     * properties of a packet as well as functions for converting from/to byte lists
     * and for verifying / authenticating via MAC and CRC.
     *
     * See "Transport layer packet structure" in combo-comm-spec.adoc for details.
     */
    class Packet() {
        constructor(bytes: List<Byte>) : this() {
            require(bytes.size >= (PACKET_HEADER_SIZE + NUM_MAC_BYTES))

            majorVersion = (bytes[VERSION_BYTE_OFFSET].toPosInt() shr 4) and 0xF
            minorVersion = bytes[VERSION_BYTE_OFFSET].toPosInt() and 0xF
            sequenceBit = (bytes[SEQ_REL_CMD_BYTE_OFFSET].toPosInt() and 0x80) != 0
            reliabilityBit = (bytes[SEQ_REL_CMD_BYTE_OFFSET].toPosInt() and 0x20) != 0

            val commandIDInt = bytes[SEQ_REL_CMD_BYTE_OFFSET].toPosInt() and 0x1F
            commandID = CommandID.fromInt(commandIDInt) ?: throw InvalidCommandIDException(commandIDInt, bytes)

            sourceAddress = (bytes[ADDRESS_BYTE_OFFSET].toPosInt() shr 4) and 0xF
            destinationAddress = bytes[ADDRESS_BYTE_OFFSET].toPosInt() and 0xF

            val payloadSize = (bytes[PAYLOAD_LENGTH_BYTES_OFFSET + 1].toPosInt() shl 8) or bytes[PAYLOAD_LENGTH_BYTES_OFFSET + 0].toPosInt()
            require(bytes.size == (PACKET_HEADER_SIZE + payloadSize + NUM_MAC_BYTES))

            for (i in 0 until NUM_NONCE_BYTES) nonce[i] = bytes[NONCE_BYTES_OFFSET + i]

            payload = ArrayList<Byte>(bytes.subList(PAYLOAD_BYTES_OFFSET, PAYLOAD_BYTES_OFFSET + payloadSize))

            for (i in 0 until NUM_MAC_BYTES) machineAuthenticationCode[i] = bytes[PAYLOAD_BYTES_OFFSET + payloadSize + i]
        }

        // Header

        /**
         * Major version number.
         *
         * In all observed packets, this was set to 1.
         *
         * Valid range is 0-15.
         */
        var majorVersion: Int = 1
            set(value) {
                require((value >= 0x0) && (value <= 0xF))
                field = value
            }

        /**
         * Minor version number.
         *
         * In all observed packets, this was set to 0.
         *
         * Valid range is 0-15.
         */
        var minorVersion: Int = 0
            set(value) {
                require((value >= 0x0) && (value <= 0xF))
                field = value
            }

        var sequenceBit: Boolean = false

        var reliabilityBit: Boolean = false

        var commandID: CommandID? = null

        var sourceAddress: Int = 0
            set(value) {
                require((value >= 0x0) && (value <= 0xF))
                field = value
            }

        var destinationAddress: Int = 0
            set(value) {
                require((value >= 0x0) && (value <= 0xF))
                field = value
            }

        var nonce = ByteArray(NUM_NONCE_BYTES)
            set(value) {
                require(value.size == NUM_NONCE_BYTES)
                field = value
            }

        // Payload

        var payload: ArrayList<Byte> = ArrayList<Byte>(0)
            set(value) {
                require(value.size <= 65535)
                field = value
            }

        // MAC

        var machineAuthenticationCode = ByteArray(NUM_MAC_BYTES)
            set(value) {
                require(value.size == NUM_MAC_BYTES)
                field = value
            }

        // Implementing custom equals operator, since otherwise,
        // the nonce and machineAuthenticationCode arrays are
        // not compared correctly.
        override fun equals(other: Any?) =
            (this === other) ||
                (other is Packet) &&
                (majorVersion == other.majorVersion) &&
                (minorVersion == other.minorVersion) &&
                (sequenceBit == other.sequenceBit) &&
                (reliabilityBit == other.reliabilityBit) &&
                (commandID == other.commandID) &&
                (sourceAddress == other.sourceAddress) &&
                (destinationAddress == other.destinationAddress) &&
                (payload == other.payload) &&
                (nonce contentEquals other.nonce) &&
                (machineAuthenticationCode contentEquals other.machineAuthenticationCode)

        fun toByteList(withMAC: Boolean = true, withPayload: Boolean = true): ArrayList<Byte> {
            val bytes = ArrayList<Byte>(PACKET_HEADER_SIZE)

            bytes.add(((majorVersion shl 4) or minorVersion).toByte())
            bytes.add(((if (sequenceBit) 0x80 else 0)
                or (if (reliabilityBit) 0x20 else 0)
                or commandID!!.id).toByte())
            bytes.add((payload.size and 0xFF).toByte())
            bytes.add(((payload.size shr 8) and 0xFF).toByte())
            bytes.add(((sourceAddress shl 4) or destinationAddress).toByte())

            bytes.addAll(nonce.asSequence())

            if (withPayload)
                bytes.addAll(payload)

            if (withMAC)
                bytes.addAll(machineAuthenticationCode.asSequence())

            return bytes
        }

        // CRC16

        fun computeCRC16Payload() {
            payload = byteArrayListOfInts(0, 0)
            val headerData = toByteList(withMAC = false, withPayload = false)
            val calculatedCRC16 = calculateCRC16MCRF4XX(headerData)
            payload[0] = (calculatedCRC16 and 0xFF).toByte()
            payload[1] = ((calculatedCRC16 shr 8) and 0xFF).toByte()
        }

        fun verifyCRC16Payload(): Boolean {
            require(payload.size == 2)
            val headerData = toByteList(withMAC = false, withPayload = false)
            val calculatedCRC16 = calculateCRC16MCRF4XX(headerData)
            return (payload[0] == (calculatedCRC16 and 0xFF).toByte()) &&
                (payload[1] == ((calculatedCRC16 shr 8) and 0xFF).toByte())
        }

        // Authentication

        fun authenticate(cipher: Cipher) {
            machineAuthenticationCode = calculateMAC(cipher)
        }

        fun verifyAuthentication(cipher: Cipher): Boolean = calculateMAC(cipher).contentEquals(machineAuthenticationCode)

        // This computes the MAC using Two-Fish and a modified RFC3610 CCM authentication
        // process. See "Packet authentication" in combo-comm-spec.adoc for details.
        private fun calculateMAC(cipher: Cipher): ByteArray {
            val MAC = ByteArray(NUM_MAC_BYTES)
            var block = ByteArray(CIPHER_BLOCK_SIZE)

            // Set up B_0.
            block[0] = 0x79
            for (i in 0 until NUM_NONCE_BYTES) block[i + 1] = nonce[i]
            block[14] = 0x00
            block[15] = 0x00

            // Produce X_1 out of B_0.
            block = cipher.encrypt(block)

            val packetData = toByteList(withMAC = false, withPayload = true)
            val numDataBlocks = packetData.size / CIPHER_BLOCK_SIZE

            // Repeatedly produce X_i+1 out of X_i and B_i.
            // X_i is the current block value, B_i is the
            // data from packetData that is being accessed
            // inside the loop.
            for (dataBlockNr in 0 until numDataBlocks) {
                for (i in 0 until CIPHER_BLOCK_SIZE) {
                    val a: Int = block[i].toPosInt()
                    val b: Int = packetData[dataBlockNr * CIPHER_BLOCK_SIZE + i].toPosInt()
                    block[i] = (a xor b).toByte()
                }

                block = cipher.encrypt(block)
            }

            // Handle the last block, and apply padding if needed.
            val remainingDataBytes = packetData.size - numDataBlocks * CIPHER_BLOCK_SIZE
            if (remainingDataBytes > 0) {
                for (i in 0 until remainingDataBytes) {
                    val a: Int = block[i].toPosInt()
                    val b: Int = packetData[packetData.size - remainingDataBytes + i].toPosInt()
                    block[i] = (a xor b).toByte()
                }

                val paddingValue = 16 - remainingDataBytes

                for (i in remainingDataBytes until CIPHER_BLOCK_SIZE)
                    block[i] = ((block[i].toPosInt()) xor paddingValue).toByte()

                block = cipher.encrypt(block)
            }

            // Here, the non-standard portion of the authentication starts.

            // Produce the "U" value.
            for (i in 0 until NUM_MAC_BYTES)
                MAC[i] = block[i]

            // Produce the new B_0.
            block[0] = 0x41
            for (i in 0 until NUM_NONCE_BYTES) block[i + 1] = nonce[i]
            block[14] = 0x00
            block[15] = 0x00

            // Produce X_1 out of the new B_0.
            block = cipher.encrypt(block)

            // Compute the final MAC out of U and the
            // first 8 bytes of X_1 XORed together.
            for (i in 0 until NUM_MAC_BYTES)
                MAC[i] = ((MAC[i].toPosInt()) xor (block[i].toPosInt())).toByte()

            return MAC
        }

        override fun hashCode(): Int {
            var result = majorVersion
            result = 31 * result + minorVersion
            result = 31 * result + sequenceBit.hashCode()
            result = 31 * result + reliabilityBit.hashCode()
            result = 31 * result + commandID!!.id
            result = 31 * result + sourceAddress
            result = 31 * result + destinationAddress
            result = 31 * result + nonce.contentHashCode()
            result = 31 * result + payload.hashCode()
            result = 31 * result + machineAuthenticationCode.contentHashCode()
            return result
        }
    }

    class State {
        /***********
         * Ciphers *
         ***********/

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

    private fun incrementTxNonce(state: State) {
        var carry = true

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
    private fun createCRCPacket(commandID: CommandID): Packet = Packet().apply {
        sequenceBit = false
        reliabilityBit = false
        sourceAddress = 0xF
        destinationAddress = 0x0
        nonce = ByteArray(NUM_NONCE_BYTES) { 0x00 }
        machineAuthenticationCode = ByteArray(NUM_MAC_BYTES) { 0x00 }
        this.commandID = commandID
        computeCRC16Payload()
        logger.log(LogLevel.DEBUG) {
            "Computed CRC16 payload 0x%02X%02X".format(this.payload[1].toPosInt(), this.payload[0].toPosInt())
        }
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
    ): Packet {
        require(state.keyResponseSourceAddress != null)
        require(state.keyResponseDestinationAddress != null)

        val packet = Packet().apply {
            sourceAddress = state.keyResponseSourceAddress!!
            destinationAddress = state.keyResponseDestinationAddress!!
        }

        packet.commandID = commandID
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
    fun createRequestPairingConnectionPacket(): Packet {
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
    fun createRequestKeysPacket(): Packet {
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
    fun createGetAvailableKeysPacket(): Packet {
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
    fun createRequestIDPacket(state: State, bluetoothFriendlyName: String): Packet {
        // The nonce is set to 1 in the REQUEST_ID packet, and
        // get incremented from that moment onwards.
        state.currentTxNonce = byteArrayOfInts(
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )

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
    fun createRequestRegularConnectionPacket(state: State): Packet {
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
    fun createAckResponsePacket(state: State, sequenceBit: Boolean): Packet {
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
    fun createDataPacket(state: State, reliabilityBit: Boolean, payload: ArrayList<Byte>): Packet {
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
     * @param state Current transport layer state. Will be updated.
     * @param weakCipher Cipher used for decrypting the pump-client
     *        and client-pump keys.
     * @param packet The packet that came from the Combo.
     */
    fun parseKeyResponsePacket(state: State, weakCipher: Cipher, packet: Packet) {
        require(packet.commandID == CommandID.KEY_RESPONSE)
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
    fun parseIDResponsePacket(state: State, packet: Packet): ComboIDs {
        require(packet.commandID == CommandID.ID_RESPONSE)
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
    private fun parseErrorResponsePacket(state: State, packet: Packet): Int? =
        state.pumpClientCipher?.let { cipher ->
            require(packet.commandID == CommandID.ERROR_RESPONSE)
            require(packet.payload.size == 1)
            require(packet.verifyAuthentication(cipher))

            packet.payload[0].toInt()
        }
}

fun List<Byte>.toTransportLayerPacket(): TransportLayer.Packet {
    return TransportLayer.Packet(this)
}
