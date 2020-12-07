package devtools

import devtools.common.*
import info.nightscout.comboctl.base.*
import info.nightscout.comboctl.linuxBlueZ.BlueZInterface
import kotlinx.coroutines.*

// Tool for manually operating the BlueZ interface.
//
// It can be run from the command line like this:
//
//     java -Djava.library.path="comboctl/src/jvmMain/cpp/linuxBlueZCppJNI/build/lib/main/debug" -jar devtools/bluez_cli/build/libs/bluez_cli-standalone.jar

class MainApp(private val mainScope: CoroutineScope) {
    private val cli: CommandLineInterface
    private val bluezInterface: BlueZInterface
    private val connectedBluetoothDevices = mutableMapOf<BluetoothAddress, BluetoothDevice>()

    init {
        cli = CommandLineInterface(
            mapOf(
                "quit" to CommandEntry(
                    0,
                    "Quits the program",
                    "") { quit() },
                "startDiscovery" to CommandEntry(
                    0,
                    "Starts Bluetooth discovery in the background",
                    "") { startDiscovery() },
                "stopDiscovery" to CommandEntry(
                    0,
                    "Stops Bluetooth discovery",
                    "") { stopDiscovery() },
                "connectDevice" to CommandEntry(
                    1,
                    "Connects to a Bluetooth device with the given address",
                    "<bluetooth address>") { args -> connectDevice(args) },
                "disconnectDevice" to CommandEntry(
                    1,
                    "Disconnects from the Bluetooth device with the given address (must be connected first)",
                    "<bluetooth address>") { args -> disconnectDevice(args) },
                "receiveFromDevice" to CommandEntry(
                    1,
                    "Receive some data via RFCOMM from the Bluetooth device with the given address (must be connected first)",
                    "<bluetooth address>") { args -> receiveFromDevice(args) },
                "sendToDevice" to CommandEntry(
                    2,
                    "Send some data via RFCOMM to the Bluetooth device with the given address (must be connected first)",
                    "<bluetooth address> <first byte in hex> [<second byte in hex> ... ]") { args -> sendToDevice(args) }
            ),
            { quit() }
        )

        // TODO: Extract loadLibrary call from within BlueZInterface,
        // since it does not belong there (it needs to be called
        // explicitely, outside of this class).
        bluezInterface = BlueZInterface()

        bluezInterface.onDeviceUnpaired = {
            deviceAddress -> println("Previously paired device with address $deviceAddress removed")
        }

        bluezInterface.deviceFilter = {
            // Filter for Combo devices based on their address.
            // The first 3 bytes of a Combo are always the same.
            deviceAddress ->
            (deviceAddress[0] == 0x00.toByte()) &&
            (deviceAddress[1] == 0x0E.toByte()) &&
            (deviceAddress[2] == 0x2F.toByte())
        }
    }

    // Public functions

    fun shutdown() {
        // Disconnect and clear up all previously connected devices
        // to make sure we have a blank slate post-shutdown.
        for (bluetoothDevice in connectedBluetoothDevices.values) {
            printLine("Disconnecting device ${bluetoothDevice.address}")
            bluetoothDevice.disconnect()
        }
        connectedBluetoothDevices.clear()

        bluezInterface.shutdown()
    }

    suspend fun run() {
        printLine("")
        printLine("Use the \"help\" command to get a list of valid commands")
        printLine("")
        cli.run("cmd> ")
    }

    private fun printLine(line: String) = cli.printLine(line)

    // Command handlers

    private fun quit() {
        printLine("Quitting")
        shutdown()
        mainScope.cancel()
    }

    private fun startDiscovery() {
        mainScope.launch {
            try {
                bluezInterface.startDiscovery(
                    Constants.BT_SDP_SERVICE_NAME,
                    "Custom ComboCtl SDP service",
                    "ComboCtl",
                    Constants.BT_PAIRING_PIN,
                    { deviceAddress -> println("Found paired device with address $deviceAddress") }
                )
                printLine("BT friendly name: ${bluezInterface.getAdapterFriendlyName()}")
            } catch (e: IllegalStateException) {
                printLine("Attempted to start discovery even though it is running already")
            } catch (e: BluetoothException) {
                printLine("Bluetooth interface exception: $e")
            }
        }
    }

    private fun stopDiscovery() {
        bluezInterface.stopDiscovery()
    }

    private fun connectDevice(arguments: List<String>) {
        val deviceAddress = getBluetoothAddressFromString(arguments[0])

        if (connectedBluetoothDevices.contains(deviceAddress)) {
            printLine("Device $deviceAddress is already (being) connected")
            return
        }

        val device = bluezInterface.getDevice(deviceAddress)
        connectedBluetoothDevices[deviceAddress] = device

        mainScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    device.connect()
                }
            } catch (e: Exception) {
                printLine("Failed to connect device $deviceAddress: $e")
                connectedBluetoothDevices.remove(deviceAddress)
            }
        }
    }

    private fun disconnectDevice(arguments: List<String>) {
        val deviceAddress = getBluetoothAddressFromString(arguments[0])
        val device = getBluetoothDevice(deviceAddress)

        device.disconnect()
        connectedBluetoothDevices.remove(deviceAddress)

        printLine("Disconnected device $deviceAddress")
    }

    private fun receiveFromDevice(arguments: List<String>) {
        val deviceAddress = getBluetoothAddressFromString(arguments[0])
        val device = getBluetoothDevice(deviceAddress)

        mainScope.launch {
            try {
                val bytes = device.receive()
                printLine("Received ${bytes.size} byte(s from device $deviceAddress: ${bytes.toHexString()}")
            } catch (e: ComboIOException) {
                printLine("Failed to receive from device $deviceAddress: $e")
            }
        }
    }

    private fun sendToDevice(arguments: List<String>) {
        val deviceAddress = getBluetoothAddressFromString(arguments[0])
        val device = getBluetoothDevice(deviceAddress)

        mainScope.launch {
            try {
                val bytesToSend = arguments.subList(1, arguments.size).map { it.toInt(16).toByte() }
                device.send(bytesToSend)
            } catch (e: NumberFormatException) {
                throw CommandLineException("Invalid hex number: $e")
            } catch (e: ComboIOException) {
                printLine("Failed to receive from device $deviceAddress: $e")
            }
        }
    }

    // Misc private functions

    private fun getBluetoothAddressFromString(addressString: String): BluetoothAddress {
        try {
            return addressString.toBluetoothAddress()
        } catch (e: IllegalArgumentException) {
            throw CommandLineException("Bluetooth address \"$addressString\" is invalid")
        }
    }

    private fun getBluetoothDevice(deviceAddress: BluetoothAddress): BluetoothDevice {
        val device = connectedBluetoothDevices.get(deviceAddress)

        if (device == null)
            throw CommandLineException("Device $deviceAddress is not connected")

        return device
    }
}

fun main(vararg args: String) {
    runBlocking {
        var mainApp: MainApp? = null
        try {
            // We use a supervisor scope to make sure
            // that if one child fails it does not drag
            // the entire scope down with it.
            supervisorScope {
                mainApp = MainApp(this)

                launch {
                    mainApp!!.run()
                }
            }
        } catch (e: CancellationException) {
            // no-op. We just want to make sure that
            // cancellation - which is not an error -
            // does not produce confusing and misleading
            // stack traces.
        } finally {
            if (mainApp != null)
                mainApp!!.shutdown()
        }
    }

    println("Bye")
}
