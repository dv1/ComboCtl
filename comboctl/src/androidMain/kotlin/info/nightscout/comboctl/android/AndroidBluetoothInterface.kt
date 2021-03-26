package info.nightscout.comboctl.android

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice as SystemBluetoothDevice
import android.bluetooth.BluetoothServerSocket as SystemBluetoothServerSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import info.nightscout.comboctl.base.BluetoothAddress
import info.nightscout.comboctl.base.BluetoothDevice
import info.nightscout.comboctl.base.BluetoothInterface
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.Logger
import info.nightscout.comboctl.base.toBluetoothAddress
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock

private val logger = Logger.get("AndroidBluetoothInterface")

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
    private val deviceAddressLock = ReentrantLock()
    private var unpairedDevicesBroadcastReceiver: BroadcastReceiver? = null

    override var onDeviceUnpaired: (deviceAddress: BluetoothAddress) -> Unit = { }

    override var deviceFilter: (deviceAddress: BluetoothAddress) -> Boolean = { true }

    fun setup() {
        val bondedDevices = bluetoothAdapter.getBondedDevices()

        logger(LogLevel.DEBUG) { "Found ${bondedDevices.size} bonded Bluetooth device(s)" }

        for (bondedDevice in bondedDevices) {
            val androidBtAddressString = bondedDevice.getAddress()
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
                logger(LogLevel.DEBUG) { "unpairedDevicesBroadcastReceiver received new action: ${intent.getAction()}" }

                when (intent.getAction()) {
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
        foundNewPairedDevice: (deviceAddress: BluetoothAddress) -> Unit
    ) {
        if (discoveryStarted)
            throw IllegalStateException("Discovery already started")

        logger(LogLevel.DEBUG) { "Setting up RFCOMM listener socket" }
        rfcommServerSocket = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(
            sdpServiceName,
            Constants.sdpSerialPortUUID
        )
        // NOTE: sdpServiceProvider and sdpServiceDescription are not used here.

        logger(LogLevel.DEBUG) {
            "Registering receiver for getting notifications about pairing requests and connected devices"
        }

        val intentFilter = IntentFilter()
        intentFilter.addAction(SystemBluetoothDevice.ACTION_ACL_CONNECTED)
        intentFilter.addAction(SystemBluetoothDevice.ACTION_PAIRING_REQUEST)

        discoveryBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                logger(LogLevel.DEBUG) { "discoveryBroadcastReceiver received new action: ${intent.getAction()}" }

                when (intent.getAction()) {
                    SystemBluetoothDevice.ACTION_ACL_CONNECTED -> onAclConnected(intent, foundNewPairedDevice)
                    SystemBluetoothDevice.ACTION_PAIRING_REQUEST -> onPairingRequest(intent, btPairingPin)
                    else -> Unit
                }
            }
        }

        androidContext.registerReceiver(discoveryBroadcastReceiver, intentFilter)

        logger(LogLevel.DEBUG) { "Starting activity for making this Android device discoverable" }

        val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
        // TODO: Currently, the ComboCtl API does not support time-limited
        // discovery. Either, add this to the API, or try to work around
        // this Android limitation, for example by detecting the end of
        // the discoverable mode & re-requesting it then.
        discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        discoverableIntent.putExtra(BluetoothAdapter.EXTRA_SCAN_MODE, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE)
        androidContext.startActivity(discoverableIntent)

        logger(LogLevel.DEBUG) { "Started discovery" }

        discoveryStarted = true
    }

    override fun stopDiscovery() {
        if (discoveryBroadcastReceiver != null) {
            androidContext.unregisterReceiver(discoveryBroadcastReceiver)
            discoveryBroadcastReceiver = null
        }

        try {
            if (rfcommServerSocket != null)
                rfcommServerSocket!!.close()
        } catch (e: IOException) {
            logger(LogLevel.ERROR) { "Caught IO exception while closing RFCOMM server socket: $e" }
        } finally {
            rfcommServerSocket = null
        }

        if (bluetoothAdapter.isDiscovering()) {
            logger(LogLevel.DEBUG) { "Stopping discovery" }
            bluetoothAdapter.cancelDiscovery()
        }

        if (discoveryStarted) {
            logger(LogLevel.DEBUG) { "Stopped discovery" }
            discoveryStarted = false
        }
    }

    override fun unpairDevice(deviceAddress: BluetoothAddress) {
    }

    override fun getDevice(deviceAddress: BluetoothAddress): BluetoothDevice =
        AndroidBluetoothDevice(this, bluetoothAdapter, deviceAddress)

    override fun getAdapterFriendlyName() =
        bluetoothAdapter.getName()

    override fun getPairedDeviceAddresses(): Set<BluetoothAddress> =
        try {
            deviceAddressLock.lock()
            pairedDeviceAddresses.filter { pairedDeviceAddress -> deviceFilter(pairedDeviceAddress) }.toSet()
        } finally {
            deviceAddressLock.unlock()
        }

    private fun onAclConnected(intent: Intent, foundNewPairedDevice: (deviceAddress: BluetoothAddress) -> Unit) {
        val androidBtDevice = intent.getParcelableExtra<SystemBluetoothDevice>(SystemBluetoothDevice.EXTRA_DEVICE)
        if (androidBtDevice == null) {
            logger(LogLevel.DEBUG) { "Ignoring ACL_CONNECTED intent that has no Bluetooth device" }
            return
        }

        val androidBtAddressString = androidBtDevice.getAddress()
        // intent.putExtra("address", androidBtAddressString)

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

        try {
            deviceAddressLock.lock()
            pairedDeviceAddresses.add(comboctlBtAddress)
        } finally {
            deviceAddressLock.unlock()
        }

        logger(LogLevel.INFO) { "Got device with address $androidBtAddressString" }

        try {
            if (deviceFilter(comboctlBtAddress))
                foundNewPairedDevice(comboctlBtAddress)
        } catch (e: Exception) {
            logger(LogLevel.ERROR) { "Caught exception while invoking foundNewPairedDevice callback: $e" }
        }
    }

    private fun onBondStateChanged(intent: Intent) {
        val androidBtDevice = intent.getParcelableExtra<SystemBluetoothDevice>(SystemBluetoothDevice.EXTRA_DEVICE)
        if (androidBtDevice == null) {
            logger(LogLevel.DEBUG) { "Ignoring BOND_STATE_CHANGED intent that has no Bluetooth device" }
            return
        }

        val androidBtAddressString = androidBtDevice.getAddress()
        // intent.putExtra("address", androidBtAddressString)

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

        if (!((currentBondState == SystemBluetoothDevice.BOND_NONE) && (previousBondState != SystemBluetoothDevice.BOND_NONE)))
            return

        try {
            deviceAddressLock.lock()
            pairedDeviceAddresses.remove(comboctlBtAddress)
            logger(LogLevel.DEBUG) { "Removed device with address $comboctlBtAddress from the list of paired devices" }
        } finally {
            deviceAddressLock.unlock()
        }

        try {
            if (deviceFilter(comboctlBtAddress))
                onDeviceUnpaired(comboctlBtAddress)
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

        val androidBtAddressString = androidBtDevice.getAddress()
        // intent.putExtra("address", androidBtAddressString)

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

        if (!androidBtDevice.setPin(btPairingPin.encodeToByteArray())) {
            logger(LogLevel.ERROR) { "Could not set Bluetooth pairing PIN" }
            return
        }

        if (!androidBtDevice.createBond()) {
            logger(LogLevel.ERROR) { "Could not create bond" }
            return
        }

        if (!androidBtDevice.setPairingConfirmation(true)) {
            logger(LogLevel.ERROR) { "Could not set pairing confirmation" }
            return
        }

        logger(LogLevel.INFO) { "Established Bluetooth pairing with Combo pump with address $androidBtAddressString" }
    }
}
