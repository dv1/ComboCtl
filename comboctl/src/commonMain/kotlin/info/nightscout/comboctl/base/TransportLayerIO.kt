package info.nightscout.comboctl.base

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val logger = Logger.get("TransportLayerIO")

/* Internal offset and sizes for packet IO. */

private const val PACKET_HEADER_SIZE = 1 + 1 + 2 + 1 + NUM_NONCE_BYTES

private const val VERSION_BYTE_OFFSET = 0
private const val SEQ_REL_CMD_BYTE_OFFSET = 1
private const val PAYLOAD_LENGTH_BYTES_OFFSET = 2
private const val ADDRESS_BYTE_OFFSET = 4
private const val NONCE_BYTES_OFFSET = 5
private const val PAYLOAD_BYTES_OFFSET = NONCE_BYTES_OFFSET + NUM_NONCE_BYTES

// Utility function to be able to throw an exception in case of
// an invalid command ID in the Packet constructor below.
private fun checkedGetCommand(value: Int, bytes: List<Byte>): TransportLayerIO.Command =
    TransportLayerIO.Command.fromInt(value) ?: throw TransportLayerIO.InvalidCommandIDException(value, bytes)

/**
 * Callback used during pairing for asking the user for the 10-digit PIN.
 *
 * This is passed to [TransportLayerIO.startIO] when pairing.
 *
 * [previousAttemptFailed] is useful for showing in a GUI that the
 * previously entered PIN seems to be wrong and that the user needs
 * to try again.
 *
 * If the user wants to cancel the pairing instead of entering the
 * PIN, [TransportLayerIO.PairingAbortedException] must be thrown by
 * the callback. See [TransportLayerIO] for information about exceptions
 * that get thrown from inside the background worker.
 *
 * @param previousAttemptFailed true if the user was already asked for
 *        the PIN and the KEY_RESPONSE authentication failed.
 * @throws TransportLayerIO.PairingAbortedException if the user cancels
 *         the operation.
 */
typealias PairingPINCallback = suspend (previousAttemptFailed: Boolean) -> PairingPIN

/**
 * Maximum allowed size for transport layer packet payloads, in bytes.
 */
const val MAX_VALID_TL_PAYLOAD_SIZE = 65535

/**
 * Class for transport layer (TL) IO operations.
 *
 * This implements IO functionality with the Combo at the transport layer.
 * It takes care of creating, sending, receiving, parsing TL packets.
 * To this end, this class uses the supplied [comboIO] for the low-level
 * IO, and the [pumpStateStore] for packet authentication, verification,
 * and generation (the store's tx nonce is used for the latter).
 *
 * Users must call [startIO] before using the IO functionality of this
 * class. Once no more IO operations are to be executed, [stopIO] must
 * be called. If the user later wants to perform IO again, [startIO]
 * can be called again (meaning that this is not a one-use only class).
 *
 * Internally, this class runs a "background worker", which is the sum
 * of coroutines that are started by [startIO]. These coroutines are run
 * by a special internal dispatcher that is single threaded. Internal
 * states are updated in these coroutines. Since they run on the same
 * thread, race conditions are prevented, and thread safety is established.
 * Likewise, access to the pumpStateStore is done in a thread
 * safe manner, since updates to the store happen inside those coroutines.
 * [stopIO] cancels the coroutines, and thus "stops" the worker.
 *
 * This class is typically not directly touched by users. Instead, it is
 * typically used by higher-level code that handles the pairing and regular
 * connection processes.
 *
 * The public API of this calls contains functions for sending / receiving
 * packets and for generating information about packets to be sent (the
 * [OutgoingPacketInfo]). This indirection is necessary since a fully
 * featured Combo packet contains header data that must be computed by
 * using - and modifying - the internal packet related states, and these
 * must not be done outside of the background worker (since this is how
 * thread safety is established, as mentioned above). The [OutgoingPacketInfo]
 * contains all of the information needed to generate a fully featured packet.
 *
 * Exceptions that are thrown from inside the background worker will cause
 * the worker to be marked as "failed". Attempts at sending / receiving
 * packets will throw exceptions if the worker is considered failed.
 * The user has to call [stopIO] to clear that mark.
 *
 * In addition, it is possible for subclasses to process incoming packets
 * immediately when they arrive within runIncomingPacketLoop(). To that
 * end, the subclass must override [applyAdditionalIncomingPacketProcessing].
 *
 * @param pumpStateStore Pump state store to use.
 * @param pumpAddress Bluetooth address of the pump. Used for
 *        accessing the pump state store.
 * @param comboIO Combo IO object to use for sending/receiving data.
 */
open class TransportLayerIO(pumpStateStore: PumpStateStore, private val pumpAddress: BluetoothAddress, private val comboIO: ComboIO) {
    // Job for keeping track of the toplevel background worker coroutine
    // that runs the incoming packet loop (see runIncomingPacketLoop()).
    // The worker is marked as "failed" if backgroundIOWorkerJob
    // is non-null and its isActive property is set to false.
    // The hasWorkerFailed() function does this check.
    private var backgroundIOWorkerJob: Job? = null

    // Flag set to suppress exceptions happening during shutdown.
    // This is accessed only from within the single-threaded worker
    // thread dispatcher (see below) to avoid race conditions.
    private var ignoreBackgroundWorkerErrors = false

    private var pairingPINCallback: PairingPINCallback = { nullPairingPIN() }

    // The single-threaded dispatcher manager for the background worker coroutines.
    private val workerThreadDispatcherManager = SingleThreadDispatcherManager()

    // Channel used by the public API functions to communicate with the
    // background worker in a thread safe manner about incoming packets.
    private var incomingPacketChannel = Channel<Packet>(Channel.UNLIMITED)

    // Packet state object. Will be touched by functions that run inside
    // the background worker (and by startIO before said worker is launched).
    private val packetState = PacketState(pumpStateStore, pumpAddress)

    // Timestamp (in ms) of the last time a packet was sent.
    // Used for throttling the output.
    private var lastSentPacketTimestamp: Long? = null

    // If an exception is thrown inside the worker, and it fails as
    // a result, then this is set to refer to that exception. Used
    // in sendPacket and receivePacket calls.
    private var backgroundIOWorkerException: Exception? = null

    /************************************
     *** PUBLIC FUNCTIONS AND CLASSES ***
     ************************************/

    /**
     * Valid commands for Combo transport layer packets.
     */
    enum class Command(
        val id: Int
    ) {
        // Pairing commands
        REQUEST_PAIRING_CONNECTION(0x09),
        PAIRING_CONNECTION_REQUEST_ACCEPTED(0x0A),
        REQUEST_KEYS(0x0C),
        GET_AVAILABLE_KEYS(0x0F),
        KEY_RESPONSE(0x11),
        REQUEST_ID(0x12),
        ID_RESPONSE(0x14),

        // Regular commands - these require that pairing was performed
        REQUEST_REGULAR_CONNECTION(0x17),
        REGULAR_CONNECTION_REQUEST_ACCEPTED(0x18),
        DISCONNECT(0x1B),
        ACK_RESPONSE(0x05),
        DATA(0x03),
        ERROR_RESPONSE(0x06);

        companion object {
            private val values = Command.values()
            /**
             * Converts an int to a command with the matching ID.
             *
             * @return Command, or null if the int is not a valid command IUD.
             */
            fun fromInt(value: Int) = values.firstOrNull { it.id == value }
        }
    }

    /**
     * Base class for transport layer exceptions.
     *
     * @param message The detail message.
     * @param cause Throwable that further describes the cause of the exception.
     */
    open class ExceptionBase(message: String?, cause: Throwable? = null) : ComboException(message, cause)

    /**
     * Exception thrown when a transport layer packet arrives with an
     * invalid application layer command ID.
     *
     * The packet is provided as bytes list since the [Packet] parser
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
     * More precisely, the arrived packet's command is not the one that was expected.
     *
     * @property packet Transport layer packet that arrived.
     * @property expectedCommand The command that was expected in the packet.
     */
    class IncorrectPacketException(
        val packet: Packet,
        val expectedCommand: Command
    ) : ExceptionBase("Incorrect packet: expected ${expectedCommand.name} packet, got ${packet.command.name} one")

    /**
     * Exception thrown when a packet fails verification.
     *
     * @property packet Transport layer packet that was found to be faulty/corrupt.
     */
    class PacketVerificationException(
        val packet: Packet
    ) : ExceptionBase("Packet verification failed; packet details:  $packet")

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
     * Exception thrown when the background IO loop fails.
     *
     * @param cause The throwable that was thrown in the loop, specifying
     *        want went wrong there.
     */
    class BackgroundIOException(cause: Throwable) : ExceptionBase(cause.message, cause)

    /**
     * Exception thrown when the PIN request is canceled while pairing.
     *
     * This happens when a KEY_RESPONSE packet is received and the PIN
     * callback (see [startIO]) is canceled (typically because the user
     * pressed some sort of Cancel button in a PIN request dialog for
     * example).
     *
     * @param message The detail message.
     */
    class PairingAbortedException(message: String = "Pairing aborted") : ExceptionBase(message)

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
     * Packets that are to be transmitted to the Combo are generated inside the
     * background worker out of [OutgoingPacketInfo] instances.
     *
     * @property command The command of this packet.
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
        val command: Command,
        val version: Byte = 0x10,
        val sequenceBit: Boolean = false,
        val reliabilityBit: Boolean = false,
        val address: Byte = 0,
        val nonce: Nonce = Nonce.nullNonce(),
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
            command = checkedGetCommand(bytes[SEQ_REL_CMD_BYTE_OFFSET].toPosInt() and 0x1F, bytes),
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
         * @throws InvalidCommandIDException if the packet data
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
         * (Alternatively, the [FramedComboIO] class can be used to implicitely
         * frame outgoing packets).
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
                or command.id).toByte())
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
        fun verifyAuthentication(cipher: Cipher): Boolean =
            calculateMAC(cipher) == machineAuthenticationCode

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

        override fun toString() =
            "version: ${version.toHexString(2)}" +
            "  command: ${command.name}" +
            "  sequence bit: $sequenceBit" +
            "  reliability bit: $reliabilityBit" +
            "  address: ${address.toHexString(2)}" +
            "  nonce: $nonce" +
            "  MAC: $machineAuthenticationCode" +
            "  payload: ${payload.size} byte(s): [${payload.toHexString()}]"
    }

    /**
     * Starts IO activities.
     *
     * This must be called before [sendPacket] and [receivePacket] can be used.
     *
     * This starts the background worker coroutines that are necessary
     * for getting the actual IO with the Combo done. These coroutines
     * inherit the supplied [backgroundIOScope], except for its dispatcher
     * (they instead use a dedicated single threaded dispatcher).
     *
     * This also resets internal packet related states and channels
     * to properly start from scratch.
     *
     * [pairingPINCallback] is only used during pairing, otherwise it is
     * set to null. During pairing, when the KEY_RESPONSE packet is received,
     * this will be called to get a PIN from the user.
     *
     * If an exception is thrown in the worker, it ceases activity,
     * and is considered as "failed". Users must call [stopIO]. To
     * be able to perform IO again, [startIO] must be called again.
     *
     * After this call, [isIORunning] will return true.
     *
     * @param backgroundIOScope Coroutine scope to start the background
     *        worker in.
     * @param pairingPINCallback Callback to be used during pairing
     *        for asking the user for the 10-digit PIN.
     * @throws IllegalStateException if IO was already started by a
     *         previous [startIO] call.
     */
    fun startIO(
        backgroundIOScope: CoroutineScope,
        pairingPINCallback: PairingPINCallback = { nullPairingPIN() }
    ) {
        if (backgroundIOWorkerJob != null) {
            throw IllegalStateException("Attempted to start IO even though it is already running")
        }

        // Get the single-threaded dispatcher to be able to run the worker.
        workerThreadDispatcherManager.acquireDispatcher()

        // Reset the packet state to make sure we start from scratch and do not
        // carry over stale states from a previous interaction with the pump.
        packetState.reset()

        // Make sure we get an incoming packet channel that is in its initial state.
        resetIncomingPacketChannel()

        this.pairingPINCallback = pairingPINCallback

        lastSentPacketTimestamp = null
        backgroundIOWorkerException = null

        logger(LogLevel.DEBUG) { "Starting background IO worker" }

        // Run the worker with the single-threaded dispatcher to ensure thread
        // safety (packet send operations are also handled by this dispatcher).
        backgroundIOWorkerJob = backgroundIOScope.launch(workerThreadDispatcherManager.dispatcher) {
            try {
                logger(LogLevel.DEBUG) { "Background IO worker started" }
                runIncomingPacketLoop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Don't pass the exception to the callback
                // if errors are to be ignored.
                if (ignoreBackgroundWorkerErrors) {
                    logger(LogLevel.VERBOSE) {
                        "Caught exception in background IO worker: $e ; will not propagate since background worker errors are to be ignored"
                    }
                } else {
                    logger(LogLevel.DEBUG) { "Caught exception in background IO worker: $e" }
                }

                // Close the channel, citing the exception as the reason why.
                incomingPacketChannel.close(e)

                // Store the exception to let future send and receive calls know what happened.
                backgroundIOWorkerException = e
            }
        }
    }

    /**
     * Stops ongoing IO activity.
     *
     * This stops the background worker that was started by [startIO].
     *
     * If IO is not running, this does nothing.
     *
     * Calling this ensures an orderly IO shutdown and should
     * not be omitted when shutting down an application.
     * This also clears the "failed" mark on a failed worker.
     *
     * After this call, [isIORunning] will return false.
     *
     * Optionally, disconnectPacketInfo can be sent after the internal
     * worker is shut down but before the rest of IO is cleaned up.
     * This is useful for sending out a final disconnect packet that
     * informs the pump that we are disconnecting. Responses from
     * the pump to this packet will _not_ be received, since the worker
     * that receives data is shut down at this point.
     *
     * Typically, to unblock ongoing blocking send / receive calls, it
     * is necessary to close / disconnect a device object that is being
     * used for communicating with the pump. Such a call must happen
     * at a specific moment to not let blocking IO calls prevent the
     * correct shutdown. For example, when the worker is being shut
     * down, stopIO waits for the worker's coroutine to end. If the
     * worker is currently blocked by a receive() call, the coroutine
     * will never end, and stopIO will also never end. Thus, it is
     * necessary to unblock that receive() call. By invoking the
     * disconnectDeviceCallback _before_ stopping the worker, this
     * problem is avoided.
     *
     * @param disconnectPacketInfo Information about the final packet
     *        to generate and send after the worker was shut down but
     *        before the rest is cleaned up. If set to null, no packet
     *        will be generated and sent.
     * @param disconnectDeviceCallback Callback to be invoked during
     *        the shutdown procedure.
     */
    suspend fun stopIO(disconnectPacketInfo: OutgoingPacketInfo? = null, disconnectDeviceCallback: suspend () -> Unit = { }) {
        if (backgroundIOWorkerJob == null) {
            disconnectDeviceCallback()
            return
        }

        try {
            // Set the ignoreBackgroundWorkerErrors flag. This prevents exceptions
            // in the worker from propagating. We don't want that here, since we
            // are shutting down IO anyway, so error notifications here are not
            // useful and just cause confusion. Exceptions can happen during
            // shutdown when the connection is terminated by the Combo, so we
            // do need to set that flag here.
            withContext(workerThreadDispatcherManager.dispatcher) {
                logger(LogLevel.VERBOSE) { "Set background worker errors as to be ignored since we are stopping IO anyway" }
                ignoreBackgroundWorkerErrors = true
            }

            // Send the disconnect packet. This will cause the Combo to terminate
            // the connection, so any blocking read call in the worker will be
            // interrupted and throw an IO exception. These are ignored (see above).
            if (disconnectPacketInfo != null) {
                try {
                    // We do not use sendPacke() here, since we need to send the
                    // disconnect packet even if the worker failed.
                    withContext(workerThreadDispatcherManager.dispatcher) {
                        val packet = produceOutgoingPacket(disconnectPacketInfo)

                        logger(LogLevel.VERBOSE) { "Sending transport layer packet: $packet" }
                        comboIO.send(packet.toByteList())
                        logger(LogLevel.VERBOSE) { "Packet sent" }
                    }
                } catch (e: Exception) {
                    // Swallowing exception since we are anyway already disconnecting.
                    logger(LogLevel.ERROR) { "Caught exception while sending disconnect packet: $e" }
                }
            }
        } finally {
            // The rest of the function concerns itself with the actual
            // disconnect and with cleanup, which must always happen.
            // Therefore, we perform this in the finally block.

            // Do device specific disconnect here to unblock any ongoing
            // blocking receive / send calls. Normally, this is not
            // necessary, since the Combo terminates the connection once
            // the disconnect packet gets transmitted. But in case the
            // Combo doesn't terminate the connection (for example, because
            // the packet never arrived), we still have to make sure that
            // the blocking calls are unblocked right away.
            //
            // We call this in a finally block to make sure it is always
            // called, even if for example a CancellationException is thrown.
            disconnectDeviceCallback()

            // Now shut down the worker.
            logger(LogLevel.DEBUG) { "Stopping background IO worker" }
            try {
                backgroundIOWorkerJob!!.cancelAndJoin()
            } catch (e: Exception) {
                logger(LogLevel.WARN) { "Exception while cancelling worker: $e ; swallowing this exception" }
                // We are tearing down the worker job already,
                // so we swallow exceptions here.
            }
            backgroundIOWorkerJob = null
            logger(LogLevel.DEBUG) { "Background IO worker stopped" }

            // Release the single-threaded dispatcher here, since we do not need
            // it anymore, and not releasing it would cause a resource leak.
            workerThreadDispatcherManager.releaseDispatcher()

            // Reset the ignoreBackgroundWorkerErrors flag. We can do this
            // here, outside of the single-threaded dispatcher, since the
            // worker is no longer running, so race conditions cannot happen.
            ignoreBackgroundWorkerErrors = false
        }
    }

    /** Returns true if IO is ongoing (due to a [startIO] call), false otherwise. */
    fun isIORunning() =
        (backgroundIOWorkerJob != null)

    /**
     * Data class with information about a packet that will go out to the Combo.#
     *
     * This is essentially a template for a [Packet] instance that will then
     * be sent to the Combo. Compared to [Packet], this is missing several fields
     * of the header in [Packet], most notably the Tx nonce and MAC authentication.
     * Both of these fields require interaction with [TransportLayerIO]'s internal
     * packet state in order to be computed. However, they are not required for
     * specifying the payload, reliability bit, and command of a packet, meaning that
     * those fields can be specified independently. This is what this class is about.
     * The packet generation functions below create [OutgoingPacketInfo] instances
     * instead of [Packet] ones for precisely that reason.
     *
     * Once an instance is created, it can be sent to the Combo via [sendPacket].
     * That function converts this to a [Packet] instance, which is then transmitted
     * to the Combo. Both the worker and [sendPacket] do their operations inside the
     * single-threaded dispatcher to ensure thread safety.
     *
     * If reliable is set to true, the background worker will set the packet's
     * reliability bit to 1 and the sequence bit to the value of the internal state's
     * sequence flag. After a packet is generated out of the [OutgoingPacketInfo],
     * said flag is toggled. (This is explained in the Sequence and data reliability
     * bits section in combo-comm-spec.adoc.) If reliable is set to false, both bits
     * in the outgoing packet are set to 0, and the internal state's flag is not altered.
     * However, in special cases (like when the Combo send over ACK_RESPONSE packets),
     * it may be necessary to force the sequence bit to be set to a specific value.
     * In such a case, sequenceBitOverride is set to a boolean value, which will
     * determine if the bit is to be set to 0 or 1. (The internal state sequence flag
     * is not modified if the normal sequence bit logic is overridden like that.)
     * sequenceBitOverride is ignored if reliable is set to false.
     *
     * Typically, users do not touch these properties manually, and instead use one
     * of the packet generation functions below.
     *
     * @property command Command of the outgoing packet.
     * @property payload The outgoing packet's payload. Empty payloads are valid.
     * @property reliable This is set to true if the packet's reliability bit
     *           shall be set to 1.
     * @property sequenceBitOverride If null, the worker will use its normal
     *           sequence bit logic, otherwise it will set the outgoing packet's
     *           bit to this value.
     */
    data class OutgoingPacketInfo(
        val command: Command,
        val payload: ArrayList<Byte> = ArrayList(),
        val reliable: Boolean = false,
        val sequenceBitOverride: Boolean? = null
    ) {
        override fun toString() =
            "command: ${command.name}" +
            "  reliable: $reliable" +
            "  sequenceBitOverride: ${sequenceBitOverride ?: "<not set>"}" +
            "  payload: ${payload.size} byte(s): [${payload.toHexString()}]"
    }

    /**
     * Sends transport layer packets to the Combo.
     *
     * This produces a packet out of the given packetInfo and sends out
     * that packet to the Combo.
     *
     * The background worker must be up and running before calling this.
     * See [startIO] for details.
     *
     * This function suspends the current coroutine until the send operation
     * is complete, or an exception is thrown.
     *
     * @param packetInfo Information about the packet to generate and send.
     * @throws IllegalStateException if the background IO worker is not running.
     * @throws BackgroundIOException if an exception was thrown inside the
     *         worker prior to this call.
     * @throws ComboIOException if sending fails due to an underlying IO error.
     * @throws PumpStateStoreAccessException if accessing the current Tx
     *         nonce in the pump state store failed while preparing the packet
     *         for sending.
     */
    suspend fun sendPacket(packetInfo: OutgoingPacketInfo) {
        if (backgroundIOWorkerJob == null) {
            throw IllegalStateException("Attempted to send packet even though IO is not running")
        }

        if (hasWorkerFailed())
            throw BackgroundIOException(backgroundIOWorkerException ?: Error("FATAL: Background IO worker failed for unknown reason!!"))

        withContext(workerThreadDispatcherManager.dispatcher) {
            // It is important to throttle the output to not overload
            // the Combo's packet ringbuffer. Otherwise, old packets
            // apprently get overwritten by new ones, and the Combo
            // begins to report errors. Empirically, a waiting period
            // of around 150-200 ms seems to work well to avoid this.
            // Here, we check how much time has passed since the last
            // packet transmission. If less than 200 ms have passed,
            // we wait with delay() until a total of 200 ms elapsed.

            val elapsedTime = getElapsedTimeInMs()

            if (lastSentPacketTimestamp != null) {
                val timePassed = elapsedTime - lastSentPacketTimestamp!!
                if (timePassed < 200L) {
                    val waitPeriod = 200L - timePassed
                    logger(LogLevel.VERBOSE) { "Waiting for $waitPeriod ms until a packet can be sent" }
                    delay(waitPeriod)
                }
            }

            lastSentPacketTimestamp = elapsedTime

            // Proceed with sending the packet.

            val packet = produceOutgoingPacket(packetInfo)

            logger(LogLevel.VERBOSE) { "Sending transport layer packet: $packet" }
            comboIO.send(packet.toByteList())
            logger(LogLevel.VERBOSE) { "Packet sent" }
        }
    }

    /**
     * Receives transport layer packets from the Combo.
     *
     * This suspends until the background worker receives data from the Combo.
     * The worker then passes on the received data to this function, which
     * then returns the received data.
     *
     * The background worker must be up and running before calling this.
     * See [startIO] for details.
     *
     * If an exception happens while waiting for the worker to receive a
     * packet, or an exception happened inside the worker before this call,
     * this function will throw a [BackgroundIOException], and the
     * background worker will be considered as "failed".
     *
     * Optionally, this function can check if a received packet has a
     * correct command. This is useful if during a sequence a specific
     * command is expected. This is done if expectedCommand is non-null.
     *
     * @param expectedCommand Optional TransportLayerIO Packet command to check for.
     * @throws IllegalStateException if the background IO worker is not running.
     * @throws BackgroundIOException if an exception is thrown inside the
     *         worker while this call is waiting for a packet or if an
     *         exception was thrown inside the worker prior to this call.
     * @throws IncorrectPacketException if expectedCommand is non-null and
     *         the received packet's command does not match expectedCommand.
     */
    suspend fun receivePacket(expectedCommand: Command? = null): Packet {
        // Check that the background worker is up and running.

        if (backgroundIOWorkerJob == null) {
            throw IllegalStateException("Attempted to receive packet even though IO is not running")
        }

        if (hasWorkerFailed())
            throw BackgroundIOException(backgroundIOWorkerException ?: Error("FATAL: Background IO worker failed for unknown reason!!"))

        // Receive the packet from the background worker.
        // Also handle exceptions while receiving.

        logger(LogLevel.VERBOSE) {
            if (expectedCommand == null)
                "Waiting for transport layer packet"
            else
                "Waiting for transport layer ${expectedCommand.name} packet"
        }

        lateinit var packet: Packet

        try {
            packet = incomingPacketChannel.receive()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger(LogLevel.ERROR) {
                "Receiving a packet failed because an exception was thrown in background IO worker; exception: $e"
            }
            throw BackgroundIOException(e)
        }

        // Check if the packet's command is correct (if required).
        if ((expectedCommand != null) && (packet.command != expectedCommand))
            throw IncorrectPacketException(packet, expectedCommand)

        logger(LogLevel.VERBOSE) { "Received packet: $packet" }

        return packet
    }

    companion object PacketFunctions {
        /**
         * Creates a REQUEST_PAIRING_CONNECTION OutgoingPacketInfo instance.
         *
         * This is exclusively used during the pairing process.
         *
         * See the combo-comm-spec.adoc file for details about this packet.
         *
         * @return The produced packet info.
         */
        fun createRequestPairingConnectionPacketInfo() =
            OutgoingPacketInfo(command = Command.REQUEST_PAIRING_CONNECTION)

        /**
         * Creates a REQUEST_KEYS OutgoingPacketInfo instance.
         *
         * This is exclusively used during the pairing process.
         *
         * See the combo-comm-spec.adoc file for details about this packet.
         *
         * @return The produced packet info.
         */
        fun createRequestKeysPacketInfo() =
            OutgoingPacketInfo(command = Command.REQUEST_KEYS)

        /**
         * Creates a GET_AVAILABLE_KEYS OutgoingPacketInfo instance.
         *
         * This is exclusively used during the pairing process.
         *
         * See the combo-comm-spec.adoc file for details about this packet.
         *
         * @return The produced packet info.
         */
        fun createGetAvailableKeysPacketInfo() =
            OutgoingPacketInfo(command = Command.GET_AVAILABLE_KEYS)

        /**
         * Creates a REQUEST_ID OutgoingPacketInfo instance.
         *
         * This is exclusively used during the pairing process.
         *
         * See the combo-comm-spec.adoc file for details about this packet.
         *
         * @param bluetoothFriendlyName Bluetooth friendly name to use in the request.
         *        Maximum length is 13 characters.
         *        See the Bluetooth specification, Vol. 3 part C section 3.2.2
         *        for details about Bluetooth friendly names.
         * @return The produced packet info.
         */
        fun createRequestIDPacketInfo(bluetoothFriendlyName: String): OutgoingPacketInfo {
            val btFriendlyNameBytes = bluetoothFriendlyName.encodeToByteArray()
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

            return OutgoingPacketInfo(
                command = Command.REQUEST_ID,
                payload = payload
            )
        }

        /**
         * Creates a REQUEST_REGULAR_CONNECTION OutgoingPacketInfo instance.
         *
         * In spite of initiating a "regular" connection, it is also used once
         * in the latter phases of the pairing process. See the combo-comm-spec.adoc
         * file for details.
         *
         * @return The produced packet info.
         */
        fun createRequestRegularConnectionPacketInfo() =
            OutgoingPacketInfo(command = Command.REQUEST_REGULAR_CONNECTION)

        /**
         * Creates an ACK_RESPONSE OutgoingPacketInfo instance.
         *
         * See the combo-comm-spec.adoc file for details about this packet.
         *
         * @param sequenceBit Sequence bit to set in the ACK_RESPONSE packet.
         * @return The produced packet info.
         */
        fun createAckResponsePacketInfo(sequenceBit: Boolean) =
            OutgoingPacketInfo(
                command = Command.ACK_RESPONSE,
                sequenceBitOverride = sequenceBit
            )

        /**
         * Creates a DATA OutgoingPacketInfo instance.
         *
         * See the combo-comm-spec.adoc file for details about this packet.
         *
         * @param reliabilityBit Reliability bit to set in the DATA packet.
         * @param payload Payload to assign to the DATA packet.
         * @return The produced packet info.
         */
        fun createDataPacketInfo(reliabilityBit: Boolean, payload: ArrayList<Byte>) =
            OutgoingPacketInfo(
                command = Command.DATA,
                payload = payload,
                reliable = reliabilityBit
            )
    }

    /**
     * Runs the specified block with the single threaded dispatcher.
     *
     * This uses the existing single threaded dispatcher that is already
     * being used for running the worker coroutines, and suspends until
     * that block is done.
     *
     * @param block Block to run with the single threaded dispatcher.
     */
    suspend fun <T> runInSingleThreadedDispatcher(block: suspend CoroutineScope.() -> T) =
        withContext(workerThreadDispatcherManager.dispatcher, block)

    /***************************************
     *** PROTECTED FUNCTIONS AND CLASSES ***
     ***************************************/

    /**
     * Function for optional additional incoming packet processing.
     *
     * This is intended to be overridden by subclasses if they need
     * to apply additional processing. This is called when a packet
     * arrives in runIncomingPacketLoop. Authentication and reliable
     * packet ACK_RESPONSE replies are done first, then this is called.
     *
     * If this function returns true, the packet will be forwarded
     * to a waiting [receivePacket] call. Modifications applied to
     * the packet will persist, and reach that call. If however this
     * returns false, the packet is dropped. This is useful for
     * subclasses that only care about filtering out specific packets
     * and want to leave other packets alone.
     *
     * If an exception is thrown here, the background worker will
     * cease to function, and be considered failed.
     *
     * Note that this is called by a coroutine that is run with the
     * single threaded dispatcher that runs the receive loop. So
     * if something is done here that affects states, and if there
     * if other code that also touches these states, it is recommended
     * to either use [runInSingleThreadedDispatcher] to run that code
     * with this dispatcher or use a Mutex or similar synchronization.
     *
     * @param tpLayerPacket Transport layer packet to process.
     * @return true if the incoming packet loop shall forward this
     *         packet to a receivePacket call. false if this packet
     *         is to be dropped instead.
     */
    protected open fun applyAdditionalIncomingPacketProcessing(tpLayerPacket: Packet): Boolean = true

    /*************************************
     *** PRIVATE FUNCTIONS AND CLASSES ***
     *************************************/

    /**
     * Class containing state that is used for transport layer IO.
     *
     * The state consists of the pump state and the current sequence
     * flag (used when sending packets with the reliability flag set).
     * The weak key cipher (used during pairing) is _not_ included,
     * since it does not need to exist after pairing.
     *
     * It also contains a cached InvariantPumpData copy. If the pump
     * is not paired yet, this copy will be set to a default value
     * in reset(), otherwise that call will retrieve the pairing data
     * from the pump store and make a copy of it (which is then the
     * cached copy). That way, the ciphers inside the pairing data
     * (which are needed all the time when dealing with Combo packets)
     * aren't constantly being pulled from the persistent store.
     *
     * During pairing, both that cached copy and the pairing data inside
     * the persistent state will be initialized.
     *
     * Also, when generating new outgoing packets, the nonce inside
     * the persistent state is updated.
     *
     * By keeping all of that state isolated in here, it becomes easier
     * to write IO code in a thread safe manner. All interactions that
     * update the state can be easily contained in one thread, thus
     * avoiding race conditions.
     *
     * @property pumpStateStore Pump pump state store to
     *           use and update during IO.
     * @property pumpAddress Bluetooth address of the pump. Used for
     *           accessing the pump state store.
     */
    private class PacketState(val pumpStateStore: PumpStateStore, val pumpAddress: BluetoothAddress) {
        var currentSequenceFlag = false

        var cachedInvariantPumpData = InvariantPumpData.nullData()

        /**
         * Resets the internal states.
         *
         * The states that only exist inside this PacketState object
         * are always reset to initial values. The cached pairing data
         * is reset to an initial default value or copied from the
         * pump state store if the pump has been paired already.
         *
         * Note that in the former case, the pairing data is not supposed
         * to be used before pairing has been performed.
         *
         * This call must be used if a new connection with a pump is
         * established to make sure no stale states are present.
         */
        fun reset() {
            currentSequenceFlag = false

            cachedInvariantPumpData = if (pumpStateStore.hasPumpState(pumpAddress))
                pumpStateStore.getInvariantPumpData(pumpAddress)
            else
                InvariantPumpData.nullData()
        }
    }

    // Resets the received packet channel back to the initial
    // state by recreating it. The channel may have been closed,
    // or still may contain some data, so the best way to reset
    // it is to create a new instance. The GC will clean up the
    // old one automatically.
    private fun resetIncomingPacketChannel() {
        incomingPacketChannel = Channel(Channel.UNLIMITED)
    }

    // Utility function for checking if the worker failed due to an
    // exception earlier. backgroundIOWorkerJob is null if the
    // worker was stopped properly by stopIO().
    private fun hasWorkerFailed() = (backgroundIOWorkerJob != null) && !backgroundIOWorkerJob!!.isActive

    // Runs an infinite loop that receives packets from the Combo,
    // processes them depending on their command, and finally
    // forward them to a waiting receivePacket() call via a channel.
    //
    // This is run in the background worker, and nowhere else,
    // to avoid internal state related data races.
    private suspend fun runIncomingPacketLoop() {
        while (true) {
            lateinit var packet: Packet

            // Skip packets with unknown IDs. These may be packets
            // that haven't been documented yet. There's no reason
            // to let the entire communication session shut down
            // because of an unknown packet command.
            try {
                packet = Packet(comboIO.receive())
            } catch (e: InvalidCommandIDException) {
                logger(LogLevel.WARN) {
                    "Skipping packet with invalid/unknown ID ${e.commandID}; ${e.packetBytes.size} packet byte(s): ${e.packetBytes.toHexString()}"
                }
                continue
            }

            logger(LogLevel.VERBOSE) { "Incoming transport layer packet: $packet" }

            // KEY_RESPONSE packets require special handling
            // since they are validated by the weak cipher,
            // and that cipher is generated from a 10-digit PIN
            // that has to be provided by the user.
            if (packet.command == Command.KEY_RESPONSE) {
                logger(LogLevel.DEBUG) { "Will ask for pairing PIN" }

                var previousAttemptFailed = false

                while (true) {
                    val weakCipher = generateWeakCipher(previousAttemptFailed)
                    if (packet.verifyAuthentication(weakCipher)) {
                        logger(LogLevel.DEBUG) { "KEY_RESPONSE packet verified" }
                        processKeyResponsePacket(packet, weakCipher)
                        // Exit the loop since we successfully verified the packet.
                        break
                    } else {
                        logger(LogLevel.DEBUG) { "Could not verify KEY_RESPONSE packet; user may have entered PIN incorrectly; asking again for PIN" }
                        previousAttemptFailed = true
                    }
                }
            }

            // Authenticate the packet. Special cases:
            //
            // - KEY_RESPONSE packet authentication is done above separately.
            // - When receiving ID_RESPONSE packets, the hasPumpState() check
            //   is omitted, since the pump state is not set up *until* this
            //   very packet arrives (the state is initialized in the
            //   processIDResponsePacket() function), so that check would
            //   always fail with this packet. (The packetState conents are
            //   valid at this point though, so authentication still succeeds.)

            val packetIsValid = when (packet.command) {
                Command.ID_RESPONSE,
                Command.REGULAR_CONNECTION_REQUEST_ACCEPTED,
                Command.ACK_RESPONSE,
                Command.DATA,
                Command.ERROR_RESPONSE -> {
                    logger(LogLevel.VERBOSE) { "Verifying incoming packet with pump-client cipher" }
                    if ((packet.command != Command.ID_RESPONSE) && !packetState.pumpStateStore.hasPumpState(pumpAddress))
                        throw IllegalStateException("Cannot verify incoming ${packet.command} packet without a pump-client cipher")
                    packet.verifyAuthentication(packetState.cachedInvariantPumpData.pumpClientCipher)
                }

                else -> true
            }
            if (!packetIsValid)
                throw PacketVerificationException(packet)

            // Packets with the reliability flag set must be immediately
            // responded to with an ACK_RESPONSE packet whose sequence bit
            // must match that of the received packet.
            if (packet.reliabilityBit) {
                logger(LogLevel.VERBOSE) {
                    "Got a transport layer ${packet.command.name} packet with its reliability bit set; " +
                    "responding with ACK_RESPONSE packet; sequence bit: ${packet.sequenceBit}"
                }
                val ackResponsePacketInfo = createAckResponsePacketInfo(packet.sequenceBit)
                val ackResponsePacket = produceOutgoingPacket(ackResponsePacketInfo)

                try {
                    comboIO.send(ackResponsePacket.toByteList())
                } catch (e: Exception) {
                    logger(LogLevel.ERROR) { "Error while sending ACK_RESPONSE transport layer packet: $e" }
                    throw e
                }
            }

            // Check that this is a packet that we expect to be one that
            // comes from the Combo. Some packets are only ever _sent_ to
            // the Combo, so if we _receive_ them, something is wrong,
            // and we must skip those packets.
            // Also, the Combo periodically sends ACK_RESPONSE packets
            // to us. These packets must be skipped, but they are not
            // an error. Note that these ACK_RESPONSE are not the same
            // as the ACK_RESPONSE packets above - those are sent _by_
            // us _to_ the Combo as a response to an incoming reliable
            // packet, while here, we are talking about an ACK_RESPONSE
            // packet coming _from_ the Combo.
            val skipPacket = when (packet.command) {
                Command.ACK_RESPONSE -> {
                    logger(LogLevel.VERBOSE) { "Got ACK_RESPONSE packet; skipping" }
                    true
                }
                Command.ERROR_RESPONSE,
                Command.DATA,
                Command.PAIRING_CONNECTION_REQUEST_ACCEPTED,
                Command.KEY_RESPONSE,
                Command.ID_RESPONSE,
                Command.REGULAR_CONNECTION_REQUEST_ACCEPTED -> false
                else -> {
                    logger(LogLevel.WARN) { "Cannot process ${packet.command.name} packet coming from the Combo; skipping packet"
                    }
                    true
                }
            }

            if (skipPacket)
                continue

            // Perform some command specific processing.
            when (packet.command) {
                Command.ID_RESPONSE -> processIDResponsePacket(packet)
                // When we get this command, we must reset the current
                // sequence flag to make sure we start the regular
                // connection with the correct flag.
                // (Not doing this for pairing connections since this
                // flag is never used during pairing.)
                Command.REGULAR_CONNECTION_REQUEST_ACCEPTED -> packetState.currentSequenceFlag = false
                Command.ERROR_RESPONSE -> processErrorResponsePacket(packet)
                else -> Unit
            }

            if (!applyAdditionalIncomingPacketProcessing(packet)) {
                logger(LogLevel.VERBOSE) { "Dropping packet as requested by subclass" }
                continue
            }

            // Forward the received packet through the channel
            // so that receivePacket() gets something.
            incomingPacketChannel.send(packet)
        }
    }

    // Produces a Packet that is to be sent to the Combo,
    // and updates the state object's nonce (since every
    // outgoing packet must have a unique nonce). It
    // also flips the state's currentSequenceFlag if this
    // is a reliable packet, and authenticates the packet
    // with the appropriate cipher if necessary.
    //
    // This is run in the background worker, and nowhere else,
    // to avoid internal state related data races.
    private fun produceOutgoingPacket(outgoingPacketInfo: OutgoingPacketInfo): Packet {
        logger(LogLevel.VERBOSE) { "About to produce outgoing packet from info: $outgoingPacketInfo" }

        val nonce = when (outgoingPacketInfo.command) {
            // These commands don't use a nonce, so we have
            // to stick with the null nonce.
            Command.REQUEST_PAIRING_CONNECTION,
            Command.REQUEST_KEYS,
            Command.GET_AVAILABLE_KEYS -> Nonce.nullNonce()

            // This is the first command that uses a non-null
            // nonce. All packets after this one increment
            // the nonce. See combo-comm-spec.adoc for details.
            // That first nonce always has value 1. We return
            // a hard-coded nonce here, since at this point,
            // we cannot call getCurrentTxNonce() yet - the
            // pump state is not yet set up. It will be once
            // the ID_RESPONSE packet (which is the response
            // to REQUEST_ID) arrives.
            Command.REQUEST_ID -> Nonce(byteArrayListOfInts(
                0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
            ))

            // These are the commands that are used in regular
            // (= non-pairing) connections. They all increment
            // the nonce.
            Command.REQUEST_REGULAR_CONNECTION,
            Command.ACK_RESPONSE,
            Command.DATA -> {
                val currentTxNonce = packetState.pumpStateStore.getCurrentTxNonce(pumpAddress).getIncrementedNonce()
                packetState.pumpStateStore.setCurrentTxNonce(pumpAddress, currentTxNonce)
                currentTxNonce
            }

            else -> throw Error("This is not a valid outgoing packet")
        }

        val address = when (outgoingPacketInfo.command) {
            // Initial pairing commands use a hardcoded address.
            Command.REQUEST_PAIRING_CONNECTION,
            Command.REQUEST_KEYS,
            Command.GET_AVAILABLE_KEYS -> 0xF0.toByte()

            Command.REQUEST_ID,
            Command.REQUEST_REGULAR_CONNECTION,
            Command.ACK_RESPONSE,
            Command.DATA -> packetState.cachedInvariantPumpData.keyResponseAddress

            else -> throw Error("This is not a valid outgoing packet")
        }

        val isCRCPacket = when (outgoingPacketInfo.command) {
            Command.REQUEST_PAIRING_CONNECTION,
            Command.REQUEST_KEYS,
            Command.GET_AVAILABLE_KEYS -> true

            else -> false
        }

        val reliabilityBit = outgoingPacketInfo.reliable

        // For reliable packets, use the current currentSequenceFlag
        // as the sequence bit, then flip the currentSequenceFlag.
        // For unreliable packets, don't touch the currentSequenceFlag,
        // and clear the sequence bit.
        // This behavior is overridden if sequenceBitOverride is
        // non-null. In that case, the value of sequenceBitOverride
        // is used for the sequence bit, and currentSequenceFlag
        // is not touched. sequenceBitOverride is used for when
        // ACK_RESPONSE packets have to be sent to the Combo
        // (see the code in runIncomingPacketLoop()).
        val sequenceBit =
            when {
                outgoingPacketInfo.sequenceBitOverride != null -> outgoingPacketInfo.sequenceBitOverride
                reliabilityBit -> {
                    val currentSequenceFlag = packetState.currentSequenceFlag
                    packetState.currentSequenceFlag = !currentSequenceFlag
                    currentSequenceFlag
                }
                else -> false
            }

        val packet = Packet(
            command = outgoingPacketInfo.command,
            sequenceBit = sequenceBit,
            reliabilityBit = reliabilityBit,
            address = address,
            nonce = nonce,
            payload = outgoingPacketInfo.payload
        )

        if (isCRCPacket) {
            packet.computeCRC16Payload()
            logger(LogLevel.DEBUG) {
                val crc16 = (packet.payload[1].toPosInt() shl 8) or packet.payload[0].toPosInt()
                "Computed CRC16 payload ${crc16.toHexString(4)}"
            }
        }

        // Outgoing packets either use no cipher (limited to some
        // of the initial pairing commands) or the client-pump cipher.
        // The pump-client cipher is used for verifying incoming packets,
        val cipher = when (outgoingPacketInfo.command) {
            Command.REQUEST_PAIRING_CONNECTION,
            Command.REQUEST_KEYS,
            Command.GET_AVAILABLE_KEYS -> null

            Command.REQUEST_ID,
            Command.REQUEST_REGULAR_CONNECTION,
            Command.ACK_RESPONSE,
            Command.DATA -> packetState.cachedInvariantPumpData.clientPumpCipher

            else -> throw Error("This is not a valid outgoing packet")
        }

        // Authenticate the packet if necessary.
        if (cipher != null) {
            logger(LogLevel.VERBOSE) { "Authenticating outgoing packet" }
            packet.authenticate(cipher)
        }

        return packet
    }

    // Reads the pump and server ID from the ID_RESPONSE packet
    // and stores the pump ID in the pump state store.
    //
    // This is run in the background worker, and nowhere else,
    // to avoid internal state related data races.
    //
    // This is also where the pump state initialization which
    // started in processKeyResponsePacket is finished, and
    // state created in the store, and the contents of
    // cachedInvariantPumpData written persistently in the store.
    private fun processIDResponsePacket(packet: Packet) {
        if (packet.command != Command.ID_RESPONSE)
            throw IncorrectPacketException(packet, Command.ID_RESPONSE)
        if (packet.payload.size != 17)
            throw InvalidPayloadException(packet, "Expected 17 bytes, got ${packet.payload.size}")

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

        // Now that we have the pump ID we can complete the pump
        // store initialization for this pump that was started in
        // processKeyResponsePacket(). Initialization requires *all*
        // invariant data to be known (including the pump ID), which
        // is why it could not be done earlier.

        val oldInvariantPumpData = packetState.cachedInvariantPumpData

        packetState.cachedInvariantPumpData = InvariantPumpData(
            clientPumpCipher = oldInvariantPumpData.clientPumpCipher,
            pumpClientCipher = oldInvariantPumpData.pumpClientCipher,
            keyResponseAddress = oldInvariantPumpData.keyResponseAddress,
            pumpID = pumpID
        )

        packetState.pumpStateStore.createPumpState(pumpAddress, packetState.cachedInvariantPumpData)

        val firstTxNonce = Nonce(byteArrayListOfInts(
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        ))

        packetState.pumpStateStore.setCurrentTxNonce(pumpAddress, firstTxNonce)
    }

    // Reads the error ID out of the packet and throws an exception.
    // This stops the background worker, which is appropriate, since
    // an error message coming from the Combo is non-recoverable.
    private fun processErrorResponsePacket(packet: Packet) {
        if (packet.command != Command.ERROR_RESPONSE)
            throw IncorrectPacketException(packet, Command.ERROR_RESPONSE)
        if (packet.payload.size != 1)
            throw InvalidPayloadException(packet, "Expected 1 byte, got ${packet.payload.size}")

        val errorID = packet.payload[0].toInt()

        throw TransportLayerIO.ErrorResponseException(packet, errorID)
    }

    // Asks the user for a PIN (via the pairingPINCallback) and
    // computes a weak cipher out of that PIN. Used in
    // runIncomingPacketLoop() when a KEY_RESPONSE packet
    // gets received. If the user enters an incorrect PIN, the
    // generated weak cipher will not be able to authenticate
    // the KEY_RESPONSE packet. In that case, this will be
    // called again to let the user try again.
    private suspend fun generateWeakCipher(previousAttemptFailed: Boolean): Cipher {
        logger(LogLevel.DEBUG) { "Waiting for the PIN to be provided" }

        // Request the PIN. If canceled, PairingAbortedException is
        // thrown by the callback.
        val pin = pairingPINCallback.invoke(previousAttemptFailed)

        logger(LogLevel.DEBUG) { "Provided PIN: $pin" }

        val weakCipher = Cipher(generateWeakKeyFromPIN(pin))
        logger(LogLevel.DEBUG) { "Generated weak cipher key ${weakCipher.key.toHexString()} out of pairing PIN" }

        return weakCipher
    }

    // Reads the pump-client and client-pump keys and the
    // source and destination addresses, all of which are
    // essential for authenticating and processing subsequent
    // packets. The weak cipher must have been generated by
    // using generateWeakCipher() before this is called.
    //
    // This will store the keys and addresses in the internal
    // cached pump pairing data. This begins the pump state
    // initialization process. It is finished in the
    // processIDResponsePacket() function.
    //
    // This function is called during pairing, not when
    // establishing regular connections.
    //
    // This is run in the background worker, and nowhere else,
    // to avoid internal state related data races.
    private fun processKeyResponsePacket(packet: Packet, weakCipher: Cipher) {
        if (packet.payload.size != (CIPHER_KEY_SIZE * 2))
            throw InvalidPayloadException(packet, "Expected ${CIPHER_KEY_SIZE * 2} bytes, got ${packet.payload.size}")

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

        packetState.cachedInvariantPumpData = InvariantPumpData(
            pumpClientCipher = pumpClientCipher,
            clientPumpCipher = clientPumpCipher,
            keyResponseAddress = keyResponseAddress,
            pumpID = "" // This gets filled later in processIDResponsePacket().
        )

        logger(LogLevel.DEBUG) {
            "Address: ${packetState.cachedInvariantPumpData.keyResponseAddress.toHexString(2)}" +
            "  decrypted client->pump key: ${packetState.cachedInvariantPumpData.clientPumpCipher.key.toHexString()}" +
            "  decrypted pump->client key: ${packetState.cachedInvariantPumpData.pumpClientCipher.key.toHexString()}"
        }
    }
}

/**
 * Produces a transport layer packet out of given data.
 *
 * This is just a convenience extension function that internally
 * creates a TransportLayerIO.Packet instance and passes the data
 * to its constructor.
 *
 * See the TransportLayerIO.Packet constructor for details.
 */
fun List<Byte>.toTransportLayerPacket(): TransportLayerIO.Packet {
    return TransportLayerIO.Packet(this)
}
