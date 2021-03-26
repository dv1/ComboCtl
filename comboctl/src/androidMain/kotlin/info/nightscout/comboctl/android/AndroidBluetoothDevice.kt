package info.nightscout.comboctl.android

import android.bluetooth.BluetoothAdapter as SystemBluetoothAdapter
import android.bluetooth.BluetoothDevice as SystemBluetoothDevice
import android.bluetooth.BluetoothSocket as SystemBluetoothSocket
import info.nightscout.comboctl.base.BluetoothAddress
import info.nightscout.comboctl.base.BluetoothDevice
import info.nightscout.comboctl.base.BluetoothException
import info.nightscout.comboctl.base.BluetoothInterface
import info.nightscout.comboctl.base.ComboIOException
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.Logger
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private val logger = Logger.get("AndroidBluetoothDevice")

class AndroidBluetoothDevice(
    bluetoothInterface: BluetoothInterface,
    private val osBluetoothAdapter: SystemBluetoothAdapter,
    override val address: BluetoothAddress
) : BluetoothDevice(bluetoothInterface) {
    private var systemBluetoothSocket: SystemBluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    // Base class overrides.

    override fun connect() {
        if (systemBluetoothSocket != null)
            throw IllegalStateException("Connection already established")

        logger(LogLevel.DEBUG) { "Attempting to get object representing device with address $address" }

        lateinit var device: SystemBluetoothDevice
        try {
            device = osBluetoothAdapter.getRemoteDevice(address.toString())
        } catch (e: IllegalArgumentException) {
            throw BluetoothException("Bluetooth address $address is invalid according to Android", e)
        }

        logger(LogLevel.DEBUG) { "Attempting to connect RFCOMM socket to device" }

        try {
            systemBluetoothSocket = device.createInsecureRfcommSocketToServiceRecord(Constants.sdpSerialPortUUID)
        } catch (e: IOException) {
            throw BluetoothException("Could not connect RFCOMM socket to device with address $address", e)
        }

        try {
            inputStream = systemBluetoothSocket!!.getInputStream()
        } catch (e: IOException) {
            disconnectImpl()
            throw BluetoothException("Could not get input stream to device with address $address", e)
        }

        try {
            outputStream = systemBluetoothSocket!!.getOutputStream()
        } catch (e: IOException) {
            disconnectImpl()
            throw BluetoothException("Could not get output stream to device with address $address", e)
        }

        logger(LogLevel.INFO) { "RFCOMM connection with device with address $address established" }
    }

    override fun disconnect() {
        if (systemBluetoothSocket == null) {
            logger(LogLevel.DEBUG) { "Device already disconnected - ignoring redundant call" }
            return
        }

        disconnectImpl()

        logger(LogLevel.INFO) { "RFCOMM connection with device with address $address terminated" }
    }

    override fun blockingSend(dataToSend: List<Byte>) {
        if (outputStream == null)
            throw IllegalStateException("Device is not connected - cannot send data")

        try {
            outputStream!!.write(dataToSend.toByteArray())
        } catch (e: IOException) {
            throw ComboIOException("Could not write data to device with address $address", e)
        }
    }

    override fun blockingReceive(): List<Byte> {
        if (inputStream == null)
            throw IllegalStateException("Device is not connected - cannot receive data")

        try {
            val buffer = ByteArray(512)
            val numReadBytes = inputStream!!.read(buffer)
            return if (numReadBytes > 0) buffer.toList().subList(0, numReadBytes) else listOf<Byte>()
        } catch (e: IOException) {
            throw ComboIOException("Could not write data to device with address $address", e)
        }
    }

    private fun disconnectImpl() {
        if (inputStream != null) {
            try {
                inputStream!!.close()
            } catch (e: IOException) {
                logger(LogLevel.WARN) { "Caught exception while closing input stream to device with address $address: $e - ignoring exception" }
            } finally {
                inputStream = null
            }
        }

        if (outputStream != null) {
            try {
                outputStream!!.close()
            } catch (e: IOException) {
                logger(LogLevel.WARN) { "Caught exception while closing output stream to device with address $address: $e - ignoring exception" }
            } finally {
                outputStream = null
            }
        }

        if (systemBluetoothSocket != null) {
            try {
                systemBluetoothSocket!!.close()
            } catch (e: IOException) {
                logger(LogLevel.WARN) { "Caught exception while closing Bluetooth socket to device with address $address: $e - ignoring exception" }
            } finally {
                systemBluetoothSocket = null
            }
        }
    }
}
