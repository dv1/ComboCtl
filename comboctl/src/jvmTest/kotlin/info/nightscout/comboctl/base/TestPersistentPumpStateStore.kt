package info.nightscout.comboctl.base

class TestPersistentPumpStateStore : PersistentPumpStateStore {
    private var pairingData: PumpPairingData? = null
    private var valid = false

    override fun retrievePumpPairingData(): PumpPairingData {
        if (!valid)
            throw IllegalStateException("Persistent pump state store is not valid")
        return pairingData!!
    }

    override fun storePumpPairingData(pumpPairingData: PumpPairingData) {
        pairingData = pumpPairingData
        valid = true
    }

    override fun isValid() = valid

    override fun reset() {
        pairingData = null
        currentTxNonce = NullNonce
        valid = false
    }

    override var pumpID = ""

    override var currentTxNonce = NullNonce
}
