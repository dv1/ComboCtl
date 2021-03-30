package info.nightscout.comboctl.android

import android.bluetooth.BluetoothAdapter as SystemBluetoothAdapter
import android.bluetooth.BluetoothDevice as SystemBluetoothDevice
import android.bluetooth.BluetoothSocket as SystemBluetoothSocket
import info.nightscout.comboctl.base.*
import info.nightscout.comboctl.utils.retryBlocking
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

private val logger = Logger.get("AndroidBluetoothDevice")

/**
 * Class representing a Bluetooth device accessible through Android's Bluetooth API.
 *
 * Users typically do not instantiate this directly. Instead,
 * [AndroidBluetoothInterface]'s implementation of [BluetoothInterface.getDevice]
 * instantiates and returns this (as a [BluetoothDevice]).
 */
class AndroidBluetoothDevice(
    bluetoothInterface: BluetoothInterface,
    private val systemBluetoothAdapter: SystemBluetoothAdapter,
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
            // Use toUpperCase() since Android expects the A-F hex
            // digits in the Bluetooth address string to be uppercase
            // (lowercase ones are considered invalid and cause an
            // exception to be thrown).
            device = systemBluetoothAdapter.getRemoteDevice(address.toString().toUpperCase(Locale.ROOT))
        } catch (e: IllegalArgumentException) {
            throw BluetoothException("Bluetooth address $address is invalid according to Android", e)
        }

        logger(LogLevel.DEBUG) { "Attempting to connect RFCOMM socket to device" }

        // The Combo communicates over RFCOMM using the SDP Serial Port Profile.
        // We use an insecure socket, which means that it lacks an authenticated
        // link key. This is done because the Combo does not use this feature.
        try {
            retryBlocking(numberOfRetries = 4, delayBetweenRetries = 100) { // TODO check why it fails at first exception
                systemBluetoothSocket = device.createInsecureRfcommSocketToServiceRecord(Constants.sdpSerialPortUUID)
            }
        } catch (e: IOException) {
            throw BluetoothException("Could not connect RFCOMM socket to device with address $address", e)
        }

        // connect() must be explicitely called. Just creating the socket via
        // createInsecureRfcommSocketToServiceRecord() does not implicitely
        // establish the connection. This is important to keep in mind, since
        // otherwise, the calls below get input and output streams that seem
        // to be OK until their read/write functions are actually used. At
        // that point, very confusing NullPointerExceptions are thrown from
        // seemingly nowhere. These NPEs happen because *inside* the streams
        // there are internal Input/OutputStreams, and *these* are set to null
        // if the connection wasn't established. See also:
        // https://stackoverflow.com/questions/24267671/inputstream-read-causes-nullpointerexception-after-having-checked-inputstream#comment37491136_24267671
        // and: https://stackoverflow.com/a/24269255/560774
        try {
            systemBluetoothSocket!!.connect()
        } catch (e: IOException) {
            throw BluetoothException("Could not connect RFCOMM socket to device with address $address", e)
        }

        try {
            inputStream = systemBluetoothSocket!!.inputStream
        } catch (e: IOException) {
            disconnectImpl()
            throw ComboIOException("Could not get input stream to device with address $address", e)
        }

        try {
            outputStream = systemBluetoothSocket!!.outputStream
        } catch (e: IOException) {
            disconnectImpl()
            throw ComboIOException("Could not get output stream to device with address $address", e)
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
