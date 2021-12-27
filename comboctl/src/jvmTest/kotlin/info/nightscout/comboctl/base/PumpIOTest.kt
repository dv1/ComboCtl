package info.nightscout.comboctl.base

import info.nightscout.comboctl.base.testUtils.TestComboIO
import info.nightscout.comboctl.base.testUtils.TestPumpStateStore
import info.nightscout.comboctl.base.testUtils.TestRefPacketItem
import info.nightscout.comboctl.base.testUtils.checkTestPacketSequence
import info.nightscout.comboctl.base.testUtils.produceTpLayerPacket
import info.nightscout.comboctl.base.testUtils.runBlockingWithWatchdog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
            Logger.threshold = LogLevel.VERBOSE

            // Set up the invariant pump data to be able to test regular connections.

            testPumpStateStore = TestPumpStateStore()
            testBluetoothAddress = BluetoothAddress(byteArrayListOfInts(1, 2, 3, 4, 5, 6))

            testIO = TestComboIO()
            testIO.respondToRTKeypressWithConfirmation = true

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
                testIO.pumpClientCipher = invariantPumpData.pumpClientCipher
            }

            pumpIO = PumpIO(testPumpStateStore, testBluetoothAddress, testIO)
        }

        // Tests that a long button press is handled correctly.
        // We expect an initial RT_BUTTON_STATUS packet with its
        // buttonStatusChanged flag set to true, followed by
        // a series of similar packet with the buttonStatusChanged
        // flag set to false, and finished by an RT_BUTTON_STATUS
        // packet whose button code is NO_BUTTON.
        fun checkLongRTButtonPressPacketSequence(appLayerButton: ApplicationLayer.RTButton) {
            assertTrue(
                testIO.sentPacketData.size >= 3,
                "Expected at least 3 items in sentPacketData list, got ${testIO.sentPacketData.size}"
            )

            checkRTButtonStatusPacketData(
                testIO.sentPacketData.first(),
                appLayerButton,
                true
            )
            testIO.sentPacketData.removeAt(0)

            checkDisconnectPacketData(testIO.sentPacketData.last())
            testIO.sentPacketData.removeAt(testIO.sentPacketData.size - 1)

            checkRTButtonStatusPacketData(
                testIO.sentPacketData.last(),
                ApplicationLayer.RTButton.NO_BUTTON,
                true
            )
            testIO.sentPacketData.removeAt(testIO.sentPacketData.size - 1)

            for (packetData in testIO.sentPacketData) {
                checkRTButtonStatusPacketData(
                    packetData,
                    appLayerButton,
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
                    TransportLayer.OutgoingPacketInfo(
                        command = TransportLayer.Command.REGULAR_CONNECTION_REQUEST_ACCEPTED
                    ),
                    invariantPumpData.pumpClientCipher
                ).toByteList()
            )

            testIO.feedIncomingData(
                produceTpLayerPacket(
                    ApplicationLayer.Packet(
                        command = ApplicationLayer.Command.CTRL_CONNECT_RESPONSE
                    ).toTransportLayerPacketInfo(),
                    invariantPumpData.pumpClientCipher
                ).toByteList()
            )

            testIO.feedIncomingData(
                produceTpLayerPacket(
                    ApplicationLayer.Packet(
                        command = ApplicationLayer.Command.CTRL_ACTIVATE_SERVICE_RESPONSE,
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
                    TransportLayer.createRequestRegularConnectionPacketInfo()
                ),
                TestRefPacketItem.ApplicationLayerPacketItem(
                    ApplicationLayer.createCTRLConnectPacket()
                ),
                TestRefPacketItem.ApplicationLayerPacketItem(
                    ApplicationLayer.createCTRLActivateServicePacket(ApplicationLayer.ServiceID.RT_MODE)
                )
            )

            checkTestPacketSequence(expectedInitialPacketSequence, testIO.sentPacketData)
            for (i in expectedInitialPacketSequence.indices)
                testIO.sentPacketData.removeAt(0)
        }

        fun checkRTButtonStatusPacketData(
            packetData: List<Byte>,
            rtButton: ApplicationLayer.RTButton,
            buttonStatusChangedFlag: Boolean
        ) {
            val appLayerPacket = ApplicationLayer.Packet(packetData.toTransportLayerPacket())
            assertEquals(ApplicationLayer.Command.RT_BUTTON_STATUS, appLayerPacket.command, "Application layer packet command mismatch")
            assertEquals(rtButton.id.toByte(), appLayerPacket.payload[2], "RT_BUTTON_STATUS button byte mismatch")
            assertEquals((if (buttonStatusChangedFlag) 0xB7 else 0x48).toByte(), appLayerPacket.payload[3], "RT_BUTTON_STATUS status flag mismatch")
        }

        fun checkDisconnectPacketData(packetData: List<Byte>) {
            val appLayerPacket = ApplicationLayer.Packet(packetData.toTransportLayerPacket())
            assertEquals(ApplicationLayer.Command.CTRL_DISCONNECT, appLayerPacket.command, "Application layer packet command mismatch")
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

            pumpIO.connectAsync(
                packetReceiverScope = mainScope,
                progressReporter = null,
                runHeartbeat = false
            ).join()

            /* launch {
                delay(300L)
                val invariantPumpData = testStates.testPumpStateStore.getInvariantPumpData(testStates.testBluetoothAddress)
                testIO.feedIncomingData(
                    produceTpLayerPacket(
                        ApplicationLayer.Packet(
                            command = ApplicationLayer.Command.RT_BUTTON_CONFIRMATION,
                            payload = byteArrayListOfInts(0, 0)
                        ).toTransportLayerPacketInfo(),
                        invariantPumpData.pumpClientCipher
                    ).toByteList()
                )
            } */

            pumpIO.sendShortRTButtonPress(ApplicationLayer.RTButton.UP)
            delay(500L)

            pumpIO.disconnect()

            testStates.checkAndRemoveInitialSentPackets()

            assertEquals(3, testIO.sentPacketData.size)

            testStates.checkRTButtonStatusPacketData(
                testIO.sentPacketData[0],
                ApplicationLayer.RTButton.UP,
                true
            )
            testStates.checkRTButtonStatusPacketData(
                testIO.sentPacketData[1],
                ApplicationLayer.RTButton.NO_BUTTON,
                true
            )
            testStates.checkDisconnectPacketData(testIO.sentPacketData[2])
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

            pumpIO.connectAsync(
                packetReceiverScope = mainScope,
                progressReporter = null,
                runHeartbeat = false
            ).join()

            /* launch {
                delay(300L)
                val invariantPumpData = testStates.testPumpStateStore.getInvariantPumpData(testStates.testBluetoothAddress)
                testIO.feedIncomingData(
                    produceTpLayerPacket(
                        ApplicationLayer.Packet(
                            command = ApplicationLayer.Command.RT_BUTTON_CONFIRMATION,
                            payload = byteArrayListOfInts(0, 0)
                        ).toTransportLayerPacketInfo(),
                        invariantPumpData.pumpClientCipher
                    ).toByteList()
                )
            } */

            var counter = 0
            pumpIO.startLongRTButtonPress(ApplicationLayer.RTButton.UP) {
                counter++

                counter <= 1
            }
            pumpIO.waitForLongRTButtonPressToFinish()

            pumpIO.disconnect()

            testStates.checkAndRemoveInitialSentPackets()
            testStates.checkLongRTButtonPressPacketSequence(ApplicationLayer.RTButton.UP)

            /* testIO.resetSentPacketData()
            testIO.resetIncomingPacketDataChannel()

            testStates.feedInitialPackets()
            testIO.feedIncomingData(
                produceTpLayerPacket(
                    ApplicationLayer.Packet(
                        command = ApplicationLayer.Command.RT_BUTTON_CONFIRMATION,
                        payload = byteArrayListOfInts(0, 0)
                    ).toTransportLayerPacketInfo(),
                    testStates.testPumpStateStore.getInvariantPumpData(testStates.testBluetoothAddress).pumpClientCipher // TODO simplify
                ).toByteList()
            )

            pumpIO.connectAsync(
                packetReceiverScope = mainScope,
                progressReporter = null,
                runHeartbeat = false
            ).join()

            pumpIO.startLongRTButtonPress(ApplicationLayer.RTButton.DOWN)
            delay(500L)
            pumpIO.stopLongRTButtonPress()
            delay(500L)

            pumpIO.disconnect()

            testStates.checkAndRemoveInitialSentPackets()
            testStates.checkLongRTButtonPressPacketSequence(ApplicationLayer.RTButton.DOWN) */
        }
    }

    /* @Test
    fun checkDoubleLongButtonPress() {
        // Check what happens if the user issues redundant startLongRTButtonPress()
        // calls. The second call here should be ignored.

        runBlockingWithWatchdog(6000) {
            val testStates = TestStates(true)
            val mainScope = this
            val pumpIO = testStates.pumpIO

            testStates.feedInitialPackets()

            pumpIO.connectAsync(
                packetReceiverScope = mainScope,
                progressReporter = null,
                runHeartbeat = false
            ).join()

            launch {
                delay(300L)
                val invariantPumpData = testStates.testPumpStateStore.getInvariantPumpData(testStates.testBluetoothAddress)
                testStates.testIO.feedIncomingData(
                    produceTpLayerPacket(
                        ApplicationLayer.Packet(
                            command = ApplicationLayer.Command.RT_BUTTON_CONFIRMATION,
                            payload = byteArrayListOfInts(0, 0)
                        ).toTransportLayerPacketInfo(),
                        invariantPumpData.pumpClientCipher
                    ).toByteList()
                )
            }

            //pumpIO.startLongRTButtonPress(ApplicationLayer.RTButton.UP)
            //pumpIO.startLongRTButtonPress(ApplicationLayer.RTButton.UP)

            var counter = 0
            pumpIO.startLongRTButtonPress(ApplicationLayer.RTButton.UP) {
                counter++

                counter <= 1
            }
            pumpIO.startLongRTButtonPress(ApplicationLayer.RTButton.UP)
            pumpIO.waitForLongRTButtonPressToFinish()
            /*
            delay(500L)
            pumpIO.stopLongRTButtonPress()
            delay(500L)
            */

            pumpIO.disconnect()

            testStates.checkAndRemoveInitialSentPackets()
            testStates.checkLongRTButtonPressPacketSequence(ApplicationLayer.RTButton.UP)
        }
    } */
}