package info.nightscout.comboctl.base

import kotlinx.coroutines.CancellationException

private val logger = Logger.get("TransportLayer")

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

// Utility function to be able to throw an exception in case of
// an invalid command ID even in the constructor below.
private fun checkedGetCommandID(value: Int, bytes: List<Byte>): TransportLayer.CommandID =
    TransportLayer.CommandID.fromInt(value) ?: throw TransportLayer.InvalidCommandIDException(value, bytes)

/**
 * Maximum allowed size for transport layer packet payloads, in bytes.
 */
const val MAX_VALID_TL_PAYLOAD_SIZE = 65535

/**
 * Combo transport layer (TL) communication implementation.
 *
 * This provides all the necessary primitives and processes to communicate
 * at the transport layer. The TransportLayer class itself contains internal
 * temporary states that only need to exist for the duration of a communication
 * session with the Combo. In addition, external states exist that must be stored
 * in a persistent fashion. The [PersistentPumpStateStore] interface is used
 * for that purpose.
 *
 * Callers create an instance of TransportLayer every time a new connection to
 * the Combo is established, and destroyed afterwards (it is not reused). Callers
 * also must provide the constructor an implementation of [PersistentPumpStateStore].
 *
 * Packets created by this class follow a specific order when pairing and when
 * initiating a regular connection. This is important to keep in mind since the
 * packet creation and parsing functions may update internal and external state.
 * If the order from the spec is followed, state updates will happen as intended.
 *
 * This class is typically not directly touched by users. Instead, it is typically
 * used by higher-level code that handles the pairing and regular connection processes.
 */
class TransportLayer(private val persistentPumpStateStore: PersistentPumpStateStore) {
    /**
     * Current sequence flag, used in reliable data packets.
     *
     * This flag gets toggled every time a reliable packet is sent.
     */
    private var currentSequenceFlag = false

    /**
     * Weak cipher generated from a pairing PIN.
     *
     * This is only needed for verifying and processing the
     * KEY_RESPONSE packet.
     */
    private var weakCipher: Cipher? = null

    private var cachedPumpPairingData: PumpPairingData

    init {
        if (persistentPumpStateStore.isValid())
            cachedPumpPairingData = persistentPumpStateStore.retrievePumpPairingData()
        else
            cachedPumpPairingData = PumpPairingData(
                Cipher(ByteArray(CIPHER_KEY_SIZE)),
                Cipher(ByteArray(CIPHER_KEY_SIZE)),
                0x00.toByte()
            )
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
        ACK_RESPONSE(0x05), DATA(0x03), ERROR_RESPONSE(0x06);

        companion object {
            private val values = CommandID.values()
            /**
             * Converts an int to a command ID.
             *
             * @return CommandID, or null if the int is not a valid ID.
             */
            fun fromInt(value: Int) = values.firstOrNull { it.id == value }
        }
    }

    /**
     * Base class for transport layer exceptions.
     *
     * @param message The detail message.
     */
    open class ExceptionBase(message: String) : ComboException(message)

    /**
     * Exception thrown when a transport layer packet arrives with an
     * invalid application layer command ID.
     *
     * The packet is provided as bytes list since the Packet parses
     * will refuse to parse a packet with an unknown ID. That's because
     * an unknown ID may indicate that this is actually not packet data.
     *
     * @property commandID The invalid application layer command ID.
     * @property packetBytes The bytes forming the invalid packet.
     */
    class InvalidCommandIDException(
        val commandID: Int,
        val packetBytes: List<Byte>
    ) : ExceptionBase("Invalid/unknown transport layer packet command ID $commandID")

    /**
     * Exception thrown when a different transport layer packet was
     * expected than the one that arrived.
     *
     * More precisely, the arrived packet's command ID is not the one that was expected.
     *
     * @property packet Transport layer packet that arrived.
     * @property expectedCommandID The command ID that was expected in the packet.
     */
    class IncorrectPacketException(
        val packet: Packet,
        val expectedCommandID: CommandID
    ) : ExceptionBase("Incorrect packet: expected ${expectedCommandID.name} packet, got ${packet.commandID.name} one")

    /**
     * Exception thrown when a packet fails verification.
     *
     * @property packet Transport layer packet that was found to be faulty/corrupt.
     */
    class PacketVerificationException(
        val packet: Packet
    ) : ExceptionBase("Packet verification failed")

    /**
     * Exception thrown when something is wrong with a transport layer packet's payload.
     *
     * @property packet Transport layer packet with the invalid payload.
     * @property message Detail message.
     */
    class InvalidPayloadException(
        val packet: Packet,
        message: String
    ) : ExceptionBase(message)

    /**
     * Exception thrown when the Combo sends an ERROR_RESPONSE packet.
     *
     * These packets notify about errors in the communication between client and Combo
     * at the transport layer.
     *
     * @property packet Transport layer packet with the error information.
     * @property errorID ID of the error.
     */
    class ErrorResponseException(
        val packet: Packet,
        val errorID: Int
    ) : ExceptionBase("Error response by the Combo; error ID = 0x${errorID.toString(16)}")

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
     *
     * NOTE: Currently, it is not clear what "address" means. However, these values
     * are checked by the Combo, so they must be set to valid values.
     *
     * @property commandID The command ID of this packet.
     * @property version Byte containing version numbers. The upper 4 bit contain the
     *           major, the lower 4 bit the minor version number.
     *           In all observed packets, this was set to 0x10.
     * @property sequenceBit The packet's sequence bit.
     * @property reliabilityBit The packet's reliability bit.
     * @property address Address byte. The upper 4 bit contain the source, the lower
     *           4 bit the destionation address.
     * @property payload The packet's actual payload. Max valid size is 65535 bytes.
     * @property machineAuthenticationCode Machine authentication code. Must be
     *           (re)calculated using [authenticate] if the packet uses MACs and
     *           it is being set up or its payload was modified.
     * @throws IllegalArgumentException if the payload size exceeds
     *         [MAX_VALID_TL_PAYLOAD_SIZE].
     */
    data class Packet(
        val commandID: CommandID,
        val version: Byte = 0x10,
        val sequenceBit: Boolean = false,
        val reliabilityBit: Boolean = false,
        val address: Byte = 0,
        val nonce: Nonce = NullNonce,
        var payload: ArrayList<Byte> = ArrayList(0),
        var machineAuthenticationCode: MachineAuthCode = NullMachineAuthCode
    ) {
        init {
            if (payload.size > MAX_VALID_TL_PAYLOAD_SIZE) {
                throw IllegalArgumentException(
                    "Payload size ${payload.size} exceeds allowed maximum of $MAX_VALID_TL_PAYLOAD_SIZE bytes"
                )
            }
        }

        // This is a trick to avoid having to retrieve the payload size from
        // the bytes more than once. The public variant of this constructor
        // extracts the size, and then calls this one, passing the size as
        // the second argument.
        private constructor(bytes: List<Byte>, payloadSize: Int) : this(
            commandID = checkedGetCommandID(bytes[SEQ_REL_CMD_BYTE_OFFSET].toPosInt() and 0x1F, bytes),
            version = bytes[VERSION_BYTE_OFFSET],
            sequenceBit = (bytes[SEQ_REL_CMD_BYTE_OFFSET].toPosInt() and 0x80) != 0,
            reliabilityBit = (bytes[SEQ_REL_CMD_BYTE_OFFSET].toPosInt() and 0x20) != 0,
            address = bytes[ADDRESS_BYTE_OFFSET],
            nonce = Nonce(bytes.subList(NONCE_BYTES_OFFSET, NONCE_BYTES_OFFSET + NUM_NONCE_BYTES)),
            payload = ArrayList<Byte>(bytes.subList(PAYLOAD_BYTES_OFFSET, PAYLOAD_BYTES_OFFSET + payloadSize)),
            machineAuthenticationCode = MachineAuthCode(
                bytes.subList(PAYLOAD_BYTES_OFFSET + payloadSize, PAYLOAD_BYTES_OFFSET + payloadSize + NUM_MAC_BYTES)
            )
        )

        /**
         * Deserializes a packet from a binary representation.
         *
         * This is needed for parsing packets coming from the Combo. However,
         * packets coming from the Combo are framed, so it is important to
         * make sure that the packet data was parsed using ComboFrameParser
         * first. In other words, don't pass data coming through the Combo
         * RFCOMM channel to this constructor directly.
         *
         * @param bytes Packet data to parse.
         * @throws TransportLayer.InvalidCommandIDException if the packet data
         *         contains a command ID that is unknown/unsupported.
         */
        constructor(bytes: List<Byte>) :
            this(bytes, (bytes[PAYLOAD_LENGTH_BYTES_OFFSET + 1].toPosInt() shl 8) or bytes[PAYLOAD_LENGTH_BYTES_OFFSET + 0].toPosInt())

        /**
         * Serializes a packet to a binary representation suitable for framing and sending.
         *
         * This is needed for sending packets to the Combo. This function produces
         * data that can be framed using [toComboFrame]. The resulting framed
         * data can then be transmitted to the Combo through the RFCOMM channel.
         *
         * The withMAC and withPayload arguments exist mainly to be able to
         * produce packet data that is suitable for generating CRCs and MACs.
         *
         * @param withMAC Include the MAC bytes into the packet data.
         * @param withPayload Include the payload bytes into the packet data.
         * @return The serialized packet data.
         */
        fun toByteList(withMAC: Boolean = true, withPayload: Boolean = true): ArrayList<Byte> {
            val bytes = ArrayList<Byte>(PACKET_HEADER_SIZE)

            bytes.add(version)
            bytes.add(((if (sequenceBit) 0x80 else 0)
                or (if (reliabilityBit) 0x20 else 0)
                or commandID.id).toByte())
            bytes.add((payload.size and 0xFF).toByte())
            bytes.add(((payload.size shr 8) and 0xFF).toByte())
            bytes.add(address)

            bytes.addAll(nonce.asSequence())

            if (withPayload)
                bytes.addAll(payload)

            if (withMAC)
                bytes.addAll(machineAuthenticationCode.asSequence())

            return bytes
        }

        /**
         * Computes a 2-byte payload that is the CRC-16-MCRF4XX checksum of the packet header.
         *
         * This erases any previously existing payload
         * and resets the payload size to 2 bytes.
         */
        fun computeCRC16Payload() {
            payload = byteArrayListOfInts(0, 0)
            val headerData = toByteList(withMAC = false, withPayload = false)
            val calculatedCRC16 = calculateCRC16MCRF4XX(headerData)
            payload[0] = (calculatedCRC16 and 0xFF).toByte()
            payload[1] = ((calculatedCRC16 shr 8) and 0xFF).toByte()
        }

        /**
         * Verifies the packet header data by computing its CRC-16-MCRF4XX checksum and
         * comparing it against the one present as the packet's 2-byte payload.
         *
         * @return true if the CRC check succeeds, false if it fails (indicating data corruption).
         * @throws InvalidPayloadException if the payload is not made of 2 bytes.
         */
        fun verifyCRC16Payload(): Boolean {
            if (payload.size != 2) {
                throw InvalidPayloadException(
                    this,
                    "Invalid CRC16 payload: CRC16 payload has 2 bytes, this packet has ${payload.size}"
                )
            }
            val headerData = toByteList(withMAC = false, withPayload = false)
            val calculatedCRC16 = calculateCRC16MCRF4XX(headerData)
            return (payload[0] == (calculatedCRC16 and 0xFF).toByte()) &&
                (payload[1] == ((calculatedCRC16 shr 8) and 0xFF).toByte())
        }

        /**
         * Authenticates the packet using the given cipher.
         *
         * Authentication means that a MAC is generated for this packet and stored
         * in the packet's last 8 bytes. The MAC is generated using the given cipher.
         *
         * @param cipher Cipher to use for generating the MAC.
         */
        fun authenticate(cipher: Cipher) {
            machineAuthenticationCode = calculateMAC(cipher)
        }

        /**
         * Verify the authenticity of the packet using the MAC.
         *
         * @param cipher Cipher to use for the verification.
         * @return true if the packet is found to be valid, false otherwise
         *         (indicating data corruption).
         */
        fun verifyAuthentication(cipher: Cipher): Boolean = calculateMAC(cipher) == machineAuthenticationCode

        // This computes the MAC using Two-Fish and a modified RFC3610 CCM authentication
        // process. See "Packet authentication" in combo-comm-spec.adoc for details.
        private fun calculateMAC(cipher: Cipher): MachineAuthCode {
            val macBytes = ArrayList<Byte>(NUM_MAC_BYTES)
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
                macBytes.add(block[i])

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
                macBytes[i] = ((macBytes[i].toPosInt()) xor (block[i].toPosInt())).toByte()

            return MachineAuthCode(macBytes)
        }

        override fun toString(): String {
            return "version: ${version.toHexString(2)}" +
                "  command ID: ${commandID.name}" +
                "  sequence bit: $sequenceBit" +
                "  reliability bit: $reliabilityBit" +
                "  address: ${address.toHexString(2)}" +
                "  nonce: $nonce" +
                "  MAC: $machineAuthenticationCode" +
                "  payload: ${payload.size} byte(s): ${payload.toHexString()}"
        }
    }

    // Base function for generating CRC-verified packets.
    // These packets only have the CRC itself as payload, and
    // are only used during the pairing process.
    private fun createCRCPacket(commandID: CommandID): Packet {
        val packet = Packet(
            commandID = commandID,
            sequenceBit = false,
            reliabilityBit = false,
            address = 0xF0.toByte(),
            nonce = NullNonce,
            machineAuthenticationCode = NullMachineAuthCode
        )
        packet.computeCRC16Payload()
        logger(LogLevel.DEBUG) {
            val crc16 = (packet.payload[1].toPosInt() shl 8) or packet.payload[0].toPosInt()
            "Computed CRC16 payload ${crc16.toHexString(4)}"
        }
        return packet
    }

    // Base function for generating MAC-authenticated packets. This
    // function assumes that the TL_KEY_RESPONSE packet has already
    // been received, because only then will keyResponseAddress and
    // clientPumpCipher.key be set to valid values.
    private fun createMACAuthenticatedPacket(
        commandID: CommandID,
        payload: ArrayList<Byte> = arrayListOf(),
        sequenceBit: Boolean = false,
        reliabilityBit: Boolean = false
    ): Packet {
        if (!persistentPumpStateStore.isValid())
            throw IllegalStateException()

        val currentTxNonce = persistentPumpStateStore.currentTxNonce

        val packet = Packet(
            commandID = commandID,
            address = cachedPumpPairingData.keyResponseAddress,
            sequenceBit = sequenceBit,
            reliabilityBit = reliabilityBit,
            payload = payload,
            nonce = currentTxNonce
        )

        persistentPumpStateStore.currentTxNonce = currentTxNonce.getIncrementedNonce()

        packet.authenticate(cachedPumpPairingData.clientPumpCipher)

        return packet
    }

    /**
     * Called when the user enters the 10-digit PIN during the pairing process.
     *
     * Outside of the pairing process, this does not need to be called.
     * It sets up the internal weak cipher that is needed for verifying
     * and parsing the KEY_RESPONSE packet.
     *
     * Typically, this is called once the REQUEST_KEYS packet was sent
     * to the Combo, because it is then when it will show the generated
     * PIN on its display for the user to read.
     *
     * @param pairingPIN 10-digit PIN to use during the pairing process.
     */
    fun usePairingPIN(pairingPIN: PairingPIN) {
        weakCipher = Cipher(generateWeakKeyFromPIN(pairingPIN))
        logger(LogLevel.DEBUG) {
            "Generated weak cipher key ${weakCipher!!.key.toHexString()} out of pairing PIN $pairingPIN"
        }
    }

    /**
     * Checks whether or not the transport layer's [PersistentState] is valid.
     */
    fun persistentStateIsValid() = persistentPumpStateStore.isValid()

    /**
     * Generic incoming packet verification function.
     *
     * This is the function intended for external callers to verify
     * transport layer packets. It is "generic" in the sense that the
     * caller does not have to specifically use CRC or MAC functions
     * for verifications; this function picks the correct function
     * based on the packet's command ID.
     *
     * Note that this is intended to be used for incoming packets
     * _only_. (It does not make much sense to verify outgoing packets,
     * since these are anyway generated by this same class.)
     *
     * @param packet Transport layer packet to verify.
     * @return true if the packet is found to be valid, false otherwise
     *         (indicating data corruption).
     * @throws IllegalArgumentException if an outgoing packet was given.
     * @throws IllegalStateException if the packet requires the weak
     *         cipher and [usePairingPIN] was not called earlier, or if
     *         the packet requires the pump-client cipher and the
     *         KEY_RESPONSE packet was not parsed earlier.
     */
    fun verifyIncomingPacket(packet: Packet): Boolean =
        when (packet.commandID) {
            // These are _outgoing_ packets. If we reach this
            // branch, the call is wrong.
            CommandID.REQUEST_PAIRING_CONNECTION,
            CommandID.REQUEST_KEYS,
            CommandID.GET_AVAILABLE_KEYS,
            CommandID.REQUEST_ID,
            CommandID.REQUEST_REGULAR_CONNECTION ->
                throw IllegalArgumentException()

            // Packets that use no (known) verification
            CommandID.PAIRING_CONNECTION_REQUEST_ACCEPTED,
            CommandID.DISCONNECT -> true

            // Packets that use MAC based verification with the weak cipher
            CommandID.KEY_RESPONSE -> {
                if (weakCipher == null)
                    throw IllegalStateException("Cannot verify KEY_RESPONSE packet without a weak cipher")
                packet.verifyAuthentication(weakCipher!!)
            }

            // Packets that use MAC based verification with the pump-client cipher
            CommandID.ID_RESPONSE,
            CommandID.REGULAR_CONNECTION_REQUEST_ACCEPTED,
            CommandID.ACK_RESPONSE,
            CommandID.DATA,
            CommandID.ERROR_RESPONSE -> {
                if (!persistentPumpStateStore.isValid())
                    throw IllegalStateException("Cannot verify ${packet.commandID} packet without a pump-client cipher")
                packet.verifyAuthentication(cachedPumpPairingData.pumpClientCipher)
            }
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
     * @param bluetoothFriendlyName Bluetooth friendly name to use for this packet.
     *        Maximum length is 13 characters.
     *        See the Bluetooth specification, Vol. 3 part C section 3.2.2
     *        for details about Bluetooth friendly names.
     * @return The produced packet.
     */
    fun createRequestIDPacket(bluetoothFriendlyName: String): Packet {
        // The nonce is set to 1 in the REQUEST_ID packet, and
        // gets incremented from that moment onwards.
        persistentPumpStateStore.currentTxNonce = Nonce(byteArrayListOfInts(
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        ))

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

        return createMACAuthenticatedPacket(CommandID.REQUEST_ID, payload)
    }

    /**
     * Creates a REQUEST_REGULAR_CONNECTION packet.
     *
     * In spite of initiating a "regular" connection, it is also used once
     * in the latter phases of the pairing process. See the combo-comm-spec.adoc
     * file for details.
     *
     * @return The produced packet.
     */
    fun createRequestRegularConnectionPacket(): Packet {
        // NOTE: Technically, this is not entirely correct, since currentSequenceFlag
        // should be reset when the REGULAR_CONNECTION_REQUEST_ACCEPTED packet
        // arrives, not when this is sent. However, in practice, this makes no
        // difference, since this must be called in order to initiate a regular
        // connection, and REGULAR_CONNECTION_REQUEST_ACCEPTED is always the
        // response to this packet (unless an error occurs), so we might as well
        // reset the currentSequenceFlag here.
        currentSequenceFlag = false
        return createMACAuthenticatedPacket(CommandID.REQUEST_REGULAR_CONNECTION)
    }

    /**
     * Creates an ACK_RESPONSE packet.
     *
     * See the combo-comm-spec.adoc file for details about this packet.
     *
     * @param sequenceBit Sequence bit to set in the ACK_RESPONSE packet.
     * @return The produced packet.
     */
    fun createAckResponsePacket(sequenceBit: Boolean): Packet {
        return createMACAuthenticatedPacket(CommandID.ACK_RESPONSE, sequenceBit = sequenceBit)
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
     * @param reliabilityBit Reliability bit to set in the DATA packet.
     * @param payload Payload to assign to the DATA packet.
     * @return The produced packet.
     */
    fun createDataPacket(reliabilityBit: Boolean, payload: ArrayList<Byte>): Packet {
        val sequenceBit: Boolean

        if (reliabilityBit) {
            // The sequence flag needs to be flipped ifthe reliabilityBit
            // is enabled. See the "Sequence and data reliability bits"
            // section in the spec for details.
            sequenceBit = currentSequenceFlag
            currentSequenceFlag = !currentSequenceFlag
        } else {
            // If the reliablity bit is cleared, the sequence bit is
            // always cleared as well. Note that this does not alter
            // currentSequenceFlag.
            sequenceBit = false
        }

        return createMACAuthenticatedPacket(
            CommandID.DATA,
            payload = payload,
            sequenceBit = sequenceBit,
            reliabilityBit = reliabilityBit
        )
    }

    /**
     * Parses a KEY_RESPONSE packet.
     *
     * The Combo sends this during the pairing process. It contains
     * client-pump and pump-client keys, encrypted with the weak key.
     * This will modify the client-pump and pump-client keys in the
     * specified state as well as its keyResponseAddress field.
     *
     * [usePairingPIN] must have been called prior to calling this,
     * otherwise there will be no weak cipher.
     *
     * Note that if the packet verification fails, and
     * PacketVerificationException is thrown, it might be because
     * the user entered the incorrect PIN. It is then valid to
     * just call [usePairingPIN] again. This will update the internal
     * weak cipher. Then, [parseKeyResponsePacket] can be called again,
     * and the updated cipher will be used. If the correct PIN was
     * entered, and the packet was not corrupted somehow during
     * transit, then verification succeeds.
     *
     * @param packet The packet that came from the Combo.
     * @throws IllegalStateException if this is called before a PIN
     *         was set by calling [usePairingPIN].
     * @throws IncorrectPacketException if packet is not a
     *         KEY_RESPONSE packet.
     * @throws InvalidPayloadException if the payload size is not
     *         the one expected from KEY_RESPONSE packets.
     * @throws PacketVerificationException if the packet
     *         verification fails.
     * @throws PumpStateStoreStorageException if placing the
     *         parsed keys and the key response address into
     *         the persistent pump state store fails.
     */
    fun parseKeyResponsePacket(packet: Packet) {
        if (weakCipher == null)
            throw IllegalStateException()

        if (packet.commandID != CommandID.KEY_RESPONSE)
            throw IncorrectPacketException(packet, CommandID.KEY_RESPONSE)
        if (packet.payload.size != (CIPHER_KEY_SIZE * 2))
            throw InvalidPayloadException(packet, "Expected ${CIPHER_KEY_SIZE * 2} bytes, got ${packet.payload.size}")
        if (!packet.verifyAuthentication(weakCipher!!))
            throw PacketVerificationException(packet)

        val encryptedPCKey = ByteArray(CIPHER_KEY_SIZE)
        val encryptedCPKey = ByteArray(CIPHER_KEY_SIZE)

        for (i in 0 until CIPHER_KEY_SIZE) {
            encryptedPCKey[i] = packet.payload[i + 0]
            encryptedCPKey[i] = packet.payload[i + CIPHER_KEY_SIZE]
        }

        val pumpClientCipher = Cipher(weakCipher!!.decrypt(encryptedPCKey))
        val clientPumpCipher = Cipher(weakCipher!!.decrypt(encryptedCPKey))

        // Note: Source and destination addresses are reversed,
        // since they are set from the perspective of the pump.
        val addressInt = packet.address.toPosInt()
        val sourceAddress = addressInt and 0xF
        val destinationAddress = (addressInt shr 4) and 0xF
        val keyResponseAddress = ((sourceAddress shl 4) or destinationAddress).toByte()

        cachedPumpPairingData = PumpPairingData(
            pumpClientCipher = pumpClientCipher,
            clientPumpCipher = clientPumpCipher,
            keyResponseAddress = keyResponseAddress
        )

        logger(LogLevel.DEBUG) {
            "Address: ${cachedPumpPairingData.keyResponseAddress.toHexString(2)}" +
            "  decrypted client->pump key: ${cachedPumpPairingData.clientPumpCipher.key.toHexString()}" +
            "  decrypted pump->client key: ${cachedPumpPairingData.pumpClientCipher.key.toHexString()}"
        }

        // Catch any exception (other than CancellationException) and
        // wrap it into a PumpStateStoreStorageException instance.
        try {
            persistentPumpStateStore.storePumpPairingData(cachedPumpPairingData)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw PumpStateStoreStorageException(e)
        }
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
     * @param packet The packet that came from the Combo.
     * @return The parsed IDs.
     */
    fun parseIDResponsePacket(packet: Packet): ComboIDs {
        if (packet.commandID != CommandID.ID_RESPONSE)
            throw IncorrectPacketException(packet, CommandID.ID_RESPONSE)
        if (packet.payload.size != 17)
            throw InvalidPayloadException(packet, "Expected 17 bytes, got ${packet.payload.size}")
        if (!persistentPumpStateStore.isValid())
            throw IllegalStateException()

        if (!packet.verifyAuthentication(cachedPumpPairingData.pumpClientCipher))
            throw PacketVerificationException(packet)

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
     * @param packet The packet that came from the Combo.
     * @return The parsed error ID.
     */
    fun parseErrorResponsePacket(packet: Packet): Int {
        if (packet.commandID != CommandID.ERROR_RESPONSE)
            throw IncorrectPacketException(packet, CommandID.ERROR_RESPONSE)
        if (packet.payload.size != 1)
            throw InvalidPayloadException(packet, "Expected 1 byte, got ${packet.payload.size}")
        if (!persistentPumpStateStore.isValid())
            throw IllegalStateException()

        if (!packet.verifyAuthentication(cachedPumpPairingData.pumpClientCipher))
            throw PacketVerificationException(packet)

        return packet.payload[0].toInt()
    }
}

/**
 * Produces a TransportLayer.Packet out of given data.
 *
 * This is just a convenience extension function that internally
 * creates a TransportLayer.Packet instance and passes the data
 * to its constructor.
 *
 * See the TransportLayer.Packet constructor for details.
 */
fun List<Byte>.toTransportLayerPacket(): TransportLayer.Packet {
    return TransportLayer.Packet(this)
}
