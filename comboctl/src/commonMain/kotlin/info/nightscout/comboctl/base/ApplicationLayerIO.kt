package info.nightscout.comboctl.base

import kotlinx.coroutines.CoroutineScope

private val logger = Logger.get("ApplicationLayerIO")

// Application layer packet structure (excluding the additional transport layer packet metadata):
//
// 1. 4 bits  : Application layer major version (always set to 0x01)
// 2. 4 bits  : Application layer minor version (always set to 0x00)
// 3. 8 bits  : Service ID; can be one of these values:
//              0x00 : control service ID
//              0x48 : RT mode service ID
//              0xB7 : command mode service ID
// 4. 16 bits : Command ID, stored as a 16-bit little endian integer
// 5. n bytes : Payload

// 1 byte with major & minor version
// 1 byte with service ID
// 2 bytes with command ID
private const val PACKET_HEADER_SIZE = 1 + 1 + 2

private const val VERSION_BYTE_OFFSET = 0
private const val SERVICE_ID_BYTE_OFFSET = 1
private const val COMMAND_ID_BYTE_OFFSET = 2
private const val PAYLOAD_BYTES_OFFSET = 4

// Utility function to be able to throw an exception in case of
// an invalid service or command ID.
private fun checkedGetCommand(
    tpLayerPacket: TransportLayerIO.Packet
): ApplicationLayerIO.Command {
    val serviceIDInt = tpLayerPacket.payload[SERVICE_ID_BYTE_OFFSET].toPosInt()
    val serviceID = ApplicationLayerIO.ServiceID.fromInt(serviceIDInt)
        ?: throw ApplicationLayerIO.InvalidServiceIDException(
            tpLayerPacket,
            serviceIDInt,
            ArrayList(tpLayerPacket.payload.subList(PAYLOAD_BYTES_OFFSET, tpLayerPacket.payload.size))
        )

    val commandID = (tpLayerPacket.payload[COMMAND_ID_BYTE_OFFSET + 0].toPosInt() shl 0) or
        (tpLayerPacket.payload[COMMAND_ID_BYTE_OFFSET + 1].toPosInt() shl 8)

    return ApplicationLayerIO.Command.fromIDs(serviceID, commandID)
        ?: throw ApplicationLayerIO.InvalidCommandIDException(
            tpLayerPacket,
            serviceID,
            commandID,
            ArrayList(tpLayerPacket.payload.subList(PAYLOAD_BYTES_OFFSET, tpLayerPacket.payload.size))
        )
}

/**
 * Maximum allowed size for application layer packet payloads, in bytes.
 */
const val MAX_VALID_AL_PAYLOAD_SIZE = 65535 - PACKET_HEADER_SIZE

/**
 * Class for application layer (TL) IO operations.
 *
 * This implements IO functionality with the Combo at the application layer.
 * It contains an instance of [TransportLayerIO], and uses it for the lower
 * level IO, while this class itself mainly concerns itself with creating
 * and parsing application layer packets. It also allows for subclasses to
 * add custom handling of specific application layer packets. This is useful
 * for processing RT_DISPLAY packets in a subclass for example. This is
 * accomplished by overriding the [processIncomingPacket] function.
 *
 * Users must call [startIO] before using the IO functionality of this
 * class. Once no more IO operations are to be executed, [stopIO] must
 * be called. If the user later wants to perform IO again, [startIO]
 * can be called again (meaning that this is not a one-use only class).
 *
 * This class is typically not directly touched by users. Instead, it is
 * typically used by higher-level code that implements IO based on
 * transport and application layer packets, like code for pairing.
 *
 * See [TransportLayerIO] for notes about the background worker and
 * error handling in that worker.
 *
 * NOTE: This class is not designed to allow multiple concurrent
 * [sendPacket] or [receiveAppLayerPacket]/[receiveTpLayerPacket] calls.
 * Such a use case is currently not considered relevant for this class.
 * So, adding synchronization primitives to make them thread safe would
 * add cost and not yield enough benefit. This may change in the future
 * though.
 *
 * @param persistentPumpStateStore Persistent state store to use.
 * @param comboIO Combo IO object to use for sending/receiving data.
 */
open class ApplicationLayerIO(persistentPumpStateStore: PersistentPumpStateStore, private val comboIO: ComboIO) {
    // RT sequence number. Used in outgoing RT packets.
    private var currentRTSequence: Int = 0

    // Internal TransportLayerIO instance used as the
    // foundation for the application layer IO.
    private val transportLayerIO: TransportLayerIO

    /************************************
     *** PUBLIC FUNCTIONS AND CLASSES ***
     ************************************/

    /**
     * Valid application layer command service IDs.
     */
    enum class ServiceID(val id: Int) {
        CONTROL(0x00),
        RT_MODE(0x48),
        COMMAND_MODE(0xB7);

        companion object {
            private val values = ServiceID.values()
            /**
             * Converts an int to a service ID.
             *
             * @return ServiceID, or null if the int is not a valid ID.
             */
            fun fromInt(value: Int) = values.firstOrNull { it.id == value }
        }
    }

    /**
     * Valid application layer commands.
     *
     * An application layer command is a combination of a service ID, a command ID,
     * and a flag whether or not the command is to be sent with the underlying
     * DATA transport layer packet's reliability flag set or unset. The former
     * two already uniquely identify the command; the "reliable" flag is additional
     * information.
     */
    enum class Command(val serviceID: ServiceID, val commandID: Int, val reliable: Boolean) {
        CTRL_CONNECT(ServiceID.CONTROL, 0x9055, true),
        CTRL_CONNECT_RESPONSE(ServiceID.CONTROL, 0xA055, true),
        CTRL_GET_SERVICE_VERSION(ServiceID.CONTROL, 0x9065, true),
        CTRL_SERVICE_VERSION_RESPONSE(ServiceID.CONTROL, 0xA065, true),
        CTRL_BIND(ServiceID.CONTROL, 0x9095, true),
        CTRL_BIND_RESPONSE(ServiceID.CONTROL, 0xA095, true),
        CTRL_DISCONNECT(ServiceID.CONTROL, 0x005A, true),
        CTRL_ACTIVATE_SERVICE(ServiceID.CONTROL, 0x9066, true),
        CTRL_ACTIVATE_SERVICE_RESPONSE(ServiceID.CONTROL, 0xA066, true),
        CTRL_DEACTIVATE_ALL_SERVICES(ServiceID.CONTROL, 0x906A, true),
        CTRL_ALL_SERVICES_DEACTIVATED(ServiceID.CONTROL, 0xA06A, true),
        CTRL_SERVICE_ERROR(ServiceID.CONTROL, 0x00AA, true),

        RT_BUTTON_STATUS(ServiceID.RT_MODE, 0x0565, false),
        RT_KEEP_ALIVE(ServiceID.RT_MODE, 0x0566, false),
        RT_BUTTON_CONFIRMATION(ServiceID.RT_MODE, 0x0556, false),
        RT_DISPLAY(ServiceID.RT_MODE, 0x0555, false),
        RT_AUDIO(ServiceID.RT_MODE, 0x0559, false),
        RT_VIBRATION(ServiceID.RT_MODE, 0x055A, false);

        companion object {
            private val values = Command.values()

            /**
             * Returns the command that has a matching service ID and command ID.
             *
             * @return Command, or null if no matching command exists.
             */
            fun fromIDs(serviceID: ServiceID, commandID: Int) = values.firstOrNull {
                (it.serviceID == serviceID) && (it.commandID == commandID)
            }
        }
    }

    /**
     * Base class for application layer exceptions.
     *
     * @param message The detail message.
     */
    open class ExceptionBase(message: String) : ComboException(message)

    /**
     * Exception thrown when an application layer packet arrives with an invalid service ID.
     *
     * @property tpLayerPacket Underlying transport layer DATA packet containing the application layer packet data.
     * @property serviceID The invalid service ID.
     * @property payload The application packet's payload.
     */
    class InvalidServiceIDException(
        val tpLayerPacket: TransportLayerIO.Packet,
        val serviceID: Int,
        val payload: List<Byte>
    ) : ExceptionBase("Invalid/unknown application layer packet service ID 0x${serviceID.toString(16)}")

    /**
     * Exception thrown when an application layer packet arrives with an invalid application layer command ID.
     *
     * @property tpLayerPacket Underlying transport layer DATA packet containing the application layer packet data.
     * @property serviceID Service ID from the application layer packet.
     * @property commandID The invalid application layer command ID.
     * @property payload The application packet's payload.
     */
    class InvalidCommandIDException(
        val tpLayerPacket: TransportLayerIO.Packet,
        val serviceID: ServiceID,
        val commandID: Int,
        val payload: List<Byte>
    ) : ExceptionBase("Invalid/unknown application layer packet command ID 0x${commandID.toString(16)} (service ID: ${serviceID.name})")

    init {
        // Create a TransportLayerIO subclass instance with our own custom
        // applyAdditionalIncomingPacketProcessing override to be able to
        // handle incoming transport layer DATA packets (which contain
        // incoming application layer packets).
        // Our override returns true if this is either not a DATA packet
        // or the processIncomingPacket function (which can be overridden
        // by ApplicationLayerIO subclasses) returns true. This tells the
        // worker to continue to forward that packet to any waiting
        // receivePacket call. Return value false however instructs the
        // worker to drop the packet.
        // This functionality is necessary because some DATA packets need
        // to make it to a receivePacket call, while other packets like
        // RT_DISPLAY only ever need to be handled inside the
        // processIncomingPacket callback (it makes no sense to pass those
        // packets to waiting receivePacket calls).
        transportLayerIO = object : TransportLayerIO(persistentPumpStateStore, comboIO) {
            override fun applyAdditionalIncomingPacketProcessing(tpLayerPacket: TransportLayerIO.Packet) =
                if (tpLayerPacket.command == TransportLayerIO.Command.DATA) {
                    val appLayerPacket = checkAndParseTransportLayerDataPacket(tpLayerPacket)
                    if (appLayerPacket != null)
                        processIncomingPacket(appLayerPacket)
                    else
                        false
                } else
                    true
        }
    }

    /**
     * Exception thrown when a different application layer packet was expected than the one that arrived.
     *
     * More precisely, the arrived packet's command is not the one that was expected.
     *
     * @property appLayerPacket Application layer packet that arrived.
     * @property expectedCommand The command that was expected in the packet.
     */
    class IncorrectPacketException(
        val appLayerPacket: Packet,
        val expectedCommand: Command
    ) : ExceptionBase("Incorrect packet: expected ${expectedCommand.name} packet, got ${appLayerPacket.command.name} one")

    /**
     * Exception thrown when the combo sends a CTRL_SERVICE_ERROR packet.
     *
     * These packets notify about errors in the communication between client and Combo
     * at the application layer.
     *
     * @property appLayerPacket Application layer packet that arrived.
     * @property serviceError The service error information from the packet.
     */
    class ServiceErrorException(
        val appLayerPacket: Packet,
        val serviceError: CTRLServiceError
    ) : ExceptionBase(
        "Service error reported by Combo: $serviceError"
    )

    /**
     * Exception thrown when something is wrong with an application layer packet's payload.
     *
     * @property appLayerPacket Application layer packet with the invalid payload.
     * @property message Detail message.
     */
    class InvalidPayloadException(
        val appLayerPacket: Packet,
        message: String
    ) : ExceptionBase(message)

    /**
     * Class containing data of a Combo application layer packet.
     *
     * Just like the transport layer, the application layer also uses packets as the
     * basic unit. Each application layer packet is contained in a transport layer
     * DATA packet and contains a small header and a payload. It is easy to confuse
     * its payload with the payload of the transport layer DATA packet, since this
     * packet's data _is_ the payload of the DATA transport layer packet. In other
     * words, within the payload of the DATA transport layer packet is _another_
     * header (the application layer packet header), and after that, the actual
     * application layer packet comes.
     *
     * Unlike transport layer packet data, application layer packet data does not
     * include any CRC or MAC authentication metadata (since the underlying DATA
     * packet already provides that).
     *
     * Also note that the application layer packet header contains a version byte.
     * It is identical in structure to the version byte in the transport layer packet
     * header, but is something entirely separate.
     *
     * See "Application layer packet structure" in combo-comm-spec.adoc for details.
     *
     * Since these packets are stored in the payload of transport layer DATA packets,
     * the transport layer DATA packet's reliability and sequence bits do need to
     * be addressed. This is done by looking up the "reliable" boolean in the command
     * enum value. For each valid command, there is one such boolean. It determines
     * whether the reliability bit of the DATA TL packet will be set or cleared.
     *
     * @property command The command of this packet. This is a combination of a
     *           service ID and a command ID, which together uniquely identify
     *           the command.
     * @property version Byte containing version numbers. The upper 4 bit contain the
     *           major, the lower 4 bit the minor version number.
     *           In all observed packets, this was set to 0x10.
     * @property payload The application layer packet payload.
     * @throws IllegalArgumentException if the payload size exceeds
     *         [MAX_VALID_AL_PAYLOAD_SIZE].
     */
    data class Packet(
        val command: Command,
        val version: Byte = 0x10,
        var payload: ArrayList<Byte> = ArrayList(0)
    ) {
        init {
            if (payload.size > MAX_VALID_AL_PAYLOAD_SIZE) {
                throw IllegalArgumentException(
                    "Payload size ${payload.size} exceeds allowed maximum of $MAX_VALID_AL_PAYLOAD_SIZE bytes"
                )
            }
        }

        /**
         * Creates an application layer packet out of a transport layer DATA packet.
         *
         * @param tpLayerPacket The transport layer DATA packet.
         * @throws IncorrectPacketException if the given packet is not a DATA packet.
         */
        constructor(tpLayerPacket: TransportLayerIO.Packet) : this(
            command = checkedGetCommand(tpLayerPacket),
            version = tpLayerPacket.payload[VERSION_BYTE_OFFSET],
            payload = ArrayList<Byte>(tpLayerPacket.payload.subList(PAYLOAD_BYTES_OFFSET, tpLayerPacket.payload.size))
        ) {
            if (tpLayerPacket.command != TransportLayerIO.Command.DATA) {
                throw TransportLayerIO.IncorrectPacketException(
                    tpLayerPacket,
                    TransportLayerIO.Command.DATA
                )
            }
        }

        /**
         * Produces transport layer DATA packet info containing this application layer
         * packet's data as its payload.
         *
         * @return Transport layer DATA packet.
         */
        fun toTransportLayerPacketInfo(): TransportLayerIO.OutgoingPacketInfo {
            val appLayerPacketPayload = ArrayList<Byte>(PACKET_HEADER_SIZE + payload.size)
            appLayerPacketPayload.add(version)
            appLayerPacketPayload.add(command.serviceID.id.toByte())
            appLayerPacketPayload.add(((command.commandID shr 0) and 0xFF).toByte())
            appLayerPacketPayload.add(((command.commandID shr 8) and 0xFF).toByte())
            appLayerPacketPayload.addAll(payload)

            return TransportLayerIO.createDataPacketInfo(
                command.reliable,
                appLayerPacketPayload
            )
        }

        override fun toString(): String {
            return "version: ${version.toHexString(2)}" +
                "  service ID: ${command.serviceID}" +
                "  command: $command" +
                "  payload: ${payload.size} byte(s): ${payload.toHexString()}"
        }
    }

    /**
     * Categories for [CTRLServiceErrorCode].
     */
    enum class CTRLServiceErrorCategory {
        MISC_APPLICATION_LAYER,
        REMOTE_TERMINAL_MODE,
        COMMAND_MODE
    }

    /**
     * Known error codes from CTRL_SERVICE_ERROR packets.
     */
    enum class CTRLServiceErrorCode(val value: Int, val category: CTRLServiceErrorCategory, val description: String) {
        UNKNOWN_SERVICE_ID(0xF003, CTRLServiceErrorCategory.MISC_APPLICATION_LAYER, "Unknown service ID"),
        INCOMPATIBLE_AL_PACKET_VERSION(0xF005, CTRLServiceErrorCategory.MISC_APPLICATION_LAYER, "Incompatible application layer packet version"),
        INVALID_PAYLOAD_LENGTH(0xF006, CTRLServiceErrorCategory.MISC_APPLICATION_LAYER, "Invalid payload length"),
        NOT_CONNECTED(0xF056, CTRLServiceErrorCategory.MISC_APPLICATION_LAYER, "Application layer not connected"),
        INCOMPATIBLE_SERVICE_VERSION(0xF059, CTRLServiceErrorCategory.MISC_APPLICATION_LAYER, "Incompatible service version"),
        REQUEST_WITH_UNKNOWN_SERVICE_ID(0xF05A, CTRLServiceErrorCategory.MISC_APPLICATION_LAYER,
            "Version, activate, deactivate request with unknown service ID"),
        SERVICE_ACTIVATION_NOT_ALLOWED(0xF05C, CTRLServiceErrorCategory.MISC_APPLICATION_LAYER, "Service activation not allowed"),
        COMMAND_NOT_ALLOWED(0xF05F, CTRLServiceErrorCategory.MISC_APPLICATION_LAYER, "Command not allowed (wrong mode)"),

        RT_PAYLOAD_WRONG_LENGTH(0xF503, CTRLServiceErrorCategory.REMOTE_TERMINAL_MODE, "RT payload wrong length"),
        RT_DISPLAY_INCORRECT_INDEX(0xF505, CTRLServiceErrorCategory.REMOTE_TERMINAL_MODE,
            "RT display with incorrect row index, update, or display index"),
        RT_DISPLAY_TIMEOUT(0xF506, CTRLServiceErrorCategory.REMOTE_TERMINAL_MODE, "RT display timeout"),
        RT_UNKNOWN_AUDIO_SEQUENCE(0xF509, CTRLServiceErrorCategory.REMOTE_TERMINAL_MODE, "RT unknown audio sequence"),
        RT_UNKNOWN_VIBRATION_SEQUENCE(0xF50A, CTRLServiceErrorCategory.REMOTE_TERMINAL_MODE, "RT unknown vibration sequence"),
        RT_INCORRECT_SEQUENCE_NUMBER(0xF50C, CTRLServiceErrorCategory.REMOTE_TERMINAL_MODE, "RT command has incorrect sequence number"),
        RT_ALIVE_TIMEOUT_EXPIRED(0xF533, CTRLServiceErrorCategory.REMOTE_TERMINAL_MODE, "RT alive timeout expired"),

        CMD_VALUES_NOT_WITHIN_THRESHOLD(0xF605, CTRLServiceErrorCategory.COMMAND_MODE, "CMD values not within threshold"),
        CMD_WRONG_BOLUS_TYPE(0xF606, CTRLServiceErrorCategory.COMMAND_MODE, "CMD wrong bolus type"),
        CMD_BOLUS_NOT_DELIVERING(0xF60A, CTRLServiceErrorCategory.COMMAND_MODE, "CMD bolus not delivering"),
        CMD_HISTORY_READ_EEPROM_ERROR(0xF60C, CTRLServiceErrorCategory.COMMAND_MODE, "CMD history read EEPROM error"),
        CMD_HISTORY_FRAM_NOT_ACCESSIBLE(0xF633, CTRLServiceErrorCategory.COMMAND_MODE, "CMD history confirm FRAM not readable or writeable"),
        CMD_UNKNOWN_BOLUS_TYPE(0xF635, CTRLServiceErrorCategory.COMMAND_MODE, "CMD unknown bolus type"),
        CMD_BOLUS_CURRENTLY_UNAVAILABLE(0xF636, CTRLServiceErrorCategory.COMMAND_MODE, "CMD bolus is not available at the moment"),
        CMD_INCORRECT_CRC_VALUE(0xF639, CTRLServiceErrorCategory.COMMAND_MODE, "CMD incorrect CRC value"),
        CMD_CH1_CH2_VALUES_INCONSISTENT(0xF63A, CTRLServiceErrorCategory.COMMAND_MODE, "CMD ch1 and ch2 values inconsistent"),
        CMD_INTERNAL_PUMP_ERROR(0xF63C, CTRLServiceErrorCategory.COMMAND_MODE, "CMD pump has internal error (RAM values changed)");

        companion object {
            private val values = CTRLServiceErrorCode.values()

            /**
             * Returns the error code that has a matching value.
             *
             * @return CTRLServiceErrorCode, or null if no matching error code exists.
             */
            fun fromValue(value: Int) = values.firstOrNull { (it.value == value) }
        }
    }

    /**
     * Error information from CTRL_SERVICE_ERROR packets.
     *
     * The values are kept as integer on purpose, since it is not known
     * if all possible values are known, so directly having enum types
     * here would not allow for representing unknown values properly.
     *
     * @property errorCodeValue Integer value of the error code.
     *           Known error codes exist in [CTRLServiceErrorCode].
     * @property serviceIDValue Integer with the value of the
     *           service ID of the command that caused the error.
     * @property commandIDValue Integer with the value of the
     *           command ID of the command that caused the error.
     */
    data class CTRLServiceError(
        val errorCodeValue: Int,
        val serviceIDValue: Int,
        val commandIDValue: Int
    ) {
        override fun toString(): String {
            val errorCode = CTRLServiceErrorCode.fromValue(errorCodeValue)
            val errorCodeStr =
                if (errorCode != null)
                    "error code \"${errorCode.description}\""
                else
                    "error code 0x${errorCodeValue.toString(16)}"

            var command: Command? = null

            val serviceID = ServiceID.fromInt(serviceIDValue)
            if (serviceID != null)
                command = Command.fromIDs(serviceID, commandIDValue)

            val commandStr =
                if (command != null)
                    "command \"${command.name}\""
                else
                    "service ID 0x${serviceIDValue.toString(16)} command ID 0x${commandIDValue.toString(16)}"

            return "$errorCodeStr $commandStr"
        }
    }

    /**
     * Valid button codes that an RT_BUTTON_STATUS packet can contain in its payload.
     * These can be bitwise OR combined to implement combined button presses.
     */
    enum class RTButtonCode(val id: Int) {
        UP(0x30),
        DOWN(0xC0),
        MENU(0x03),
        CHECK(0x0C),
        NO_BUTTON(0x00)
    }

    /**
     * Valid display update reasons that an RT_DISPLAY packet can contain in its payload.
     */
    enum class RTDisplayUpdateReason(val id: Int) {
        PUMP(0x48),
        DM(0xB7);

        companion object {
            private val values = RTDisplayUpdateReason.values()
            /**
             * Converts an int to an RTDisplayUpdateReason.
             *
             * @return RTDisplayUpdateReason, or null if the int is not a valid reason ID.
             */
            fun fromInt(value: Int) = values.firstOrNull { it.id == value }
        }
    }

    /**
     * Data class containing the fields of an RT_DISPLAY packet's payload.
     */
    data class RTDisplayPayload(
        val currentRTSequence: Int,
        val reason: RTDisplayUpdateReason,
        val index: Int,
        val row: Int,
        val pixels: List<Byte>
    )

    companion object PacketFunctions {
        /**
         * Creates a CTRL_CONNECT packet.
         *
         * This initiates a connection at the application layer. The transport
         * layer must have been connected first with the transport layer's
         * REQUEST_REGULAR_CONNECTION command.
         *
         * See the combo-comm-spec.adoc file for details about this packet.
         *
         * @return The produced packet.
         */
        fun createCTRLConnectPacket(): Packet {
            val serialNumber = Constants.APPLICATION_LAYER_CONNECT_SERIAL_NUMBER
            val payload = byteArrayListOfInts(
                (serialNumber shr 0) and 0xFF,
                (serialNumber shr 8) and 0xFF,
                (serialNumber shr 16) and 0xFF,
                (serialNumber shr 24) and 0xFF
            )
            return Packet(
                command = Command.CTRL_CONNECT,
                payload = payload
            )
        }

        /**
         * Creates a CTRL_GET_SERVICE_VERSION packet.
         *
         * This is used during the pairing process. It is not needed in
         * regular connections.
         *
         * See the combo-comm-spec.adoc file for details about this packet.
         *
         * @return The produced packet.
         */
        fun createCTRLGetServiceVersionPacket(serviceID: ServiceID) = Packet(
            command = Command.CTRL_GET_SERVICE_VERSION,
            payload = byteArrayListOfInts(serviceID.id)
        )

        /**
         * Creates a CTRL_BIND packet.
         *
         * This is used during the pairing process. It is not needed in
         * regular connections.
         *
         * See the combo-comm-spec.adoc file for details about this packet.
         *
         * @return The produced packet.
         */
        fun createCTRLBindPacket() = Packet(
            // TODO: See the spec for this command. It is currently
            // unclear why the payload has to be 0x48.
            command = Command.CTRL_BIND,
            payload = byteArrayListOfInts(0x48)
        )

        /**
         * Creates a CTRL_DISCONNECT packet.
         *
         * This terminates the connection at the application layer.
         *
         * See the combo-comm-spec.adoc file for details about this packet.
         *
         * @return The produced packet.
         */
        fun createCTRLDisconnectPacket() = Packet(
            // TODO: See the spec for this command. It is currently
            // unclear why the payload should be 0x6003, and why
            // Ruffy sets this to 0x0003 instead. But since we know
            // that Ruffy works, we currently pick 0x0003.
            command = Command.CTRL_DISCONNECT,
            payload = byteArrayListOfInts(0x03, 0x00)
        )

        /**
         * Creates a CTRL_ACTIVATE_SERVICE packet.
         *
         * This activates the RT or command mode (depending on the argument).
         *
         * See the combo-comm-spec.adoc file for details about this packet.
         *
         * @return The produced packet.
         */
        fun createCTRLActivateServicePacket(serviceID: ServiceID) = Packet(
            command = Command.CTRL_ACTIVATE_SERVICE,
            payload = byteArrayListOfInts(serviceID.id, 1, 0)
        )

        /**
         * Creates a CTRL_DEACTIVATE_ALL_SERVICES packet.
         *
         * This deactivates any currently active service.
         *
         * See the combo-comm-spec.adoc file for details about this packet.
         *
         * @return The produced packet.
         */
        fun createCTRLDeactivateAllServicesPacket() = Packet(
            command = Command.CTRL_DEACTIVATE_ALL_SERVICES
        )

        /**
         * Parses an CTRL_SERVICE_ERROR packet and extracts its payload.
         *
         * @param packet Application layer CTRL_SERVICE_ERROR packet to parse.
         * @return The packet's parsed payload.
         * @throws InvalidPayloadException if the payload size is not the expected size.
         */
        fun parseCTRLServiceErrorPacket(packet: Packet): CTRLServiceError {
            val payload = packet.payload
            checkPayloadSize(packet, 5)

            return CTRLServiceError(
                errorCodeValue = (payload[0].toPosInt() shl 0) or (payload[1].toPosInt() shl 8),
                serviceIDValue = payload[2].toPosInt(),
                commandIDValue = (payload[3].toPosInt() shl 0) or (payload[4].toPosInt() shl 8)
            )
        }

        /**
         * Creates an RT_BUTTON_STATUS packet.
         *
         * The RT mode must have been activated before this can be sent to the Combo.
         *
         * See the combo-comm-spec.adoc file for details about this packet.
         *
         * To implement multiple pressed buttons, use a bitwise OR combination
         * of IDs from the RTButtonCode enum.
         *
         * @param rtButtonCodes Button ID / combined button IDs.
         * @return The produced packet.
         */
        fun createRTButtonStatusPacket(rtButtonCodes: Int, buttonStatusChanged: Boolean) = Packet(
            command = Command.RT_BUTTON_STATUS,
            payload = byteArrayListOfInts(
                0x00, 0x00, // RT sequence - will be filled in later by sendPacket()
                rtButtonCodes,
                if (buttonStatusChanged) 0xB7 else 0x48
            )
        )

        /**
         * Creates an RT_KEEP_ALIVE packet.
         *
         * The RT mode must have been activated before this can be sent to the Combo.
         *
         * See the combo-comm-spec.adoc file for details about this packet.
         *
         * @return The produced packet.
         */
        fun createRTKeepAlivePacket() = Packet(
            command = Command.RT_KEEP_ALIVE,
            payload = byteArrayListOfInts(
                0x00, 0x00 // RT sequence - will be filled in later by sendPacket()
            )
        )

        /**
         * Parses an RT_DISPLAY packet and extracts its payload.
         *
         * @param packet Application layer RT_DISPLAY packet to parse.
         * @return The packet's parsed payload.
         * @throws InvalidPayloadException if the payload size is not the expected size,
         *         or if the payload contains an invalid display row ID or reason.
         */
        fun parseRTDisplayPacket(packet: Packet): RTDisplayPayload {
            val payload = packet.payload
            checkPayloadSize(packet, 5 + 96)

            val reasonInt = payload[2].toPosInt()
            val reason = RTDisplayUpdateReason.fromInt(reasonInt) ?: throw InvalidPayloadException(
                packet, "Invalid RT display update reason $reasonInt")

            val row = when (val rowInt = payload[4].toPosInt()) {
                0x47 -> 0
                0x48 -> 1
                0xB7 -> 2
                0xB8 -> 3
                else -> throw InvalidPayloadException(packet, "Invalid RT display update row $rowInt")
            }

            return RTDisplayPayload(
                currentRTSequence = (payload[0].toPosInt() shl 0) or (payload[1].toPosInt() shl 8),
                reason = reason,
                index = payload[3].toPosInt(),
                row = row,
                pixels = payload.subList(5, 101)
            )
        }

        /**
         * Parses an RT_AUDIO packet and extracts its payload.
         *
         * @param packet Application layer RT_AUDIO packet to parse.
         * @return The packet's parsed payload (the 32-bit little
         *         endian integer specifying the audio type).
         * @throws InvalidPayloadException if the payload size is not the expected size.
         */
        fun parseRTAudioPacket(packet: Packet): Int {
            val payload = packet.payload
            checkPayloadSize(packet, 6)

            // The first 2 bytes in the payload contain the RT
            // sequence number, which we are not interested in,
            // so we ignore these 2 bytes.

            return (payload[2].toPosInt() shl 0) or
                   (payload[3].toPosInt() shl 8) or
                   (payload[4].toPosInt() shl 16) or
                   (payload[5].toPosInt() shl 24)
        }

        /**
         * Parses an RT_VIBRATION packet and extracts its payload.
         *
         * @param packet Application layer RT_VIBRATION packet to parse.
         * @return The packet's parsed payload (the 32-bit little
         *         endian integer specifying the vibration type).
         * @throws InvalidPayloadException if the payload size is not the expected size.
         */
        fun parseRTVibrationPacket(packet: Packet): Int {
            val payload = packet.payload
            checkPayloadSize(packet, 6)

            // The first 2 bytes in the payload contain the RT
            // sequence number, which we are not interested in,
            // so we ignore these 2 bytes.

            return (payload[2].toPosInt() shl 0) or
                   (payload[3].toPosInt() shl 8) or
                   (payload[4].toPosInt() shl 16) or
                   (payload[5].toPosInt() shl 24)
        }

        private fun checkPayloadSize(packet: Packet, expectedPayloadSize: Int) {
            if (packet.payload.size != expectedPayloadSize) {
                throw InvalidPayloadException(
                    packet,
                    "Incorrect payload size in ${packet.command} packet; expected $expectedPayloadSize byte(s), got ${packet.payload.size}"
                )
            }
        }
    }

    /**
     * Starts IO activities.
     *
     * This must be called before [sendPacket], [receiveAppLayerPacket],
     * and [receiveTpLayerPacket] can be used.
     *
     * This starts the background worker coroutines that are necessary
     * for getting the actual IO with the Combo done. These coroutines
     * inherit the supplied [backgroundIOScope], except for its dispatcher
     * (they instead use a dedicated single threaded dispatcher).
     *
     * This also resets internal packet related states and channels
     * to properly start from scratch.
     *
     * [onBackgroundIOException] is an optional callback for when an
     * exception is thrown inside the background worker.
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
     * @param onBackgroundIOException Optional callback for notifying
     *        about exceptions that get thrown inside the worker.
     * @param pairingPINCallback Callback to be used during pairing
     *        for asking the user for the 10-digit PIN.
     * @throws IllegalStateException if IO was already started by a
     *         previous [startIO] call.
     */
    fun startIO(
        backgroundIOScope: CoroutineScope,
        onBackgroundIOException: (e: Exception) -> Unit = { },
        pairingPINCallback: PairingPINCallback = { nullPairingPIN() }
    ) {
        currentRTSequence = 0
        transportLayerIO.startIO(backgroundIOScope, onBackgroundIOException, pairingPINCallback)
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
     * This will call the underlying transport layer's [TransportLayerIO.stopIO]
     * function, and pass a CTRL_DISCONNECT packet as its disconnect packet.
     * This is necessary, otherwise the pump will not disconnect itself properly.
     * Especially in command mode this means that the pump will not respond and
     * any blocking receive call will block until the pump's internal watchdog
     * times out and resets the Bluetooth connection.
     */
    suspend fun stopIO() {
        val disconnectPacketInfo = ApplicationLayerIO.createCTRLDisconnectPacket()
        logger(LogLevel.VERBOSE) { "Will send application layer disconnect packet:  $disconnectPacketInfo" }

        transportLayerIO.stopIO(disconnectPacketInfo.toTransportLayerPacketInfo())
    }

    /** Returns true if IO is ongoing (due to a [startIO] call), false otherwise. */
    fun isIORunning() = transportLayerIO.isIORunning()

    /**
     * Sends application layer packets to the Combo.
     *
     * This wraps the application layer packet in a transport layer
     * DATA packet by placing the application layer packet as the payload
     * of a [TransportLayerIO.OutgoingPacketInfo] instance. That instance
     * is then sent to the Combo via the internal TransportLayerIO instance.
     * Additionally, if an application layer RT packet is to be sent,
     * the current RT sequence number will be filled into the packet's bytes
     * before sending.
     *
     * The background worker must be up and running before calling this.
     * See [startIO] for details.
     *
     * This function suspends the current coroutine until the send operation
     * is complete, or an exception is thrown.
     *
     * @param appLayerPacket Application layer packet to send.
     * @throws IllegalStateException if the background IO worker is not
     *         running or if it has failed.
     * @throws ComboIOException if sending fails due to an underlying IO error.
     * @throws InvalidPayloadException if appLayerPacket is an RT packet
     *         and its payload is not big enough to contain the RT sequence
     *         number, indicating that this is an invalid / malformed packet.
     */
    suspend fun sendPacket(appLayerPacket: Packet) {
        // RT packets contain a sequence number which is incremented
        // every time an RT packet is sent out. Since the create*
        // functions are stateless, the RT sequence number that is
        // present in the packets they create isn't set there right
        // away. Instead, it is set here, to the value of the
        // currentRTSequence integer. Said integer is incremented
        // afterwards. This way, the create* functions can remain
        // stateless, and we keep the currentRTSequence state change
        // isolated in here.

        logger(LogLevel.VERBOSE) {
            "Sending application layer packet via transport layer:  $appLayerPacket"
        }

        val outgoingPacketInfo = appLayerPacket.toTransportLayerPacketInfo()

        if (appLayerPacket.command.serviceID == ServiceID.RT_MODE) {
            if (outgoingPacketInfo.payload.size < (PAYLOAD_BYTES_OFFSET + 2)) {
                throw InvalidPayloadException(
                    appLayerPacket,
                    "Cannot send application layer RT packet since there's no room in the payload for the RT sequence number"
                )
            }

            // The RT sequence is always stored in the
            // first 2 bytes  of an RT packet's payload.
            // 
            // Also, we set the RT sequence in the outgoingPacketInfo,
            // and not in appLayerPacket's payload, since the latter
            // is a function argument, and modifying the payload of
            // an outside value may lead to confusing behavior.
            // By writing the RT sequence into outgoingPacketInfo
            // instead, that change stays contained in here.
            outgoingPacketInfo.payload[PAYLOAD_BYTES_OFFSET + 0] = ((currentRTSequence shr 0) and 0xFF).toByte()
            outgoingPacketInfo.payload[PAYLOAD_BYTES_OFFSET + 1] = ((currentRTSequence shr 8) and 0xFF).toByte()

            // After using the RT sequence, increment it to
            // make sure the next RT packet doesn't use the
            // same RT sequence.
            incrementRTSequence()
        }

        transportLayerIO.sendPacket(outgoingPacketInfo)
    }

    /**
     * Sends transport layer packets to the Combo.
     *
     * This passes the [TransportLayerIO.OutgoingPacketInfo] instance
     * directly to the internal TransportLayerIO instance. Consult
     * the [TransportLayerIO.sendPacket] documentation for details.
     */
    suspend fun sendPacket(tpLayerPacketInfo: TransportLayerIO.OutgoingPacketInfo) =
        transportLayerIO.sendPacket(tpLayerPacketInfo)

    /**
     * Receives application layer packets from the Combo.
     *
     * This suspends until the background worker receives data from the Combo.
     * The worker then passes on the received data to this function, which
     * then parses the transport layer packet to extract its payload, which
     * is an application layer packet. That one is then returned.
     *
     * The background worker must be up and running before calling this.
     * See [startIO] for details.
     *
     * If an exception happens while waiting for the worker to receive a packet,
     * this function will throw a [TransportLayerIO.BackgroundIOException],
     * and the background worker will be considered as "failed".
     *
     * Optionally, this function can check if a received packet has a
     * correct command. This is useful if during a sequence a specific
     * command is expected. This is done if expectedCommand is non-null.
     *
     * @param expectedCommand Optional ApplicationLayerIO Packet command to check for.
     * @throws IllegalStateException if the background IO worker is not
     *         running or if it has failed.
     * @throws TransportLayerIO.BackgroundIOException if an exception is thrown
     *         inside the worker while this call is waiting for a packet.
     * @throws IncorrectPacketException if expectedCommand is non-null and
     *         the received packet's command does not match expectedCommand.
     */
    suspend fun receiveAppLayerPacket(expectedCommand: ApplicationLayerIO.Command? = null): Packet {
        logger(LogLevel.VERBOSE) {
            if (expectedCommand == null)
                "Waiting for application layer packet (will arrive in a transport layer DATA packet)"
            else
                "Waiting for application layer ${expectedCommand.name} packet (will arrive in a transport layer DATA packet)"
        }

        val tpLayerPacket = transportLayerIO.receivePacket(TransportLayerIO.Command.DATA)
        val appLayerPacket = Packet(tpLayerPacket)

        if ((expectedCommand != null) && (appLayerPacket.command != expectedCommand))
            throw ApplicationLayerIO.IncorrectPacketException(appLayerPacket, expectedCommand)

        return appLayerPacket
    }

    /**
     * Receives transport layer packets to the Combo.
     *
     * This directly calls the [TransportLayerIO.receivePacket] function
     * to the internal TransportLayerIO instance. Consult the
     * [TransportLayerIO.receivePacket] documentation for details.
     */
    suspend fun receiveTpLayerPacket(expectedCommand: TransportLayerIO.Command? = null) =
        transportLayerIO.receivePacket(expectedCommand)

    /***************************************
     *** PROTECTED FUNCTIONS AND CLASSES ***
     ***************************************/

    /**
     * Open function that gets called when a new packet is received.
     *
     * This is useful if a subclass needs to analyze incoming application
     * layer packets before passing it further along. The incoming
     * transport layer packet will be processed first as usual. Then,
     * if it is a DATA packet, its payload is extracted (that payload is
     * the application layer packet we are interested in). That application
     * layer packet is then passed to this function. If it returns true,
     * then the packet will be forwarded to a waiting receivePacket call.
     * If it returns false, the packet is dropped.
     *
     * Dropping packets is useful if the packet itself is not supposed
     * to reach receivePacket calls. Status update packets are one example.
     *
     * This function can modify the packet if needed. If it returns true,
     * then the modified packet will be forwarded.
     *
     * If an exception is thrown here, the background worker will
     * cease to function, and be considered failed.
     *
     * @param appLayerPacket Packet to analyze (and possible modify).
     * @return True if the packet shall be forwarded to receivePacket
     *         calls. False if it shall be dropped right after this
     *         function call is done.
     */
    protected open fun processIncomingPacket(appLayerPacket: Packet): Boolean = true

    /*************************************
     *** PRIVATE FUNCTIONS AND CLASSES ***
     *************************************/

    // Utility function to increment the RT sequence number with overflow check.
    // Used when new outgoing RT packets are generated.
    private fun incrementRTSequence() {
        currentRTSequence++
        if (currentRTSequence > 65535)
            currentRTSequence = 0
    }

    private fun checkAndParseTransportLayerDataPacket(tpLayerPacket: TransportLayerIO.Packet): ApplicationLayerIO.Packet? {
        // Parse the transport layer DATA packet as an application layer packet.
        // This is called inside the TransportLayerIO's overriden
        // applyAdditionalIncomingPacketProcessing function. This makes sure
        // that the background worker ceases function if an exception is thrown
        // here. Also, since we perform these checks here, we do not have to
        // apply them in receiveAppLayerPacket() as well, since all packets
        // that make it to that function must have passed through here.

        try {
            logger(LogLevel.VERBOSE) { "Parsing DATA packet as application layer packet" }
            val appLayerPacket = ApplicationLayerIO.Packet(tpLayerPacket)
            logger(LogLevel.VERBOSE) { "This is an application layer packet with command ${appLayerPacket.command}" }
            return appLayerPacket
        } catch (e: ApplicationLayerIO.InvalidCommandIDException) {
            logger(LogLevel.WARN) {
                "Got an application layer packet with invalid/unknown command ID 0x${e.commandID.toString(16)} " +
                "service ID ${e.serviceID.name} and payload (with ${e.payload.size} byte(s)) ${e.payload.toHexString()}" +
                "; dropping packet"
            }
            return null
        } catch (e: ApplicationLayerIO.ExceptionBase) {
            logger(LogLevel.ERROR) { "Could not parse DATA packet as application layer packet: $e" }
            throw e
        }
    }
}
