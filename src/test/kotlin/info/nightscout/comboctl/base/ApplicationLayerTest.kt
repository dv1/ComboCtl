package info.nightscout.comboctl.base

import org.junit.Assert.assertEquals
import org.junit.Test

class ApplicationLayerTest {
    @Test
    fun checkCtrlCommands() {
        val tpLayer = TransportLayer()
        val tpLayerState = TransportLayer.State()
        val appLayer = ApplicationLayer()
        val appLayerState = ApplicationLayer.State(tpLayer, tpLayerState)

        tpLayerState.keyResponseSourceAddress = 1
        tpLayerState.keyResponseDestinationAddress = 0
        tpLayerState.clientPumpCipher = Cipher(byteArrayOfInts(
            0x5a, 0x25, 0x0b, 0x75, 0xa9, 0x02, 0x21, 0xfa,
            0xab, 0xbd, 0x36, 0x4d, 0x5c, 0xb8, 0x37, 0xd7))
        tpLayerState.pumpClientCipher = Cipher(byteArrayOfInts(
            0x2a, 0xb0, 0xf2, 0x67, 0xc2, 0x7d, 0xcf, 0xaa,
            0x32, 0xb2, 0x48, 0x94, 0xe1, 0x6d, 0xe9, 0x5c))

        val packet = appLayer.createCTRLConnectPacket(appLayerState)
        assertEquals(1, packet.majorVersion)
        assertEquals(0, packet.minorVersion)
        assertEquals(TransportLayer.CommandID.DATA.id, packet.commandID)
        assertEquals(tpLayerState.keyResponseSourceAddress, packet.sourceAddress)
        assertEquals(tpLayerState.keyResponseDestinationAddress, packet.destinationAddress)

        val serialNumber: Int = 12345
        val payload = byteArrayListOfInts(
            0x10,
            ApplicationLayer.Command.CTRL_CONNECT.serviceID.id,
            (ApplicationLayer.Command.CTRL_CONNECT.commandID shr 0) and 0xFF,
            (ApplicationLayer.Command.CTRL_CONNECT.commandID shr 8) and 0xFF,
            (serialNumber shr 0) and 0xFF,
            (serialNumber shr 8) and 0xFF,
            (serialNumber shr 16) and 0xFF,
            (serialNumber shr 24) and 0xFF
        )
        assertEquals(payload, packet.payload)
    }
}
