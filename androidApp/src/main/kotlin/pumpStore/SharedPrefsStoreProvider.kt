package info.nightscout.comboctl.comboandroid.persist

import android.content.SharedPreferences
import androidx.core.content.edit
import info.nightscout.comboctl.base.BluetoothAddress
import info.nightscout.comboctl.base.Nonce
import info.nightscout.comboctl.base.NullNonce
import info.nightscout.comboctl.base.PumpPairingData
import info.nightscout.comboctl.base.PumpStateStore
import info.nightscout.comboctl.base.PumpStateStoreProvider
import info.nightscout.comboctl.base.toBluetoothAddress
import info.nightscout.comboctl.base.toCipher
import info.nightscout.comboctl.base.toNonce
import info.nightscout.comboctl.base.toPosInt
import info.nightscout.comboctl.comboandroid.App

class SharedPrefsStoreProvider(private val sharedPreferences: SharedPreferences) :
    PumpStateStoreProvider {
    private var btAddress: String by PreferenceDelegateString(sharedPreferences, BT_ADDRESS_KEY, "")

    override fun requestStore(pumpAddress: BluetoothAddress): PumpStateStore =
        SharedPrefsPumpStateStore(sharedPreferences)

    override fun hasValidStore(pumpAddress: BluetoothAddress): Boolean =
        (btAddress == pumpAddress.toString())

    override fun getAvailableStoreAddresses(): Set<BluetoothAddress> =
        if (btAddress.isBlank()) setOf<BluetoothAddress>() else setOf(btAddress.toBluetoothAddress())

    companion object {
        const val BT_ADDRESS_KEY = "bt-address-key"
        const val NONCE_KEY = "nonce-key"
        const val CP_CIPHER_KEY = "cp-cipher-key"
        const val PC_CIPHER_KEY = "pc-cipher-key"
        const val KEY_RESPONSE_ADDRESS_KEY = "key-response-address-key"
        const val PUMP_ID_KEY = "pump-id-key"
    }
}

class SharedPrefsPumpStateStore(private val sharedPreferences: SharedPreferences) :
    PumpStateStore {
    private var nonceString:
        String by PreferenceDelegateString(sharedPreferences, SharedPrefsStoreProvider.NONCE_KEY, NullNonce.toString())
    private var cpCipherString:
        String by PreferenceDelegateString(sharedPreferences, SharedPrefsStoreProvider.CP_CIPHER_KEY, "")
    private var pcCipherString:
        String by PreferenceDelegateString(sharedPreferences, SharedPrefsStoreProvider.PC_CIPHER_KEY, "")
    private var keyResponseAddressInt:
        Int by PreferenceDelegateInt(sharedPreferences, SharedPrefsStoreProvider.KEY_RESPONSE_ADDRESS_KEY, 0)

    override fun retrievePumpPairingData() = PumpPairingData(
        clientPumpCipher = cpCipherString.toCipher(),
        pumpClientCipher = pcCipherString.toCipher(),
        keyResponseAddress = keyResponseAddressInt.toByte()
    )

    override fun storePumpPairingData(pumpPairingData: PumpPairingData) {
        cpCipherString = pumpPairingData.clientPumpCipher.toString()
        pcCipherString = pumpPairingData.pumpClientCipher.toString()
        keyResponseAddressInt = pumpPairingData.keyResponseAddress.toPosInt()
    }

    override fun isValid() = sharedPreferences.contains(SharedPrefsStoreProvider.NONCE_KEY)

    override fun reset() {
        sharedPreferences.edit(commit = true) {
            remove(SharedPrefsStoreProvider.BT_ADDRESS_KEY)
            remove(SharedPrefsStoreProvider.NONCE_KEY)
            remove(SharedPrefsStoreProvider.CP_CIPHER_KEY)
            remove(SharedPrefsStoreProvider.PC_CIPHER_KEY)
            remove(SharedPrefsStoreProvider.KEY_RESPONSE_ADDRESS_KEY)
        }
    }

    override var pumpID:
        String by PreferenceDelegateString(sharedPreferences, SharedPrefsStoreProvider.PUMP_ID_KEY, "")

    override var currentTxNonce: Nonce
        get() = nonceString.toNonce()
        set(value) { nonceString = value.toString() }
}
