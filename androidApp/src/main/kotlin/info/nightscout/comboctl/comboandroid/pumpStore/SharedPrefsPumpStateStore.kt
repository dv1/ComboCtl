package info.nightscout.comboctl.comboandroid.persist

import android.content.SharedPreferences
import androidx.core.content.edit
import info.nightscout.comboctl.base.BluetoothAddress
import info.nightscout.comboctl.base.InvariantPumpData
import info.nightscout.comboctl.base.Nonce
import info.nightscout.comboctl.base.PumpStateStore
import info.nightscout.comboctl.base.toBluetoothAddress
import info.nightscout.comboctl.base.toCipher
import info.nightscout.comboctl.base.toNonce

class SharedPrefsPumpStateStore(private val sharedPreferences: SharedPreferences) : PumpStateStore {
    private var btAddress: String
        by PreferenceDelegateString(sharedPreferences, BT_ADDRESS_KEY, "")

    private var nonceString: String
        by PreferenceDelegateString(sharedPreferences, SharedPrefsPumpStateStore.NONCE_KEY, Nonce.nullNonce().toString())
    private var cpCipherString: String
        by PreferenceDelegateString(sharedPreferences, SharedPrefsPumpStateStore.CP_CIPHER_KEY, "")
    private var pcCipherString: String
        by PreferenceDelegateString(sharedPreferences, SharedPrefsPumpStateStore.PC_CIPHER_KEY, "")
    private var keyResponseAddressInt: Int
        by PreferenceDelegateInt(sharedPreferences, SharedPrefsPumpStateStore.KEY_RESPONSE_ADDRESS_KEY, 0)
    private var pumpID: String
        by PreferenceDelegateString(sharedPreferences, SharedPrefsPumpStateStore.PUMP_ID_KEY, "")

    override fun createPumpState(pumpAddress: BluetoothAddress, invariantPumpData: InvariantPumpData) {
        btAddress = pumpAddress.toString().uppercase()

        cpCipherString = invariantPumpData.clientPumpCipher.toString()
        pcCipherString = invariantPumpData.pumpClientCipher.toString()
        keyResponseAddressInt = invariantPumpData.keyResponseAddress.toInt() and 0xFF
        pumpID = invariantPumpData.pumpID
    }

    override fun deletePumpState(pumpAddress: BluetoothAddress): Boolean {
        val hasState = sharedPreferences.contains(SharedPrefsPumpStateStore.NONCE_KEY)

        sharedPreferences.edit(commit = true) {
            remove(SharedPrefsPumpStateStore.BT_ADDRESS_KEY)
            remove(SharedPrefsPumpStateStore.NONCE_KEY)
            remove(SharedPrefsPumpStateStore.CP_CIPHER_KEY)
            remove(SharedPrefsPumpStateStore.PC_CIPHER_KEY)
            remove(SharedPrefsPumpStateStore.KEY_RESPONSE_ADDRESS_KEY)
        }

        return hasState
    }

    override fun hasPumpState(pumpAddress: BluetoothAddress) =
        sharedPreferences.contains(SharedPrefsPumpStateStore.NONCE_KEY)

    override fun getAvailablePumpStateAddresses() =
        if (btAddress.isBlank()) setOf<BluetoothAddress>() else setOf(btAddress.toBluetoothAddress())

    override fun getInvariantPumpData(pumpAddress: BluetoothAddress) = InvariantPumpData(
        clientPumpCipher = cpCipherString.toCipher(),
        pumpClientCipher = pcCipherString.toCipher(),
        keyResponseAddress = keyResponseAddressInt.toByte(),
        pumpID = pumpID
    )

    override fun getCurrentTxNonce(pumpAddress: BluetoothAddress) = nonceString.toNonce()

    override fun setCurrentTxNonce(pumpAddress: BluetoothAddress, currentTxNonce: Nonce) {
        nonceString = currentTxNonce.toString()
    }

    companion object {
        const val BT_ADDRESS_KEY = "bt-address-key"
        const val NONCE_KEY = "nonce-key"
        const val CP_CIPHER_KEY = "cp-cipher-key"
        const val PC_CIPHER_KEY = "pc-cipher-key"
        const val KEY_RESPONSE_ADDRESS_KEY = "key-response-address-key"
        const val PUMP_ID_KEY = "pump-id-key"
    }
}
