package info.nightscout.comboctl.base

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HighLevelIOTest {
    private class TestComboIO : ComboIO {
        val sentPacketData = mutableListOf<List<Byte>>()

        final override suspend fun send(dataToSend: List<Byte>) {
            sentPacketData.add(dataToSend)
        }

        final override suspend fun receive(): List<Byte> =
            throw ComboException("No more")

        final override fun cancelSend() = Unit

        final override fun cancelReceive() = Unit
    }

    private fun checkRTButtonStatusPacketData(
        packetData: List<Byte>,
        rtButtonCode: ApplicationLayer.RTButtonCode,
        buttonStatusChangedFlag: Boolean
    ) {
        val appLayerPacket = ApplicationLayer.Packet(TransportLayer.Packet(packetData))
        assertTrue(appLayerPacket.command == ApplicationLayer.Command.RT_BUTTON_STATUS)
        assertEquals(appLayerPacket.payload[2], rtButtonCode.id.toByte())
        assertEquals(appLayerPacket.payload[3], (if (buttonStatusChangedFlag) 0xB7 else 0x48).toByte())
    }

    private lateinit var tpLayerState: TestPersistentTLState
    private lateinit var tpLayer: TransportLayer
    private lateinit var appLayer: ApplicationLayer
    private lateinit var testIO: TestComboIO
    private lateinit var highLevelIO: HighLevelIO

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
        testIO = TestComboIO()
        highLevelIO = HighLevelIO(
            tpLayer,
            appLayer,
            testIO,
            {}
        )
    }

    fun checkLongRTButtonPressPacketSequence(appLayerButtonCode: ApplicationLayer.RTButtonCode) {
        assertTrue(testIO.sentPacketData.size >= 3)

        checkRTButtonStatusPacketData(
            testIO.sentPacketData.first(),
            appLayerButtonCode,
            true
        )
        testIO.sentPacketData.removeAt(0)

        checkRTButtonStatusPacketData(
            testIO.sentPacketData.last(),
            ApplicationLayer.RTButtonCode.NO_BUTTON,
            true
        )
        testIO.sentPacketData.removeAt(testIO.sentPacketData.size - 1)

        for (packetData in testIO.sentPacketData) {
            checkRTButtonStatusPacketData(
                packetData,
                appLayerButtonCode,
                false
            )
        }
    }

    @Test
    fun upDownLongRTButtonPress() {
        runBlocking {
            val mainScope = this
            mainScope.launch {
                highLevelIO.startLongRTButtonPress(HighLevelIO.Button.UP, mainScope)
                delay(500L)
                highLevelIO.stopLongRTButtonPress()
                delay(500L)
            }
        }
        checkLongRTButtonPressPacketSequence(ApplicationLayer.RTButtonCode.UP)
        testIO.sentPacketData.clear()

        runBlocking {
            val mainScope = this
            mainScope.launch {
                highLevelIO.startLongRTButtonPress(HighLevelIO.Button.DOWN, mainScope)
                delay(500L)
                highLevelIO.stopLongRTButtonPress()
                delay(500L)
            }
        }
        checkLongRTButtonPressPacketSequence(ApplicationLayer.RTButtonCode.DOWN)
        testIO.sentPacketData.clear()
    }

    @Test
    fun doubleLongButtonPress() {
        runBlocking {
            val mainScope = this
            mainScope.launch {
                highLevelIO.startLongRTButtonPress(HighLevelIO.Button.UP, mainScope)
                highLevelIO.startLongRTButtonPress(HighLevelIO.Button.UP, mainScope)
                delay(500L)
                highLevelIO.stopLongRTButtonPress()
                delay(500L)
            }
        }
        checkLongRTButtonPressPacketSequence(ApplicationLayer.RTButtonCode.UP)
        testIO.sentPacketData.clear()
    }

    @Test
    fun doubleLongButtonRelease() {
        runBlocking {
            val mainScope = this
            mainScope.launch {
                highLevelIO.startLongRTButtonPress(HighLevelIO.Button.UP, mainScope)
                delay(500L)
                highLevelIO.stopLongRTButtonPress()
                highLevelIO.stopLongRTButtonPress()
                delay(500L)
            }
        }
        checkLongRTButtonPressPacketSequence(ApplicationLayer.RTButtonCode.UP)
        testIO.sentPacketData.clear()
    }
}
