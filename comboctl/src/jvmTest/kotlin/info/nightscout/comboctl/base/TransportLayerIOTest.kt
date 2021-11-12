package info.nightscout.comboctl.base

import info.nightscout.comboctl.base.testUtils.TestComboIO
import info.nightscout.comboctl.base.testUtils.TestPumpStateStore
import info.nightscout.comboctl.base.testUtils.runBlockingWithWatchdog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay

class TransportLayerIOTest {
    @Test
    fun parsePacketData() {
        // Test the packet parser by parsing hardcoded packet data
        // and verifying the individual packet property values.

        val packetDataWithCRCPayload = byteArrayListOfInts(
            0x10, // version
            0x09, // request_pairing_connection command (sequence and data reliability bit set to 0)
            0x02, 0x00, // payload length
            0xF0, // address
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // nonce
            0x99, 0x44, // payload
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 // nullbyte MAC
        )

        // The actual parsing.
        val packet = packetDataWithCRCPayload.toTransportLayerPacket()

        // Check the individual properties.

        assertEquals(0x10, packet.version)

        assertFalse(packet.sequenceBit)

        assertFalse(packet.reliabilityBit)

        assertEquals(TransportLayerIO.Command.REQUEST_PAIRING_CONNECTION, packet.command)

        assertEquals(0xF0.toByte(), packet.address)

        assertEquals(Nonce.nullNonce(), packet.nonce)

        assertEquals(byteArrayListOfInts(0x99, 0x44), packet.payload)

        assertEquals(NullMachineAuthCode, packet.machineAuthenticationCode)
    }

    @Test
    fun createPacketData() {
        // Create packet, and check that it is correctly converted to a byte list.

        val packet = TransportLayerIO.Packet(
            command = TransportLayerIO.Command.REQUEST_PAIRING_CONNECTION,
            version = 0x42,
            sequenceBit = true,
            reliabilityBit = false,
            address = 0x45,
            nonce = Nonce(byteArrayListOfInts(0x0A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0B)),
            payload = byteArrayListOfInts(0x50, 0x60, 0x70),
            machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08))
        )

        val byteList = packet.toByteList()

        val expectedPacketData = byteArrayListOfInts(
            0x42, // version
            0x80 or 0x09, // command 0x09 with sequence bit enabled
            0x03, 0x00, // payload length
            0x45, // address,
            0x0A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0B, // nonce
            0x50, 0x60, 0x70, // payload
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 // MAC
        )

        assertEquals(byteList, expectedPacketData)
    }

    @Test
    fun verifyPacketDataIntegrityWithCRC() {
        // Create packet and verify that the CRC check detects data corruption.

        val packet = TransportLayerIO.Packet(
            command = TransportLayerIO.Command.REQUEST_PAIRING_CONNECTION,
            version = 0x42,
            sequenceBit = true,
            reliabilityBit = false,
            address = 0x45,
            nonce = Nonce(byteArrayListOfInts(0x0A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0B)),
            machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        )

        // Check that the computed CRC is correct.
        packet.computeCRC16Payload()
        val expectedCRCPayload = byteArrayListOfInts(0xE1, 0x7B)
        assertEquals(expectedCRCPayload, packet.payload)

        // The CRC should match, since it was just computed.
        assertTrue(packet.verifyCRC16Payload())

        // Simulate data corruption by altering the CRC itself.
        // This should produce a CRC mismatch, since the check
        // will recompute the CRC from the header data.
        packet.payload[0] = (packet.payload[0].toPosInt() xor 0xFF).toByte()
        assertFalse(packet.verifyCRC16Payload())
    }

    @Test
    fun verifyPacketDataIntegrityWithMAC() {
        // Create packet and verify that the MAC check detects data corruption.

        val key = ByteArray(CIPHER_KEY_SIZE).apply { fill('0'.code.toByte()) }
        val cipher = Cipher(key)

        val packet = TransportLayerIO.Packet(
            command = TransportLayerIO.Command.REQUEST_PAIRING_CONNECTION,
            version = 0x42,
            sequenceBit = true,
            reliabilityBit = false,
            address = 0x45,
            nonce = Nonce(byteArrayListOfInts(0x0A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0B)),
            payload = byteArrayListOfInts(0x00, 0x00)
        )

        // Check that the computed MAC is correct.
        packet.authenticate(cipher)
        val expectedMAC = MachineAuthCode(byteArrayListOfInts(0x00, 0xC5, 0x48, 0xB3, 0xA8, 0xE6, 0x97, 0x76))
        assertEquals(expectedMAC, packet.machineAuthenticationCode)

        // The MAC should match, since it was just computed.
        assertTrue(packet.verifyAuthentication(cipher))

        // Simulate data corruption by altering the payload.
        // This should produce a MAC mismatch.
        packet.payload[0] = 0xFF.toByte()
        assertFalse(packet.verifyAuthentication(cipher))
    }

    @Test
    fun checkBasicTransportLayerIOSequence() {
        // Run a basic sequence of IO operations and verify
        // that they produce the expected results.

        // The calls must be run from within a coroutine scope.
        // Starts a blocking scope with a watchdog that fails
        // the test if it does not finish within 5 seconds
        // (in case the tested code hangs).
        runBlockingWithWatchdog(5000) {
            val testPumpStateStore = TestPumpStateStore()
            val testComboIO = TestComboIO()
            val testBluetoothAddress = BluetoothAddress(byteArrayListOfInts(1, 2, 3, 4, 5, 6))
            val tpLayerIO = TransportLayerIO(testPumpStateStore, testBluetoothAddress, testComboIO)

            // We'll simulate sending a REQUEST_PAIRING_CONNECTION packet and
            // receiving a PAIRING_CONNECTION_REQUEST_ACCEPTED packet.

            val pairingConnectionRequestAcceptedPacket = TransportLayerIO.Packet(
                command = TransportLayerIO.Command.PAIRING_CONNECTION_REQUEST_ACCEPTED,
                version = 0x10.toByte(),
                sequenceBit = false,
                reliabilityBit = false,
                address = 0x0f.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(0x00, 0xF0, 0x6D),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
            )

            tpLayerIO.startIO(
                backgroundIOScope = this
            )

            tpLayerIO.sendPacket(TransportLayerIO.createRequestPairingConnectionPacketInfo())

            // "Receive" by feeding the simulated data into the IO object.
            testComboIO.feedIncomingData(pairingConnectionRequestAcceptedPacket.toByteList())
            val receivedPacket = tpLayerIO.receivePacket()

            tpLayerIO.stopIO()

            // IO is done. We expect 1 packet to have been sent by the transport layer IO.
            // Also, we expect to have received the PAIRING_CONNECTION_REQUEST_ACCEPTED
            // packet. Check for this, and verify that the sent packet data and the
            // received packet data are correct.

            val expectedReqPairingConnectionPacket = TransportLayerIO.Packet(
                command = TransportLayerIO.Command.REQUEST_PAIRING_CONNECTION,
                version = 0x10.toByte(),
                sequenceBit = false,
                reliabilityBit = false,
                address = 0xf0.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(0xB2, 0x11),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
            )

            assertEquals(1, testComboIO.sentPacketData.size)
            assertEquals(expectedReqPairingConnectionPacket.toByteList(), testComboIO.sentPacketData[0])

            assertEquals(pairingConnectionRequestAcceptedPacket.toByteList(), receivedPacket.toByteList())
        }
    }

    @Test
    fun checkProcessKeyAndIDResponsePacketHandling() {
        // Run an IO sequence where we receive a KEY_RESPONSE and
        // an ID_RESPONSE packet. This tests that the transport
        // layer IO correctly parses these packets and updates
        // internal states.

        runBlockingWithWatchdog(5000) {
            val testPumpStateStore = TestPumpStateStore()
            val testComboIO = TestComboIO()
            val testBluetoothAddress = BluetoothAddress(byteArrayListOfInts(1, 2, 3, 4, 5, 6))
            val tpLayerIO = TransportLayerIO(testPumpStateStore, testBluetoothAddress, testComboIO)

            val testPumpID = "PUMP_10230947"
            val testPIN = PairingPIN(intArrayOf(2, 6, 0, 6, 8, 1, 9, 2, 7, 3))
            val testDecryptedCPKey =
                byteArrayListOfInts(0x5a, 0x25, 0x0b, 0x75, 0xa9, 0x02, 0x21, 0xfa, 0xab, 0xbd, 0x36, 0x4d, 0x5c, 0xb8, 0x37, 0xd7)
            val testDecryptedPCKey =
                byteArrayListOfInts(0x2a, 0xb0, 0xf2, 0x67, 0xc2, 0x7d, 0xcf, 0xaa, 0x32, 0xb2, 0x48, 0x94, 0xe1, 0x6d, 0xe9, 0x5c)
            val testAddress = 0x10.toByte()

            val keyResponsePacket = TransportLayerIO.Packet(
                command = TransportLayerIO.Command.KEY_RESPONSE,
                version = 0x10.toByte(),
                sequenceBit = false,
                reliabilityBit = false,
                address = 0x01.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(
                    0x54, 0x9E, 0xF7, 0x7D, 0x8D, 0x27, 0x48, 0x0C, 0x1D, 0x11, 0x43, 0xB8, 0xF7, 0x08, 0x92, 0x7B,
                    0xF0, 0xA3, 0x75, 0xF3, 0xB4, 0x5F, 0xE2, 0xF3, 0x46, 0x63, 0xCD, 0xDD, 0xC4, 0x96, 0x37, 0xAC),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0x25, 0xA0, 0x26, 0x47, 0x29, 0x37, 0xFF, 0x66))
            )

            val idResponsePacket = TransportLayerIO.Packet(
                command = TransportLayerIO.Command.ID_RESPONSE,
                version = 0x10.toByte(),
                sequenceBit = false,
                reliabilityBit = false,
                address = 0x01.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(
                    0x59, 0x99, 0xD4, 0x01, 0x50, 0x55, 0x4D, 0x50, 0x5F, 0x31, 0x30, 0x32, 0x33, 0x30, 0x39, 0x34, 0x37),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0x6E, 0xF4, 0x4D, 0xFE, 0x35, 0x6E, 0xFE, 0xB4))
            )

            // Start IO, and "receive" the packets by feeding them into the test IO object.

            tpLayerIO.startIO(
                backgroundIOScope = this,
                pairingPINCallback = { testPIN }
            )

            testComboIO.feedIncomingData(keyResponsePacket.toByteList())
            val receivedKeyResponsePacket = tpLayerIO.receivePacket()

            testComboIO.feedIncomingData(idResponsePacket.toByteList())
            val receivedIDResponsePacket = tpLayerIO.receivePacket()

            tpLayerIO.stopIO()

            // IO done. Check that the pump state store was properly updated.

            assertNotNull(testPumpStateStore.hasPumpState(testBluetoothAddress))

            val invariantPumpData = testPumpStateStore.getInvariantPumpData(testBluetoothAddress)

            assertEquals(keyResponsePacket.toByteList(), receivedKeyResponsePacket.toByteList())
            assertEquals(testDecryptedCPKey, invariantPumpData.clientPumpCipher.key.toList())
            assertEquals(testDecryptedPCKey, invariantPumpData.pumpClientCipher.key.toList())
            assertEquals(testAddress, invariantPumpData.keyResponseAddress)

            assertEquals(idResponsePacket.toByteList(), receivedIDResponsePacket.toByteList())
            assertEquals(testPumpID, invariantPumpData.pumpID)
        }
    }

    @Test
    fun checkBackgroundWorkerExceptionHandling() {
        // Test how exceptions in the background worker are handled.
        // We expect that the worker invokes a specified callback
        // (which is optional), stops itself, and marks itself as
        // failed. Subsequent send and receive call attempts need
        // to throw the exception that caused the worker to fail.

        runBlockingWithWatchdog(5000) {
            val testPumpStateStore = TestPumpStateStore()
            val testComboIO = TestComboIO()
            val testBluetoothAddress = BluetoothAddress(byteArrayListOfInts(1, 2, 3, 4, 5, 6))
            val tpLayerIO = TransportLayerIO(testPumpStateStore, testBluetoothAddress, testComboIO)

            val testDecryptedCPKey =
                byteArrayListOfInts(0x5a, 0x25, 0x0b, 0x75, 0xa9, 0x02, 0x21, 0xfa, 0xab, 0xbd, 0x36, 0x4d, 0x5c, 0xb8, 0x37, 0xd7)
            val testDecryptedPCKey =
                byteArrayListOfInts(0x2a, 0xb0, 0xf2, 0x67, 0xc2, 0x7d, 0xcf, 0xaa, 0x32, 0xb2, 0x48, 0x94, 0xe1, 0x6d, 0xe9, 0x5c)
            val testAddress = 0x10.toByte()

            testPumpStateStore.createPumpState(
                testBluetoothAddress,
                InvariantPumpData(
                    clientPumpCipher = Cipher(testDecryptedCPKey.toByteArray()),
                    pumpClientCipher = Cipher(testDecryptedPCKey.toByteArray()),
                    keyResponseAddress = testAddress,
                    pumpID = "testPump"
                )
            )

            val errorResponsePacket = TransportLayerIO.Packet(
                command = TransportLayerIO.Command.ERROR_RESPONSE,
                version = 0x10.toByte(),
                sequenceBit = false,
                reliabilityBit = false,
                address = 0x01.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(0x0F)
            )
            errorResponsePacket.authenticate(Cipher(testDecryptedPCKey.toByteArray()))

            // Start IO, and "receive" the error response packet by feeding
            // it into the test IO object. Since this packet contains an
            // error report by the simulated Combo, the worker throws an
            // exception to cause itself to fail.
            // Also, we start the IO with an exception callback
            // so we can test that said callback is invoked.

            // Use a CompletableDeferred, since the worker runs in the
            // background, and therefore, exceptions also are thrown in
            // the background. (This is why we can't use a simple try-catch
            // blocks in application code to catch those - they happen in
            // a separate coroutine, where the worker runs, and that
            // coroutine may be running in a separate thread.) When we
            // feed the "received" packet into the test IO, that packet
            // is processed asynchronously. This means that we somehow
            // need to wait until the exception is thrown in the worker.
            // We accomplish that by using this CompletableDeferred.
            // In our exception callback, we complete this deferred
            // by passing the exception as its value. The await() call
            // below suspends until the deferred is completed.
            //

            tpLayerIO.startIO(
                backgroundIOScope = this,
                pairingPINCallback = { nullPairingPIN() }
            )

            testComboIO.feedIncomingData(errorResponsePacket.toByteList())

            // TODO: There is a race condition with this test. See the
            // checkCanceledPINDeferredHandling test below for details.
            delay(200)

            // At this point, the worker failed. Attempts at sending
            // and receiving must fail and throw the exception that
            // caused the worker to fail. This allows for propagating
            // that error in a POSIX-esque style, where return codes
            // inform about a failure that previously happened.
            val exceptionThrownBySendCall = assertFailsWith<IllegalStateException> {
                // The actual packet does not matter here. We just
                // use createRequestPairingConnectionPacketInfo() to
                // be able to build the code. Might as well use any
                // other create*PacketInfo function.
                tpLayerIO.sendPacket(TransportLayerIO.createRequestPairingConnectionPacketInfo())
            }
            System.err.println(
                "Details about exception thrown by sendPacket() call (this exception was expected by the test): " +
                exceptionThrownBySendCall
            )
            val exceptionThrownByReceiveCall = assertFailsWith<IllegalStateException> {
                tpLayerIO.receivePacket()
            }
            System.err.println(
                "Details about exception thrown by receivePacket() call (this exception was expected by the test): " +
                exceptionThrownByReceiveCall
            )

            tpLayerIO.stopIO()
        }
    }

    @Test
    fun checkCanceledPINDeferredHandling() {
        // Test how a canceled CompletableDeferred for PIN requests is handled.
        // When that Deferred is cancelled, the worker should fail with a
        // PairingAbortedException.
        //
        // This test is a lot like the checkBackgroundWorkerExceptionHandling
        // test above, except that it feeds a KEY_RESPONSE packet and triggers
        // an exception by canceling the getPINDeferred in the PIN callback.

        runBlockingWithWatchdog(5000) {
            val testPumpStateStore = TestPumpStateStore()
            val testComboIO = TestComboIO()
            val testBluetoothAddress = BluetoothAddress(byteArrayListOfInts(1, 2, 3, 4, 5, 6))
            val tpLayerIO = TransportLayerIO(testPumpStateStore, testBluetoothAddress, testComboIO)

            val keyResponsePacket = TransportLayerIO.Packet(
                command = TransportLayerIO.Command.KEY_RESPONSE,
                version = 0x10.toByte(),
                sequenceBit = false,
                reliabilityBit = false,
                address = 0x01.toByte(),
                nonce = Nonce(byteArrayListOfInts(0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
                payload = byteArrayListOfInts(
                    0x54, 0x9E, 0xF7, 0x7D, 0x8D, 0x27, 0x48, 0x0C, 0x1D, 0x11, 0x43, 0xB8, 0xF7, 0x08, 0x92, 0x7B,
                    0xF0, 0xA3, 0x75, 0xF3, 0xB4, 0x5F, 0xE2, 0xF3, 0x46, 0x63, 0xCD, 0xDD, 0xC4, 0x96, 0x37, 0xAC),
                machineAuthenticationCode = MachineAuthCode(byteArrayListOfInts(0x25, 0xA0, 0x26, 0x47, 0x29, 0x37, 0xFF, 0x66))
            )

            // As soon as we feed the KEY_RESPONSE packet, the worker will
            // invoke the specified PIN callback. Our callback will cancel
            // the getPINDeferred. This will cause an exception to be thrown.
            // We check for precisely that exception.

            tpLayerIO.startIO(
                backgroundIOScope = this,
                pairingPINCallback = { throw TransportLayerIO.PairingAbortedException("PIN canceled") }
            )

            testComboIO.feedIncomingData(keyResponsePacket.toByteList())

            // TODO: There is a race condition in this test. The exception
            // thrown in pairingPINCallback stops the background worker, which
            // in turn causes the expected sendPacket() and receivePacket()
            // failures below. However, the background worker failure cannot
            // be waited for here, so we currently have to use delay(). If
            // this is not used, the code below runs too soon, that is, _before_
            // the background worker fails.
            delay(200)

            val exceptionThrownBySendCall = assertFailsWith<IllegalStateException> {
                tpLayerIO.sendPacket(TransportLayerIO.createRequestPairingConnectionPacketInfo())
            }
            System.err.println(
                "Details about exception thrown by sendPacket() call (this exception was expected by the test): " +
                exceptionThrownBySendCall
            )
            val exceptionThrownByReceiveCall = assertFailsWith<IllegalStateException> {
                tpLayerIO.receivePacket()
            }
            System.err.println(
                "Details about exception thrown by receivePacket() call (this exception was expected by the test): " +
                exceptionThrownByReceiveCall
            )

            tpLayerIO.stopIO()
        }
    }
}
