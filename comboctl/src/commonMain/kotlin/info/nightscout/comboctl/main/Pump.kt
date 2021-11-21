package info.nightscout.comboctl.main

import info.nightscout.comboctl.base.ApplicationLayerIO
import info.nightscout.comboctl.base.BasicProgressStage
import info.nightscout.comboctl.base.BluetoothAddress
import info.nightscout.comboctl.base.BluetoothDevice
import info.nightscout.comboctl.base.DateTime
import info.nightscout.comboctl.base.DisplayFrame
import info.nightscout.comboctl.base.FramedComboIO
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.Logger
import info.nightscout.comboctl.base.PairingPINCallback
import info.nightscout.comboctl.base.ProgressReporter
import info.nightscout.comboctl.base.PumpIO
import info.nightscout.comboctl.base.PumpStateStore
import info.nightscout.comboctl.base.ioDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.plus
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
 * along with [PumpManager]. The methods in this class provide
 * the controls that the pump directly provides (pressing buttons
 * in RT mode, receiving display frames in RT mode, administering
 * bolus in command mode etc). More complex operations are built
 * on these methods and are provided by [PumpCommandDispatcher].
 *
 * Each Pump object has a [PumpStateStore] associated with
 * it. A [PumpStateStore] of one Pump instance must be kept
 * entirely separate from other persistent states. So, even if a
 * pump's persistent state is reset (that is, completely wiped),
 * other pump states must not be affected.
 *
 * The constructor does not immediately connect to the pump.
 * This is done by calling [connectAsync]. Also note that before being
 * able to establish a connection, the pump must have been paired.
 * The [performPairing] function can be used for this purpose.
 *
 * Internally, this class runs a "background worker", which is the
 * sum of coroutines started by [connectAsync] and [performPairing]. These
 * coroutines are run by a special internal dispatcher that is single
 * threaded. Internal states are updated in these coroutines. Since they
 * run on the same thread, race conditions are prevented, and thread
 * safety is established.
 *
 * Instances of this class are typically not created manually,
 * but rather by calling [PumpManager.acquirePump]. Likewise, the
 * [performPairing] function is typically not called directly,
 * but rather by [PumpManager] during discovery.
 *
 * WARNING: Do not create more than one Pump instance for the same
 * pump at the same time. Two Pump instances operating the same pump
 * leads to undefined behavior.
 *
 * @param bluetoothDevice [BluetoothDevice] object to use for
 *        Bluetooth I/O. Must be in a disconnected state when
 *        assigned to this instance.
 * @param pumpStateStore Pump state store for this pump.
 */
class Pump(
    private val bluetoothDevice: BluetoothDevice,
    private val pumpStateStore: PumpStateStore
) {
    private val pumpIO: PumpIO
    private val framedComboIO = FramedComboIO(bluetoothDevice)

    // Arguments from the last connect() call. Used by reconnect().
    private var prevBackgroundIOScope: CoroutineScope? = null
    private var prevInitialMode: PumpIO.Mode? = null

    init {
        // Pass IO through the FramedComboIO class since the Combo
        // sends packets in a framed form (See [ComboFrameParser]
        // and [List<Byte>.toComboFrame] for details).
        pumpIO = PumpIO(
            pumpStateStore,
            bluetoothDevice.address,
            framedComboIO
        )
    }

    /**
     * Callback for when an exception is thrown in the background IO worker.
     *
     * This is mainly meant for internal use in code that performs automated
     * pump operation in RT mode, for example for getting the quick info.
     */
    var onBackgroundIOException: (e: Exception) -> Unit
        get() = pumpIO.onBackgroundIOException
        set(value) { pumpIO.onBackgroundIOException = value }

    /**
     * The pump's Bluetooth address.
     */
    val address: BluetoothAddress = bluetoothDevice.address

    /**
     * Read-only [SharedFlow] property that delivers newly assembled display frames.
     *
     * See [DisplayFrame] for details about these frames.
     */
    val displayFrameFlow: SharedFlow<DisplayFrame> = pumpIO.displayFrameFlow

    /**
     * Read-only [StateFlow] property that announces when the current [PumpIO.Mode] changed.
     *
     * Note that, unlike most other flow types, a [StateFlow] is a _hot_ flow.
     * This means that its emitter runs independently of any collector.
     */
    val currentModeFlow: StateFlow<PumpIO.Mode?> = pumpIO.currentModeFlow

    /**
     * Current connection state.
     *
     * This differs from the [connectProgressFlow] in that the progress flow is generally
     * not useful to check the current state, while the connection state is not generally
     * useful for keeping track of the connection progress (since it does not contain
     * extra info like the overall progress value etc.)
     *
     * Note that if the worker fails, the connection state is still [CONNECTED]. This
     * is because a failed worker is not the same as a disconnected state; the user
     * still has to manually call [disconnect] after the worker fails (typically because
     * a call like [sendShortRTButtonPress] failed with a [TransportLayerIO.BackgroundIOException]).
     */
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    private var mutableConnectionState = MutableStateFlow<ConnectionState>(ConnectionState.DISCONNECTED)

    /**
     * Read-only [StateFlow] property that notifies about connection state changes.
     */
    val connectionState = mutableConnectionState.asStateFlow()

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
    fun isPaired(): Boolean = pumpStateStore.hasPumpState(address)

    /**
     * Performs a pairing procedure with the pump.
     *
     * This performs the Combo-specific pairing. When this is called,
     * the pump must have been paired at the Bluetooth level already.
     * From Bluetooth's point of view, the pump is already paired with
     * the client at this point. But the Combo itself needs additional
     * pairing, which we perform with this function.
     *
     * Once this is done, the [pumpStateStore will be filled
     * with all of the necessary information (ciphers etc.) for
     * establishing regular connections with [connectAsync].
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
     * happen due to corrupted packets.
     *
     * This internally uses [PumpIO.performPairing], but also
     * handles the Bluetooth connection setup and teardown, so do
     * not connect to the Bluetooth device prior to this call.
     *
     * WARNING: Do not run multiple performPairing functions simultaneously
     *  on the same pump. Otherwise, undefined behavior occurs.
     *
     * @param bluetoothFriendlyName The Bluetooth friendly name to use in
     *        REQUEST_ID packets. Use [BluetoothInterface.getAdapterFriendlyName]
     *        to get the friendly name.
     * @param progressReporter [ProgressReporter] for tracking pairing progress.
     * @param pairingPINCallback Callback that gets invoked as soon as
     *        the pairing process needs the 10-digit-PIN.
     * @throws IllegalStateException if this is ran while a connection
     *         is running, or if the pump is already paired.
     * @throws TransportLayerIO.BackgroundIOException if an exception is
     *         thrown inside the worker while an IO call is waiting for
     *         completion.
     */
    suspend fun performPairing(
        bluetoothFriendlyName: String,
        progressReporter: ProgressReporter<Unit>?,
        pairingPINCallback: PairingPINCallback
    ) {
        // This function is called once the Bluetooth pairing
        // has already been established. We still need to perform
        // the Combo specific pairing.

        if (isPaired())
            throw IllegalStateException(
                "Attempting to pair with Combo with address ${bluetoothDevice.address} even though it is already paired")

        if (pumpIO.isConnected())
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
        // unpair and reset the pumpStateStore to revert
        // back to the unpaired state, since pairing failed, and
        // the state is undefined when that happens.
        try {
            mutableConnectionState.value = ConnectionState.CONNECTING

            progressReporter?.setCurrentProgressStage(BasicProgressStage.StartingConnectionSetup)

            // Connecting to Bluetooth may block, so run it in
            // a coroutine with an IO dispatcher.
            withContext(ioDispatcher()) {
                bluetoothDevice.connect(progressReporter)
            }

            mutableConnectionState.value = ConnectionState.CONNECTED

            pumpIO.performPairing(bluetoothFriendlyName, progressReporter, pairingPINCallback)
            doUnpair = false
            logger(LogLevel.INFO) { "Paired with Combo with address ${bluetoothDevice.address}" }
        } finally {
            mutableConnectionState.value = ConnectionState.DISCONNECTED
            disconnectBTDeviceAndCatchExceptions()
            if (doUnpair) {
                // Unpair in a separate context, since this
                // can block for up to a second or so.
                withContext(ioDispatcher()) {
                    bluetoothDevice.unpair()
                }
                pumpStateStore.deletePumpState(address)
            }
        }
    }

    /**
     * Unpairs the pump.
     *
     * Unpairing consists of resetting the [pumpStateStore],
     * followed by unpairing the Bluetooth device.
     *
     * This calls [disconnect] before unpairing to make sure there
     * is no ongoing connection before attempting to unpair.
     *
     * If we aren't paired already, this function does nothing.
     */
    suspend fun unpair() {
        // TODO: Currently, this function is not supposed to throw.
        // Are there legitimate cases when throwing in unpair()
        // makes sense? And if so, what should the caller do?
        // Try to unpair again?

        if (!pumpStateStore.hasPumpState(address))
            return

        disconnect()

        pumpStateStore.deletePumpState(address)

        // Unpairing in a coroutine with an IO dispatcher
        // in case unpairing blocks.
        // NOTE: The user still has to manually unpair the client through
        // the Combo's UI before any communication with it can be resumed.
        withContext(ioDispatcher()) {
            bluetoothDevice.unpair()
        }

        logger(LogLevel.INFO) { "Unpaired from Combo with address ${bluetoothDevice.address}" }
    }

    private val connectProgressReporter = ProgressReporter<Unit>(
        listOf(
            BasicProgressStage.StartingConnectionSetup::class,
            BasicProgressStage.EstablishingBtConnection::class,
            BasicProgressStage.PerformingConnectionHandshake::class
        ),
        Unit
    )

    /**
     * [StateFlow] for reporting progress during the [connectAsync] call.
     *
     * See the [ProgressReporter] documentation for details.
     */
    val connectProgressFlow = connectProgressReporter.progressFlow

    /**
     * Establishes a regular connection.
     *
     * A "regular connection" is a connection that is used for
     * regular Combo operation. The client must have been paired with
     * the Combo before such connections can be established.
     *
     * This must be called before [sendShortRTButtonPress], [startLongRTButtonPress],
     * [stopLongRTButtonPress], and [switchMode] can be used.
     *
     * This function starts the background worker, using the
     * [backgroundIOScope] as the scope for the coroutines that make up
     * the worker. Some functions such as [startLongRTButtonPress] also
     * launch coroutines. They use this same coroutine scope.
     *
     * The actual connection procedure also happens in that scope, in its
     * own coroutine. That coroutine is represented by the instance of
     * [kotlinx.coroutines.Deferred] that is returned by this function.
     * If an exception occurs _during_ connection setup, the connection
     * attempt is aborted, this Pump instance reverts to the disconnected
     * state, and the exception is caught by the returned Deferred value.
     * Users call [kotlinx.coroutines.Deferred.await] to wait for the
     * connection procedure to finish; if an exception occurred during
     * that procedure, it is re-thrown by that function.
     *
     * Exceptions from inside the running worker cause the worker to "fail".
     * In that failed state, any of the Pump functions ([switchMode] etc.)
     * mentioned above will immediately fail with an [TransportLayerIO.BackgroundIOException].
     * The user has to call [disconnect] to change the worker from a failed
     * to a disconnected state. Then, the user can attempt to connect again.
     *
     * Note that even though [backgroundIOScope] refers to IO in its name,
     * this function ignores its dispatcher and uses the default dispatcher
     * instead. This is because the coroutines that run in that scope are
     * about _coordinating_ IO operations. When actual, blocking, low level
     * IO is performed, the IO dispatcher is used to make sure that such a
     * blocking wait does not fill up the threads of the Default dispatcher.
     * [backgroundIOScope] is an argument only because it is intended to
     * give the caller control over structured concurrency. The dispatcher
     * override also means that it can be okay to pass a UI scope as the
     * background IO scope, since background worker operations won't run
     * in the UI thread. (Still, if the UI is torn down and the scope is
     * cancelled as a result, the worker would go away as well, so it is
     * still important to evaluate whether or not a UI scope should be used).
     *
     * [isConnected] will return true if the connection was established.
     *
     * [disconnect] is the counterpart to this function. It terminates
     * an existing connection and stops the worker.
     *
     * This internally uses [PumpIO.connect], but also
     * handles the Bluetooth connection setup. Also, it terminates
     * the Bluetooth connection in case of an exception.
     *
     * @param backgroundIOScope Coroutine scope to start the background
     *        worker in.
     * @param initialMode What mode to initially switch to.
     * @return [kotlinx.coroutines.Deferred] representing the coroutine
     *         that runs the connection setup procedure.
     * @throws IllegalStateException if IO was already started by a
     *         previous [startIO] call or if the [PumpStateStore]
     *         that was passed to the class constructor isn't initialized
     *         (= [PumpStateStore.isValid] returns false).
     */
    fun connectAsync(
        backgroundIOScope: CoroutineScope,
        initialMode: PumpIO.Mode = PumpIO.Mode.REMOTE_TERMINAL
    ): Deferred<Unit> =
        connectInternalAsync(backgroundIOScope + Dispatchers.Default, initialMode)

    private fun connectInternalAsync(
        backgroundIOScope: CoroutineScope,
        initialMode: PumpIO.Mode
    ): Deferred<Unit> {
        if (pumpIO.isConnected())
            throw IllegalStateException("Already connected to Combo")

        if (!isPaired())
            throw IllegalStateException(
                "Attempting to connect to Combo with address ${bluetoothDevice.address} even though it is not paired")

        this.prevBackgroundIOScope = backgroundIOScope
        this.prevInitialMode = initialMode

        // Make sure the frame parser has no leftover data from
        // a previous connection.
        framedComboIO.reset()

        connectProgressReporter.reset(Unit)
        connectProgressReporter.setCurrentProgressStage(BasicProgressStage.StartingConnectionSetup)

        // Run the actual connection attempt in the background IO scope.
        return backgroundIOScope.async {
            try {
                mutableConnectionState.value = ConnectionState.CONNECTING

                // Suspend the coroutine until Bluetooth is connected.
                // Do this in a separate coroutine with an IO dispatcher
                // since the connection setup may block.
                withContext(ioDispatcher()) {
                    try {
                        bluetoothDevice.connect(connectProgressReporter)
                    } catch (e: Exception) {
                        connectProgressReporter.setCurrentProgressStage(BasicProgressStage.Aborted)
                        throw e
                    }
                }

                // Establish the regular connection. Also call join() here
                // to make sure the coroutine here waits until the sub-coroutine
                // that is started by pumpIO.connect() finishes.
                runChecked {
                    try {
                        pumpIO.connect(
                            backgroundIOScope = backgroundIOScope,
                            progressReporter = connectProgressReporter,
                            initialMode = initialMode,
                            runKeepAliveLoop = true
                        ).await()

                        mutableConnectionState.value = ConnectionState.CONNECTED

                        connectProgressReporter.setCurrentProgressStage(BasicProgressStage.Finished)
                    } catch (e: Exception) {
                        connectProgressReporter.setCurrentProgressStage(BasicProgressStage.Aborted)
                        throw e
                    }
                }
            } catch (e: Throwable) {
                mutableConnectionState.value = ConnectionState.DISCONNECTED
                throw e
            }
        }
    }

    /**
     * Disconnects from a pump.
     *
     * This terminates the connection and stops the background worker that
     * was started by [connectAsync].
     *
     * If no connection is running, this does nothing.
     *
     * Calling this ensures an orderly IO shutdown and should
     * not be omitted when shutting down an application.
     * This also clears the "failed" mark on a failed worker.
     *
     * This internally uses [PumpIO.disconnect], but also
     * handles the Bluetooth connection teardown.
     */
    suspend fun disconnect() {
        if (!pumpIO.isConnected()) {
            logger(LogLevel.DEBUG) {
                "Attempted to disconnect from Combo with address ${bluetoothDevice.address} even though it is not connected; ignoring call"
            }
            return
        }

        // Perform the actual disconnect. During that process,
        // the disconnectBTDeviceAndCatchExceptions() will be
        // called to make sure the Bluetooth connection is
        // terminated and any blocking send / receive calls
        // are unblocked.
        pumpIO.disconnect {
            disconnectBTDeviceAndCatchExceptions()
        }

        mutableConnectionState.value = ConnectionState.DISCONNECTED

        logger(LogLevel.INFO) { "Disconnected from Combo with address ${bluetoothDevice.address}" }
    }

    /**
     * Reconnects a pump.
     *
     * This is useful if the background worker failed. It reconnects the
     * pump with the coroutine scope and initial mode specified in the
     * last [connectAsync] call.
     *
     * Look up [connectAsync] for the list of exceptions that can be thrown
     * in addition to what is listed below.
     *
     * @throws IllegalStateException if this was called even though no
     *         previous successful [connect] call was made.
     */
    suspend fun reconnect() {
        check(prevBackgroundIOScope != null)
        check(prevInitialMode != null)

        logger(LogLevel.DEBUG) { "Reconnecting Combo with address ${bluetoothDevice.address}" }

        disconnect()
        connectAsync(prevBackgroundIOScope!!, prevInitialMode!!).await()
    }

    /**
     * Reads the current datetime of the pump in COMMAND (CMD) mode.
     *
     * The current datetime is always given in localtime.
     *
     * @return The current datetime.
     * @throws IllegalStateException if the pump is not in the comand
     *         mode or the pump is not connected.
     * @throws TransportLayerIO.BackgroundIOException if an exception is thrown
     *         inside the worker while this call is waiting for a packet or if
     *         an exception was thrown inside the worker prior to this call.
     * @throws ApplicationLayerIO.InvalidPayloadException if the size
     *         of a packet's payload does not match the expected size.
     * @throws ComboIOException if IO with the pump fails.
     */
    suspend fun readCMDDateTime(): DateTime {
        if (!pumpIO.isConnected())
            throw IllegalStateException("Not connected to Combo")

        return runChecked {
            pumpIO.readCMDDateTime()
        }
    }

    /**
     * Reads the current status of the pump in COMMAND (CMD) mode.
     *
     * The pump can be either in the stopped or in the running status.
     *
     * @return The current status.
     * @throws IllegalStateException if the pump is not in the comand
     *         mode or the pump is not connected.
     * @throws TransportLayerIO.BackgroundIOException if an exception is thrown
     *         inside the worker while this call is waiting for a packet or if
     *         an exception was thrown inside the worker prior to this call.
     * @throws ApplicationLayerIO.InvalidPayloadException if the size
     *         of a packet's payload does not match the expected size.
     * @throws ComboIOException if IO with the pump fails.
     */
    suspend fun readCMDPumpStatus(): ApplicationLayerIO.CMDPumpStatus {
        if (!pumpIO.isConnected())
            throw IllegalStateException("Not connected to Combo")

        return runChecked {
            pumpIO.readCMDPumpStatus()
        }
    }

    /**
     * Reads the current error/warning status of the pump in COMMAND (CMD) mode.
     *
     * @return The current status.
     * @throws IllegalStateException if the pump is not in the comand
     *         mode or the pump is not connected.
     * @throws TransportLayerIO.BackgroundIOException if an exception is thrown
     *         inside the worker while this call is waiting for a packet or if
     *         an exception was thrown inside the worker prior to this call.
     * @throws ApplicationLayerIO.InvalidPayloadException if the size
     *         of a packet's payload does not match the expected size.
     * @throws ComboIOException if IO with the pump fails.
     */
    suspend fun readCMDErrorWarningStatus(): ApplicationLayerIO.CMDErrorWarningStatus {
        if (!pumpIO.isConnected())
            throw IllegalStateException("Not connected to Combo")

        return runChecked {
            pumpIO.readCMDErrorWarningStatus()
        }
    }

    /**
     * Requests a CMD history delta.
     *
     * In the command mode, the Combo can provide a "history delta".
     * This means that the user can get what events occurred since the
     * last time a request was sent. Because this is essentially the
     * difference between the current history state and the history
     * state when the last request was sent, it is called a "delta".
     * This also means that if a request is sent again, and no new
     * event occurred in the meantime, the history delta will be empty
     * (= it will have zero events recorded). It is _not_ possible
     * to get the entire history with this function.
     *
     * The maximum amount of history block request is limited by the
     * maxRequests argument. This is a safeguard in case the data
     * keeps getting corrupted for some reason. Having a maximum
     * guarantees that we can't get stuck in an infinite loop.
     *
     * @param maxRequests How many history block request we can
     *        maximally send. This must be at least 10.
     * @return The history delta.
     * @throws IllegalArgumentException if maxRequests is less than 10.
     * @throws IllegalStateException if the pump is not in the comand
     *         mode or the pump is not connected.
     * @throws TransportLayerIO.BackgroundIOException if an exception is thrown
     *         inside the worker while this call is waiting for a packet or if
     *         an exception was thrown inside the worker prior to this call.
     * @throws ApplicationLayerIO.InvalidPayloadException if the size
     *         of a packet's payload does not match the expected size.
     * @throws ApplicationLayerIO.PayloadDataCorruptionException if
     *         packet data integrity is compromised.
     * @throws ApplicationLayerIO.InfiniteHistoryDataException if the
     *         call did not ever get a history block that marked an end
     *         to the history.
     * @throws ComboIOException if IO with the pump fails.
     */
    suspend fun getCMDHistoryDelta(maxRequests: Int = 40): List<ApplicationLayerIO.CMDHistoryEvent> {
        if (!pumpIO.isConnected())
            throw IllegalStateException("Not connected to Combo")

        return runChecked {
            pumpIO.getCMDHistoryDelta(maxRequests)
        }
    }

    /**
     * Requests the current status of an ongoing bolus delivery.
     *
     * This is used for keeping track of the status of an ongoing bolus.
     * If no bolus is ongoing, the return value's bolusType field is
     * set to [ApplicationLayerIO.CMDBolusDeliveryState.NOT_DELIVERING].
     *
     * @return The current status.
     * @throws IllegalStateException if the pump is not in the comand
     *         mode or the pump is not connected.
     * @throws TransportLayerIO.BackgroundIOException if an exception is thrown
     *         inside the worker while this call is waiting for a packet or if
     *         an exception was thrown inside the worker prior to this call.
     * @throws ApplicationLayerIO.InvalidPayloadException if the size
     *         of a packet's payload does not match the expected size.
     * @throws ApplicationLayerIO.DataCorruptionException if some of
     *         the fields in the status data received from the pump
     *         contain invalid values.
     * @throws ComboIOException if IO with the pump fails.
     */
    suspend fun getCMDCurrentBolusDeliveryStatus(): ApplicationLayerIO.CMDBolusDeliveryStatus {
        if (!pumpIO.isConnected())
            throw IllegalStateException("Not connected to Combo")

        return runChecked {
            pumpIO.getCMDCurrentBolusDeliveryStatus()
        }
    }

    /**
     * Instructs the pump to deliver the specified standard bolus amount.
     *
     * As the name suggests, this function can only deliver a standard bolus,
     * and no multi-wave or extended ones. In the future, additional functions
     * may be written that can deliver those.
     *
     * The return value indicates whether or not the delivery was actually
     * done. The delivery may not happen if for example the pump is currently
     * stopped, or if it is already administering another bolus. It is
     * recommended to keep track of the current bolus status by periodically
     * calling [getCMDBolusStatus].
     *
     * @param bolusAmount Bolus amount to deliver. Note that this is given
     *        in 0.1 IU units, so for example, "57" means 5.7 IU.
     * @return true if the bolus could be delivered, false otherwise.
     * @throws IllegalStateException if the pump is not in the comand
     *         mode or the pump is not connected.
     * @throws TransportLayerIO.BackgroundIOException if an exception is thrown
     *         inside the worker while this call is waiting for a packet or if
     *         an exception was thrown inside the worker prior to this call.
     * @throws ApplicationLayerIO.InvalidPayloadException if the size
     *         of a packet's payload does not match the expected size.
     * @throws ComboIOException if IO with the pump fails.
     */
    suspend fun deliverCMDStandardBolus(bolusAmount: Int): Boolean {
        if (!pumpIO.isConnected())
            throw IllegalStateException("Not connected to Combo")

        return runChecked {
            pumpIO.deliverCMDStandardBolus(bolusAmount)
        }
    }

    /**
     * Cancels an ongoing bolus.
     *
     * @return true if the bolus was cancelled, false otherwise.
     *         If no bolus is ongoing, this returns false as well.
     * @throws IllegalStateException if the pump is not in the command
     *         mode or the pump is not connected.
     * @throws TransportLayerIO.BackgroundIOException if an exception is thrown
     *         inside the worker while this call is waiting for a packet or if
     *         an exception was thrown inside the worker prior to this call.
     */
    suspend fun cancelCMDStandardBolus() {
        if (!pumpIO.isConnected())
            throw IllegalStateException("Not connected to Combo")

        return runChecked {
            pumpIO.cancelCMDStandardBolus()
        }
    }

    /**
     * Performs a short button press.
     *
     * This mimics the physical pressing of buttons for a short
     * moment, followed by those buttons being released.
     *
     * This may not be called while a long button press is ongoing.
     * It can only be called in the remote terminal (RT) mode.
     *
     * It is possible to short-press multiple buttons at the same
     * time. This is necessary for moving back in the Combo's menu
     * example. The buttons in the specified list are combined to
     * form a code that is transmitted to the pump.
     *
     * Internally, this calls [PumpIO.sendShortRTButtonPress],
     * but also disconnects if this call throws an exception.
     * It also switches to the RT mode before issuing the call.
     *
     * @param buttons What button(s) to short-press.
     * @throws IllegalArgumentException If the buttons list is empty.
     * @throws IllegalStateException if a long button press is
     *         ongoing, the pump is not in the RT mode, or the
     *         pump is not connected.
     * @throws TransportLayerIO.BackgroundIOException if an exception is thrown
     *         inside the worker while this call is waiting for a packet or if
     *         an exception was thrown inside the worker prior to this call.
     */
    suspend fun sendShortRTButtonPress(buttons: List<PumpIO.Button>) {
        if (!pumpIO.isConnected())
            throw IllegalStateException("Not connected to Combo")

        runChecked {
            pumpIO.sendShortRTButtonPress(buttons)
        }
    }

    /**
     * Performs a short button press.
     *
     * This overload is for convenience in case exactly one button
     * is to be pressed.
     */
    suspend fun sendShortRTButtonPress(button: PumpIO.Button) =
        sendShortRTButtonPress(listOf(button))

    /**
     * Starts a long RT button press, imitating buttons being held down.
     *
     * This can only be called in the remote terminal (RT) mode.
     *
     * If a long button press is already ongoing, this function
     * does nothing.
     *
     * It is possible to long-press multiple buttons at the same
     * time. This is necessary for moving back in the Combo's menu
     * example. The buttons in the specified list are combined to
     * form a code that is transmitted to the pump.
     *
     * Internally, this calls [PumpIO.startLongRTButtonPress],
     * but also disconnects if this call throws an exception.
     * It also switches to the RT mode before issuing the call.
     * Consult the [PumpIO.startLongRTButtonPress] documentation
     * for additional details.
     *
     * @param buttons What button(s) to long-press.
     * @param keepGoing Predicate for deciding whether or not to
     *        continue the internal loop. If this is set to null,
     *        the loop behaves as if this returned true all the time.
     * @throws IllegalArgumentException If the buttons list is empty.
     * @throws IllegalStateException if the pump is not in the RT mode
     *         or the pump is not connected.
     * @throws TransportLayerIO.BackgroundIOException if an exception is thrown
     *         inside the worker while this call is waiting for a packet or if
     *         an exception was thrown inside the worker prior to this call.
     */
    suspend fun startLongRTButtonPress(buttons: List<PumpIO.Button>, keepGoing: (suspend () -> Boolean)? = null) {
        if (!pumpIO.isConnected())
            throw IllegalStateException("Not connected to Combo")

        runChecked {
            pumpIO.startLongRTButtonPress(buttons, keepGoing)
        }
    }

    /**
     * Performs a long button press.
     *
     * This overload is for convenience in case exactly one button
     * is to be pressed.
     */
    suspend fun startLongRTButtonPress(button: PumpIO.Button, keepGoing: (suspend () -> Boolean)? = null) =
        startLongRTButtonPress(listOf(button), keepGoing)

    /**
     * Stops an ongoing RT button press, imitating buttons being released.
     *
     * This is the counterpart to [startLongRTButtonPress]. It stops
     * long button presses that were started by that function.
     *
     * If no long button press is ongoing, this function does nothing.
     *
     * Internally, this calls [PumpIO.stopLongRTButtonPress],
     * but also disconnects if this call throws an exception.
     * It also switches to the RT mode before issuing the call.
     *
     * @throws IllegalStateException if the pump is not in the RT mode,
     *         or the worker has failed (see [connect]), or the pump
     *         is not connected.
     */
    suspend fun stopLongRTButtonPress() {
        if (!pumpIO.isConnected())
            throw IllegalStateException("Not connected to Combo")

        runChecked {
            pumpIO.stopLongRTButtonPress()
        }
    }

    /**
     * Waits for the coroutine that drives the long RT button press loop to finish.
     *
     * This finishes when either the keepAlive predicate in [startLongRTButtonPress]
     * returns false or [stopLongRTButtonPress] is called. The former is the more
     * common use case for this function.
     *
     * If no long RT button press is ongoing, this function does nothing,
     * and just exits immediately.
     */
    suspend fun waitForLongRTButtonPressToFinish() =
        pumpIO.waitForLongRTButtonPressToFinish()

    /**
     * Switches the operating mode of the pump.
     *
     * The pump can run in one of two modes: The COMMAND mode and the
     * REMOTE_TERMINAL (RT) mode. After connecting, the pump is running in
     * one of these two modes (which one is initially used can be specified
     * as an argument to [connectAsync]). Switching to a different mode afterwards
     * is accomplished by calling this function.
     *
     * If an attempt is made to switch to the mode that is already active,
     * this function does nothing.
     *
     * If an exception occurs, either disconnect, or try to repeat the mode
     * switch. This is important to make sure the pump is in a known mode.
     *
     * @param newMode Mode to switch to.
     * @throws IllegalStateException if the pump is not connected.
     * @throws TransportLayerIO.BackgroundIOException if an exception is thrown
     *         inside the worker while this call is waiting for a packet or if
     *         an exception was thrown inside the worker prior to this call.
     * @throws ComboIOException if IO with the pump fails.
     */
    suspend fun switchMode(newMode: PumpIO.Mode) {
        try {
            pumpIO.switchMode(newMode)
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
            throw e
        }
    }

    private suspend fun disconnectBTDeviceAndCatchExceptions() {
        // Disconnect the Bluetooth device and catch exceptions.
        // disconnectBTDeviceAndCatchExceptions() is a function that gets called
        // in catch and finally blocks, so propagating exceptions
        // here would only complicate matters, because disconnect()
        // gets called in catch blocks.
        try {
            withContext(ioDispatcher()) {
                bluetoothDevice.disconnect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger(LogLevel.ERROR) {
                "Caught exception during Bluetooth device disconnect; not propagating; exception: $e"
            }
        }
    }

    private suspend fun <T> runChecked(block: suspend () -> T): T {
        // Runs a block in a try-catch block to disconnect from
        // the pump in case of an exception being thrown.

        try {
            return block()
        } catch (e: Exception) {
            disconnect()
            throw e
        }
    }
}
