package info.nightscout.comboctl.base.testUtils

import info.nightscout.comboctl.base.*

class TestPumpStateStore : PumpStateStore {
    data class Entry(val invariantPumpData: InvariantPumpData, var currentTxNonce: Nonce)

    var states = mutableMapOf<BluetoothAddress, Entry>()
        private set

    override fun createPumpState(pumpAddress: BluetoothAddress, invariantPumpData: InvariantPumpData) {
        if (states.contains(pumpAddress))
            throw PumpStateAlreadyExistsException(pumpAddress)

        states[pumpAddress] = Entry(invariantPumpData, Nonce(List(NUM_NONCE_BYTES) { 0x00 }))
    }

    override fun deletePumpState(pumpAddress: BluetoothAddress) =
        if (states.contains(pumpAddress)) {
            states.remove(pumpAddress)
            true
        } else {
            false
        }

    override fun hasPumpState(pumpAddress: BluetoothAddress): Boolean =
        states.contains(pumpAddress)

    override fun getAvailablePumpStateAddresses(): Set<BluetoothAddress> = states.keys

    override fun getInvariantPumpData(pumpAddress: BluetoothAddress): InvariantPumpData {
        if (!states.contains(pumpAddress))
            throw PumpStateDoesNotExistException(pumpAddress)
        return states[pumpAddress]!!.invariantPumpData
    }

    override fun getCurrentTxNonce(pumpAddress: BluetoothAddress): Nonce {
        if (!states.contains(pumpAddress))
            throw PumpStateDoesNotExistException(pumpAddress)
        return states[pumpAddress]!!.currentTxNonce
    }

    override fun setCurrentTxNonce(pumpAddress: BluetoothAddress, currentTxNonce: Nonce) {
        if (!states.contains(pumpAddress))
            throw PumpStateDoesNotExistException(pumpAddress)
        states[pumpAddress]!!.currentTxNonce = currentTxNonce
    }
}
