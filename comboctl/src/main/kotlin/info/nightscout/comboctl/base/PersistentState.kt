package info.nightscout.comboctl.base

/**
 * Interface for accessing states that must stored in a persistent manner.
 *
 * Implementations must store updated values immediately. If possible, they
 * must be stored atomically.
 *
 * Initially, these values are not set (= the properties are set to null).
 * All values are set during the pairing process. After pairing, the only
 * value that keeps being updated is currentTxNonce (it is incremented every
 * time a new packet is created and sent to the Combo).
 *
 * As for the ciphers, the 128-bit keys are what needs to be persistently stored.
 */
interface PersistentState {
    /**
     * Client-pump cipher.
     *
     * This cipher is used for authenticating packets going to the Combo.
     */
    var clientPumpCipher: Cipher?

    /**
     * Pump-client cipher.
     *
     * This cipher is used for verifying packets coming from the Combo.
     */
    var pumpClientCipher: Cipher?

    /**
     * Current tx nonce.
     *
     * This 13-byte nonce is incremented every time a packet that goes
     * to the Combo is generated, except during the pairing process,
     * where the initial few packets use a nonce made of nullbytes.
     * The first time this value is written to is when the ID_RESPONSE
     * packet is generated (it is set to "nonce 1", that is, a nonce
     * with its first byte set to 1 and the rest set to zero). After
     * that, normal incrementing behavior commences.
     */
    var currentTxNonce: Nonce

    /**
     * The address byte of a previously received KEY_RESPONSE packet.
     *
     * The source and destination address values inside the address
     * byte must have been reordered to match the order that outgoing
     * packets expect. That is: Source address stored in the upper,
     * destination address in the lower 4 bit of the byte.
     * (In incoming packets - and KEY_RESPONSE is an incoming packet -
     * these two are ordered the other way round.)
     */
    var keyResponseAddress: Byte?

    /**
     * Clears all values from the persistent state, resetting it.
     *
     * This effectively unpairs the pump.
     */
    fun reset()
}
