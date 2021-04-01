package info.nightscout.comboctl.base.testUtils

import info.nightscout.comboctl.base.ApplicationLayerIO
import info.nightscout.comboctl.base.TransportLayerIO
import info.nightscout.comboctl.base.toTransportLayerPacket
import kotlin.test.assertEquals
import kotlin.test.assertTrue

typealias TestPacketData = List<Byte>

fun newTestPacketSequence() = mutableListOf<TestPacketData>()

sealed class TestRefPacketItem {
    data class TransportLayerPacketItem(val packetInfo: TransportLayerIO.OutgoingPacketInfo) : TestRefPacketItem()
    data class ApplicationLayerPacketItem(val packet: ApplicationLayerIO.Packet) : TestRefPacketItem()
}

fun checkTestPacketSequence(referenceSequence: List<TestRefPacketItem>, testPacketSequence: List<TestPacketData>) {
    assertTrue(testPacketSequence.size >= referenceSequence.size)

    referenceSequence.zip(testPacketSequence) { referenceItem, tpLayerPacketData ->
        val testTpLayerPacket = tpLayerPacketData.toTransportLayerPacket()

        when (referenceItem) {
            is TestRefPacketItem.TransportLayerPacketItem -> {
                val refPacketInfo = referenceItem.packetInfo
                assertEquals(refPacketInfo.command, testTpLayerPacket.command, "Transport layer packet command mismatch")
                assertEquals(refPacketInfo.payload, testTpLayerPacket.payload, "Transport layer packet payload mismatch")
                assertEquals(refPacketInfo.reliable, testTpLayerPacket.reliabilityBit, "Transport layer packet reliability bit mismatch")
            }
            is TestRefPacketItem.ApplicationLayerPacketItem -> {
                val refAppLayerPacket = referenceItem.packet
                val testAppLayerPacket = ApplicationLayerIO.Packet(testTpLayerPacket)
                assertEquals(refAppLayerPacket, testAppLayerPacket)
            }
        }
    }
}
