package info.nightscout.comboctl.base.testUtils

import info.nightscout.comboctl.base.*

class TestPumpStateStore : PumpStateStore {
    var pairingData: PumpPairingData? = null
        private set

    private var valid = false

    override fun retrievePumpPairingData(): PumpPairingData {
        if (!valid)
            throw IllegalStateException("Pump state store is not valid")
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
