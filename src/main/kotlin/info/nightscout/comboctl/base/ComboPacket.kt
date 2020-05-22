package info.nightscout.comboctl.base

// Packet structure:
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

public const val NUM_NONCE_BYTES = 13
public const val NUM_MAC_BYTES = 8

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
class ComboPacket() {

    constructor(bytes: List<Byte>) : this() {
        require(bytes.size >= (PACKET_HEADER_SIZE + NUM_MAC_BYTES))

        majorVersion = (bytes[VERSION_BYTE_OFFSET].toInt() shr 4) and 0xF
        minorVersion = bytes[VERSION_BYTE_OFFSET].toInt() and 0xF
        sequenceBit = (bytes[SEQ_REL_CMD_BYTE_OFFSET].toInt() and 0x80) != 0
        reliabilityBit = (bytes[SEQ_REL_CMD_BYTE_OFFSET].toInt() and 0x20) != 0
        commandID = bytes[SEQ_REL_CMD_BYTE_OFFSET].toInt() and 0x1F

        sourceAddress = (bytes[ADDRESS_BYTE_OFFSET].toInt() shr 4) and 0xF
        destinationAddress = bytes[ADDRESS_BYTE_OFFSET].toInt() and 0xF

        val payloadSize = (bytes[PAYLOAD_LENGTH_BYTES_OFFSET + 1].toInt() shl 8) or bytes[PAYLOAD_LENGTH_BYTES_OFFSET + 0].toInt()
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
    var majorVersion: Int = 0
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

    var commandID: Int = 0
        set(value) {
            require((value >= 0) && (value <= 31))
            field = value
        }

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
            (other is ComboPacket) &&
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
            or commandID).toByte())
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
                var a: Int = block[i].toPosInt()
                var b: Int = packetData[dataBlockNr * CIPHER_BLOCK_SIZE + i].toPosInt()
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
        result = 31 * result + commandID
        result = 31 * result + sourceAddress
        result = 31 * result + destinationAddress
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + payload.hashCode()
        result = 31 * result + machineAuthenticationCode.contentHashCode()
        return result
    }
}

fun List<Byte>.toComboPacket(): ComboPacket {
    return ComboPacket(this)
}
