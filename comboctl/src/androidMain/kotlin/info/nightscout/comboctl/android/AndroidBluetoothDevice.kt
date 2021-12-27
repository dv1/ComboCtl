package info.nightscout.comboctl.android

import android.bluetooth.BluetoothAdapter as SystemBluetoothAdapter
import android.bluetooth.BluetoothDevice as SystemBluetoothDevice
import android.bluetooth.BluetoothSocket as SystemBluetoothSocket
import info.nightscout.comboctl.base.BasicProgressStage
import info.nightscout.comboctl.base.BluetoothAddress
import info.nightscout.comboctl.base.BluetoothDevice
import info.nightscout.comboctl.base.BluetoothException
import info.nightscout.comboctl.base.BluetoothInterface
import info.nightscout.comboctl.base.ComboIOException
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.Logger
import info.nightscout.comboctl.base.ProgressReporter
import info.nightscout.comboctl.utils.retryBlocking
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

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

    // Use toUpperCase() since Android expects the A-F hex digits in the
    // Bluetooth address string to be uppercase (lowercase ones are considered
    // invalid and cause an exception to be thrown).
    private val androidBtAddressString = address.toString().uppercase(Locale.ROOT)

    // Base class overrides.

    override fun connect(progressReporter: ProgressReporter<Unit>?) {
        check(systemBluetoothSocket == null) { "Connection already established" }

        logger(LogLevel.DEBUG) { "Attempting to get object representing device with address $address" }

        lateinit var device: SystemBluetoothDevice

        try {
            // Establishing the RFCOMM connection does not always work right away.
            // Depending on the Android version and the individual Android device,
            // it may require several attempts until the connection is actually
            // established. Some phones behave better in this than others. We
            // also retrieve the BluetoothDevice instance, create an RFCOMM
            // socket, _and_ try to connect in each attempt, since any one of
            // these steps may initially fail.
            // TODO: Test and define what happens when all attempts failed.
            // The user needs to be informed and given the choice to try again.
            val totalNumAttempts = 5
            retryBlocking(numberOfRetries = totalNumAttempts, delayBetweenRetries = 100) { attemptNumber, previousException ->
                if (attemptNumber == 0) {
                    logger(LogLevel.DEBUG) { "First attempt to establish an RFCOMM client connection to the Combo" }
                } else {
                    logger(LogLevel.DEBUG) {
                        "Previous attempt to establish an RFCOMM client connection to the Combo failed with" +
                        "exception \"$previousException\"; trying again (this is attempt #${attemptNumber + 1} of 5)"
                    }
                }

                progressReporter?.setCurrentProgressStage(BasicProgressStage.EstablishingBtConnection(attemptNumber + 1, totalNumAttempts))

                // Give the GC the chance to collect an older BluetoohSocket instance
                // while this thread sleep (see below).
                systemBluetoothSocket = null

                device = systemBluetoothAdapter.getRemoteDevice(androidBtAddressString)

                // Wait for 500 ms until we actually try to connect. This seems to
                // circumvent an as-of-yet unknown Bluetooth related race condition.
                // TODO: Clarify this and wait for whatever is going on there properly.
                try {
                    Thread.sleep(500)
                } catch (ignored: InterruptedException) {
                }

                systemBluetoothSocket = device.createInsecureRfcommSocketToServiceRecord(Constants.sdpSerialPortUUID)

                // connect() must be explicitly called. Just creating the socket via
                // createInsecureRfcommSocketToServiceRecord() does not implicitly
                // establish the connection. This is important to keep in mind, since
                // otherwise, the calls below get input and output streams appear at
                // first to be OK until their read/write functions are actually used.
                // At that point, very confusing NullPointerExceptions are thrown from
                // seemingly nowhere. These NPEs happen because *inside* the streams
                // there are internal Input/OutputStreams, and *these* are set to null
                // if the connection wasn't established. See also:
                // https://stackoverflow.com/questions/24267671/inputstream-read-causes-nullpointerexception-after-having-checked-inputstream#comment37491136_24267671
                // and: https://stackoverflow.com/a/24269255/560774
                systemBluetoothSocket!!.connect()
            }
        } catch (t: Throwable) {
            throw BluetoothException("Could not establish an RFCOMM client connection to device with address $address", t)
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

    override fun unpair() {
        try {
            val device = systemBluetoothAdapter.getRemoteDevice(androidBtAddressString)

            // At time of writing (2021-12-06), the removeBond method
            // is inexplicably still marked with @hide, so we must use
            // reflection to get to it and unpair this device.
            val removeBondMethod = device::class.java.getMethod("removeBond")
            removeBondMethod.invoke(device)
        } catch (t: Throwable) {
            logger(LogLevel.ERROR) { "Unpairing device with address $address failed with error $t" }
        }
    }

    override fun blockingSend(dataToSend: List<Byte>) {
        check(outputStream != null) { "Device is not connected - cannot send data" }

        try {
            outputStream!!.write(dataToSend.toByteArray())
        } catch (e: IOException) {
            throw ComboIOException("Could not write data to device with address $address", e)
        }
    }

    override fun blockingReceive(): List<Byte> {
        check(inputStream != null) { "Device is not connected - cannot receive data" }

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
