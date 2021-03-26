package info.nightscout.comboctl.javafxApp

import com.beust.klaxon.JsonObject
import com.beust.klaxon.Klaxon
import com.beust.klaxon.json
import info.nightscout.comboctl.base.BluetoothAddress
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.Logger
import info.nightscout.comboctl.base.Nonce
import info.nightscout.comboctl.base.NullNonce
import info.nightscout.comboctl.base.PumpPairingData
import info.nightscout.comboctl.base.PumpStateStore
import info.nightscout.comboctl.base.PumpStateStoreProvider
import info.nightscout.comboctl.base.toBluetoothAddress
import info.nightscout.comboctl.base.toCipher
import info.nightscout.comboctl.base.toNonce
import java.io.File
import java.io.FileReader
import java.io.IOException

private val logger = Logger.get("JsonPumpStateStore")

class JsonPumpStateStore(
    val pumpAddress: BluetoothAddress,
    private var storeProvider: JsonPumpStateStoreProvider,
    private var pairingData: PumpPairingData? = null,
    pumpID: String = "",
    txNonce: Nonce = NullNonce
) : PumpStateStore {
    override fun retrievePumpPairingData(): PumpPairingData {
        if (!isValid())
            throw IllegalStateException("Pump state store is not valid")
        return pairingData!!
    }

    override fun storePumpPairingData(pumpPairingData: PumpPairingData) {
        pairingData = pumpPairingData
        storeProvider.write()
    }

    override fun isValid() = (pairingData != null)

    override fun reset() {
        pairingData = null
        txNonceValue = NullNonce
        storeProvider.erase(this)
    }

    override var currentTxNonce
        get() = txNonceValue
        set(value) {
            txNonceValue = value
            storeProvider.write()
        }

    private var txNonceValue = txNonce

    override var pumpID = pumpID
        set(value) {
            field = value
            storeProvider.write()
        }
}

class JsonPumpStateStoreProvider : PumpStateStoreProvider {
    private val jsonFilename = "jsonPumpStores.json"
    private val storeMap = mutableMapOf<BluetoothAddress, JsonPumpStateStore>()

    init {
        try {
            val file = File(jsonFilename)
            val jsonStores = Klaxon().parseJsonObject(FileReader(file))
            logger(LogLevel.DEBUG) { "Reading JSON data from pump stores file, with ${jsonStores.size} entries" }

            for (key in jsonStores.keys) {
                val jsonObj = jsonStores.obj(key)
                if (jsonObj == null) {
                    logger(LogLevel.WARN) { "Did not find JSON object with key \"$key\"" }
                    continue
                }

                val btAddress = key.toBluetoothAddress()

                val store = JsonPumpStateStore(
                    btAddress,
                    this,
                    PumpPairingData(
                        clientPumpCipher = jsonObj.string("clientPumpCipher")!!.toCipher(),
                        pumpClientCipher = jsonObj.string("pumpClientCipher")!!.toCipher(),
                        keyResponseAddress = jsonObj.int("keyResponseAddress")!!.toByte()
                    ),
                    jsonObj.string("pumpID")!!,
                    jsonObj.string("currentTxNonce")!!.toNonce()
                )

                storeMap[btAddress] = store
            }
        } catch (e: IOException) {
            logger(LogLevel.ERROR) { "Could not read data from store collection file" }
        }
    }

    override fun getAvailableStoreAddresses() = storeMap.keys

    override fun requestStore(pumpAddress: BluetoothAddress): PumpStateStore {
        var store = storeMap[pumpAddress]

        if (store == null) {
            store = JsonPumpStateStore(pumpAddress, this)
            storeMap[pumpAddress] = store
        }

        return store
    }

    override fun hasValidStore(pumpAddress: BluetoothAddress): Boolean = storeMap.containsKey(pumpAddress)

    fun write() {
        val jsonStores = JsonObject()

        for (pumpAddress in storeMap.keys) {
            val store = storeMap[pumpAddress]!!

            if (!store.isValid())
                continue

            val pumpPairingData = store.retrievePumpPairingData()

            val jsonObj = json { obj(
                "clientPumpCipher" to pumpPairingData.clientPumpCipher.toString(),
                "pumpClientCipher" to pumpPairingData.pumpClientCipher.toString(),
                "keyResponseAddress" to pumpPairingData.keyResponseAddress.toInt(),
                "pumpID" to store.pumpID,
                "currentTxNonce" to store.currentTxNonce.toString()
            ) }

            jsonStores[pumpAddress.toString()] = jsonObj
        }

        File(jsonFilename).writeText(jsonStores.toJsonString(true))
    }

    fun erase(store: JsonPumpStateStore) {
        storeMap.remove(store.pumpAddress)
        write()
    }
}