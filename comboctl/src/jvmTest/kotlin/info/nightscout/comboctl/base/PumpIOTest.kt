package info.nightscout.comboctl.base

import info.nightscout.comboctl.base.testUtils.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.delay

class PumpIOTest {
    // Common test code.
    class TestStates(setupPumpPairingData: Boolean) {
        var testPumpStateStore: TestPersistentPumpStateStore
        var testIO: TestComboIO
        var pumpIO: PumpIO

        init {
            // Set up the "pairing data" to be able to test regular connections.

            testPumpStateStore = TestPersistentPumpStateStore()

            if (setupPumpPairingData) {
                val pumpPairingData = PumpPairingData(
                    keyResponseAddress = 0x10,
                    clientPumpCipher = Cipher(byteArrayOfInts(
                        0x5a, 0x25, 0x0b, 0x75, 0xa9, 0x02, 0x21, 0xfa,
                        0xab, 0xbd, 0x36, 0x4d, 0x5c, 0xb8, 0x37, 0xd7)),
                    pumpClientCipher = Cipher(byteArrayOfInts(
                        0x2a, 0xb0, 0xf2, 0x67, 0xc2, 0x7d, 0xcf, 0xaa,
                        0x32, 0xb2, 0x48, 0x94, 0xe1, 0x6d, 0xe9, 0x5c))
                )
                testPumpStateStore.storePumpPairingData(pumpPairingData)
            }

            testIO = TestComboIO()
            pumpIO = PumpIO(testPumpStateStore, testIO)
        }

        // Tests that a long button press is handled correctly.
        // We expect an initial RT_BUTTON_STATUS packet with its
        // buttonStatusChanged flag set to true, followed by
        // a series of similar packet with the buttonStatusChanged
        // flag set to false, and finished by an RT_BUTTON_STATUS
        // packet whose button code is NO_BUTTON.
        fun checkLongRTButtonPressPacketSequence(appLayerButtonCode: ApplicationLayerIO.RTButtonCode) {
            assertTrue(
                testIO.sentPacketData.size >= 3,
                "Expected at least 3 items in sentPacketData list, got ${testIO.sentPacketData.size}"
            )

            checkRTButtonStatusPacketData(
                testIO.sentPacketData.first(),
                appLayerButtonCode,
                true
            )
            testIO.sentPacketData.removeAt(0)

            checkRTButtonStatusPacketData(
                testIO.sentPacketData.last(),
                ApplicationLayerIO.RTButtonCode.NO_BUTTON,
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

        // Feeds initial connection setup packets into the test IO
        // that would normally be sent by the Combo during connection
        // setup. In that setup, the Combo is instructed to switch to
        // the RT mode, so this also feeds a CTRL_ACTIVATE_SERVICE_RESPONSE
        // packet into the IO.
        suspend fun feedInitialPackets() {
            testIO.feedIncomingData(
                produceTpLayerPacket(
                    TransportLayerIO.OutgoingPacketInfo(
                        command = TransportLayerIO.Command.REGULAR_CONNECTION_REQUEST_ACCEPTED
                    ),
                    testPumpStateStore.pairingData!!.pumpClientCipher
                ).toByteList()
            )

            testIO.feedIncomingData(
                produceTpLayerPacket(
                    ApplicationLayerIO.Packet(
                        command = ApplicationLayerIO.Command.CTRL_CONNECT_RESPONSE
                    ).toTransportLayerPacketInfo(),
                    testPumpStateStore.pairingData!!.pumpClientCipher
                ).toByteList()
            )

            testIO.feedIncomingData(
                produceTpLayerPacket(
                    ApplicationLayerIO.Packet(
                        command = ApplicationLayerIO.Command.CTRL_ACTIVATE_SERVICE_RESPONSE,
                        payload = byteArrayListOfInts(1, 2, 3, 4, 5)
                    ).toTransportLayerPacketInfo(),
                    testPumpStateStore.pairingData!!.pumpClientCipher
                ).toByteList()
            )
        }

        // This removes initial connection setup packets that are
        // normally sent to the Combo. Outgoing packets are recorded
        // in the testIO.sentPacketData list. In the tests here, we
        // are not interested in these initial packets. This function
        // gets rid of them.
        fun checkAndRemoveInitialSentPackets() {
            val expectedInitialPacketSequence = listOf(
                TestRefPacketItem.TransportLayerPacketItem(
                    TransportLayerIO.createRequestRegularConnectionPacketInfo()
                ),
                TestRefPacketItem.ApplicationLayerPacketItem(
                    ApplicationLayerIO.createCTRLConnectPacket()
                ),
                TestRefPacketItem.ApplicationLayerPacketItem(
                    ApplicationLayerIO.createCTRLActivateServicePacket(ApplicationLayerIO.ServiceID.RT_MODE)
                )
            )

            checkTestPacketSequence(expectedInitialPacketSequence, testIO.sentPacketData)
            for (i in 0 until expectedInitialPacketSequence.size)
                testIO.sentPacketData.removeAt(0)
        }

        fun checkRTButtonStatusPacketData(
            packetData: List<Byte>,
            rtButtonCode: ApplicationLayerIO.RTButtonCode,
            buttonStatusChangedFlag: Boolean
        ) {
            val appLayerPacket = ApplicationLayerIO.Packet(packetData.toTransportLayerPacket())
            assertEquals(ApplicationLayerIO.Command.RT_BUTTON_STATUS, appLayerPacket.command, "Application layer packet command mismatch")
            assertEquals(rtButtonCode.id.toByte(), appLayerPacket.payload[2], "RT_BUTTON_STATUS button byte mismatch")
            assertEquals((if (buttonStatusChangedFlag) 0xB7 else 0x48).toByte(), appLayerPacket.payload[3], "RT_BUTTON_STATUS status flag mismatch")
        }
    }

    @Test
    fun checkUpDownLongRTButtonPress() {
        // Basic long press test. After connecting to the simulated Combo,
        // the UP button is long-pressed. Then, the client reconnects to the
        // Combo, and the same is done with the DOWN button. This tests that
        // no states remain from a previous connection, and also of course
        // tests that long-presses are handled correctly.
        // The connection is established with the RT Keep-alive loop disabled
        // to avoid having to deal with RT_KEEP_ALIVE packets in the
        // testIO.sentPacketData list.

        runBlockingWithWatchdog(6000) {
            val testStates = TestStates(true)
            val mainScope = this
            val testIO = testStates.testIO
            val pumpIO = testStates.pumpIO

            testStates.feedInitialPackets()

            pumpIO.connect(
                backgroundIOScope = mainScope,
                onBackgroundIOException = { e -> fail("Exception thrown in background worker: $e") },
                runKeepAliveLoop = false
            ).join()

            pumpIO.startLongRTButtonPress(PumpIO.Button.UP)
            delay(500L)
            pumpIO.stopLongRTButtonPress()
            delay(500L)

            pumpIO.disconnect()

            testStates.checkAndRemoveInitialSentPackets()
            testStates.checkLongRTButtonPressPacketSequence(ApplicationLayerIO.RTButtonCode.UP)

            testIO.resetSentPacketData()
            testIO.resetIncomingPacketDataChannel()

            testStates.feedInitialPackets()

            pumpIO.connect(
                backgroundIOScope = mainScope,
                onBackgroundIOException = { e -> fail("Exception thrown in background worker: $e") },
                runKeepAliveLoop = false
            ).join()

            pumpIO.startLongRTButtonPress(PumpIO.Button.DOWN)
            delay(500L)
            pumpIO.stopLongRTButtonPress()
            delay(500L)

            pumpIO.disconnect()

            testStates.checkAndRemoveInitialSentPackets()
            testStates.checkLongRTButtonPressPacketSequence(ApplicationLayerIO.RTButtonCode.DOWN)
        }
    }

    @Test
    fun checkDoubleLongButtonPress() {
        // Check what happens if the user issues redundant startLongRTButtonPress()
        // calls. The second call here should be ignored.

        runBlockingWithWatchdog(6000) {
            val testStates = TestStates(true)
            val mainScope = this
            val pumpIO = testStates.pumpIO

            testStates.feedInitialPackets()

            pumpIO.connect(
                backgroundIOScope = mainScope,
                onBackgroundIOException = { e -> fail("Exception thrown in background worker: $e") },
                runKeepAliveLoop = false
            ).join()

            pumpIO.startLongRTButtonPress(PumpIO.Button.UP)
            pumpIO.startLongRTButtonPress(PumpIO.Button.UP)
            delay(500L)
            pumpIO.stopLongRTButtonPress()
            delay(500L)

            pumpIO.disconnect()

            testStates.checkAndRemoveInitialSentPackets()
            testStates.checkLongRTButtonPressPacketSequence(ApplicationLayerIO.RTButtonCode.UP)
        }
    }

    @Test
    fun checkDoubleLongButtonRelease() {
        // Check what happens if the user issues redundant stopLongRTButtonPress()
        // calls. The second call here should be ignored.

        runBlockingWithWatchdog(6000) {
            val testStates = TestStates(true)
            val mainScope = this
            val pumpIO = testStates.pumpIO

            testStates.feedInitialPackets()

            pumpIO.connect(
                backgroundIOScope = mainScope,
                onBackgroundIOException = { e -> fail("Exception thrown in background worker: $e") },
                runKeepAliveLoop = false
            ).join()

            pumpIO.startLongRTButtonPress(PumpIO.Button.UP)
            delay(500L)
            pumpIO.stopLongRTButtonPress()
            pumpIO.stopLongRTButtonPress()
            delay(500L)

            pumpIO.disconnect()

            testStates.checkAndRemoveInitialSentPackets()
            testStates.checkLongRTButtonPressPacketSequence(ApplicationLayerIO.RTButtonCode.UP)
        }
    }

    @Test
    fun checkShortButtonPress() {
        // Check that a short button press is handled correctly.
        // Short button presses are performed by sending two RT_BUTTON_STATUS
        // packets. The first one contains the actual button code, the second
        // one contains a NO_BUTTON code.

        runBlockingWithWatchdog(6000) {
            val testStates = TestStates(true)
            val mainScope = this
            val pumpIO = testStates.pumpIO
            val testIO = testStates.testIO

            testStates.feedInitialPackets()

            pumpIO.connect(
                backgroundIOScope = mainScope,
                onBackgroundIOException = { e -> fail("Exception thrown in background worker: $e") },
                runKeepAliveLoop = false
            ).join()

            pumpIO.sendShortRTButtonPress(PumpIO.Button.UP)
            delay(500L)

            pumpIO.disconnect()

            testStates.checkAndRemoveInitialSentPackets()

            assertEquals(2, testIO.sentPacketData.size)

            testStates.checkRTButtonStatusPacketData(
                testIO.sentPacketData.first(),
                ApplicationLayerIO.RTButtonCode.UP,
                true
            )
            testStates.checkRTButtonStatusPacketData(
                testIO.sentPacketData.last(),
                ApplicationLayerIO.RTButtonCode.NO_BUTTON,
                true
            )
        }
    }
}
