package info.nightscout.comboctl.base

// PersistentState mock used for testing.
class TestPersistentTLState : PersistentState {
    override var clientPumpCipher: Cipher? = null
    override var pumpClientCipher: Cipher? = null
    override var currentTxNonce: Nonce = NullNonce
    override var keyResponseAddress: Byte? = null

    override fun reset() {
        clientPumpCipher = null
        pumpClientCipher = null
        currentTxNonce = NullNonce
        keyResponseAddress = null
    }
}
