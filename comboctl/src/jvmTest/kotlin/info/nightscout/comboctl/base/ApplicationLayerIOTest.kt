package info.nightscout.comboctl.base

import info.nightscout.comboctl.base.testUtils.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.fail

class ApplicationLayerIOTest {
    @Test
    fun checkBasicApplicationLayerIOSequence() {
        // Run a basic sequence of IO operations and verify
        // that they produce the expected results.

        // The calls must be run from within a coroutine scope.
        // Starts a blocking scope with a watchdog that fails
        // the test if it does not finish within 5 seconds
        // (in case the tested code hangs).
        runBlockingWithWatchdog(5000) {
            val testPumpStateStore = TestPumpStateStore()
            val testComboIO = TestComboIO()
            val appLayerIO = ApplicationLayerIO(testPumpStateStore, testComboIO)

            val testDecryptedCPKey =
                byteArrayListOfInts(0x5a, 0x25, 0x0b, 0x75, 0xa9, 0x02, 0x21, 0xfa, 0xab, 0xbd, 0x36, 0x4d, 0x5c, 0xb8, 0x37, 0xd7)
            val testDecryptedPCKey =
                byteArrayListOfInts(0x2a, 0xb0, 0xf2, 0x67, 0xc2, 0x7d, 0xcf, 0xaa, 0x32, 0xb2, 0x48, 0x94, 0xe1, 0x6d, 0xe9, 0x5c)
            val testAddress = 0x10.toByte()

            testPumpStateStore.storePumpPairingData(PumpPairingData(
                clientPumpCipher = Cipher(testDecryptedCPKey.toByteArray()),
                pumpClientCipher = Cipher(testDecryptedPCKey.toByteArray()),
                keyResponseAddress = testAddress
            ))

            val ctrlConnectPacket = ApplicationLayerIO.createCTRLConnectPacket()
            val ctrlConnectResponsePacket = ApplicationLayerIO.Packet(
                command = ApplicationLayerIO.Command.CTRL_CONNECT_RESPONSE
            )

            appLayerIO.startIO(
                backgroundIOScope = this,
                onBackgroundIOException = { e -> fail("Exception thrown in background worker: $e") },
                pairingPINCallback = { nullPairingPIN() }
            )

            appLayerIO.sendPacketNoResponse(ctrlConnectPacket)

            // "Receive" by feeding the simulated data into the IO object.
            testComboIO.feedIncomingData(
                produceTpLayerPacket(
                    ctrlConnectResponsePacket.toTransportLayerPacketInfo(),
                    Cipher(testDecryptedPCKey.toByteArray())
                ).toByteList()
            )
            val receivedPacket = appLayerIO.receiveAppLayerPacket(ctrlConnectResponsePacket.command)

            appLayerIO.stopIO()

            assertEquals(2, testComboIO.sentPacketData.size)
            assertEquals(
                ctrlConnectPacket,
                ApplicationLayerIO.Packet(testComboIO.sentPacketData[0].toTransportLayerPacket())
            )
            assertEquals(
                ApplicationLayerIO.Command.CTRL_DISCONNECT,
                ApplicationLayerIO.Packet(testComboIO.sentPacketData[1].toTransportLayerPacket()).command
            )

            assertEquals(ctrlConnectResponsePacket, receivedPacket)
        }
    }

    @Test
    fun checkRTSequenceNumberAssignment() {
        // Check that ApplicationLayerIO fills in correctly
        // the RT sequence in outgoing RT packets.
        // We do this by sending 3 RT_KEEP_ALIVE_PACKETS.
        // Then, we check what the "sent" packets look like.
        // We expect exactly 3 packets in the sentPacketData
        // list, all of them being RT_KEEP_ALIVE_PACKETS.
        // The first packert should have RT sequence should o,
        // the second one should have RT sequence 1 etc.

        runBlockingWithWatchdog(5000) {
            val testPumpStateStore = TestPumpStateStore()
            val testComboIO = TestComboIO()
            val appLayerIO = ApplicationLayerIO(testPumpStateStore, testComboIO)

            val testDecryptedCPKey =
                byteArrayListOfInts(0x5a, 0x25, 0x0b, 0x75, 0xa9, 0x02, 0x21, 0xfa, 0xab, 0xbd, 0x36, 0x4d, 0x5c, 0xb8, 0x37, 0xd7)
            val testDecryptedPCKey =
                byteArrayListOfInts(0x2a, 0xb0, 0xf2, 0x67, 0xc2, 0x7d, 0xcf, 0xaa, 0x32, 0xb2, 0x48, 0x94, 0xe1, 0x6d, 0xe9, 0x5c)
            val testAddress = 0x10.toByte()

            testPumpStateStore.storePumpPairingData(PumpPairingData(
                clientPumpCipher = Cipher(testDecryptedCPKey.toByteArray()),
                pumpClientCipher = Cipher(testDecryptedPCKey.toByteArray()),
                keyResponseAddress = testAddress
            ))

            appLayerIO.startIO(
                backgroundIOScope = this,
                onBackgroundIOException = { e -> fail("Exception thrown in background worker: $e") },
                pairingPINCallback = { nullPairingPIN() }
            )

            appLayerIO.sendPacketNoResponse(ApplicationLayerIO.createRTKeepAlivePacket())
            appLayerIO.sendPacketNoResponse(ApplicationLayerIO.createRTKeepAlivePacket())
            appLayerIO.sendPacketNoResponse(ApplicationLayerIO.createRTKeepAlivePacket())

            appLayerIO.stopIO()

            assertEquals(4, testComboIO.sentPacketData.size)

            val disconnectPacket = ApplicationLayerIO.Packet(testComboIO.sentPacketData.last().toTransportLayerPacket())
            assertEquals(ApplicationLayerIO.Command.CTRL_DISCONNECT, disconnectPacket.command)
            testComboIO.sentPacketData.removeAt(testComboIO.sentPacketData.size - 1)

            testComboIO.sentPacketData.forEachIndexed {
                index, tpLayerPacketData ->

                val appLayerPacket = ApplicationLayerIO.Packet(tpLayerPacketData.toTransportLayerPacket())
                assertEquals(ApplicationLayerIO.Command.RT_KEEP_ALIVE, appLayerPacket.command)
                // RT_KEEP_ALIVE packets only have the RT sequence as the payload.
                assertEquals(2, appLayerPacket.payload.size)

                // Reassemble the RT sequence, which is stored
                // as a 16-bit little endian integer.
                val rtSequenceNumber = (appLayerPacket.payload[0].toPosInt() shl 0) or (appLayerPacket.payload[1].toPosInt() shl 8)

                assertEquals(index, rtSequenceNumber)
            }
        }
    }

    @Test
    fun checkCustomIncomingPacketProcessing() {
        // Test the custom incoming packet processing feature and
        // its ability to drop packets. We simulate 5 incoming packets,
        // three of which are RT_KEEP_ALIVE. These we want to drop,
        // that is, they never reach a waiting receiveAppLayerPacket()
        // call. Consequently, we expect only 2 packets to be received
        // via that call, while a third attempt at receiving should
        // cause that third call to be suspended indefinitely. Also,
        // we expect our processIncomingPacket function to see all
        // 5 packets. We count the number of RT_KEEP_ALIVE packets
        // observed to confirm that the 3 RT_KEEP_ALIVE packets are
        // in fact received by the ApplicationLayerIO.

        runBlockingWithWatchdog(6000) {
            val testPumpStateStore = TestPumpStateStore()
            val testComboIO = TestComboIO()
            val appLayerIO = object : ApplicationLayerIO(testPumpStateStore, testComboIO) {
                var keepAliveCounter = 0
                override fun processIncomingPacket(appLayerPacket: Packet): Boolean {
                    return if (appLayerPacket.command == ApplicationLayerIO.Command.RT_KEEP_ALIVE) {
                        keepAliveCounter++
                        false
                    } else
                        true
                }
            }

            val testDecryptedCPKey =
                byteArrayListOfInts(0x5a, 0x25, 0x0b, 0x75, 0xa9, 0x02, 0x21, 0xfa, 0xab, 0xbd, 0x36, 0x4d, 0x5c, 0xb8, 0x37, 0xd7)
            val testDecryptedPCKey =
                byteArrayListOfInts(0x2a, 0xb0, 0xf2, 0x67, 0xc2, 0x7d, 0xcf, 0xaa, 0x32, 0xb2, 0x48, 0x94, 0xe1, 0x6d, 0xe9, 0x5c)
            val testAddress = 0x10.toByte()

            testPumpStateStore.storePumpPairingData(PumpPairingData(
                clientPumpCipher = Cipher(testDecryptedCPKey.toByteArray()),
                pumpClientCipher = Cipher(testDecryptedPCKey.toByteArray()),
                keyResponseAddress = testAddress
            ))

            val ctrlConnectResponsePacket = ApplicationLayerIO.Packet(
                command = ApplicationLayerIO.Command.CTRL_CONNECT_RESPONSE
            )
            val ctrlBindResponsePacket = ApplicationLayerIO.Packet(
                command = ApplicationLayerIO.Command.CTRL_BIND_RESPONSE
            )

            appLayerIO.startIO(
                backgroundIOScope = this,
                onBackgroundIOException = { e -> fail("Exception thrown in background worker: $e") },
                pairingPINCallback = { nullPairingPIN() }
            )

            testComboIO.feedIncomingData(
                produceTpLayerPacket(
                    ctrlConnectResponsePacket.toTransportLayerPacketInfo(),
                    Cipher(testDecryptedPCKey.toByteArray())
                ).toByteList()
            )

            testComboIO.feedIncomingData(
                produceTpLayerPacket(
                    ApplicationLayerIO.createRTKeepAlivePacket().toTransportLayerPacketInfo(),
                    Cipher(testDecryptedPCKey.toByteArray())
                ).toByteList()
            )

            testComboIO.feedIncomingData(
                produceTpLayerPacket(
                    ApplicationLayerIO.createRTKeepAlivePacket().toTransportLayerPacketInfo(),
                    Cipher(testDecryptedPCKey.toByteArray())
                ).toByteList()
            )

            testComboIO.feedIncomingData(
                produceTpLayerPacket(
                    ApplicationLayerIO.createRTKeepAlivePacket().toTransportLayerPacketInfo(),
                    Cipher(testDecryptedPCKey.toByteArray())
                ).toByteList()
            )

            testComboIO.feedIncomingData(
                produceTpLayerPacket(
                    ctrlBindResponsePacket.toTransportLayerPacketInfo(),
                    Cipher(testDecryptedPCKey.toByteArray())
                ).toByteList()
            )

            // Check that we can get 2 packets and that the
            // three RT_KEEP_ALIVE packets were filtered out.
            for (i in 0 until 2) {
                val appLayerPacket = appLayerIO.receiveAppLayerPacket()
                assertNotEquals(ApplicationLayerIO.Command.RT_KEEP_ALIVE, appLayerPacket.command)
            }

            // A third receiveAppLayerPacket() call should never
            // return. Verify this by attempting that call within
            // a coroutine scope that has a watchdog attached.
            // We expect that watchdog to time out since the
            // receiveAppLayerPacket() suspends indefinitely.
            assertFailsWith<WatchdogTimeoutException> {
                coroutineScopeWithWatchdog(1000) {
                    appLayerIO.receiveAppLayerPacket()
                }
            }

            appLayerIO.stopIO()

            assertEquals(3, appLayerIO.keepAliveCounter)
        }
    }
}
