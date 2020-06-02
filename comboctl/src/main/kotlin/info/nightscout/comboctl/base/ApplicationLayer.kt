package info.nightscout.comboctl.base

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

private fun checkedGetCommand(
    appPacketBytes: ArrayList<Byte>,
    tpLayerPacket: TransportLayer.Packet
): ApplicationLayer.Command {
    val serviceIDInt = appPacketBytes[SERVICE_ID_BYTE_OFFSET].toPosInt()
    val serviceID = ApplicationLayer.ServiceID.fromInt(serviceIDInt)
        ?: throw ApplicationLayer.InvalidServiceIDException(tpLayerPacket, serviceIDInt)
    val commandID = (tpLayerPacket.payload[COMMAND_ID_BYTE_OFFSET + 0].toPosInt() shl 0) or
        (tpLayerPacket.payload[COMMAND_ID_BYTE_OFFSET + 1].toPosInt() shl 8)
    return ApplicationLayer.Command.fromIDs(serviceID, commandID)
        ?: throw ApplicationLayer.InvalidCommandIDException(tpLayerPacket, serviceID, commandID)
}

class ApplicationLayer {
    /**
     * Valid application layer command service IDs.
     */
    enum class ServiceID(val id: Int) {
        CONTROL(0x00),
        RT_MODE(0x48),
        COMMAND_MODE(0xB7);

        companion object {
            private val values = ServiceID.values()
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

        RT_BUTTON_STATUS(ServiceID.RT_MODE, 0x0565, false),
        RT_DISPLAY(ServiceID.RT_MODE, 0x0555, false);

        companion object {
            private val values = Command.values()

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
     */
    class InvalidServiceIDException(
        val tpLayerPacket: TransportLayer.Packet,
        val serviceID: Int
    ) : ExceptionBase("Invalid/unknown application layer packet service ID $serviceID")

    /**
     * Exception thrown when an application layer packet arrives with an invalid application layer command ID.
     *
     * @property tpLayerPacket Underlying transport layer DATA packet containing the application layer packet data.
     * @property serviceID Service ID from the application layer packet.
     * @property commandID The invalid application layer command ID.
     */
    class InvalidCommandIDException(
        val tpLayerPacket: TransportLayer.Packet,
        val serviceID: ServiceID,
        val commandID: Int
    ) : ExceptionBase("Invalid/unknown application layer packet command ID $commandID (service ID: ${serviceID.name})")

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
     * Exception thrown when something is wrong with an application layer packet's payload.
     *
     * @property appLayerPacket Application layer packet with the invalid payload.
     * @property message Detail message.
     */
    class InvalidPayloadException(
        val appLayerPacket: Packet,
        message: String
    ) : ExceptionBase(message)

    class State(
        val transportLayer: TransportLayer,
        val transportLayerState: TransportLayer.State,
        var currentRTSequence: Int = 0
    )

    data class Packet(
        val command: Command,
        val version: Byte = 0x10,
        var payload: ArrayList<Byte> = ArrayList<Byte>(0)
    ) {
        init {
            require(payload.size <= (65535 - PACKET_HEADER_SIZE))
        }

        constructor(tpLayerPacket: TransportLayer.Packet) : this(
            command = checkedGetCommand(tpLayerPacket.payload, tpLayerPacket),
            version = tpLayerPacket.payload[VERSION_BYTE_OFFSET],
            payload = ArrayList<Byte>(tpLayerPacket.payload.subList(PAYLOAD_BYTES_OFFSET, tpLayerPacket.payload.size))
        ) {
            require(tpLayerPacket.commandID == TransportLayer.CommandID.DATA)
        }

        fun toTransportLayerPacket(appLayerState: State): TransportLayer.Packet {
            val appLayerPacketPayload = ArrayList<Byte>(PACKET_HEADER_SIZE + payload.size)
            appLayerPacketPayload.add(version)
            appLayerPacketPayload.add(command.serviceID.id.toByte())
            appLayerPacketPayload.add(((command.commandID shr 0) and 0xFF).toByte())
            appLayerPacketPayload.add(((command.commandID shr 8) and 0xFF).toByte())
            appLayerPacketPayload.addAll(payload)

            return appLayerState.transportLayer.createDataPacket(
                appLayerState.transportLayerState,
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

    private fun incrementRTSequence(state: State) {
        state.currentRTSequence++
        if (state.currentRTSequence > 65535)
            state.currentRTSequence = 0
    }

    fun createCTRLConnectPacket(): Packet {
        val serialNumber = 12345
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

    fun createCTRLGetServiceVersionPacket(serviceID: ServiceID): Packet {
        return Packet(
            command = Command.CTRL_GET_SERVICE_VERSION,
            payload = byteArrayListOfInts(serviceID.id)
        )
    }

    fun createCTRLBindPacket(): Packet {
        // TODO: See the spec for this command. It is currently
        // unclear why the payload has to be 0x48.
        return Packet(
            command = Command.CTRL_BIND,
            payload = byteArrayListOfInts(0x48)
        )
    }

    fun createCTRLDisconnectPacket(): Packet {
        // TODO: See the spec for this command. It is currently
        // unclear why the payload has to be 0x6003, and why
        // Ruffy sets this to 0x0003 instead. But since we know
        // that Ruffy works, we currently pick 0x0003.
        return Packet(
            command = Command.CTRL_DISCONNECT,
            payload = byteArrayListOfInts(0x03, 0x00)
        )
    }

    fun createCTRLActivateServicePacket(serviceID: ServiceID): Packet {
        return Packet(
            command = Command.CTRL_ACTIVATE_SERVICE,
            payload = byteArrayListOfInts(serviceID.id, 1, 0)
        )
    }

    fun createCTRLDeactivateAllServicesPacket(): Packet {
        return Packet(command = Command.CTRL_DEACTIVATE_ALL_SERVICES)
    }

    enum class RTButtonCode(val id: Int) {
        UP(0x30),
        DOWN(0xC0),
        MENU(0x03),
        CHECK(0x0C),
        NO_BUTTON(0x00)
    }

    fun createRTButtonStatusPacket(state: State, rtButtonCode: RTButtonCode, buttonStatusChanged: Boolean): Packet {
        val payload = byteArrayListOfInts(
            (state.currentRTSequence shr 0) and 0xFF,
            (state.currentRTSequence shr 8) and 0xFF,
            rtButtonCode.id,
            if (buttonStatusChanged) 0xB7 else 0x48
        )

        incrementRTSequence(state)

        return Packet(
            command = Command.RT_BUTTON_STATUS,
            payload = payload
        )
    }

    enum class RTDisplayUpdateReason(val id: Int) {
        PUMP(0x48),
        DM(0xB7);

        companion object {
            private val values = RTDisplayUpdateReason.values()
            fun fromInt(value: Int) = values.firstOrNull { it.id == value }
        }
    }

    data class RTDisplayContent(
        val currentRTSequence: Int,
        val reason: RTDisplayUpdateReason,
        val index: Int,
        val row: Int,
        val pixels: List<Byte>
    )

    fun parseRTDisplayPacket(packet: Packet): RTDisplayContent {
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

        return RTDisplayContent(
            currentRTSequence = (payload[0].toPosInt() shl 0) or (payload[1].toPosInt() shl 8),
            reason = reason,
            index = payload[3].toPosInt(),
            row = row,
            pixels = payload.subList(4, 101)
        )
    }
}
