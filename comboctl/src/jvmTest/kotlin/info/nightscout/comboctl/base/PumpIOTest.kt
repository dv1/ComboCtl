package info.nightscout.comboctl.base

import info.nightscout.comboctl.base.testUtils.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PumpIOTest {
    // Common test code.
    class TestStates(setupInvariantPumpData: Boolean) {
        var testPumpStateStore: TestPumpStateStore
        val testBluetoothAddress: BluetoothAddress
        var testIO: TestComboIO
        var pumpIO: PumpIO

        init {
            // Set up the invariant pump data to be able to test regular connections.

            testPumpStateStore = TestPumpStateStore()
            testBluetoothAddress = BluetoothAddress(byteArrayListOfInts(1, 2, 3, 4, 5, 6))

            if (setupInvariantPumpData) {
                val invariantPumpData = InvariantPumpData(
                    keyResponseAddress = 0x10,
                    clientPumpCipher = Cipher(byteArrayOfInts(
                        0x5a, 0x25, 0x0b, 0x75, 0xa9, 0x02, 0x21, 0xfa,
                        0xab, 0xbd, 0x36, 0x4d, 0x5c, 0xb8, 0x37, 0xd7)),
                    pumpClientCipher = Cipher(byteArrayOfInts(
                        0x2a, 0xb0, 0xf2, 0x67, 0xc2, 0x7d, 0xcf, 0xaa,
                        0x32, 0xb2, 0x48, 0x94, 0xe1, 0x6d, 0xe9, 0x5c)),
                    pumpID = "testPump"
                )
                testPumpStateStore.createPumpState(testBluetoothAddress, invariantPumpData)
            }

            testIO = TestComboIO()
            pumpIO = PumpIO(testPumpStateStore, testBluetoothAddress, testIO)
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

            checkDisconnectPacketData(testIO.sentPacketData.last())
            testIO.sentPacketData.removeAt(testIO.sentPacketData.size - 1)

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
            val invariantPumpData = testPumpStateStore.getInvariantPumpData(testBluetoothAddress)

            testIO.feedIncomingData(
                produceTpLayerPacket(
                    TransportLayerIO.OutgoingPacketInfo(
                        command = TransportLayerIO.Command.REGULAR_CONNECTION_REQUEST_ACCEPTED
                    ),
                    invariantPumpData.pumpClientCipher
                ).toByteList()
            )

            testIO.feedIncomingData(
                produceTpLayerPacket(
                    ApplicationLayerIO.Packet(
                        command = ApplicationLayerIO.Command.CTRL_CONNECT_RESPONSE
                    ).toTransportLayerPacketInfo(),
                    invariantPumpData.pumpClientCipher
                ).toByteList()
            )

            testIO.feedIncomingData(
                produceTpLayerPacket(
                    ApplicationLayerIO.Packet(
                        command = ApplicationLayerIO.Command.CTRL_ACTIVATE_SERVICE_RESPONSE,
                        payload = byteArrayListOfInts(1, 2, 3, 4, 5)
                    ).toTransportLayerPacketInfo(),
                    invariantPumpData.pumpClientCipher
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
            for (i in expectedInitialPacketSequence.indices)
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

        fun checkDisconnectPacketData(packetData: List<Byte>) {
            val appLayerPacket = ApplicationLayerIO.Packet(packetData.toTransportLayerPacket())
            assertEquals(ApplicationLayerIO.Command.CTRL_DISCONNECT, appLayerPacket.command, "Application layer packet command mismatch")
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

            launch {
                delay(300L)
                val invariantPumpData = testStates.testPumpStateStore.getInvariantPumpData(testStates.testBluetoothAddress)
                testIO.feedIncomingData(
                    produceTpLayerPacket(
                        ApplicationLayerIO.Packet(
                            command = ApplicationLayerIO.Command.RT_BUTTON_CONFIRMATION,
                            payload = byteArrayListOfInts(0, 0)
                        ).toTransportLayerPacketInfo(),
                        invariantPumpData.pumpClientCipher
                    ).toByteList()
                )
            }

            pumpIO.sendShortRTButtonPress(PumpIO.Button.UP)
            delay(500L)

            pumpIO.disconnect()

            testStates.checkAndRemoveInitialSentPackets()

            assertEquals(3, testIO.sentPacketData.size)

            testStates.checkRTButtonStatusPacketData(
                testIO.sentPacketData[0],
                ApplicationLayerIO.RTButtonCode.UP,
                true
            )
            testStates.checkRTButtonStatusPacketData(
                testIO.sentPacketData[1],
                ApplicationLayerIO.RTButtonCode.NO_BUTTON,
                true
            )
            testStates.checkDisconnectPacketData(testIO.sentPacketData[2])
        }
    }

    @Test
    fun checkCMDHistoryDeltaRetrieval() {
        runBlockingWithWatchdog(6000) {
            // Check that a simulated CMD history delta retrieval is performed successfully.
            // Feed in raw data bytes into the test IO. These raw bytes are packets that
            // contain history data with a series of events inside. Check that these packets
            // are correctly parsed and that the retrieved history is correct.

            val testStates = TestStates(setupInvariantPumpData = false)
            val mainScope = this
            val pumpIO = testStates.pumpIO
            val testIO = testStates.testIO

            // Need to set up custom keys since the test data was
            // created with those instead of the default test keys.
            val invariantPumpData = InvariantPumpData(
                keyResponseAddress = 0x10,
                clientPumpCipher = Cipher(byteArrayOfInts(
                    0x75, 0xb8, 0x88, 0xa8, 0xe7, 0x68, 0xc9, 0x25,
                    0x66, 0xc9, 0x3c, 0x4b, 0xd8, 0x09, 0x27, 0xd8)),
                pumpClientCipher = Cipher(byteArrayOfInts(
                    0xb8, 0x75, 0x8c, 0x54, 0x88, 0x71, 0x78, 0xed,
                    0xad, 0xb7, 0xb7, 0xc1, 0x48, 0x37, 0xf3, 0x07)),
                pumpID = "testPump"
            )
            testStates.testPumpStateStore.createPumpState(testStates.testBluetoothAddress, invariantPumpData)

            val historyBlockPacketData = listOf(
                // CMD_READ_HISTORY_BLOCK_RESPONSE
                byteArrayListOfInts(
                    0x10, 0xa3, 0x65, 0x00, 0x01, 0x08, 0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x10, 0xb7, 0x96, 0xa9, 0x00, 0x00, 0x10, 0x00, 0x48, 0xb7, 0x05, 0xaa, 0x0d, 0x93, 0x54, 0x0f, 0x00, 0x00,
                    0x00, 0x04, 0x00, 0x6b, 0xf3, 0x09, 0x3b, 0x01, 0x00, 0x92, 0x4c, 0xb1, 0x0d, 0x93, 0x54, 0x0f, 0x00, 0x00,
                    0x00, 0x05, 0x00, 0xa1, 0x25, 0x0b, 0x3b, 0x01, 0x00, 0xe4, 0x75, 0x46, 0x0e, 0x93, 0x54, 0x1d, 0x00, 0x00,
                    0x00, 0x06, 0x00, 0xb7, 0xda, 0x0d, 0x3b, 0x01, 0x00, 0x7e, 0x3e, 0x54, 0x0e, 0x93, 0x54, 0x1d, 0x00, 0x00,
                    0x00, 0x07, 0x00, 0x73, 0x49, 0x0f, 0x3b, 0x01, 0x00, 0x08, 0x07, 0x77, 0x0e, 0x93, 0x54, 0x05, 0x00, 0x00,
                    0x00, 0x04, 0x00, 0x2f, 0xd8, 0x11, 0x3b, 0x01, 0x00, 0xeb, 0x6a, 0x81, 0xf5, 0x6c, 0x43, 0xf0, 0x88, 0x15, 0x3b
                ),
                // CMD_CONFIRM_HISTORY_BLOCK_RESPONSE
                byteArrayListOfInts(
                    0x10, 0x23, 0x06, 0x00, 0x01, 0x0a, 0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x10, 0xb7, 0x99, 0xa9, 0x00, 0x00, 0x8f, 0xec, 0xfa, 0xa7, 0xf5, 0x0d, 0x01, 0x6c
                ),
                // CMD_READ_HISTORY_BLOCK_RESPONSE
                byteArrayListOfInts(
                    0x10, 0xa3, 0x65, 0x00, 0x01, 0x0c, 0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x10, 0xb7, 0x96, 0xa9, 0x00, 0x00, 0x0b, 0x00, 0x48, 0xb7, 0x05, 0x79, 0x0e, 0x93, 0x54, 0x05, 0x00, 0x00,
                    0x00, 0x05, 0x00, 0x0c, 0x40, 0x13, 0x3b, 0x01, 0x00, 0x9d, 0x53, 0xad, 0x0e, 0x93, 0x54, 0x12, 0x00, 0x00,
                    0x00, 0x06, 0x00, 0x46, 0xa5, 0x15, 0x3b, 0x01, 0x00, 0x07, 0x18, 0xb6, 0x0e, 0x93, 0x54, 0x12, 0x00, 0x00,
                    0x00, 0x07, 0x00, 0x8c, 0x73, 0x17, 0x3b, 0x01, 0x00, 0x71, 0x21, 0x13, 0x10, 0x93, 0x54, 0xb1, 0x00, 0x0f,
                    0x00, 0x08, 0x00, 0xbb, 0x78, 0x1a, 0x3b, 0x01, 0x00, 0xfe, 0xaa, 0xd2, 0x13, 0x93, 0x54, 0xb1, 0x00, 0x0f,
                    0x00, 0x09, 0x00, 0xce, 0x68, 0x1c, 0x3b, 0x01, 0x00, 0x64, 0xe1, 0x2c, 0xc8, 0x37, 0xb3, 0xe5, 0xb7, 0x7c, 0xc4
                ),
                // CMD_CONFIRM_HISTORY_BLOCK_RESPONSE
                byteArrayListOfInts(
                    0x10, 0x23, 0x06, 0x00, 0x01, 0x0e, 0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x10, 0xb7, 0x99, 0xa9, 0x00, 0x00, 0xe5, 0xab, 0x11, 0x6d, 0xfc, 0x60, 0xfb, 0xee
                ),
                // CMD_READ_HISTORY_BLOCK_RESPONSE
                byteArrayListOfInts(
                    0x10, 0xa3, 0x65, 0x00, 0x01, 0x10, 0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x10, 0xb7, 0x96, 0xa9, 0x00, 0x00, 0x06, 0x00, 0x48, 0xb7, 0x05, 0x5f, 0x15, 0x93, 0x54, 0xc1, 0x94, 0xe0,
                    0x01, 0x0a, 0x00, 0x76, 0x3b, 0x1e, 0x3b, 0x01, 0x00, 0x12, 0xd8, 0xc8, 0x1c, 0x93, 0x54, 0xc1, 0x94, 0xe0,
                    0x01, 0x0b, 0x00, 0xc8, 0xa4, 0x20, 0x3b, 0x01, 0x00, 0xa2, 0x3a, 0x59, 0x20, 0x93, 0x54, 0x40, 0x30, 0x93,
                    0x54, 0x18, 0x00, 0xbb, 0x0c, 0x23, 0x3b, 0x01, 0x00, 0x6f, 0x1f, 0x40, 0x30, 0x93, 0x54, 0x00, 0x00, 0x00,
                    0x00, 0x19, 0x00, 0x2b, 0x80, 0x24, 0x3b, 0x01, 0x00, 0x4e, 0x48, 0x85, 0x30, 0x93, 0x54, 0x14, 0x00, 0x00,
                    0x00, 0x04, 0x00, 0xe8, 0x98, 0x2b, 0x3b, 0x01, 0x00, 0xb7, 0xfa, 0x0e, 0x32, 0x37, 0x19, 0xb6, 0x59, 0x5a, 0xb1
                ),
                // CMD_CONFIRM_HISTORY_BLOCK_RESPONSE
                byteArrayListOfInts(
                    0x10, 0x23, 0x06, 0x00, 0x01, 0x12, 0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x10, 0xb7, 0x99, 0xa9, 0x00, 0x00, 0xae, 0xaa, 0xa7, 0x3a, 0xbc, 0x82, 0x8c, 0x15
                ),
                // CMD_READ_HISTORY_BLOCK_RESPONSE
                byteArrayListOfInts(
                    0x10, 0xa3, 0x1d, 0x00, 0x01, 0x14, 0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x10, 0xb7, 0x96, 0xa9, 0x00, 0x00, 0x01, 0x00, 0xb7, 0xb7, 0x01, 0x8f, 0x30, 0x93, 0x54, 0x14, 0x00, 0x00,
                    0x00, 0x05, 0x00, 0x57, 0xb0, 0x2d, 0x3b, 0x01, 0x00, 0x2d, 0xb1, 0x29, 0x32, 0xde, 0x3c, 0xa0, 0x80, 0x33, 0xd3
                ),
                // CMD_CONFIRM_HISTORY_BLOCK_RESPONSE
                byteArrayListOfInts(
                    0x10, 0x23, 0x06, 0x00, 0x01, 0x16, 0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x10, 0xb7, 0x99, 0xa9, 0x00, 0x00, 0x15, 0x63, 0xa5, 0x60, 0x3d, 0x75, 0xff, 0xfc
                )
            )

            val expectedHistoryDeltaEvents = listOf(
                ApplicationLayerIO.CMDHistoryEvent(
                    DateTime(2021, 2, 9, 16, 54, 42),
                    80649,
                    ApplicationLayerIO.CMDHistoryEventDetail.QuickBolusRequested(15)
                ),
                ApplicationLayerIO.CMDHistoryEvent(
                    DateTime(2021, 2, 9, 16, 54, 49),
                    80651,
                    ApplicationLayerIO.CMDHistoryEventDetail.QuickBolusInfused(15)
                ),

                ApplicationLayerIO.CMDHistoryEvent(
                    DateTime(2021, 2, 9, 16, 57, 6),
                    80653,
                    ApplicationLayerIO.CMDHistoryEventDetail.StandardBolusRequested(29, true)
                ),
                ApplicationLayerIO.CMDHistoryEvent(
                    DateTime(2021, 2, 9, 16, 57, 20),
                    80655,
                    ApplicationLayerIO.CMDHistoryEventDetail.StandardBolusInfused(29, true)
                ),

                ApplicationLayerIO.CMDHistoryEvent(
                    DateTime(2021, 2, 9, 16, 57, 55),
                    80657,
                    ApplicationLayerIO.CMDHistoryEventDetail.QuickBolusRequested(5)
                ),
                ApplicationLayerIO.CMDHistoryEvent(
                    DateTime(2021, 2, 9, 16, 57, 57),
                    80659,
                    ApplicationLayerIO.CMDHistoryEventDetail.QuickBolusInfused(5)
                ),

                ApplicationLayerIO.CMDHistoryEvent(
                    DateTime(2021, 2, 9, 16, 58, 45),
                    80661,
                    ApplicationLayerIO.CMDHistoryEventDetail.StandardBolusRequested(18, true)
                ),
                ApplicationLayerIO.CMDHistoryEvent(
                    DateTime(2021, 2, 9, 16, 58, 54),
                    80663,
                    ApplicationLayerIO.CMDHistoryEventDetail.StandardBolusInfused(18, true)
                ),

                ApplicationLayerIO.CMDHistoryEvent(
                    DateTime(2021, 2, 9, 17, 0, 19),
                    80666,
                    ApplicationLayerIO.CMDHistoryEventDetail.ExtendedBolusStarted(177, 15)
                ),
                ApplicationLayerIO.CMDHistoryEvent(
                    DateTime(2021, 2, 9, 17, 15, 18),
                    80668,
                    ApplicationLayerIO.CMDHistoryEventDetail.ExtendedBolusEnded(177, 15)
                ),

                ApplicationLayerIO.CMDHistoryEvent(
                    DateTime(2021, 2, 9, 17, 21, 31),
                    80670,
                    ApplicationLayerIO.CMDHistoryEventDetail.MultiwaveBolusStarted(193, 37, 30)
                ),
                ApplicationLayerIO.CMDHistoryEvent(
                    DateTime(2021, 2, 9, 17, 51, 8),
                    80672,
                    ApplicationLayerIO.CMDHistoryEventDetail.MultiwaveBolusEnded(193, 37, 30)
                ),

                ApplicationLayerIO.CMDHistoryEvent(
                    DateTime(2021, 2, 9, 18, 1, 25),
                    80675,
                    ApplicationLayerIO.CMDHistoryEventDetail.NewDateTimeSet(DateTime(2021, 2, 9, 19, 1, 0))
                ),

                ApplicationLayerIO.CMDHistoryEvent(
                    DateTime(2021, 2, 9, 19, 2, 5),
                    80683,
                    ApplicationLayerIO.CMDHistoryEventDetail.QuickBolusRequested(20)
                ),
                ApplicationLayerIO.CMDHistoryEvent(
                    DateTime(2021, 2, 9, 19, 2, 15),
                    80685,
                    ApplicationLayerIO.CMDHistoryEventDetail.QuickBolusInfused(20)
                )
            )

            testStates.feedInitialPackets()

            pumpIO.connect(
                backgroundIOScope = mainScope,
                onBackgroundIOException = { e -> fail("Exception thrown in background worker: $e") },
                initialMode = PumpIO.Mode.COMMAND,
                runKeepAliveLoop = false
            ).join()

            historyBlockPacketData.forEach { testIO.feedIncomingData(it) }

            val historyDelta = pumpIO.getCMDHistoryDelta(100)

            pumpIO.disconnect()

            assertEquals(expectedHistoryDeltaEvents.size, historyDelta.size)
            for (events in expectedHistoryDeltaEvents.zip(historyDelta))
                assertEquals(events.first, events.second)
        }
    }
}
