package info.nightscout.comboctl.base

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ApplicationLayerTest {
    val tpLayer = TransportLayer(LoggerFactory(StderrLoggerBackend()).getLogger(LogCategory.TP_LAYER))
    val tpLayerState = TransportLayer.State()
    val appLayer = ApplicationLayer()
    val appLayerState = ApplicationLayer.State(tpLayer, tpLayerState)

    private fun checkCreatedPacket(
        packet: ComboPacket,
        command: ApplicationLayer.Command,
        appLayerPayload: ArrayList<Byte> = arrayListOf()
    ) {
        assertEquals(1, packet.majorVersion)
        assertEquals(0, packet.minorVersion)
        assertEquals(TransportLayer.CommandID.DATA.id, packet.commandID)
        assertEquals(tpLayerState.keyResponseSourceAddress, packet.sourceAddress)
        assertEquals(tpLayerState.keyResponseDestinationAddress, packet.destinationAddress)

        val payload = byteArrayListOfInts(
            0x10,
            command.serviceID.id,
            (command.commandID shr 0) and 0xFF,
            (command.commandID shr 8) and 0xFF
        )
        payload.addAll(appLayerPayload)
        assertEquals(payload, packet.payload)
    }

    @BeforeEach
    fun setup() {
        tpLayerState.keyResponseSourceAddress = 1
        tpLayerState.keyResponseDestinationAddress = 0
        tpLayerState.clientPumpCipher = Cipher(byteArrayOfInts(
            0x5a, 0x25, 0x0b, 0x75, 0xa9, 0x02, 0x21, 0xfa,
            0xab, 0xbd, 0x36, 0x4d, 0x5c, 0xb8, 0x37, 0xd7))
        tpLayerState.pumpClientCipher = Cipher(byteArrayOfInts(
            0x2a, 0xb0, 0xf2, 0x67, 0xc2, 0x7d, 0xcf, 0xaa,
            0x32, 0xb2, 0x48, 0x94, 0xe1, 0x6d, 0xe9, 0x5c))
    }

    @Test
    fun checkCTRLConnectPacket() {
        val packet = appLayer.createCTRLConnectPacket(appLayerState)
        val serialNumber: Int = 12345
        checkCreatedPacket(
            packet,
            ApplicationLayer.Command.CTRL_CONNECT,
            byteArrayListOfInts(
                (serialNumber shr 0) and 0xFF,
                (serialNumber shr 8) and 0xFF,
                (serialNumber shr 16) and 0xFF,
                (serialNumber shr 24) and 0xFF
            )
        )
    }

    @Test
    fun checkCTRLGetServiceVersionPacket() {
        val packet = appLayer.createCTRLGetServiceVersionPacket(appLayerState, ApplicationLayer.ServiceID.COMMAND_MODE)
        checkCreatedPacket(
            packet,
            ApplicationLayer.Command.CTRL_GET_SERVICE_VERSION,
            byteArrayListOfInts(ApplicationLayer.ServiceID.COMMAND_MODE.id)
        )
    }

    @Test
    fun checkCTRLBindPacket() {
        val packet = appLayer.createCTRLBindPacket(appLayerState)
        checkCreatedPacket(
            packet,
            ApplicationLayer.Command.CTRL_BIND,
            byteArrayListOfInts(0x48)
        )
    }

    @Test
    fun checkCTRLDisconnect() {
        val packet = appLayer.createCTRLDisconnectPacket(appLayerState)
        checkCreatedPacket(
            packet,
            ApplicationLayer.Command.CTRL_DISCONNECT,
            byteArrayListOfInts(0x03, 0x60)
        )
    }

    @Test
    fun checkCTRLActivateServicePacket() {
        val packet = appLayer.createCTRLActivateServicePacket(appLayerState, ApplicationLayer.ServiceID.COMMAND_MODE)
        checkCreatedPacket(
            packet,
            ApplicationLayer.Command.CTRL_ACTIVATE_SERVICE,
            byteArrayListOfInts(ApplicationLayer.ServiceID.COMMAND_MODE.id, 1, 0)
        )
    }

    @Test
    fun checkCTRLDeactivateAllServicesPacket() {
        val packet = appLayer.createCTRLDeactivateAllServicesPacket(appLayerState)
        checkCreatedPacket(
            packet,
            ApplicationLayer.Command.CTRL_DEACTIVATE_ALL_SERVICES
        )
    }

    @Test
    fun checkRTButtonStatusPacket() {
        appLayerState.currentRTSequence = 0x1122
        val packet = appLayer.createRTButtonStatusPacket(appLayerState, ApplicationLayer.RTButtonCode.UP, true)
        checkCreatedPacket(
            packet,
            ApplicationLayer.Command.RT_BUTTON_STATUS,
            byteArrayListOfInts(0x22, 0x11, ApplicationLayer.RTButtonCode.UP.id, 0xB7)
        )
    }

    @Test
    fun checkRTDisplayPacket() {
        val packet = ComboPacket(byteArrayListOfInts(
            0x10,
            0x03,
            0x69, 0x00,
            0x01,
            0x13, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x10, 0x48, 0x55, 0x05, 0x02, 0x00, 0x48, 0x00, 0xb7, 0x00,
            0x00, 0x7f, 0x7f, 0x00, 0x01, 0x7f, 0x7f, 0x00, 0x00, 0x01, 0x0f, 0x7e,
            0x70, 0x00, 0x3f, 0x7f, 0x40, 0x40, 0x7f, 0x3f, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x1f, 0x3f, 0x60, 0x40, 0x40, 0x60, 0x3f, 0x1f, 0x00, 0x00, 0x00,
            0x00, 0x1f, 0x3f, 0x60, 0x40, 0x40, 0x60, 0x3f, 0x1f, 0x00, 0x00, 0x00,
            0x00, 0x70, 0x70, 0x70, 0x00, 0x00, 0x00, 0x1f, 0x3f, 0x60, 0x40, 0x40,
            0x60, 0x3f, 0x1f, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x40, 0x7f, 0x42, 0x00, 0x00, 0x7f, 0x7f, 0x00, 0x00, 0x00, 0x7f,
            0x7f, 0x00, 0x00, 0x00, 0x7f, 0x7f, 0x00, 0x00, 0x00, 0x7f, 0x7f,
            0xbd, 0x1a, 0x44, 0xfc, 0x76, 0xf5, 0x2e, 0xb9
        ))

        val command = appLayer.parseAppLayerPacketCommand(packet)
        assertEquals(ApplicationLayer.Command.RT_DISPLAY, command)

        val content = appLayer.parseRTDisplayPacket(packet)
        assertEquals(0x0002, content.currentRTSequence)
        assertEquals(ApplicationLayer.RTDisplayUpdateReason.PUMP, content.reason)
        assertEquals(0x00, content.index)
        assertEquals(2, content.row)
    }
}
