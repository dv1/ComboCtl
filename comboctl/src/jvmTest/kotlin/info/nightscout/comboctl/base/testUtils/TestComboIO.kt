package info.nightscout.comboctl.base.testUtils

import info.nightscout.comboctl.base.ApplicationLayer
import info.nightscout.comboctl.base.Cipher
import info.nightscout.comboctl.base.ComboIO
import info.nightscout.comboctl.base.TransportLayer
import info.nightscout.comboctl.base.byteArrayListOfInts
import info.nightscout.comboctl.base.toAppLayerPacket
import info.nightscout.comboctl.base.toTransportLayerPacket
import kotlinx.coroutines.channels.Channel
import kotlin.test.assertNotNull

class TestComboIO : ComboIO {
    val sentPacketData = newTestPacketSequence()
    var incomingPacketDataChannel = Channel<TestPacketData>(Channel.UNLIMITED)

    var respondToRTKeypressWithConfirmation = false
    var pumpClientCipher: Cipher? = null

    override suspend fun send(dataToSend: TestPacketData) {
        sentPacketData.add(dataToSend)

        if (respondToRTKeypressWithConfirmation) {
            assertNotNull(pumpClientCipher)
            val tpLayerPacket = dataToSend.toTransportLayerPacket()
            if (tpLayerPacket.command == TransportLayer.Command.DATA) {
                try {
                    val appLayerPacket = tpLayerPacket.toAppLayerPacket()
                    if (appLayerPacket.command == ApplicationLayer.Command.RT_BUTTON_STATUS) {
                        feedIncomingData(
                            produceTpLayerPacket(
                                ApplicationLayer.Packet(
                                    command = ApplicationLayer.Command.RT_BUTTON_CONFIRMATION,
                                    payload = byteArrayListOfInts(0, 0)
                                ).toTransportLayerPacketInfo(),
                                pumpClientCipher!!
                            ).toByteList()
                        )
                    }
                } catch (ignored: ApplicationLayer.ErrorCodeException) {
                }
            }
        }
    }

    override suspend fun receive(): TestPacketData =
        incomingPacketDataChannel.receive()

    suspend fun feedIncomingData(dataToFeed: TestPacketData) =
        incomingPacketDataChannel.send(dataToFeed)

    fun resetSentPacketData() = sentPacketData.clear()

    fun resetIncomingPacketDataChannel() {
        incomingPacketDataChannel.close()
        incomingPacketDataChannel = Channel(Channel.UNLIMITED)
    }
}
