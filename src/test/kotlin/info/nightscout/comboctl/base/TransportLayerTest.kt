package info.nightscout.comboctl.base

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TransportLayerTest {
    @Test
    fun checkPairingSequenceProcessing() {
        // Test the behavior of the transport layer code when it
        // produces and consumes pairing setup packets. Packets
        // are produced by the create* functions, and would normally
        // be sent over Bluetooth to the Combo. Here, we simply
        // verify that the created packets are correct, and behave
        // as it they got sent right after the create* call.

        val requestPairingConnectionPacket = ComboPacket(byteArrayListOfInts(
            0x10,
            0x09,
            0x02, 0x00,
            0xf0,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0xb2, 0x11,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        val requestKeysPacket = ComboPacket(byteArrayListOfInts(
            0x10,
            0x0c,
            0x02, 0x00,
            0xf0,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x81, 0x41,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        val getAvailableKeysPacket = ComboPacket(byteArrayListOfInts(
            0x10,
            0x0f,
            0x02, 0x00,
            0xf0,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x90, 0x71,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        val keyResponsePacket = ComboPacket(byteArrayListOfInts(
            0x10,
            0x11,
            0x20, 0x00,
            0x01,
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x54, 0x9e, 0xf7, 0x7d, 0x8d, 0x27, 0x48, 0x0c, 0x1d, 0x11, 0x43, 0xb8, 0xf7,
            0x08, 0x92, 0x7b, 0xf0, 0xa3, 0x75, 0xf3, 0xb4, 0x5f, 0xe2, 0xf3, 0x46, 0x63,
            0xcd, 0xdd, 0xc4, 0x96, 0x37, 0xac,
            0x25, 0xa0, 0x26, 0x47, 0x29, 0x37, 0xff, 0x66))
        val requestIDPacket = ComboPacket(byteArrayListOfInts(
            0x10,
            0x12,
            0x11, 0x00,
            0x10,
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x08, 0x29, 0x00, 0x00, 0x54, 0x65, 0x73, 0x74, 0x20, 0x31, 0x32, 0x33, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x75, 0xca, 0x6c, 0x95, 0x01, 0xbf, 0x9b, 0x5a))
        val idResponsePacket = ComboPacket(byteArrayListOfInts(
            0x10,
            0x14,
            0x11, 0x00,
            0x01,
            0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x40, 0xe2, 0x01, 0x00, 0x50, 0x55, 0x4d, 0x50, 0x5f, 0x30, 0x31, 0x32, 0x33,
            0x34, 0x35, 0x36, 0x37,
            0xab, 0xc6, 0x89, 0x7f, 0x14, 0x9b, 0xdf, 0x3b))

        val tpLayer = TransportLayer(LoggerFactory(StderrLoggerBackend(), LogLevel.DEBUG).getLogger(LogCategory.TP_LAYER))
        val tpLayerState = TransportLayer.State()

        // Send the first 3 pairing setup packets. After REQUEST_PAIRING_CONNECTION,
        // the Combo would normally send back PAIRING_CONNECTION_REQUEST_ACCEPTED,
        // but we cannot use that packet here, since it does not have any payload
        // to parse, so it is not fed into TransportLayer.
        assertEquals(requestPairingConnectionPacket, tpLayer.createRequestPairingConnectionPacket())
        assertEquals(requestKeysPacket, tpLayer.createRequestKeysPacket())

        // After REQUEST_KEYS is received, the user must enter the PIN shown on
        // the Combo. This PIN is used to create the weak key.
        tpLayerState.weakCipher = Cipher(generateWeakKeyFromPIN(intArrayOf(2, 6, 0, 6, 8, 1, 9, 2, 7, 3)))

        // After the weak key was generated, the client has to send GET_AVAILABLE_KEYS
        // to the Combo to retrieve the client-pump and pump-client keys it generated.
        assertEquals(getAvailableKeysPacket, tpLayer.createGetAvailableKeysPacket())

        // After sending GET_AVAILABLE_KEYS, the Combo will respond with KEY_RESPONSE.
        // We simulate this with keyResponsePacket. This packet must be parsed to get
        // the client-pump and pump-client keys as well as the source and destination
        // key response addresses.
        tpLayer.parseKeyResponsePacket(tpLayerState, keyResponsePacket)
        // Verify that the state has been updated with the correct addresses
        // and decrypted keys.
        assertEquals(1, tpLayerState.keyResponseSourceAddress)
        assertEquals(0, tpLayerState.keyResponseDestinationAddress)
        assertArrayEquals(byteArrayOfInts(
            0x5a, 0x25, 0x0b, 0x75, 0xa9, 0x02, 0x21, 0xfa,
            0xab, 0xbd, 0x36, 0x4d, 0x5c, 0xb8, 0x37, 0xd7),
            tpLayerState.clientPumpCipher!!.key)
        assertArrayEquals(byteArrayOfInts(
            0x2a, 0xb0, 0xf2, 0x67, 0xc2, 0x7d, 0xcf, 0xaa,
            0x32, 0xb2, 0x48, 0x94, 0xe1, 0x6d, 0xe9, 0x5c),
            tpLayerState.pumpClientCipher!!.key)

        // After getting KEY_RESPONSE, the client must transmit REQUEST_ID.
        val createdRequestIDPacket = tpLayer.createRequestIDPacket(tpLayerState, "Test 123")
        assertEquals(requestIDPacket, createdRequestIDPacket)

        // Parse ID_RESPONSE, the response to REQUEST_ID.
        val ids = tpLayer.parseIDResponsePacket(tpLayerState, idResponsePacket)
        assertEquals(0x0001e240, ids.serverID)
        assertEquals("PUMP_01234567", ids.pumpID)
    }
}
