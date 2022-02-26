package info.nightscout.comboctl.javafxApp

import com.beust.klaxon.JsonObject
import com.beust.klaxon.Klaxon
import com.beust.klaxon.json
import info.nightscout.comboctl.base.BluetoothAddress
import info.nightscout.comboctl.base.CurrentTbrState
import info.nightscout.comboctl.base.InvariantPumpData
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.Logger
import info.nightscout.comboctl.base.NUM_NONCE_BYTES
import info.nightscout.comboctl.base.Nonce
import info.nightscout.comboctl.base.PumpStateAlreadyExistsException
import info.nightscout.comboctl.base.PumpStateDoesNotExistException
import info.nightscout.comboctl.base.PumpStateStore
import info.nightscout.comboctl.base.Tbr
import info.nightscout.comboctl.base.toBluetoothAddress
import info.nightscout.comboctl.base.toCipher
import info.nightscout.comboctl.base.toNonce
import kotlinx.datetime.Instant
import java.io.File
import java.io.FileReader
import java.io.IOException
import kotlinx.datetime.UtcOffset

private val logger = Logger.get("JsonPumpStateStore")

// Simple pump state store which writes to a local JSON file.
// This is only meant for development and testing.
class JsonPumpStateStore : PumpStateStore {
    data class Entry(
        val invariantPumpData: InvariantPumpData,
        var currentTxNonce: Nonce,
        var currentUtcOffset: UtcOffset,
        var currentTbrState: CurrentTbrState
    )

    private val jsonFilename = "jsonPumpStateStore.json"
    private val states = mutableMapOf<BluetoothAddress, Entry>()

    init {
        try {
            val file = File(jsonFilename)
            val jsonStates = Klaxon().parseJsonObject(FileReader(file))
            logger(LogLevel.DEBUG) { "Reading JSON data from pump state store file, with ${jsonStates.size} entries" }

            for (key in jsonStates.keys) {
                val jsonObj = jsonStates.obj(key)
                if (jsonObj == null) {
                    logger(LogLevel.WARN) { "Did not find JSON object with key \"$key\"" }
                    continue
                }

                val btAddress = key.toBluetoothAddress()

                val tbrJson = jsonObj.obj("tbr")
                val tbrState = if (tbrJson != null) {
                    CurrentTbrState.TbrStarted(Tbr(
                        timestamp = Instant.fromEpochSeconds(tbrJson.long("timestamp")!!),
                        percentage = tbrJson.int("percentage")!!,
                        durationInMinutes = tbrJson.int("durationInMinutes")!!,
                        type = Tbr.fromStringId(tbrJson.string("type")!!)!!
                    ))
                } else CurrentTbrState.NoTbrOngoing

                val entry = Entry(
                    InvariantPumpData(
                        clientPumpCipher = jsonObj.string("clientPumpCipher")!!.toCipher(),
                        pumpClientCipher = jsonObj.string("pumpClientCipher")!!.toCipher(),
                        keyResponseAddress = jsonObj.int("keyResponseAddress")!!.toByte(),
                        pumpID = jsonObj.string("pumpID")!!
                    ),
                    jsonObj.string("currentTxNonce")!!.toNonce(),
                    UtcOffset(seconds = jsonObj.int("utcOffsetInSeconds")!!),
                    tbrState
                )

                states[btAddress] = entry
            }
        } catch (e: IOException) {
            logger(LogLevel.ERROR) { "Could not read data from pump state store file" }
        }
    }

    override fun createPumpState(
        pumpAddress: BluetoothAddress,
        invariantPumpData: InvariantPumpData,
        utcOffset: UtcOffset,
        tbrState: CurrentTbrState
    ) {
        if (states.contains(pumpAddress))
            throw PumpStateAlreadyExistsException(pumpAddress)

        states[pumpAddress] = Entry(
            invariantPumpData,
            Nonce(List(NUM_NONCE_BYTES) { 0x00 }),
            utcOffset,
            tbrState
        )

        write()
    }

    override fun deletePumpState(pumpAddress: BluetoothAddress) =
        if (states.contains(pumpAddress)) {
            states.remove(pumpAddress)
            write()
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
        write()
    }

    override fun getCurrentUtcOffset(pumpAddress: BluetoothAddress): UtcOffset {
        if (!states.contains(pumpAddress))
            throw PumpStateDoesNotExistException(pumpAddress)
        return states[pumpAddress]!!.currentUtcOffset
    }

    override fun setCurrentUtcOffset(pumpAddress: BluetoothAddress, utcOffset: UtcOffset) {
        if (!states.contains(pumpAddress))
            throw PumpStateDoesNotExistException(pumpAddress)
        states[pumpAddress]!!.currentUtcOffset = utcOffset
        write()
    }

    override fun getCurrentTbrState(pumpAddress: BluetoothAddress): CurrentTbrState {
        if (!states.contains(pumpAddress))
            throw PumpStateDoesNotExistException(pumpAddress)
        return states[pumpAddress]!!.currentTbrState
    }

    override fun setCurrentTbrState(pumpAddress: BluetoothAddress, currentTbrState: CurrentTbrState) {
        if (!states.contains(pumpAddress))
            throw PumpStateDoesNotExistException(pumpAddress)
        states[pumpAddress]!!.currentTbrState = currentTbrState
        write()
    }

    fun write() {
        val jsonStores = JsonObject()

        for (pumpAddress in states.keys) {
            val state = states[pumpAddress]!!

            val tbrObj = when (val tbrState = state.currentTbrState) {
                is CurrentTbrState.TbrStarted -> json { obj(
                    "timestamp" to tbrState.tbr.timestamp.epochSeconds,
                    "percentage" to tbrState.tbr.percentage,
                    "durationInMinutes" to tbrState.tbr.durationInMinutes,
                    "type" to tbrState.tbr.type.stringId
                ) }
                else -> null
            }

            val jsonObj = json { obj(
                "clientPumpCipher" to state.invariantPumpData.clientPumpCipher.toString(),
                "pumpClientCipher" to state.invariantPumpData.pumpClientCipher.toString(),
                "keyResponseAddress" to state.invariantPumpData.keyResponseAddress.toInt(),
                "pumpID" to state.invariantPumpData.pumpID,
                "currentTxNonce" to state.currentTxNonce.toString(),
                "utcOffsetInSeconds" to state.currentUtcOffset.totalSeconds,
                "tbr" to tbrObj
            ) }

            jsonStores[pumpAddress.toString()] = jsonObj
        }

        File(jsonFilename).writeText(jsonStores.toJsonString(true))
    }
}
