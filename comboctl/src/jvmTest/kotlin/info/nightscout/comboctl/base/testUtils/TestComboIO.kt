package info.nightscout.comboctl.base.testUtils

import info.nightscout.comboctl.base.ComboIO
import kotlinx.coroutines.channels.Channel

class TestComboIO : ComboIO {
    val sentPacketData = newTestPacketSequence()
    var incomingPacketDataChannel = Channel<TestPacketData>(Channel.UNLIMITED)

    override suspend fun send(dataToSend: TestPacketData) {
        sentPacketData.add(dataToSend)
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
