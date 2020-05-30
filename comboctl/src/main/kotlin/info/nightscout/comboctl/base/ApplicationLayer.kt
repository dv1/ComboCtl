package info.nightscout.comboctl.base

import java.text.ParseException

class ApplicationLayer {
    class State(
        val transportLayer: TransportLayer,
        val transportLayerState: TransportLayer.State,
        var currentRTSequence: Int = 0
    )

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

    enum class Command(val serviceID: ServiceID, val commandID: Int) {
        CTRL_CONNECT(ServiceID.CONTROL, 0x9055),
        CTRL_CONNECT_RESPONSE(ServiceID.CONTROL, 0xA055),
        CTRL_GET_SERVICE_VERSION(ServiceID.CONTROL, 0x9065),
        CTRL_SERVICE_VERSION_RESPONSE(ServiceID.CONTROL, 0xA065),
        CTRL_BIND(ServiceID.CONTROL, 0x9095),
        CTRL_BIND_RESPONSE(ServiceID.CONTROL, 0xA095),
        CTRL_DISCONNECT(ServiceID.CONTROL, 0x005A),
        CTRL_ACTIVATE_SERVICE(ServiceID.CONTROL, 0x9066),
        CTRL_ACTIVATE_SERVICE_RESPONSE(ServiceID.CONTROL, 0xA066),
        CTRL_DEACTIVATE_ALL_SERVICES(ServiceID.CONTROL, 0x906A),
        CTRL_ALL_SERVICES_DEACTIVATED(ServiceID.CONTROL, 0xA06A),

        RT_BUTTON_STATUS(ServiceID.RT_MODE, 0x0565),
        RT_DISPLAY(ServiceID.RT_MODE, 0x0555);

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
    open class Exception(message: String) : ComboException(message)

    /**
     * Exception thrown when an application layer packet arrives with an invalid service ID.
     *
     * @property tpLayerPacket Underlying transport layer DATA packet containing the application layer packet data.
     * @property serviceID The invalid service ID.
     */
    class InvalidServiceIDException(
        val tpLayerPacket: TransportLayer.Packet,
        val serviceID: Int
    ) : ApplicationLayer.Exception("Invalid/unknown application layer packet service ID $serviceID")

    /**
     * Exception thrown when an application layer packet packet arrives with an invalid application layer command ID.
     *
     * @property tpLayerPacket Underlying transport layer DATA packet containing the application layer packet data.
     * @property seviceID Service ID from the application layer packet.
     * @property commandID The invalid application layer command ID.
     */
    class InvalidCommandIDException(
        val tpLayerPacket: TransportLayer.Packet,
        val serviceID: ServiceID,
        val commandID: Int
    ) : ApplicationLayer.Exception("Invalid/unknown application layer packet command ID $commandID (service ID: ${serviceID.name})")

    /**
     * Exception thrown when something is wrong with an application layer packet's payload.
     *
     * @property appLayerPacket Application layer packet with the invalid payload.
     * @property message Detail message.
     */
    class InvalidPayloadException(
        val appLayerPacket: Packet,
        message: String
    ) : ApplicationLayer.Exception(message)

    class State(
        val transportLayer: TransportLayer,
        val transportLayerState: TransportLayer.State,
        var currentRTSequence: Int = 0
    )

    private fun createAppLayerPacket(
        state: State,
        command: Command,
        reliabilityBit: Boolean = false,
        payload: ArrayList<Byte> = arrayListOf()
    ): TransportLayer.Packet {
        val appLayerPacketPayload = ArrayList<Byte>(1 + 1 + 2 + payload.size)
        appLayerPacketPayload.add(0x10) // Major version (1) and minor version (0)
        appLayerPacketPayload.add(command.serviceID.id.toByte())
        appLayerPacketPayload.add(((command.commandID shr 0) and 0xFF).toByte())
        appLayerPacketPayload.add(((command.commandID shr 8) and 0xFF).toByte())
        appLayerPacketPayload.addAll(payload)
        return state.transportLayer.createDataPacket(state.transportLayerState, reliabilityBit, appLayerPacketPayload)
    }

    private fun incrementRTSequence(state: State) {
        state.currentRTSequence++
        if (state.currentRTSequence > 65535)
            state.currentRTSequence = 0
    }

    fun createCTRLConnectPacket(state: State): TransportLayer.Packet {
        val serialNumber: Int = 12345
        val payload = byteArrayListOfInts(
            (serialNumber shr 0) and 0xFF,
            (serialNumber shr 8) and 0xFF,
            (serialNumber shr 16) and 0xFF,
            (serialNumber shr 24) and 0xFF
        )
        return createAppLayerPacket(state, Command.CTRL_CONNECT, true, payload)
    }

    fun createCTRLGetServiceVersionPacket(state: State, serviceID: ServiceID): TransportLayer.Packet {
        return createAppLayerPacket(state, Command.CTRL_GET_SERVICE_VERSION, true, byteArrayListOfInts(serviceID.id))
    }

    fun createCTRLBindPacket(state: State): TransportLayer.Packet {
        // TODO: See the spec for this command. It is currently
        // unclear why the payload has to be 0x48.
        return createAppLayerPacket(state, Command.CTRL_BIND, true, byteArrayListOfInts(0x48))
    }

    fun createCTRLDisconnectPacket(state: State): TransportLayer.Packet {
        // TODO: See the spec for this command. It is currently
        // unclear why the payload has to be 0x6003, and why
        // Ruffy sets this to 0x0003 instead.
        return createAppLayerPacket(state, Command.CTRL_DISCONNECT, true, byteArrayListOfInts(0x03, 0x60))
    }

    fun createCTRLActivateServicePacket(state: State, serviceID: ServiceID): TransportLayer.Packet {
        return createAppLayerPacket(state, Command.CTRL_ACTIVATE_SERVICE, true, byteArrayListOfInts(serviceID.id, 1, 0))
    }

    fun createCTRLDeactivateAllServicesPacket(state: State): TransportLayer.Packet {
        return createAppLayerPacket(state, Command.CTRL_DEACTIVATE_ALL_SERVICES, true)
    }

    enum class RTButtonCode(val id: Int) {
        UP(0x30),
        DOWN(0xC0),
        MENU(0x03),
        CHECK(0x0C),
        NO_BUTTON(0x00)
    }

    fun createRTButtonStatusPacket(state: State, rtButtonCode: RTButtonCode, buttonStatusChanged: Boolean): TransportLayer.Packet {
        val payload = byteArrayListOfInts(
            (state.currentRTSequence shr 0) and 0xFF,
            (state.currentRTSequence shr 8) and 0xFF,
            rtButtonCode.id,
            if (buttonStatusChanged) 0xB7 else 0x48
        )

        incrementRTSequence(state)

        return createAppLayerPacket(state, Command.RT_BUTTON_STATUS, false, payload)
    }

    fun parseAppLayerPacketCommand(packet: TransportLayer.Packet): Command {
        val payload = packet.payload

        if (payload.size < 4)
            throw ParseException("Insufficient payload bytes in application layer packet", 0)

        val serviceIDInt = payload[1].toPosInt()
        val serviceID = ServiceID.fromInt(serviceIDInt) ?: throw ParseException("Invalid service ID 0x%02X".format(serviceIDInt), 1)

        val commandID = (payload[2].toPosInt() shl 0) or (payload[3].toPosInt() shl 8)
        val command = Command.fromIDs(serviceID, commandID) ?: throw ParseException(
            "Invalid command ID 0x%04X (service ID: %02X)".format(commandID, serviceIDInt), 2)

        return command
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

    fun parseRTDisplayPacket(packet: TransportLayer.Packet): RTDisplayContent {
        val payload = packet.payload

        if (payload.size < (4 + 5 + 96))
            throw ParseException("Insufficient payload bytes in RT display packet", 0)

        val reasonInt = payload[6].toPosInt()
        val reason = RTDisplayUpdateReason.fromInt(reasonInt) ?: throw ParseException(
            "Invalid RT display update reason %02X".format(reasonInt), 6)

        val row = when (val rowInt = payload[8].toPosInt()) {
            0x47 -> 0
            0x48 -> 1
            0xB7 -> 2
            0xB8 -> 3
            else -> throw ParseException("Invalid RT display update row %02X".format(rowInt), 8)
        }

        return RTDisplayContent(
            currentRTSequence = (payload[4].toPosInt() shl 0) or (payload[5].toPosInt() shl 8),
            reason = reason,
            index = payload[7].toPosInt(),
            row = row,
            pixels = payload.subList(9, 105)
        )
    }
}
