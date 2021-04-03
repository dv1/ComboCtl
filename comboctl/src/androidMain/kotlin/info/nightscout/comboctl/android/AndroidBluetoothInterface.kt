package info.nightscout.comboctl.android

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice as SystemBluetoothDevice
import android.bluetooth.BluetoothServerSocket as SystemBluetoothServerSocket
import android.bluetooth.BluetoothSocket as SystemBluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import info.nightscout.comboctl.base.BluetoothAddress
import info.nightscout.comboctl.base.BluetoothDevice
import info.nightscout.comboctl.base.BluetoothException
import info.nightscout.comboctl.base.BluetoothInterface
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.Logger
import info.nightscout.comboctl.base.toBluetoothAddress
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

private val logger = Logger.get("AndroidBluetoothInterface")

/**
 * Class for accessing Bluetooth functionality on Android.
 *
 * This needs an Android [Context] that is always present for
 * the duration of the app's existence. It is not recommended
 * to use the context from an [Activity], since such a context
 * may go away if the user turns the screen for example. If
 * the context goes away, and discovery is ongoing, then that
 * discovery prematurely ends. The context of an [Application]
 * instance is an ideal choice.
 */
class AndroidBluetoothInterface(private val androidContext: Context) : BluetoothInterface {
    private val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    private var rfcommServerSocket: SystemBluetoothServerSocket? = null
    private var discoveryStarted = false
    private var discoveryBroadcastReceiver: BroadcastReceiver? = null

    // Note that this contains ALL paired/bonded devices,
    // not just the ones that pass the deviceFilter. This
    // is important in case the filter is changed sometime
    // later, otherwise getPairedDeviceAddresses() would
    // return an incomplete list.getPairedDeviceAddresses()
    // has to apply the filter manually.
    private val pairedDeviceAddresses = mutableSetOf<BluetoothAddress>()

    // This is necessary, since the BroadcastReceivers always
    // run in the UI thread, while access to the pairedDeviceAddresses
    // can be requested from other threads.
    private val deviceAddressLock = ReentrantLock()

    private var listenThread: Thread? = null

    private var unpairedDevicesBroadcastReceiver: BroadcastReceiver? = null

    // Stores SystemBluetoothDevice that were previously seen in
    // onAclConnected(). These instances represent a device that
    // was found during discovery. The first time the device is
    // discovered, an instance is provided - but that first instance
    // is not usable (this seems to be caused by an underlying
    // Bluetooth stack bug). Only when _another_ instance that
    // represents the same device is seen can that other instance
    // be used and pairing can continue. Therefore, we store the
    // previous observation to be able to detect whether a
    // discovered instance is the first or second one that represents
    // the device. We also retain the first instance until the
    // second one is found - this seems to improve pairing stability
    // on some Android devices.
    // TODO: Find out why these weird behavior occurs and why
    // we can only use the second instance.
    private val previouslyDiscoveredDevices = mutableMapOf<BluetoothAddress, SystemBluetoothDevice?>()

    private var discoveryStopped: (reason: BluetoothInterface.DiscoveryStoppedReason) -> Unit = { }

    override var onDeviceUnpaired: (deviceAddress: BluetoothAddress) -> Unit = { }

    override var deviceFilter: (deviceAddress: BluetoothAddress) -> Boolean = { true }

    fun setup() {
        previouslyDiscoveredDevices.clear()

        val bondedDevices = bluetoothAdapter.bondedDevices

        logger(LogLevel.DEBUG) { "Found ${bondedDevices.size} bonded Bluetooth device(s)" }

        for (bondedDevice in bondedDevices) {
            val androidBtAddressString = bondedDevice.address
            logger(LogLevel.DEBUG) {
                "... device $androidBtAddressString"
            }

            try {
                val comboctlBtAddress = androidBtAddressString.toBluetoothAddress()
                pairedDeviceAddresses.add(comboctlBtAddress)
            } catch (e: Exception) {
                logger(LogLevel.ERROR) {
                    "Could not convert Android bluetooth device address " +
                            "\"$androidBtAddressString\" to a valid BluetoothAddress instance; skipping device"
                }
            }
        }

        unpairedDevicesBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                logger(LogLevel.DEBUG) { "unpairedDevicesBroadcastReceiver received new action: ${intent.action}" }

                when (intent.action) {
                    SystemBluetoothDevice.ACTION_BOND_STATE_CHANGED -> onBondStateChanged(intent)
                    else -> Unit
                }
            }
        }

        androidContext.registerReceiver(
            unpairedDevicesBroadcastReceiver,
            IntentFilter(SystemBluetoothDevice.ACTION_BOND_STATE_CHANGED)
        )
    }

    fun teardown() {
        if (unpairedDevicesBroadcastReceiver != null) {
            androidContext.unregisterReceiver(unpairedDevicesBroadcastReceiver)
            unpairedDevicesBroadcastReceiver = null
        }
    }

    override fun startDiscovery(
        sdpServiceName: String,
        sdpServiceProvider: String,
        sdpServiceDescription: String,
        btPairingPin: String,
        discoveryDuration: Int,
        discoveryStopped: (reason: BluetoothInterface.DiscoveryStoppedReason) -> Unit,
        foundNewPairedDevice: (deviceAddress: BluetoothAddress) -> Unit
    ) {
        if (discoveryStarted)
            throw IllegalStateException("Discovery already started")

        // The Combo communicates over RFCOMM using the SDP Serial Port Profile.
        // We use an insecure socket, which means that it lacks an authenticated
        // link key. This is done because the Combo does not use this feature.
        //
        // TODO: Can Android RFCOMM SDP service records be given custom
        // sdpServiceProvider and sdpServiceDescription values? (This is not
        // necessary for correct function, just a detail for sake of completeness.)
        logger(LogLevel.DEBUG) { "Setting up RFCOMM listener socket" }
        rfcommServerSocket = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(
            sdpServiceName,
            Constants.sdpSerialPortUUID
        )

        // Run a separate thread to accept and throw away incoming RFCOMM connections.
        // We do not actually use those; the RFCOMM listener socket only exists to be
        // able to provide an SDP SerialPort service record that can be discovered by
        // the pump, and that record needs an RFCOMM listener port number.
        listenThread = thread {
            logger(LogLevel.DEBUG) { "RFCOMM listener thread started" }

            try {
                while (true) {
                    logger(LogLevel.DEBUG) { "Waiting for incoming RFCOMM socket to accept" }
                    var socket: SystemBluetoothSocket? = null
                    if (rfcommServerSocket != null)
                        socket = rfcommServerSocket!!.accept()
                    if (socket != null) {
                        logger(LogLevel.DEBUG) { "Closing accepted incoming RFCOMM socket" }
                        try {
                            socket.close()
                        } catch (e: IOException) {
                        }
                    }
                }
            } catch (e: Exception) {
                // This happens when rfcommServerSocket.close() is called.
                logger(LogLevel.DEBUG) { "RFCOMM listener accept() call aborted" }
            }

            logger(LogLevel.DEBUG) { "RFCOMM listener thread stopped" }
        }

        this.discoveryStopped = discoveryStopped

        logger(LogLevel.DEBUG) {
            "Registering receiver for getting notifications about pairing requests and connected devices"
        }

        val intentFilter = IntentFilter()
        intentFilter.addAction(SystemBluetoothDevice.ACTION_ACL_CONNECTED)
        intentFilter.addAction(SystemBluetoothDevice.ACTION_PAIRING_REQUEST)
        intentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)

        discoveryBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                logger(LogLevel.DEBUG) { "discoveryBroadcastReceiver received new action: ${intent.action}" }

                when (intent.action) {
                    SystemBluetoothDevice.ACTION_ACL_CONNECTED -> onAclConnected(intent, foundNewPairedDevice)
                    SystemBluetoothDevice.ACTION_PAIRING_REQUEST -> onPairingRequest(intent, btPairingPin)
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> onDiscoveryFinished()
                    else -> Unit
                }
            }
        }

        androidContext.registerReceiver(discoveryBroadcastReceiver, intentFilter)

        logger(LogLevel.DEBUG) { "Starting activity for making this Android device discoverable" }

        val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, discoveryDuration)
            putExtra(BluetoothAdapter.EXTRA_SCAN_MODE, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE)
            // Necessary since we want to be able to start scans from any
            // context, not just from activities. In fact, starting scans
            // from activities would be a back pick, since they can  can
            // go away at any moment, taking the ongoing scan with them.
            flags = flags or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        androidContext.startActivity(discoverableIntent)

        logger(LogLevel.DEBUG) { "Started discovery" }

        discoveryStarted = true
    }

    override fun stopDiscovery() {
        // Close the server socket. This frees RFCOMM resources and ends
        // the listenThread because the accept() call inside will be aborted
        // by the close() call.
        try {
            if (rfcommServerSocket != null)
                rfcommServerSocket!!.close()
        } catch (e: IOException) {
            logger(LogLevel.ERROR) { "Caught IO exception while closing RFCOMM server socket: $e" }
        } finally {
            rfcommServerSocket = null
        }

        // The listenThread will be shutting down now after the server
        // socket was closed, since the blocking accept() call inside
        // the thread gets aborted by close(). Just wait here for the
        // thread to fully finish before we continue.
        if (listenThread != null) {
            logger(LogLevel.DEBUG) { "Waiting for RFCOMM listener thread to finish" }
            listenThread!!.join()
            logger(LogLevel.DEBUG) { "RFCOMM listener thread finished" }
            listenThread = null
        }

        if (discoveryBroadcastReceiver != null) {
            androidContext.unregisterReceiver(discoveryBroadcastReceiver)
            discoveryBroadcastReceiver = null
        }

        if (bluetoothAdapter.isDiscovering) {
            logger(LogLevel.DEBUG) { "Stopping discovery" }
            bluetoothAdapter.cancelDiscovery()
        }

        if (discoveryStarted) {
            logger(LogLevel.DEBUG) { "Stopped discovery" }
            discoveryStarted = false
        }
    }

    override fun unpairDevice(deviceAddress: BluetoothAddress) {
        // NOTE: Unfortunately, Android has no public removeBond() functionality,
        // so we cannot do anything here.
    }

    override fun getDevice(deviceAddress: BluetoothAddress): BluetoothDevice =
        AndroidBluetoothDevice(this, bluetoothAdapter, deviceAddress)

    override fun getAdapterFriendlyName() =
        bluetoothAdapter.name ?: throw BluetoothException("Could not get Bluetooth adapter friendly name")

    override fun getPairedDeviceAddresses(): Set<BluetoothAddress> =
        try {
            deviceAddressLock.lock()
            pairedDeviceAddresses.filter { pairedDeviceAddress -> deviceFilter(pairedDeviceAddress) }.toSet()
        } finally {
            deviceAddressLock.unlock()
        }

    private fun onAclConnected(intent: Intent, foundNewPairedDevice: (deviceAddress: BluetoothAddress) -> Unit) {
        // Sanity check in case we get this notification for the
        // device already and need to avoid duplicate processing.
        if (intent.getStringExtra("address") != null)
            return

        // Sanity check to make sure we can actually get
        // a Bluetooth device out of the intent. Otherwise,
        // we have to wait for the next notification.
        val androidBtDevice = intent.getParcelableExtra<SystemBluetoothDevice>(SystemBluetoothDevice.EXTRA_DEVICE)
        if (androidBtDevice == null) {
            logger(LogLevel.DEBUG) { "Ignoring ACL_CONNECTED intent that has no Bluetooth device" }
            return
        }

        val androidBtAddressString = androidBtDevice.address
        // This effectively marks the device as "already processed"
        // (see the getStringExtra() call above).
        intent.putExtra("address", androidBtAddressString)

        logger(LogLevel.DEBUG) { "ACL_CONNECTED intent has Bluetooth device with address $androidBtAddressString" }

        val comboctlBtAddress = try {
            androidBtAddressString.toBluetoothAddress()
        } catch (e: Exception) {
            logger(LogLevel.ERROR) {
                "Could not convert Android bluetooth device address " +
                        "\"$androidBtAddressString\" to a valid BluetoothAddress instance; skipping device"
            }
            return
        }

        // During discovery, the ACTION_ACL_CONNECTED action apparently
        // is notified at least *twice*. And, the device that is present
        // in the intent may not be the same device there was during
        // the first notification (= the parcelableExtra EXTRA_DEVICE
        // seen above). It turns out that only the *second EXTRA_DEVICE
        // can actually be used (otherwise pairing fails). The reason
        // for this is unknown, but seems to be caused by bugs in the
        // Fluoride (aka BlueDroid) Bluetooth stack.
        // To circumvent this, we don't do anything the first time,
        // but remember the device's Bluetooth address. Only when we
        // see that address again do we actually proceed with announcing
        // the device as having been discovered.
        // NOTE: This is different from the getStringExtra() check
        // above. That one checks if the *same* Android Bluetooth device
        // instance was already processed. This check here instead
        // verifies if we have seen the same Bluetooth address on
        // *different* Android Bluetooth device instances.
        // TODO: Test how AndroidBluetoothInterface behaves if the
        // device is unpaired while discovery  is ongoing (manually by
        // the user for example). In theory, this should be handled
        // properly by the onBondStateChanged function below.
        // TODO: This check may not be necessary on all Android
        // devices. On some, it seems to also work if we use the
        // first offered BluetoothDevice.
        if (comboctlBtAddress !in previouslyDiscoveredDevices) {
            previouslyDiscoveredDevices[comboctlBtAddress] = androidBtDevice
            logger(LogLevel.DEBUG) {
                "Device with address $comboctlBtAddress discovered for the first time; " +
                        "need to \"discover\" it again to be able to announce its discovery"
            }
            return
        } else {
            previouslyDiscoveredDevices[comboctlBtAddress] = null
            logger(LogLevel.DEBUG) {
                "Device with address $comboctlBtAddress discovered for the second time; " +
                        "announcing it as discovered"
            }
        }

        // Always adding the device to the paired addresses even
        // if the deviceFilter() below returns false. See the
        // pairedDeviceAddresses comments above for more.
        try {
            deviceAddressLock.lock()
            pairedDeviceAddresses.add(comboctlBtAddress)
        } finally {
            deviceAddressLock.unlock()
        }

        logger(LogLevel.INFO) { "Got device with address $androidBtAddressString" }

        try {
            // Apply device filter before announcing a newly
            // discovered device, just as the ComboCtl
            // BluetoothInterface.startDiscovery()
            // documentation requires.
            if (deviceFilter(comboctlBtAddress))
                foundNewPairedDevice(comboctlBtAddress)
        } catch (e: Exception) {
            logger(LogLevel.ERROR) { "Caught exception while invoking foundNewPairedDevice callback: $e" }
        }
    }

    private fun onBondStateChanged(intent: Intent) {
        // Here, we handle the case where a previously paired
        // device just got unpaired. The caller needs to know
        // about this to check if said device was a Combo.
        // If so, the caller may have to update states like
        // the pump state store accordingly.

        val androidBtDevice = intent.getParcelableExtra<SystemBluetoothDevice>(SystemBluetoothDevice.EXTRA_DEVICE)
        if (androidBtDevice == null) {
            logger(LogLevel.DEBUG) { "Ignoring BOND_STATE_CHANGED intent that has no Bluetooth device" }
            return
        }

        val androidBtAddressString = androidBtDevice.address

        logger(LogLevel.DEBUG) { "PAIRING_REQUEST intent has Bluetooth device with address $androidBtAddressString" }

        val comboctlBtAddress = try {
            androidBtAddressString.toBluetoothAddress()
        } catch (e: Exception) {
            logger(LogLevel.ERROR) {
                "Could not convert Android bluetooth device address " +
                        "\"$androidBtAddressString\" to a valid BluetoothAddress instance; ignoring device"
            }
            return
        }

        val previousBondState = intent.getIntExtra(SystemBluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, SystemBluetoothDevice.ERROR)
        val currentBondState = intent.getIntExtra(SystemBluetoothDevice.EXTRA_BOND_STATE, SystemBluetoothDevice.ERROR)

        // An unpaired device is characterized by a state change
        // from non-NONE to NONE. Filter out all other state changes.
        if (!((currentBondState == SystemBluetoothDevice.BOND_NONE) && (previousBondState != SystemBluetoothDevice.BOND_NONE))) {
            return
        }

        previouslyDiscoveredDevices.remove(comboctlBtAddress)

        // Always removing the device from the paired addresses
        // event if the deviceFilter() below returns false. See
        // the pairedDeviceAddresses comments above for more.
        try {
            deviceAddressLock.lock()
            pairedDeviceAddresses.remove(comboctlBtAddress)
            logger(LogLevel.DEBUG) { "Removed device with address $comboctlBtAddress from the list of paired devices" }
        } finally {
            deviceAddressLock.unlock()
        }

        // Apply device filter before announcing an
        // unpaired device, just as the ComboCtl
        // BluetoothInterface.startDiscovery()
        // documentation requires.
        try {
            if (deviceFilter(comboctlBtAddress)) {
                onDeviceUnpaired(comboctlBtAddress)
            }
        } catch (e: Exception) {
            logger(LogLevel.ERROR) { "Caught exception while invoking onDeviceUnpaired callback: $e" }
        }
    }

    private fun onPairingRequest(intent: Intent, btPairingPin: String) {
        val androidBtDevice = intent.getParcelableExtra<SystemBluetoothDevice>(SystemBluetoothDevice.EXTRA_DEVICE)
        if (androidBtDevice == null) {
            logger(LogLevel.DEBUG) { "Ignoring PAIRING_REQUEST intent that has no Bluetooth device" }
            return
        }

        val androidBtAddressString = androidBtDevice.address

        logger(LogLevel.DEBUG) { "PAIRING_REQUEST intent has Bluetooth device with address $androidBtAddressString" }

        val comboctlBtAddress = try {
            androidBtAddressString.toBluetoothAddress()
        } catch (e: Exception) {
            logger(LogLevel.ERROR) {
                "Could not convert Android bluetooth device address " +
                        "\"$androidBtAddressString\" to a valid BluetoothAddress instance; ignoring device"
            }
            return
        }

        if (!deviceFilter(comboctlBtAddress)) {
            logger(LogLevel.DEBUG) { "This is not a Combo pump; ignoring device" }
            return
        }

        logger(LogLevel.INFO) {
            " Device with address $androidBtAddressString is a Combo pump; accepting Bluetooth pairing request"
        }

        // TODO: The following calls can fail, and yet,
        // the pairing is established successfully.
        // This requires further clarification.

        try {
            if (!androidBtDevice.setPin(btPairingPin.encodeToByteArray())) {
                logger(LogLevel.WARN) { "Could not set Bluetooth pairing PIN" }
            }
        } catch (e: Exception) {
            logger(LogLevel.WARN) { "Caught exception while setting Bluetooth pairing PIN: $e" }
        }

        try {
            if (!androidBtDevice.createBond()) {
                logger(LogLevel.WARN) { "Could not create bond" }
            }
        } catch (e: Exception) {
            logger(LogLevel.WARN) { "Caught exception while creating bond: $e" }
        }

        // TODO: setPairingConfirmation requires the BLUETOOTH_PRIVILEGED
        // permission. However, this permission is only accessible to
        // system apps. Leaving this call here in case this can be resolved
        // somehow in the future, but this most likely throws a
        // SecurityException on most Android devices.
        try {
            if (!androidBtDevice.setPairingConfirmation(true)) {
                logger(LogLevel.WARN) { "Could not set pairing confirmation" }
            }
        } catch (e: Exception) {
            logger(LogLevel.WARN) { "Caught exception while setting pairing confirmation: $e" }
        }

        logger(LogLevel.INFO) { "Established Bluetooth pairing with Combo pump with address $androidBtAddressString" }
    }

    private fun onDiscoveryFinished() {
        logger(LogLevel.DEBUG) { "Discovery finished" }
        // TODO: Is there a way to determine the reason for the stop?
        discoveryStopped(BluetoothInterface.DiscoveryStoppedReason.MANUALLY_STOPPED)
    }
}
