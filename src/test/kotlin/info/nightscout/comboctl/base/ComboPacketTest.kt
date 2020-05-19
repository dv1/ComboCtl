package info.nightscout.comboctl.base

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComboPacketTest {
    @Test
    fun parsePacketData() {
        // Test the packet parser by parsing hardcoded packet data
        // and verifying the individual packet property values.

        val packetDataWithCRCPayload = byteArrayListOfInts(
            0x10, // versions
            0x09, // request_pairing_connection command (sequence and data reliability bit set to 0)
            0x02, 0x00, // payload length
            0xF0, // addresses
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // nonce
            0x99, 0x44, // payload
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 // nullbyte MAC
        )

        // The actual parsing.
        var packet = packetDataWithCRCPayload.toComboPacket()

        // Check the individual properties.

        assertEquals(1, packet.majorVersion)
        assertEquals(0, packet.minorVersion)

        assertFalse(packet.sequenceBit)

        assertFalse(packet.reliabilityBit)

        assertEquals(0x09, packet.commandID)

        assertEquals(0xF, packet.sourceAddress)
        assertEquals(0x0, packet.destinationAddress)

        assertArrayEquals(byteArrayOfInts(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00), packet.nonce)

        assertEquals(byteArrayListOfInts(0x99, 0x44), packet.payload)

        assertArrayEquals(byteArrayOfInts(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00), packet.machineAuthenticationCode)
    }

    @Test
    fun createPacketData() {
        // Create packet, and check that it is correctly converted to a byte list.

        val packet = ComboPacket().apply {
            majorVersion = 4
            minorVersion = 2
            sequenceBit = true
            reliabilityBit = false
            commandID = 0x05
            sourceAddress = 0x4
            destinationAddress = 0x5
            nonce = byteArrayOfInts(0x0A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0B)
            payload = byteArrayListOfInts(0x50, 0x60, 0x70)
            machineAuthenticationCode = byteArrayOfInts(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        }

        val byteList = packet.toByteList()

        val expectedPacketData = byteArrayListOfInts(
            0x42, // versions
            0x80 or 0x05, // command 0x05 with sequence bit enabled
            0x03, 0x00, // payload length
            0x45, // addresses,
            0x0A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0B, // nonce
            0x50, 0x60, 0x70, // payload
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 // MAC
        )

        assertEquals(byteList, expectedPacketData)
    }

    @Test
    fun verifyPacketDataIntegrityWithCRC() {
        // Create packet and verify that the CRC check detects data corruption.

        val packet = ComboPacket().apply {
            majorVersion = 4
            minorVersion = 2
            sequenceBit = true
            reliabilityBit = false
            commandID = 0x05
            sourceAddress = 0x4
            destinationAddress = 0x5
            nonce = byteArrayOfInts(0x0A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0B)
            machineAuthenticationCode = byteArrayOfInts(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        }

        // Check that the computed CRC is correct.
        packet.computeCRC16Payload()
        val expectedCRCPayload = byteArrayListOfInts(0xA5, 0xBB)
        assertEquals(packet.payload, expectedCRCPayload)

        // The CRC should match, since it was just computed.
        assertTrue(packet.verifyCRC16Payload())

        // Simulate data corruption by altering the command ID.
        // This should produce a CRC mismatch.
        packet.commandID = 0x10
        assertFalse(packet.verifyCRC16Payload())
    }

    @Test
    fun verifyPacketDataIntegrityWithMAC() {
        // Create packet and verify that the MAC check detects data corruption.

        val key = ByteArray(CIPHER_KEY_SIZE).apply { fill('0'.toByte()) }
        val cipher = Cipher(key)

        val packet = ComboPacket().apply {
            majorVersion = 4
            minorVersion = 2
            sequenceBit = true
            reliabilityBit = false
            commandID = 0x05
            sourceAddress = 0x4
            destinationAddress = 0x5
            nonce = byteArrayOfInts(0x0A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0B)
            payload = byteArrayListOfInts(0x00, 0x00)
        }

        // Check that the computed MAC is correct.
        packet.authenticate(cipher)
        val expectedMAC = byteArrayOfInts(0x27, 0xD5, 0xAC, 0x50, 0x60, 0xE1, 0x3A, 0xB3)
        assertArrayEquals(packet.machineAuthenticationCode, expectedMAC)

        // The MAC should match, since it was just computed.
        assertTrue(packet.verifyAuthentication(cipher))

        // Simulate data corruption by altering the payload.
        // This should produce a MAC mismatch.
        packet.payload[0] = 0xFF.toByte()
        assertFalse(packet.verifyAuthentication(cipher))
    }
}
