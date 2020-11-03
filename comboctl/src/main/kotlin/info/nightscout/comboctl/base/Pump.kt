package info.nightscout.comboctl.base

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val logger = Logger.get("Pump")

/**
 * Class for operating a Combo pump.
 *
 * Instances of this class represent a Combo pump that is accessible
 * via a corresponding [BluetoothDevice] instance. That instance
 * must not be connected when it is associated with this Pump object,
 * since the object will take care of connecting/disconnecting.
 *
 * This is the high level interface for controlling a pump with
 * ComboCtl. Programs using ComboCtl primarily use this class,
 * along with [MainControl].
 *
 * Each Pump object has a [PersistentPumpStateStore] associated with
 * it. A [PersistentPumpStateStore] of one Pump instance must be kept
 * entirely separate from other persistent states. So, even if a
 * pump's persistent state is reset (that is, completely wiped),
 * other pump states must not be affected.
 *
 * The constructor does not immediately connect to the pump.
 * This is done by calling [connect]. Also note that before being
 * able to establish a connection, the pump must have been paired.
 * The [performPairing] function can be used for this purpose.
 *
 * Instances of this class are typically not created manually,
 * but rather by calling [MainControl.connect]. Likewise, the
 * [performPairing] function is typically not called directly,
 * but rather by [MainControl] during discovery.
 *
 * WARNING: Do not create more than one Pump instance for the same
 * pump at the same time. Two Pump instances operating the same pump
 * leads to undefined behavior.
 *
 * @param bluetoothDevice [BluetoothDevice] object to use for
 *        Bluetooth I/O. Must be in a disconnected state when
 *        assigned to this instance.
 * @param persistentPumpStateStore Persistent state store for this pump.
 * @param onNewDisplayFrame Callback invoked every time the pump
 *        receives a new complete remote terminal frame.
 */
class Pump(
    private val bluetoothDevice: BluetoothDevice,
    private val persistentPumpStateStore: PersistentPumpStateStore,
    private val onNewDisplayFrame: (displayFrame: DisplayFrame) -> Unit
) {
    private val transportLayer: TransportLayer
    private val applicationLayer = ApplicationLayer()
    private val highLevelIO: HighLevelIO
    private val framedComboIO: FramedComboIO

    private var isConnected = false

    init {
        // Pass IO through the FramedComboIO class since the Combo
        // sends packets in a framed form (See [ComboFrameParser]
        // and [List<Byte>.toComboFrame] for details).
        framedComboIO = FramedComboIO(bluetoothDevice)
        transportLayer = TransportLayer(persistentPumpStateStore)
        highLevelIO = HighLevelIO(
            transportLayer,
            applicationLayer,
            framedComboIO,
            onNewDisplayFrame
        )
    }

    /**
     * Returns whether or not this pump has already been paired.
     *
     * "Pairing" refers to the custom Combo pairing here, not the
     * Bluetooth pairing. It is not possible to get a valid [Pump]
     * instance without the device having been paired at the
     * Bluetooth level before anyway.
     *
     * This detects whether or not the Combo pairing has been
     * performed by looking at the persistent state associated
     * with this [Pump] instance. If the state is set to valid
     * values, then the pump is assumed to be paired. If the
     * persistent state is in its initial state (ciphers set to
     * null, key response address set to null), then it is assumed
     * to be unpaired.
     *
     * @return true if the pump is paired.
     */
    fun isPaired() = persistentPumpStateStore.isValid()

    /**
     * Performs a pairing procedure with the pump.
     *
     * This performs the Combo-specific pairing. When this is called,
     * the pump must have been paired at the Bluetooth level already.
     * From Bluetooth's point of view, the pump is already paired with
     * the client at this point. But the Combo itself needs additional
     * pairing, which we perform with this function.
     *
     * Once this is done, the [persistentPumpStateStore will be filled
     * with all of the necessary information (ciphers etc.) for
     * establishing regular connections with [connect].
     *
     * Packets are received in a loop that runs in a background
     * coroutine that operates in the [backgroundReceiveScope].
     *
     * That scope's context needs to be associated with the same thread
     * this function is called in. Otherwise, the receive loop
     * may run in a different thread than this function, potentially
     * leading to race conditions.
     *
     * The pairingPINCallback callback has two arguments. previousAttemptFailed
     * is set to false initially, and true if this is a repeated call due
     * to a previous failure to apply the PIN. Such a failure typically
     * happens because the user mistyped the PIN, but in rare cases can also
     * happen due to corrupted packets. getPINDeferred is a CompletableDeferred
     * that will suspend this function until the callback invokes its
     * [CompletableDeferred.complete] function or the coroutine is cancelled.
     *
     * This internally uses [HighLevelIO.performPairing], but also
     * handles the Bluetooth connection setup and teardown, so do
     * not connect to the Bluetooth device prior to this call.
     *
     * WARNING: Do not run multiple performPairing functions simultaneously
     *  on the same pump. Otherwise, undefined behavior occurs.
     *
     * @param backgroundReceiveScope [CoroutineScope] to run the background
     *        packet receive loop in.
     * @param bluetoothFriendlyName The Bluetooth friendly name to use in
     *        REQUEST_ID packets. Use [BluetoothInterface.getAdapterFriendlyName]
     *        to get the friendly name.
     * @param pairingPINCallback Callback that gets invoked as soon as
     *        the pairing process needs the 10-digit-PIN.
     * @throws IllegalStateException if this is ran while a connection
     *         is running, or if the pump is already paired.
     * @throws ComboIOException if connection fails due to an underlying
     *         IO issue.
     * @throws ReceiveLoopFailureException if the background
     *         receive loop failed.
     */
    suspend fun performPairing(
        backgroundReceiveScope: CoroutineScope,
        bluetoothFriendlyName: String,
        pairingPINCallback: (previousAttemptFailed: Boolean, getPINDeferred: CompletableDeferred<PairingPIN>) -> Unit
    ) {
        // This function is called once the Bluetooth pairing
        // has already been established. We still need to perform
        // the Combo specific pairing.

        if (isPaired())
            throw IllegalStateException(
                "Attempting to pair with Combo with address ${bluetoothDevice.address} even though it is already paired")

        if (isConnected)
            throw IllegalStateException(
                "Attempting to pair with Combo with address ${bluetoothDevice.address} even though it is currently connected")

        // This flag is used for checking if we need to unpair
        // the Bluetooth device before leaving this function. This
        // makes sure that in case of an error, any established
        // Bluetooth pairing is undone, and the persistent state
        // is reverted back to its initial state.
        var doUnpair = true

        // Make sure the frame parser has no leftover data from
        // a previous connection.
        framedComboIO.reset()

        // Attempt the pairing process, and use a finally block
        // to ensure that we always disconnect afterwards, even
        // in case of an exception, to make sure we always do
        // an ordered shutdown. In case of an exception, we also
        // unpair and reset the persistentPumpStateStore to revert
        // back to the unpaired state, since pairing failed, and
        // the state is undefined when that happens.
        try {
            // Connecting to Bluetooth may block, so run it in
            // a coroutine with an IO dispatcher.
            withContext(Dispatchers.IO) {
                bluetoothDevice.connect()
            }

            highLevelIO.performPairing(backgroundReceiveScope, bluetoothFriendlyName, pairingPINCallback)
            doUnpair = false
            logger(LogLevel.INFO) { "Paired with Combo with address ${bluetoothDevice.address}" }
        } finally {
            disconnectBTDeviceAndCatchExceptions()
            if (doUnpair) {
                // Unpair in a separate context, since this
                // can block for up to a second or so.
                withContext(Dispatchers.IO) {
                    bluetoothDevice.unpair()
                }
                persistentPumpStateStore.reset()
            }
        }
    }

    /**
     * Unpairs the pump.
     *
     * Unpairing consists of resetting the [persistentPumpStateStore],
     * followed by unpairing the Bluetooth device.
     *
     * If we aren't paired already, this function does nothing.
     */
    fun unpair() {
        // TODO: Currently, this function is not supposed to throw.
        // Are there legitimate cases when throwing in unpair()
        // makes sense? And if so, what should the caller do?
        // Try to unpair again?

        if (!persistentPumpStateStore.isValid())
            return

        persistentPumpStateStore.reset()

        // NOTE: The user still has to manually unpair the client through
        // the Combo's UI before any communication with it can be resumed.
        bluetoothDevice.unpair()

        logger(LogLevel.INFO) { "Unpaired from Combo with address ${bluetoothDevice.address}" }
    }

    /**
     * Establishes a regular connection.
     *
     * "Regular" means "not pairing". This is the type of connection
     * one uses for regular operation.
     *
     * Packets are received in a loop that runs in a background
     * coroutine that operates in the [backgroundReceiveScope].
     *
     * That scope's context needs to be associated with the same thread
     * this function is called in. Otherwise, the receive loop
     * may run in a different thread than this function, potentially
     * leading to race conditions.
     *
     * If an exception is thrown, the connection attempt is rolled
     * back. The device is in a disconnected state afterwards. This
     * applies to an exception thrown _during_ the connection setup;
     * any exception thrown in the background receive loop will cause
     * [onBackgroundReceiveException] to be called instead. Also,
     * other functions that involve IO to/from the Combo will throw
     * that exception.
     *
     * This internally uses [HighLevelIO.connect], but also
     * handles the Bluetooth connection setup. Also, it terminates
     * the Bluetooth connection in case of an exception.
     *
     * @param backgroundReceiveScope [CoroutineScope] to run the background
     *        packet receive loop in.
     * @param onBackgroundReceiveException Callback that gets invoked if
     *        an exception occurs in the background receive loop.
     * @throws ComboIOException if an IO error occurs during
     *         the connection attempts.
     * @throws IllegalStateException if no pairing was done with
     *         the device. This is indicated by [isPaired] returning
     *         false. Also thrown if this is called after a connection
     *         was already established.
     */
    suspend fun connect(
        backgroundReceiveScope: CoroutineScope,
        onBackgroundReceiveException: (e: Exception) -> Unit = { Unit }
    ) {
        if (isConnected)
            throw IllegalStateException("Already connected to Combo")

        if (!isPaired())
            throw IllegalStateException(
                "Attempting to connect to Combo with address ${bluetoothDevice.address} even though it is not paired")

        // Make sure the frame parser has no leftover data from
        // a previous connection.
        framedComboIO.reset()

        withContext(Dispatchers.IO) {
            bluetoothDevice.connect()
        }

        runChecked {
            highLevelIO.connect(backgroundReceiveScope, onBackgroundReceiveException)
            isConnected = true
        }
    }

    /**
     * Disconnects from a pump.
     *
     * If no connection is running, this does nothing.
     *
     * Other than the usual [kotlinx.coroutines.CancellationException]
     * that is present in all suspending functions, this throws no
     * exceptions.
     *
     * This internally uses [HighLevelIO.disconnect], but also
     * handles the Bluetooth connection teardown.
     */
    suspend fun disconnect() {
        if (!isConnected) {
            logger(LogLevel.DEBUG) {
                "Attempted to disconnect from Combo with address ${bluetoothDevice.address} even though it is not connected; ignoring call"
            }
            return
        }

        // Wrap the disconnect packet sequence in a try-finally block
        // to make sure we always terminate the Bluetooth connection
        // no matter what, even if it is a CancellationException.
        try {
            highLevelIO.disconnect()
        } finally {
            disconnectBTDeviceAndCatchExceptions()
            isConnected = false
            logger(LogLevel.INFO) { "Disconnected from Combo with address ${bluetoothDevice.address}" }
        }
    }

    /**
     * Performs a short button press.
     *
     * This mimics the physical press of a button for a short
     * moment, followed by that button being released.
     *
     * Internally, this calls [HighLevelIO.sendSingleRTButtonPress],
     * but also disconnects if this call throws an exception.
     * It also switches to the RT mode before issuing the call.
     *
     * @param button What button to press.
     * @throws ComboIOException if an IO error occurs during
     *         the button press.
     * @throws IllegalStateException if a long button press is
     *         ongoing, the pump is not in the RT mode, or the
     *         pump is not connected.
     */
    suspend fun sendSingleRTButtonPress(button: HighLevelIO.Button) {
        if (!isConnected)
            throw IllegalStateException("Not connected to Combo")

        highLevelIO.switchToMode(HighLevelIO.Mode.REMOTE_TERMINAL)

        runChecked {
            highLevelIO.sendSingleRTButtonPress(button)
        }
    }

    /**
     * Starts a long RT button press, imitating a button being held down.
     *
     * If a long button press is already ongoing, this function
     * does nothing.
     *
     * The [scope] is where a loop will run, periodically transmitting
     * codes to the Combo, until [stopLongRTButtonPress] is called,
     * [disconnect] is called, or an error occurs.
     *
     * That scope's context needs to be associated with the same thread
     * this function is called in. Otherwise, the press loop may run
     * in a different thread than this function, potentially leading
     * to race conditions.
     *
     * Internally, this calls [HighLevelIO.startLongRTButtonPress],
     * but also disconnects if this call throws an exception.
     * It also switches to the RT mode before issuing the call.
     *
     * @param button What button to press.
     * @param scope CoroutineScope to run the press loop in.
     * @throws ComboIOException if an IO error occurs during
     *         the button press.
     * @throws IllegalStateException if the pump is not connected.
     */
    suspend fun startLongRTButtonPress(button: HighLevelIO.Button, scope: CoroutineScope) {
        if (!isConnected)
            throw IllegalStateException("Not connected to Combo")

        highLevelIO.switchToMode(HighLevelIO.Mode.REMOTE_TERMINAL)

        runChecked {
            highLevelIO.startLongRTButtonPress(button, scope)
        }
    }

    /**
     * Stops an ongoing RT button press, imitating a button being released.
     *
     * This is the counterpart to [startLongRTButtonPress]. It stops
     * long button presses that were started by that function.
     *
     * If no long button press is ongoing, this function does nothing.
     *
     * Internally, this calls [HighLevelIO.stopLongRTButtonPress],
     * but also disconnects if this call throws an exception.
     * It also switches to the RT mode before issuing the call.
     *
     * @throws ComboIOException if an IO error occurs during
     *         the button press.
     */
    suspend fun stopLongRTButtonPress() {
        if (!isConnected)
            throw IllegalStateException("Not connected to Combo")

        highLevelIO.switchToMode(HighLevelIO.Mode.REMOTE_TERMINAL)

        runChecked {
            highLevelIO.stopLongRTButtonPress()
        }
    }

    private suspend fun disconnectBTDeviceAndCatchExceptions() {
        // Disconnect the Bluetooth device and catch exceptions.
        // disconnectBTDeviceAndCatchExceptions() is a function that gets called
        // in catch and finally blocks, so propagating exceptions
        // here would only complicate matters, because disconnect()
        // gets called in catch blocks.
        try {
            withContext(Dispatchers.IO) {
                bluetoothDevice.disconnect()
            }
        } catch (e: Exception) {
            logger(LogLevel.ERROR) {
                "Caught exception during Bluetooth device disconnect; not propagating; exception: $e"
            }
        }
    }

    private suspend fun switchToMode(newMode: HighLevelIO.Mode) {
        try {
            highLevelIO.switchToMode(newMode)
        } catch (e: Exception) {
            logger(LogLevel.ERROR) {
                "Need to disconnect because an exception was thrown while switching to mode ${newMode.str}; exception: $e"
            }

            // We disconnect from Bluetooth directly instead of calling the
            // disconnect() function in this class. This is because if an
            // exception happened here, then it happened either while transmitting
            // or receiving the service deactivation packets, or while calling
            // activateMode(). In the former case, we are in an undefined state,
            // and cannot reliably send the service deactivation packets again.
            // In the latter case, the services were already deactivated, so
            // the service deactivation packets sent in disconnect() are redundant.
            // TODO: Can these packets be sent redundantly without error?

            disconnectBTDeviceAndCatchExceptions()
            isConnected = false
            throw e
        }
    }

    private suspend fun runChecked(block: suspend () -> Unit) {
        // Runs a block in a try-finally block to disconnect from
        // the pump in case of an exception being thrown.

        var doDisconnect = true

        try {
            block()
            doDisconnect = false
        } finally {
            if (doDisconnect)
                disconnect()
        }
    }
}
