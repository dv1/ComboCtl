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

    class IncorrectPacketException(
        val appLayerPacket: Packet,
        val expectedCommand: Command
    ) : ComboException("Incorrect packet: expected ${expectedCommand.name} packet, got ${appLayerPacket.command?.name ?: "<invalid>"} one")

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

    class Packet() {
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

        var command: Command? = null

        var payload: ArrayList<Byte> = ArrayList<Byte>(0)
            set(value) {
                require(value.size <= (65535 - PACKET_HEADER_SIZE))
                field = value
            }

        constructor(tpLayerPacket: TransportLayer.Packet) : this() {
            require(tpLayerPacket.commandID == TransportLayer.CommandID.DATA)
            require(tpLayerPacket.payload.size >= (PACKET_HEADER_SIZE))

            val appPacketBytes = tpLayerPacket.payload

            majorVersion = (appPacketBytes[VERSION_BYTE_OFFSET].toPosInt() shr 4) and 0xF
            minorVersion = appPacketBytes[VERSION_BYTE_OFFSET].toPosInt() and 0xF

            val serviceIDInt = appPacketBytes[SERVICE_ID_BYTE_OFFSET].toPosInt()
            val serviceID = ServiceID.fromInt(serviceIDInt) ?: throw InvalidServiceIDException(tpLayerPacket, serviceIDInt)
            val commandID = (tpLayerPacket.payload[COMMAND_ID_BYTE_OFFSET + 0].toPosInt() shl 0) or
                (tpLayerPacket.payload[COMMAND_ID_BYTE_OFFSET + 1].toPosInt() shl 8)
            command = Command.fromIDs(serviceID, commandID) ?: throw InvalidCommandIDException(tpLayerPacket, serviceID, commandID)

            payload = ArrayList<Byte>(appPacketBytes.subList(PAYLOAD_BYTES_OFFSET, appPacketBytes.size))
        }

        override fun equals(other: Any?) =
            (this === other) ||
                (other is Packet) &&
                (majorVersion == other.majorVersion) &&
                (minorVersion == other.minorVersion) &&
                (command == other.command) &&
                (payload == other.payload)

        fun toTransportLayerPacket(appLayerState: ApplicationLayer.State): TransportLayer.Packet {
            require(command != null)

            val appLayerPacketPayload = ArrayList<Byte>(PACKET_HEADER_SIZE + payload.size)
            appLayerPacketPayload.add(((majorVersion shl 4) or minorVersion).toByte())
            appLayerPacketPayload.add(command!!.serviceID.id.toByte())
            appLayerPacketPayload.add(((command!!.commandID shr 0) and 0xFF).toByte())
            appLayerPacketPayload.add(((command!!.commandID shr 8) and 0xFF).toByte())
            appLayerPacketPayload.addAll(payload)

            return appLayerState.transportLayer.createDataPacket(
                appLayerState.transportLayerState,
                command!!.reliable,
                appLayerPacketPayload
            )
        }
    }

    private fun incrementRTSequence(state: State) {
        state.currentRTSequence++
        if (state.currentRTSequence > 65535)
            state.currentRTSequence = 0
    }

    fun createCTRLConnectPacket(): Packet {
        val serialNumber: Int = 12345
        val payload = byteArrayListOfInts(
            (serialNumber shr 0) and 0xFF,
            (serialNumber shr 8) and 0xFF,
            (serialNumber shr 16) and 0xFF,
            (serialNumber shr 24) and 0xFF
        )
        return Packet().apply {
            command = Command.CTRL_CONNECT
            this.payload = payload
        }
    }

    fun createCTRLGetServiceVersionPacket(serviceID: ServiceID): Packet {
        return Packet().apply {
            command = Command.CTRL_GET_SERVICE_VERSION
            payload = byteArrayListOfInts(serviceID.id)
        }
    }

    fun createCTRLBindPacket(): Packet {
        // TODO: See the spec for this command. It is currently
        // unclear why the payload has to be 0x48.
        return Packet().apply {
            command = Command.CTRL_BIND
            payload = byteArrayListOfInts(0x48)
        }
    }

    fun createCTRLDisconnectPacket(): Packet {
        // TODO: See the spec for this command. It is currently
        // unclear why the payload has to be 0x6003, and why
        // Ruffy sets this to 0x0003 instead.
        return Packet().apply {
            command = Command.CTRL_DISCONNECT
            payload = byteArrayListOfInts(0x03, 0x60)
        }
    }

    fun createCTRLActivateServicePacket(serviceID: ServiceID): Packet {
        return Packet().apply {
            command = Command.CTRL_ACTIVATE_SERVICE
            payload = byteArrayListOfInts(serviceID.id, 1, 0)
        }
    }

    fun createCTRLDeactivateAllServicesPacket(): Packet {
        return Packet().apply { command = Command.CTRL_DEACTIVATE_ALL_SERVICES }
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

        return Packet().apply {
            command = Command.RT_BUTTON_STATUS
            this.payload = payload
        }
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
