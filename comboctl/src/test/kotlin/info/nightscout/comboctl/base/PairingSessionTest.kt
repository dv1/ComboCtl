package info.nightscout.comboctl.base

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PairingSessionTest {
    val loggerFactory = LoggerFactory(StderrLoggerBackend(), LogLevel.DEBUG)
    val tpLayer = TransportLayer(loggerFactory.getLogger(LogCategory.TP_LAYER))
    val appLayer = ApplicationLayer()
    lateinit var tpLayerState: TransportLayer.State
    lateinit var appLayerState: ApplicationLayer.State

    @BeforeEach
    fun setup() {
        tpLayerState = TransportLayer.State()
        appLayerState = ApplicationLayer.State(tpLayer, tpLayerState)
    }

    @Test
    fun verifyPairingProcess() {
        // Test the pairing coroutine by feeding in data that was recorded from
        // pairing an actual Combo with Ruffy (using an nVidia SHIELD Tablet as
        // client). Check that the outgoing packets match those that Ruffy sent
        // to the Combo.

        val incomingDataChannel = Channel<List<Byte>>()
        val outgoingDataChannel = Channel<List<Byte>>()
        val testBtFriendlyName = "SHIELD Tablet"
        val testPIN = PairingPIN(intArrayOf(2, 6, 0, 6, 8, 1, 9, 2, 7, 3))

        val expectedOutgoingPackets = listOf<TransportLayer.Packet>(
            TransportLayer.Packet(
                commandID = TransportLayer.CommandID.REQUEST_PAIRING_CONNECTION,
                version = 0x10.toByte(),
                sequenceBit = false,
                reliabilityBit = false,
                address = 0xf0.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(0xB2, 0x11),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))),
            TransportLayer.Packet(
                commandID = TransportLayer.CommandID.REQUEST_KEYS,
                version = 0x10.toByte(),
                sequenceBit = false,
                reliabilityBit = false,
                address = 0xf0.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(0x81, 0x41),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))),
            TransportLayer.Packet(
                commandID = TransportLayer.CommandID.GET_AVAILABLE_KEYS,
                version = 0x10.toByte(),
                sequenceBit = false,
                reliabilityBit = false,
                address = 0xf0.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(0x90, 0x71),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))),
            TransportLayer.Packet(
                commandID = TransportLayer.CommandID.REQUEST_ID,
                version = 0x10.toByte(),
                sequenceBit = false,
                reliabilityBit = false,
                address = 0x10.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(
                    0x08, 0x29, 0x00, 0x00, 0x53, 0x48, 0x49, 0x45, 0x4C, 0x44, 0x20, 0x54, 0x61, 0x62, 0x6C, 0x65, 0x74),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0x99, 0xED, 0x58, 0x29, 0x54, 0x6A, 0xBB, 0x35))),
            TransportLayer.Packet(
                commandID = TransportLayer.CommandID.REQUEST_REGULAR_CONNECTION,
                version = 0x10.toByte(),
                sequenceBit = false,
                reliabilityBit = false,
                address = 0x10.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0xCF, 0xEE, 0x61, 0xF2, 0x83, 0xD3, 0xDC, 0x39))),
            TransportLayer.Packet(
                commandID = TransportLayer.CommandID.DATA,
                version = 0x10.toByte(),
                sequenceBit = false,
                reliabilityBit = true,
                address = 0x10.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(0x10, 0x00, 0x55, 0x90, 0x39, 0x30, 0x00, 0x00),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0xEF, 0xB9, 0x9E, 0xB6, 0x7B, 0x30, 0x7A, 0xCB))),
            TransportLayer.Packet(
                commandID = TransportLayer.CommandID.ACK_RESPONSE,
                version = 0x10.toByte(),
                sequenceBit = false,
                reliabilityBit = false,
                address = 0x10.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0x76, 0x01, 0xB6, 0xAB, 0x48, 0xDB, 0x4E, 0x87))),
            TransportLayer.Packet(
                commandID = TransportLayer.CommandID.DATA,
                version = 0x10.toByte(),
                sequenceBit = true,
                reliabilityBit = true,
                address = 0x10.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(0x10, 0x00, 0x65, 0x90, 0xB7),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0xEC, 0xA6, 0x4D, 0x59, 0x1F, 0xD3, 0xF4, 0xCD))),
            TransportLayer.Packet(
                commandID = TransportLayer.CommandID.ACK_RESPONSE,
                version = 0x10.toByte(),
                sequenceBit = true,
                reliabilityBit = false,
                address = 0x10.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x06, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0x15, 0xA9, 0x9A, 0x64, 0x9C, 0x57, 0xD2, 0x72))),
            TransportLayer.Packet(
                commandID = TransportLayer.CommandID.DATA,
                version = 0x10.toByte(),
                sequenceBit = false,
                reliabilityBit = true,
                address = 0x10.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x07, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(0x10, 0x00, 0x95, 0x90, 0x48),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0x39, 0x8E, 0x57, 0xCC, 0xEE, 0x68, 0x41, 0xBB))),
            TransportLayer.Packet(
                commandID = TransportLayer.CommandID.ACK_RESPONSE,
                version = 0x10.toByte(),
                sequenceBit = false,
                reliabilityBit = false,
                address = 0x10.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0x38, 0x3D, 0x52, 0x56, 0x73, 0xBF, 0x59, 0xD8))),
            TransportLayer.Packet(
                commandID = TransportLayer.CommandID.REQUEST_REGULAR_CONNECTION,
                version = 0x10.toByte(),
                sequenceBit = false,
                reliabilityBit = false,
                address = 0x10.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x09, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0x1D, 0xD4, 0xD5, 0xC6, 0x03, 0x3E, 0x0A, 0xBE))),
            TransportLayer.Packet(
                commandID = TransportLayer.CommandID.DATA,
                version = 0x10.toByte(),
                sequenceBit = false,
                reliabilityBit = true,
                address = 0x10.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x0A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(0x10, 0x00, 0x5A, 0x00, 0x03, 0x00),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0x9D, 0xF4, 0x0F, 0x24, 0x44, 0xE3, 0x52, 0x03)))
        )

        val incomingPackets = listOf<TransportLayer.Packet>(
            TransportLayer.Packet(
                commandID = TransportLayer.CommandID.PAIRING_CONNECTION_REQUEST_ACCEPTED,
                version = 0x10.toByte(),
                sequenceBit = false,
                reliabilityBit = false,
                address = 0x0f.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(0x00, 0xF0, 0x6D),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))),
            TransportLayer.Packet(
                commandID = TransportLayer.CommandID.KEY_RESPONSE,
                version = 0x10.toByte(),
                sequenceBit = false,
                reliabilityBit = false,
                address = 0x01.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(
                    0x54, 0x9E, 0xF7, 0x7D, 0x8D, 0x27, 0x48, 0x0C, 0x1D, 0x11, 0x43, 0xB8, 0xF7, 0x08, 0x92, 0x7B,
                    0xF0, 0xA3, 0x75, 0xF3, 0xB4, 0x5F, 0xE2, 0xF3, 0x46, 0x63, 0xCD, 0xDD, 0xC4, 0x96, 0x37, 0xAC),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0x25, 0xA0, 0x26, 0x47, 0x29, 0x37, 0xFF, 0x66))),
            TransportLayer.Packet(
                commandID = TransportLayer.CommandID.ID_RESPONSE,
                version = 0x10.toByte(),
                sequenceBit = false,
                reliabilityBit = false,
                address = 0x01.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(
                    0x59, 0x99, 0xD4, 0x01, 0x50, 0x55, 0x4D, 0x50, 0x5F, 0x31, 0x30, 0x32, 0x33, 0x30, 0x39, 0x34, 0x37),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0x6E, 0xF4, 0x4D, 0xFE, 0x35, 0x6E, 0xFE, 0xB4))),
            TransportLayer.Packet(
                commandID = TransportLayer.CommandID.REGULAR_CONNECTION_REQUEST_ACCEPTED,
                version = 0x10.toByte(),
                sequenceBit = false,
                reliabilityBit = false,
                address = 0x01.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0x40, 0x00, 0xB3, 0x41, 0x84, 0x55, 0x5F, 0x12))),
            TransportLayer.Packet(
                commandID = TransportLayer.CommandID.ACK_RESPONSE,
                version = 0x10.toByte(),
                sequenceBit = false,
                reliabilityBit = false,
                address = 0x01.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0xD6, 0x39, 0xBD, 0x84, 0x66, 0xB7, 0xD3, 0x8C))),
            TransportLayer.Packet(
                commandID = TransportLayer.CommandID.DATA,
                version = 0x10.toByte(),
                sequenceBit = false,
                reliabilityBit = true,
                address = 0x01.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(0x10, 0x00, 0x55, 0xA0, 0x00, 0x00),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0xF4, 0x4D, 0xB8, 0xB3, 0xC1, 0x2E, 0xDE, 0x97))),
            TransportLayer.Packet(
                commandID = TransportLayer.CommandID.ACK_RESPONSE,
                version = 0x10.toByte(),
                sequenceBit = true,
                reliabilityBit = false,
                address = 0x01.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x06, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0x62, 0x9D, 0x5E, 0xD4, 0x94, 0x07, 0x29, 0x15))),
            TransportLayer.Packet(
                commandID = TransportLayer.CommandID.DATA,
                version = 0x10.toByte(),
                sequenceBit = true,
                reliabilityBit = true,
                address = 0x01.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x07, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(0x10, 0x00, 0x65, 0xA0, 0x00, 0x00, 0x01, 0x00),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0x9D, 0xB3, 0x3F, 0x84, 0x87, 0x49, 0xE3, 0xAC))),
            TransportLayer.Packet(
                commandID = TransportLayer.CommandID.ACK_RESPONSE,
                version = 0x10.toByte(),
                sequenceBit = false,
                reliabilityBit = false,
                address = 0x01.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0xC1, 0x59, 0x1C, 0x03, 0x1A, 0xA7, 0x82, 0x89))),
            TransportLayer.Packet(
                commandID = TransportLayer.CommandID.DATA,
                version = 0x10.toByte(),
                sequenceBit = false,
                reliabilityBit = true,
                address = 0x01.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x09, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(0x10, 0x00, 0x95, 0xA0, 0x00, 0x00, 0x48),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0xF0, 0x49, 0xD4, 0x91, 0x01, 0x26, 0x33, 0xEF))),
            TransportLayer.Packet(
                commandID = TransportLayer.CommandID.REGULAR_CONNECTION_REQUEST_ACCEPTED,
                version = 0x10.toByte(),
                sequenceBit = false,
                reliabilityBit = false,
                address = 0x01.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x0A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0x34, 0xD2, 0x8B, 0x40, 0x27, 0x44, 0x82, 0x89))),
            TransportLayer.Packet(
                commandID = TransportLayer.CommandID.ACK_RESPONSE,
                version = 0x10.toByte(),
                sequenceBit = false,
                reliabilityBit = false,
                address = 0x01.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x0B, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0x61, 0x12, 0x4C, 0xF7, 0x2A, 0x29, 0x6E, 0x34)))
        )

        runBlocking {
            // Feed recorded incoming packets into the incoming data channel,
            // _and_ verify packets coming from the outgoing data channel.
            // Do these two processes in separate coroutine scopes to simulate
            // simultaneously operating devices (pump and client).
            launch {
                for (incomingPacket in incomingPackets) {
                    incomingDataChannel.send(incomingPacket.toByteList())
                }
            }
            launch {
                for (expectedOutgoingPacket in expectedOutgoingPackets) {
                    val outgoingPacket = outgoingDataChannel.receive().toTransportLayerPacket()
                    assertEquals(expectedOutgoingPacket, outgoingPacket)
                }
            }

            // performPairing() does the actual pairing, and is implemented
            // as a suspend function. It throws exceptions in case of errors.
            // If pairing completes successfully, the incoming data channel
            // will have its reception canceled (cancel() will be called),
            // and the outgoingDataChannel will be close()d.
            performPairing(
                appLayer,
                appLayerState,
                testBtFriendlyName,
                loggerFactory.getLogger(LogCategory.APP_LAYER),
                { getPINDeferred -> getPINDeferred.complete(testPIN) },
                incomingDataChannel,
                outgoingDataChannel
            )

            loggerFactory.getLogger(LogCategory.TP_LAYER).log(LogLevel.DEBUG) { "Test completed" }
        }
    }
}
