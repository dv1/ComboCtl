package info.nightscout.comboctl.base

private val logger = Logger.get("ApplicationLayer")

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
    tpLayerPacket: TransportLayer.Packet
): ApplicationLayer.Command {
    val serviceIDInt = tpLayerPacket.payload[SERVICE_ID_BYTE_OFFSET].toPosInt()
    val serviceID = ApplicationLayer.ServiceID.fromInt(serviceIDInt)
        ?: throw ApplicationLayer.InvalidServiceIDException(
            tpLayerPacket,
            serviceIDInt,
            ArrayList<Byte>(tpLayerPacket.payload.subList(PAYLOAD_BYTES_OFFSET, tpLayerPacket.payload.size))
        )

    val commandID = (tpLayerPacket.payload[COMMAND_ID_BYTE_OFFSET + 0].toPosInt() shl 0) or
        (tpLayerPacket.payload[COMMAND_ID_BYTE_OFFSET + 1].toPosInt() shl 8)

    return ApplicationLayer.Command.fromIDs(serviceID, commandID)
        ?: throw ApplicationLayer.InvalidCommandIDException(
            tpLayerPacket,
            serviceID,
            commandID,
            ArrayList<Byte>(tpLayerPacket.payload.subList(PAYLOAD_BYTES_OFFSET, tpLayerPacket.payload.size))
        )
}

/**
 * Maximum allowed size for application layer packet payloads, in bytes.
 */
const val MAX_VALID_AL_PAYLOAD_SIZE = 65535 - PACKET_HEADER_SIZE

/**
 * Combo application layer (AL) communication implementation.
 *
 * This provides all the necessary primitives and processes to communicate
 * at the application layer. The ApplicationLayer class itself contains internal
 * temporary states that only need to exist for the duration of a communication
 * session with the Combo.
 *
 * Callers create an instance of ApplicationLayer every time a new connection to
 * the Combo is established, and destroyed afterwards (it is not reused).
 *
 * The application layer sits on top of the transport layer. Application layer
 * packets are encapsulated in transport layer DATA packets.
 *
 * Just like with TransportLayer, the packet creation and parsing functions
 * in this class may update internal and external state (both its own internal
 * state and the internal&external state from the underlying transport layer).
 * Consult the spec to see the correct sequence for pairing and for initiating
 * regular connections.
 */
class ApplicationLayer {
    /**
     * RT sequence number. Used in outgoing RT packets.
     */
    private var currentRTSequence: Int = 0

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
        RT_KEY_CONFIRMATION(ServiceID.RT_MODE, 0x0556, false),
        RT_DISPLAY(ServiceID.RT_MODE, 0x0555, false);

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
        val tpLayerPacket: TransportLayer.Packet,
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
        val tpLayerPacket: TransportLayer.Packet,
        val serviceID: ServiceID,
        val commandID: Int,
        val payload: List<Byte>
    ) : ExceptionBase("Invalid/unknown application layer packet command ID 0x${commandID.toString(16)} (service ID: ${serviceID.name})")

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
        constructor(tpLayerPacket: TransportLayer.Packet) : this(
            command = checkedGetCommand(tpLayerPacket),
            version = tpLayerPacket.payload[VERSION_BYTE_OFFSET],
            payload = ArrayList<Byte>(tpLayerPacket.payload.subList(PAYLOAD_BYTES_OFFSET, tpLayerPacket.payload.size))
        ) {
            if (tpLayerPacket.commandID != TransportLayer.CommandID.DATA) {
                throw TransportLayer.IncorrectPacketException(
                    tpLayerPacket,
                    TransportLayer.CommandID.DATA
                )
            }
        }

        /**
         * Produces a transport layer DATA packet containing this application layer
         * packet's data as its payload.
         *
         * @param transportLayer TransportLayer instance used for generating the packet.
         * @return Transport layer DATA packet.
         */
        fun toTransportLayerPacket(transportLayer: TransportLayer): TransportLayer.Packet {
            val appLayerPacketPayload = ArrayList<Byte>(PACKET_HEADER_SIZE + payload.size)
            appLayerPacketPayload.add(version)
            appLayerPacketPayload.add(command.serviceID.id.toByte())
            appLayerPacketPayload.add(((command.commandID shr 0) and 0xFF).toByte())
            appLayerPacketPayload.add(((command.commandID shr 8) and 0xFF).toByte())
            appLayerPacketPayload.addAll(payload)

            return transportLayer.createDataPacket(
                command.reliable,
                appLayerPacketPayload
            )
        }

        override fun toString(): String {
            return "version: ${"%02x".format(version)}" +
                "  service ID: ${command.serviceID}" +
                "  command: $command" +
                "  payload: ${payload.size} byte(s): ${payload.toHexString()}"
        }
    }

    // Utility function to increment the RT sequence number with overflow check.
    // Used when new outgoing RT packets are generated.
    private fun incrementRTSequence() {
        currentRTSequence++
        if (currentRTSequence > 65535)
            currentRTSequence = 0
    }

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
    fun createCTRLGetServiceVersionPacket(serviceID: ServiceID): Packet {
        return Packet(
            command = Command.CTRL_GET_SERVICE_VERSION,
            payload = byteArrayListOfInts(serviceID.id)
        )
    }

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
    fun createCTRLBindPacket(): Packet {
        // TODO: See the spec for this command. It is currently
        // unclear why the payload has to be 0x48.
        return Packet(
            command = Command.CTRL_BIND,
            payload = byteArrayListOfInts(0x48)
        )
    }

    /**
     * Creates a CTRL_DISCONNECT packet.
     *
     * This terminates the connection at the application layer.
     *
     * See the combo-comm-spec.adoc file for details about this packet.
     *
     * @return The produced packet.
     */
    fun createCTRLDisconnectPacket(): Packet {
        // TODO: See the spec for this command. It is currently
        // unclear why the payload should be 0x6003, and why
        // Ruffy sets this to 0x0003 instead. But since we know
        // that Ruffy works, we currently pick 0x0003.
        return Packet(
            command = Command.CTRL_DISCONNECT,
            payload = byteArrayListOfInts(0x03, 0x00)
        )
    }

    /**
     * Creates a CTRL_ACTIVATE_SERVICE packet.
     *
     * This activates the RT or command mode (depending on the argument).
     *
     * See the combo-comm-spec.adoc file for details about this packet.
     *
     * @return The produced packet.
     */
    fun createCTRLActivateServicePacket(serviceID: ServiceID): Packet {
        return Packet(
            command = Command.CTRL_ACTIVATE_SERVICE,
            payload = byteArrayListOfInts(serviceID.id, 1, 0)
        )
    }

    /**
     * Creates a CTRL_DEACTIVATE_ALL_SERVICES packet.
     *
     * This deactivates any currently active service.
     *
     * See the combo-comm-spec.adoc file for details about this packet.
     *
     * @return The produced packet.
     */
    fun createCTRLDeactivateAllServicesPacket(): Packet {
        return Packet(command = Command.CTRL_DEACTIVATE_ALL_SERVICES)
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
     * Parses an CTRL_SERVICE_ERROR packet and extracts its payload.
     *
     * @param packet Application layer CTRL_SERVICE_ERROR packet to parse.
     * @return The packet's parsed payload.
     * @throws InvalidPayloadException if the payload size is not the expected size.
     */
    fun parseCTRLServiceErrorPacket(packet: Packet): CTRLServiceError {
        val payload = packet.payload

        val expectedPayloadSize = 5
        if (payload.size < expectedPayloadSize) {
            throw InvalidPayloadException(
                packet,
                "Insufficient payload bytes in RT display packet; expected $expectedPayloadSize byte(s), got ${payload.size}"
            )
        }

        return CTRLServiceError(
            errorCodeValue = (payload[0].toPosInt() shl 0) or (payload[1].toPosInt() shl 8),
            serviceIDValue = payload[2].toPosInt(),
            commandIDValue = (payload[3].toPosInt() shl 0) or (payload[4].toPosInt() shl 8)
        )
    }

    /**
     * Valid button codes that an RT_BUTTON_STATUS packet can contain in its payload.
     */
    enum class RTButtonCode(val id: Int) {
        UP(0x30),
        DOWN(0xC0),
        MENU(0x03),
        CHECK(0x0C),
        NO_BUTTON(0x00)
    }

    /**
     * Creates an RT_BUTTON_STATUS packet.
     *
     * The RT mode must have been activated before this can be sent to the Combo.
     *
     * See the combo-comm-spec.adoc file for details about this packet.
     *
     * @return The produced packet.
     */
    fun createRTButtonStatusPacket(rtButtonCode: RTButtonCode, buttonStatusChanged: Boolean): Packet {
        val payload = byteArrayListOfInts(
            (currentRTSequence shr 0) and 0xFF,
            (currentRTSequence shr 8) and 0xFF,
            rtButtonCode.id,
            if (buttonStatusChanged) 0xB7 else 0x48
        )

        incrementRTSequence()

        return Packet(
            command = Command.RT_BUTTON_STATUS,
            payload = payload
        )
    }

    /**
     * Creates an RT_KEEP_ALIVE packet.
     *
     * The RT mode must have been activated before this can be sent to the Combo.
     *
     * See the combo-comm-spec.adoc file for details about this packet.
     *
     * @return The produced packet.
     */
    fun createRTKeepAlivePacket(): Packet {
        val payload = byteArrayListOfInts(
            (currentRTSequence shr 0) and 0xFF,
            (currentRTSequence shr 8) and 0xFF
        )

        incrementRTSequence()

        return Packet(
            command = Command.RT_KEEP_ALIVE,
            payload = payload
        )
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

        val expectedPayloadSize = 5 + 96
        if (payload.size < expectedPayloadSize) {
            throw InvalidPayloadException(
                packet,
                "Insufficient payload bytes in RT display packet; expected $expectedPayloadSize byte(s), got ${payload.size}"
            )
        }

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
}
