package info.nightscout.comboctl.base

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ApplicationLayerTest {
    lateinit var tpLayerState: TestPersistentTLState
    lateinit var tpLayer: TransportLayer
    lateinit var appLayer: ApplicationLayer

    // Common checks for verifying that a newly created packet is OK.
    private fun checkCreatedPacket(
        packet: ApplicationLayer.Packet,
        command: ApplicationLayer.Command,
        appLayerPayload: ArrayList<Byte> = arrayListOf()
    ) {
        val tpLayerPacket = packet.toTransportLayerPacket(tpLayer)

        // Verify the DATA packet header fields.
        assertEquals(0x10, tpLayerPacket.version)
        assertEquals(TransportLayer.CommandID.DATA, tpLayerPacket.commandID)
        assertEquals(tpLayerState.keyResponseAddress, tpLayerPacket.address)

        // Verify application layer payload by recreating the corresponding
        // transport layer DATA packet payload and comparing the recreation
        // with the payload of the actual DATA packet.
        val payload = byteArrayListOfInts(
            0x10,
            command.serviceID.id,
            (command.commandID shr 0) and 0xFF,
            (command.commandID shr 8) and 0xFF
        )
        payload.addAll(appLayerPayload)
        assertEquals(payload, tpLayerPacket.payload)
    }

    @BeforeEach
    fun setup() {
        tpLayerState = TestPersistentTLState()

        tpLayerState.keyResponseAddress = 0x10
        tpLayerState.clientPumpCipher = Cipher(byteArrayOfInts(
            0x5a, 0x25, 0x0b, 0x75, 0xa9, 0x02, 0x21, 0xfa,
            0xab, 0xbd, 0x36, 0x4d, 0x5c, 0xb8, 0x37, 0xd7))
        tpLayerState.pumpClientCipher = Cipher(byteArrayOfInts(
            0x2a, 0xb0, 0xf2, 0x67, 0xc2, 0x7d, 0xcf, 0xaa,
            0x32, 0xb2, 0x48, 0x94, 0xe1, 0x6d, 0xe9, 0x5c))

        tpLayer = TransportLayer(tpLayerState)
        appLayer = ApplicationLayer()
    }

    @Test
    fun checkCTRLConnectPacket() {
        val packet = appLayer.createCTRLConnectPacket()
        val serialNumber = Constants.APPLICATION_LAYER_CONNECT_SERIAL_NUMBER
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
        val packet = appLayer.createCTRLGetServiceVersionPacket(ApplicationLayer.ServiceID.COMMAND_MODE)
        checkCreatedPacket(
            packet,
            ApplicationLayer.Command.CTRL_GET_SERVICE_VERSION,
            byteArrayListOfInts(ApplicationLayer.ServiceID.COMMAND_MODE.id)
        )
    }

    @Test
    fun checkCTRLBindPacket() {
        val packet = appLayer.createCTRLBindPacket()
        checkCreatedPacket(
            packet,
            ApplicationLayer.Command.CTRL_BIND,
            byteArrayListOfInts(0x48)
        )
    }

    @Test
    fun checkCTRLDisconnect() {
        val packet = appLayer.createCTRLDisconnectPacket()
        checkCreatedPacket(
            packet,
            ApplicationLayer.Command.CTRL_DISCONNECT,
            byteArrayListOfInts(0x03, 0x00)
        )
    }

    @Test
    fun checkCTRLActivateServicePacket() {
        val packet = appLayer.createCTRLActivateServicePacket(ApplicationLayer.ServiceID.COMMAND_MODE)
        checkCreatedPacket(
            packet,
            ApplicationLayer.Command.CTRL_ACTIVATE_SERVICE,
            byteArrayListOfInts(ApplicationLayer.ServiceID.COMMAND_MODE.id, 1, 0)
        )
    }

    @Test
    fun checkCTRLDeactivateAllServicesPacket() {
        val packet = appLayer.createCTRLDeactivateAllServicesPacket()
        checkCreatedPacket(
            packet,
            ApplicationLayer.Command.CTRL_DEACTIVATE_ALL_SERVICES
        )
    }

    @Test
    fun checkRTButtonStatusPacket() {
        val packet = appLayer.createRTButtonStatusPacket(ApplicationLayer.RTButtonCode.UP, true)
        checkCreatedPacket(
            packet,
            ApplicationLayer.Command.RT_BUTTON_STATUS,
            byteArrayListOfInts(0x00, 0x00, ApplicationLayer.RTButtonCode.UP.id, 0xB7)
        )
    }

    @Test
    fun checkRTDisplayPacket() {
        val tpLayerPacket = TransportLayer.Packet(byteArrayListOfInts(
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

        val appLayerPacket = ApplicationLayer.Packet(tpLayerPacket)

        assertEquals(ApplicationLayer.Command.RT_DISPLAY, appLayerPacket.command)

        val content = appLayer.parseRTDisplayPacket(appLayerPacket)
        assertEquals(0x0002, content.currentRTSequence)
        assertEquals(ApplicationLayer.RTDisplayUpdateReason.PUMP, content.reason)
        assertEquals(0x00, content.index)
        assertEquals(2, content.row)
    }
}
