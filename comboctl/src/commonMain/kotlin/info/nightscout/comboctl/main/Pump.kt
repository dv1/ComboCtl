package info.nightscout.comboctl.main

import info.nightscout.comboctl.base.ApplicationLayer
import info.nightscout.comboctl.base.ApplicationLayer.CMDHistoryEventDetail
import info.nightscout.comboctl.base.BasicProgressStage
import info.nightscout.comboctl.base.BluetoothAddress
import info.nightscout.comboctl.base.BluetoothDevice
import info.nightscout.comboctl.base.BluetoothException
import info.nightscout.comboctl.base.ComboException
import info.nightscout.comboctl.base.ComboIOException
import info.nightscout.comboctl.base.CurrentTbrState
import info.nightscout.comboctl.base.DisplayFrame
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.Logger
import info.nightscout.comboctl.base.Nonce
import info.nightscout.comboctl.base.ProgressReport
import info.nightscout.comboctl.base.ProgressReporter
import info.nightscout.comboctl.base.ProgressStage
import info.nightscout.comboctl.base.PumpIO
import info.nightscout.comboctl.base.PumpIO.ConnectionRequestIsNotBeingAcceptedException
import info.nightscout.comboctl.base.PumpStateStore
import info.nightscout.comboctl.base.Tbr
import info.nightscout.comboctl.base.TransportLayer
import info.nightscout.comboctl.base.ioDispatcher
import info.nightscout.comboctl.base.toStringWithDecimal
import info.nightscout.comboctl.parser.AlertScreenContent
import info.nightscout.comboctl.parser.AlertScreenException
import info.nightscout.comboctl.parser.BatteryState
import info.nightscout.comboctl.parser.MainScreenContent
import info.nightscout.comboctl.parser.ParsedScreen
import info.nightscout.comboctl.parser.ReservoirState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.absoluteValue

private val logger = Logger.get("Pump")

private const val NUM_IDEMPOTENT_COMMAND_DISPATCH_ATTEMPTS = 10
private const val DELAY_IN_MS_BETWEEN_COMMAND_DISPATCH_ATTEMPTS = 2000L

object RTCommandProgressStage {
    /**
     * Basal profile setting stage.
     *
     * @property numSetFactors How many basal rate factors have been set by now.
     *   When the basal profile has been fully set, this value equals the value of
     *   totalNumFactors. Valid range is 0 to ([NUM_COMBO_BASAL_PROFILE_FACTORS] - 1).
     */
    data class SettingBasalProfile(val numSetFactors: Int) : ProgressStage("settingBasalProfile")

    /**
     * Basal profile getting stage.
     *
     * @property numSetFactors How many basal rate factors have been retrieved by now.
     *   When the basal profile has been fully retrieved, this value equals the value
     *   of totalNumFactors. Valid range is 0 to ([NUM_COMBO_BASAL_PROFILE_FACTORS] - 1).
     */
    data class GettingBasalProfile(val numSetFactors: Int) : ProgressStage("gettingBasalProfile")

    /**
     * TBR percentage setting stage.
     *
     * @property settingProgress How far along the TBR percentage setting is, in the 0-100 range.
     *           0 = procedure started. 100 = TBR percentage setting finished.
     */
    data class SettingTBRPercentage(val settingProgress: Int) : ProgressStage("settingTBRPercentage")

    /**
     * TBR duration setting stage.
     *
     * @property settingProgress How far along the TBR duration setting is, in the 0-100 range.
     *           0 = procedure started. 100 = TBR duration setting finished.
     */
    data class SettingTBRDuration(val settingProgress: Int) : ProgressStage("settingTBRDuration")

    /**
     * Bolus delivery stage.
     *
     * The amounts are given in 0.1 IU units. For example, "57" means 5.7 IU.
     *
     * @property deliveredAmount How many units have been delivered so far.
     *           This is always <= totalAmount.
     * @property totalAmount Total amount of bolus units.
     */
    data class DeliveringBolus(val deliveredAmount: Int, val totalAmount: Int) : ProgressStage("deliveringBolus")

    /**
     * TDD fetching history stage.
     *
     * @property historyEntryIndex Index of the history entry that was just fetched.
     *           Valid range is 1 to [totalNumEntries].
     * @property totalNumEntries Total number of entries in the history.
     */
    data class FetchingTDDHistory(val historyEntryIndex: Int, val totalNumEntries: Int) : ProgressStage("fetchingTDDHistory")

    /**
     * SetDateTime stage when the current hour is set.
     */
    object SettingDateTimeHour : ProgressStage("settingDateTimeHour")

    /**
     * SetDateTime stage when the current minute is set.
     */
    object SettingDateTimeMinute : ProgressStage("settingDateTimeMinute")

    /**
     * SetDateTime stage when the current year is set.
     */
    object SettingDateTimeYear : ProgressStage("settingDateTimeYear")

    /**
     * SetDateTime stage when the current month is set.
     */
    object SettingDateTimeMonth : ProgressStage("settingDateTimeMonth")

    /**
     * SetDateTime stage when the current day is set.
     */
    object SettingDateTimeDay : ProgressStage("settingDateTimeDay")
}

/**
 * Main pump control class.
 *
 * This is the class that callers will mainly use for interacting with a pump.
 * It takes care of IO with the pump and implements higher level commands like
 * setting / getting the basal profile, delivering a bolus, getting / setting
 * TBRs and the current datetime etc.
 *
 * To begin operating the pump, call [connect] to set up a Bluetooth connection.
 * The connection can be terminated with [disconnect].
 *
 * This class applies a series of checks for safety and robustness reasons.
 * These are divided into checks performed by [connect] and checks performed
 * before, during, and after command execution. See [connect] for a documentation
 * about the on-connect checks. As for the command execution ones, these are:
 *
 * 1. Before each command, the Combo's warning & error flags are queried.
 *   If these are set, the Combo is switched to the remote terminal mode
 *   to "see" what warning/error is on the RT screen. That screen is parsed
 *   and processed. If it can't be handled locally, an [AlertScreenException]
 *   is thrown.
 * 2. During command execution, if the execution fails due to connection issues,
 *   and the command is idempotent, this class attempts to reconnect to the pump,
 *   followed by another command execution attempt. This is repeated a number of
 *   times until execution succeeds or all possible attempts have been exhausted.
 *   If no attempt succeeded, [CommandExecutionAttemptsFailedException] is thrown.
 *   However, if the command is _not_ idempotent ([deliverBolus] is a notable
 *   example), then no repeat attempts are made. A command is idempotent if
 *   a command can be repeated safely. This is the case when repeated execution
 *   doesn't actually change anything unless the previous attempt failed.
 *   For example, if the same TBR is (re)started twice in quick succession,
 *   the second attempt effectively changes nothing. Repeating the same bolus
 *   however is _not_ idempotent since these boluses stack up, so failed bolus
 *   deliveries *must not* be repeated.
 * 3. After command execution, the same check from step #1 is performed.
 *
 * All datetime timestamps are given as [Instant] values instead of localtime.
 * This is done to ensure that timezone and/or daylight savings changes do
 * not negatively affect operation of the pump. The pump's current datetime
 * is automatically adjusted if it deviates from the current system datetime,
 * and the system's current UTC offset is also stored (in the [PumpStateStore]).
 *
 * This class also informs callers about various events of type [Event].
 * Events can be for example "battery low", "TBR started", "bolus delivered" etc.
 * When the Combo is suspended, a 0% 15-minute TBR event is emitted, since the
 * suspended state effectively acts like such a 0% TBR. Events are emitted via
 * the [onEvent] callback. Errors are communicated as exceptions, not as events.
 *
 * The class has a state (via [stateFlow]) and a status ([statusFlow]). The
 * state informs about what the pump is currently doing or what it can
 * currently do, while the status informs about various quantities in the
 * pump, like how many IUs the pump's reservoir currently has. The status
 * is updated by calling [updateStatus]. Note however that some functions
 * like [connect] also automatically update the status.
 *
 * [initialBasalProfile] allows for setting a known basal profile as the
 * current one. This does _not_ program that profile into the pump; instead,
 * this sets the initial value of [currentBasalProfile]. If that property
 * is null, [connect] will read the profile from the pump, so if the user
 * is certain that the pump already contains a certain profile, setting
 * this argument to that profile avoids an unnecessary basal profile read
 * operation when connecting.
 *
 * IMPORTANT: The commands in this class are not designed to be executed
 * concurrently (the Combo does not support this), so make sure these
 * commands (for example, [setBasalProfile] and [deliverBolus]) are
 * never called concurrently by multiple threads and/or coroutines.
 * If necessary, use synchronization primitives.
 *
 * @param bluetoothDevice [BluetoothDevice] object to use for
 *   Bluetooth I/O. Must be in a disconnected state when
 *   assigned to this instance.
 * @param pumpStateStore Pump state store to use.
 * @param initialBasalProfile Basal profile to use as the initial value
 *   of [currentBasalProfile].
 * @param onEvent Callback to inform caller about events that happen
 *   during a connection, like when the battery is going low, or when
 *   a TBR started.
 */
class Pump(
    private val bluetoothDevice: BluetoothDevice,
    private val pumpStateStore: PumpStateStore,
    initialBasalProfile: BasalProfile? = null,
    private val onEvent: (event: Event) -> Unit = { }
) {
    private val pumpIO = PumpIO(pumpStateStore, bluetoothDevice)
    // Updated by updateStatusImpl(). true if the Combo
    // is currently in the stop mode. If true, commands
    // are not executed, and an exception is thrown instead.
    // See the checks in executeCommand() for details.
    private var pumpSuspended = false

    // States for navigating through remote terminal (RT) screens. Needed by
    // all commands that simulate user interactions in the RT mode, like
    // setBasalProfile(). Not used by command-mode commands like [deliverBolus].
    private val rtNavigationContext = RTNavigationContextProduction(pumpIO)
    private var parsedScreenFlow = rtNavigationContext.getParsedScreenFlow()

    // Used for counting how many times an RT alert screen was dismissed by a button press.
    private var dismissalCount = 0
    // Used in handleAlertScreenContent() to check if the current alert
    // screen contains the same alert as the previous one.
    private var lastObservedAlertScreenContent: AlertScreenContent? = null

    private var currentPumpUtcOffset: UtcOffset? = null

    // Command progress reporters.

    private val setBasalProfileReporter = createBasalProgressReporter<RTCommandProgressStage.SettingBasalProfile>()
    private val getBasalProfileReporter = createBasalProgressReporter<RTCommandProgressStage.GettingBasalProfile>()

    private val setTbrProgressReporter = ProgressReporter(
        listOf(
            RTCommandProgressStage.SettingTBRPercentage::class,
            RTCommandProgressStage.SettingTBRDuration::class
        ),
        Unit
    ) { _: Int, _: Int, stage: ProgressStage, _: Unit ->
        // TBR progress is divided in two stages, each of which have
        // their own individual progress. Combine them by letting the
        // SettingTBRPercentage stage cover the 0.0 - 0.5 progress
        // range, and SettingTBRDuration cover the remaining 0.5 -1.0
        // progress range.
        when (stage) {
            BasicProgressStage.Finished,
            BasicProgressStage.Aborted -> 1.0
            is RTCommandProgressStage.SettingTBRPercentage ->
                0.0 + stage.settingProgress.toDouble() / 100.0 * 0.5
            is RTCommandProgressStage.SettingTBRDuration ->
                0.5 + stage.settingProgress.toDouble() / 100.0 * 0.5
            else -> 0.0
        }
    }

    private val bolusDeliveryProgressReporter = ProgressReporter(
        listOf(
            RTCommandProgressStage.DeliveringBolus::class
        ),
        Unit
    ) { _: Int, _: Int, stage: ProgressStage, _: Unit ->
        // Bolus delivery progress is determined by the single
        // stage in the reporter, which is DeliveringBolus.
        // That stage contains how many IU have been delivered
        // so far, which is suitable for a progress indicator,
        // so we use that for the overall progress.
        when (stage) {
            BasicProgressStage.Finished,
            BasicProgressStage.Aborted -> 1.0
            is RTCommandProgressStage.DeliveringBolus ->
                stage.deliveredAmount.toDouble() / stage.totalAmount.toDouble()
            else -> 0.0
        }
    }

    private val setDateTimeProgressReporter = ProgressReporter<Unit>(
        listOf(
            RTCommandProgressStage.SettingDateTimeHour::class,
            RTCommandProgressStage.SettingDateTimeMinute::class,
            RTCommandProgressStage.SettingDateTimeYear::class,
            RTCommandProgressStage.SettingDateTimeMonth::class,
            RTCommandProgressStage.SettingDateTimeDay::class
        ),
        Unit
    )

    private val tddHistoryProgressReporter = ProgressReporter<Unit>(
        listOf(
            RTCommandProgressStage.FetchingTDDHistory::class
        ),
        Unit
    ) { _: Int, _: Int, stage: ProgressStage, _: Unit ->
        when (stage) {
            // TDD history fetching progress is determined by the single
            // stage in the reporter, which is FetchingTDDHistory.
            // That stage contains the index of the TDD that was just
            // read, which is suitable for a progress indicator,
            // so we use that for the overall progress.
            BasicProgressStage.Finished,
            BasicProgressStage.Aborted -> 1.0
            is RTCommandProgressStage.FetchingTDDHistory ->
                stage.historyEntryIndex.toDouble() / stage.totalNumEntries.toDouble()
            else -> 0.0
        }
    }

    /**
     * Exception thrown when an idempotent command failed every time.
     *
     * Idempotent commands are retried multiple times if they fail. If all attempts
     * fail, the dispatcher gives up, and throws this exception instead.
     */
    class CommandExecutionAttemptsFailedException :
        ComboException("All attempts to execute the command failed")

    /**
     * Exception thrown when setting the pump datetime fails.
     */
    class SettingPumpDatetimeFailedException :
        ComboException("Could not set pump datetime")

    class UnaccountedBolusDetectedException :
        ComboException("Unaccounted bolus(es) detected")

    class UnknownTbrDetectedException(val tbrPercentage: Int) :
        ComboException("Unknown TBR with $tbrPercentage% detected")

    /**
     * Exception thrown when something goes wrong with a bolus delivery.
     *
     * @param totalAmount Total bolus amount that was supposed to be delivered. In 0.1 IU units.
     * @param message The detail message.
     */
    open class BolusDeliveryException(val totalAmount: Int, message: String) : ComboException(message)

    /**
     * Exception thrown when the Combo did not deliver the bolus at all.
     *
     * @param totalAmount Total bolus amount that was supposed to be delivered. In 0.1 IU units.
     */
    class BolusNotDeliveredException(totalAmount: Int) :
        BolusDeliveryException(totalAmount, "Could not deliver bolus amount of ${totalAmount.toStringWithDecimal(1)} IU")

    /**
     * Exception thrown when the bolus delivery was cancelled.
     *
     * @param deliveredAmount Bolus amount that was delivered before the bolus was cancelled. In 0.1 IU units.
     * @param totalAmount Total bolus amount that was supposed to be delivered. In 0.1 IU units.
     */
    class BolusCancelledByUserException(val deliveredAmount: Int, totalAmount: Int) :
        BolusDeliveryException(
            totalAmount,
            "Bolus cancelled (delivered amount: ${deliveredAmount.toStringWithDecimal(1)} IU  " +
            "total programmed amount: ${totalAmount.toStringWithDecimal(1)} IU"
        )

    /**
     * Exception thrown when the bolus delivery was aborted due to an error.
     *
     * @param deliveredAmount Bolus amount that was delivered before the bolus was aborted. In 0.1 IU units.
     * @param totalAmount Total bolus amount that was supposed to be delivered.
     */
    class BolusAbortedDueToErrorException(deliveredAmount: Int, totalAmount: Int) :
        BolusDeliveryException(
            totalAmount,
            "Bolus aborted due to an error (delivered amount: ${deliveredAmount.toStringWithDecimal(1)} IU  " +
            "total programmed amount: ${totalAmount.toStringWithDecimal(1)} IU"
        )

    /**
     * Exception thrown when there isn't enough insulin in the reservoir for the bolus to be delivered.
     *
     * IMPORTANT: Bolus amount is given in 0.1 IU units, while the available units in the
     * reservoir are given in whole 1 IU units.
     *
     * @param bolusAmount Bolus amount that was attempted to be delivered. In 0.1 IU units.
     * @param availableUnitsInReservoir Number of units in the reservoir. In 1 IU units.
     */
    class InsufficientInsulinAvailableException(bolusAmount: Int, val availableUnitsInReservoir: Int) :
        BolusDeliveryException(
            bolusAmount,
            "Insufficient insulin in reservoir for bolus: bolus amount: ${bolusAmount.toStringWithDecimal(1)} IU  " +
            "available units in reservoir: $availableUnitsInReservoir"
        )

    /**
     * Exception thrown when the TBR that was passed to setTbr() does not match the actually active TBR.
     *
     * If no TBR is active, [actualTbrDuration] is 0. If no TBR was expected to be active,
     * [expectedTbrDuration] is 0.
     */
    class UnexpectedTbrStateException(
        val expectedTbrPercentage: Int,
        val expectedTbrDuration: Int,
        val actualTbrPercentage: Int,
        val actualTbrDuration: Int
    ) : ComboException(
        "Expected TBR: $expectedTbrPercentage% $expectedTbrDuration minutes ; " +
        "actual TBR: $actualTbrPercentage% $actualTbrDuration minutes"
    )

    /**
     * Events that can occur during operation.
     *
     * These are forwarded through the [onEvent] property.
     *
     * IMPORTANT: Bolus amounts are given in 0.1 IU units,
     * so for example, "57" means 5.7 IU.
     */
    sealed class Event {
        object BatteryLow : Event()
        object ReservoirLow : Event()
        class QuickBolusRequested(
            val bolusId: Long,
            val timestamp: Instant,
            val bolusAmount: Int
        ) : Event()
        class QuickBolusInfused(
            val bolusId: Long,
            val timestamp: Instant,
            val bolusAmount: Int
        ) : Event()
        class StandardBolusRequested(
            val bolusId: Long,
            val timestamp: Instant,
            val manual: Boolean,
            val bolusAmount: Int
        ) : Event()
        class StandardBolusInfused(
            val bolusId: Long,
            val timestamp: Instant,
            val manual: Boolean,
            val bolusAmount: Int
        ) : Event()
        class ExtendedBolusStarted(
            val bolusId: Long,
            val timestamp: Instant,
            val totalBolusAmount: Int,
            val totalDurationMinutes: Int
        ) : Event()
        class ExtendedBolusEnded(
            val bolusId: Long,
            val timestamp: Instant,
            val totalBolusAmount: Int,
            val totalDurationMinutes: Int
        ) : Event()
        class MultiwaveBolusStarted(
            val bolusId: Long,
            val timestamp: Instant,
            val totalBolusAmount: Int,
            val immediateBolusAmount: Int,
            val totalDurationMinutes: Int
        ) : Event()
        class MultiwaveBolusEnded(
            val bolusId: Long,
            val timestamp: Instant,
            val totalBolusAmount: Int,
            val immediateBolusAmount: Int,
            val totalDurationMinutes: Int
        ) : Event()
        class TbrStarted(val tbr: Tbr) : Event()
        class TbrEnded(val tbr: Tbr) : Event()
    }

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
     * This flow's value is null until the connection is fully established (at which point
     * the mode is set to [PumpIO.Mode.REMOTE_TERMINAL] or [PumpIO.Mode.COMMAND]), and
     * set back to null again after disconnecting.
     */
    val currentModeFlow: StateFlow<PumpIO.Mode?> = pumpIO.currentModeFlow

    /**
     * Possible states the pump can be in.
     */
    enum class State {
        /**
         * There is no connection to the pump. This is the initial state.
         */
        DISCONNECTED,

        /**
         * Connection to the pump is being established. This state is set
         * while [connect] is running. If connecting fails, the state
         * is set to [ERROR], otherwise it is set to [CHECKING_PUMP],
         * [SUSPENDED], or [READY_FOR_COMMANDS].
         */
        CONNECTING,

        /**
         * After connection was established, [connect] performs checks
         * (if [performOnConnectChecks] is set to true). The pump state
         * is set to this one while these checks are running.
         * If [performOnConnectChecks] is set to false, this state
         * is never set. Instead, after [CONNECTING], the state transitions
         * directly to [READY_FOR_COMMANDS], [SUSPENDED], or [ERROR].
         */
        CHECKING_PUMP,

        /**
         * After successfully connecting and performing the checks, this
         * becomes the current state. Commands can be run in this state.
         * If the Combo is stopped (also known as "suspended"), the
         * state is set to [SUSPENDED] instead (see below).
         */
        READY_FOR_COMMANDS,

        /**
         * A command is currently being executed. This state remains set
         * until the command execution finishes. If it finishes successfully,
         * it is set back to [READY_FOR_COMMANDS]. If an error occurs,
         * it is set to [ERROR].
         */
        EXECUTING_COMMAND,

        /**
         * The Combo is currently stopped (= suspended). No commands can
         * be executed. This is not an error, but the user has to resume
         * pump operation manually.
         */
        SUSPENDED,

        /**
         * An error occurred during connection setup or command execution.
         * Said error was non-recoverable. The only valid operation that
         * can be performed in this state is to call [disconnect].
         * Commands cannot be executed in this state.
         */
        ERROR
    }

    private val _stateFlow = MutableStateFlow(State.DISCONNECTED)

    /**
     * [StateFlow] that notifies about the pump's current state.
     */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * [StateFlow] for reporting progress during the [connect] call.
     *
     * See the [ProgressReporter] documentation for details.
     */
    val connectProgressFlow: StateFlow<ProgressReport> = pumpIO.connectProgressFlow

    /**
     * [ProgressReporter] flow for reporting progress while the pump datetime is set.
     *
     * See the [ProgressReporter] documentation for details.
     *
     * This flow consists of these stages (aside from Finished/Aborted/Idle):
     *
     * - [RTCommandProgressStage.SettingDateTimeHour]
     * - [RTCommandProgressStage.SettingDateTimeMinute]
     * - [RTCommandProgressStage.SettingDateTimeYear]
     * - [RTCommandProgressStage.SettingDateTimeMonth]
     * - [RTCommandProgressStage.SettingDateTimeDay]
     */
    val setDateTimeProgressFlow = setDateTimeProgressReporter.progressFlow

    /**
     * Pump status.
     *
     * This contains status information like the number of available
     * units in the reservoir, the percentage of a currently ongoing
     * TBR, the battery state etc.
     *
     * There is no field that specifies whether the Combo is running
     * or stopped. That's because that information is already covered
     * by [State.SUSPENDED].
     *
     * A [currentBasalRateFactor] is special in that it indicates
     * that [updateStatus] could not get the current factor. This
     * happens when the pump is stopped (the main screen does not
     * show any factor then). It also happens when a 0% TBR is
     * active (the factor shown on screen is then always 0 regardless
     * or what the actual underlying factor is).
     */
    data class Status(
        val availableUnitsInReservoir: Int,
        val activeBasalRateNumber: Int,
        val currentBasalRateFactor: Int,
        val tbrOngoing: Boolean,
        val remainingTbrDurationInMinutes: Int,
        val tbrPercentage: Int,
        val reservoirState: ReservoirState,
        val batteryState: BatteryState
    )

    private val _statusFlow = MutableStateFlow<Status?>(null)

    /**
     * [StateFlow] that notifies about the pump's current status.
     *
     * This is updated by the [updateStatus] function. Initially,
     * it is set to null. It is set to null again after disconnecting.
     */
    val statusFlow = _statusFlow.asStateFlow()

    /**
     * The basal profile that is currently being used.
     *
     * This is initially set to the profile that is passed to [Pump]'s
     * constructor. If [setBasalProfile] is called, and the pump's
     * profile is updated, then so is this property.
     */
    var currentBasalProfile: BasalProfile? = initialBasalProfile
        private set

    /**
     * Information about the last bolus. See [lastBolusFlow].
     *
     * NOTE: This only reports quick and standard boluses, not multiwave and extended ones.
     *
     * @property bolusId ID associated with this bolus.
     * @property bolusAmount Bolus amount, in 0.1 IU units.
     * @property timestamp Timestamp of the bolus delivery.
     */
    data class LastBolus(val bolusId: Long, val bolusAmount: Int, val timestamp: Instant)

    private var _lastBolusFlow = MutableStateFlow<LastBolus?>(null)

    /**
     * Informs about the last bolus that was administered during this connection.
     *
     * Boluses that might have happened in an earlier connection are not looked
     * at. This is purely about the _current_ connection.
     */
    val lastBolusFlow = _lastBolusFlow.asStateFlow()

    /**
     * Unpairs the pump.
     *
     * Unpairing consists of deleting any associated pump state,
     * followed by unpairing the Bluetooth device.
     *
     * This disconnects before unpairing to make sure there
     * is no ongoing connection while attempting to unpair.
     *
     * If the pump isn't paired already, this function does nothing.
     *
     * NOTE: This removes pump data from ComboCtl's pump state store
     * and unpairs the Combo at the Bluetooth level, but does _not_
     * remove this client from the Combo. The user still has to
     * operate the Combo's local LCD UI to manually remove this
     * client from the Combo in its Bluetooth settings. There is
     * no way to do this remotely by the client.
     */
    suspend fun unpair() {
        if (!pumpStateStore.hasPumpState(address))
            return

        disconnect()

        pumpStateStore.deletePumpState(address)

        // Unpairing in a coroutine with an IO dispatcher
        // in case unpairing blocks.
        withContext(ioDispatcher()) {
            bluetoothDevice.unpair()
        }

        logger(LogLevel.INFO) { "Unpaired from pump with address ${bluetoothDevice.address}" }
    }


    /**
     * Establishes a connection to the Combo.
     *
     * This suspends the calling coroutine until the connection
     * is up and running, a connection error occurs, or the
     * calling coroutine is cancelled.
     *
     * This changes the current state multiple times. These
     * updates are accessible through [stateFlow]. Initially,
     * the state is set to [State.CONNECTING]. Once the underlying
     * Bluetooth device is connected, this function transitions to
     * the [State.CHECKING_PUMP] state and performs checks on the
     * pump (described below). As part of these checks, if the Combo
     * is found to be currently stopped (= suspended), the state is
     * set to [State.SUSPENDED], otherwise it is set to
     * [State.READY_FOR_COMMANDS]. At this point, this function
     * finishes successfully.
     *
     * If any error occurs while this function runs, the state
     * is set to [State.ERROR]. If the calling coroutine is cancelled,
     * the state is instead set to [State.DISCONNECTED] because
     * cancellation rolls back any partial connection setup that
     * might have been done by the time the cancellation occurs.
     *
     * At each connection setup, a series of checks are performed.:
     *
     * 1. [updateStatus] is called to get the current up-to-date status,
     * which is needed by other checks. This also updates the [statusFlow].
     * 2. The command mode history delta is retrieved. This contains all
     * delivered boluses since the last time the history delta was retrieved.
     * If no boluses happened in between connections, this list will be empty.
     * Otherwise, unaccounted boluses happened. These are announced via [onEvent].
     * Afterwards, this function throws an [UnaccountedBolusDetectedException].
     * 3. The current pump status is evaluated. If the pump is found to be
     * suspended, the [stateFlow] switches to [State.SUSPENDED], the checks
     * end, and so does this function. Otherwise, it continues.
     * 4. The TBR state is evaluated according to the information from
     * [PumpStateStore] and what is displayed on the main Combo screen
     * (this is retrieved by [updateStatus] in the remote terminal mode).
     * If an unknown TBR is detected, then that unknown TBR is cancelled,
     * and [UnknownTbrDetectedException] is thrown.
     * 5. If [currentBasalProfile] is null, or if the current basal rate
     * that is shown on the main Combo RT screen does not match the current
     * basal rate from the profile at this hour, the basal profile is read
     * from the Combo, and [currentBasalProfile] is updated. The basal
     * profile retrieval can be tracked via [getBasalProfileFlow].
     * 6. The current pump's datetime is updated to match the current
     * system datetime if there is a mismatch. This is done through the
     * remote terminal mode. The progress can be tracked by watching the
     * [setDateTimeProgressFlow].
     * 7. The current pump's UTC offset is updated to match the current
     * system's UTC offset if there is a mismatch. The UTC offset is
     * written to [pumpStateStore].
     *
     * Since no two clients can deliver a bolus, set a TBR etc. on the same
     * Combo simultaneously, these checks do not have to be performed before
     * each command - it is sufficient to do them upon connection setup.
     *
     * This function also handles a special situation if the [Nonce] that is
     * stored in [PumpStateStore] for this pump is incorrect. The Bluetooth
     * socket can then be successfully connected, but right afterwards, when
     * this function tries to send a [TransportLayer.Command.REQUEST_REGULAR_CONNECTION]
     * packet, the Combo does not respond, instead terminating the connection
     * and producing a [BluetoothException]. If this happens, this function
     * increments the nonce and tries again. This is done multiple times
     * until either the connection setup succeeds or the maximum number of
     * attempts is reached. In the latter case, this function throws a
     * [ConnectionRequestIsNotBeingAcceptedException]. The user should then
     * be recommended to re-pair with the Combo, since establishing a connection
     * isn't working.
     *
     * @throws IllegalStateException if the current state is not
     *   [State.DISCONNECTED] (calling [connect] while a connection is present
     *   makes no sense).
     * @throws ConnectionRequestIsNotBeingAcceptedException if connecting the
     *   actual Bluetooth socket succeeds, but the Combo does not accept the
     *   packet that requests a connection, and this failed several times
     *   in a row.
     * @throws AlertScreenException if the pump reports errors or
     *   unhandled warnings during the connection setup and/or
     *   pump checks.
     * @throws SettingPumpDatetimeFailedException if during the checks,
     *   the pump's datetime was found to be deviating too much from the
     *   actual current datetime, and adjusting the pump's datetime failed.
     * @throws UnaccountedBolusDetectedException if during the pump checks
     *   a bolus is found in the pump's history delta that wasn't delivered
     *   by ComboCtl.
     * @throws UnknownTbrDetectedException if during the pump checks
     *   the pump is found to be currently delivering a TBR that is unknown
     *   to ComboCtl.
     */
    suspend fun connect() {
        check(stateFlow.value == State.DISCONNECTED) { "Attempted to connect to pump in a the ${stateFlow.value} state" }

        try {
            setState(State.CONNECTING)

            // Get the current pump state UTC offset to translate localtime
            // timestamps from the history delta to Instant timestamps.
            currentPumpUtcOffset = pumpStateStore.getCurrentUtcOffset(bluetoothDevice.address)

            // Set the command mode as the initial mode to be able
            // to directly check for warnings / errors through the
            // CMD_READ_PUMP_STATUS command.
            pumpIO.connect(initialMode = PumpIO.Mode.COMMAND, runHeartbeat = true)

            setState(State.CHECKING_PUMP)
            performOnConnectChecks()

            setState(if (pumpSuspended) State.SUSPENDED else State.READY_FOR_COMMANDS)
        } catch (e: CancellationException) {
            _statusFlow.value = null
            setState(State.DISCONNECTED)
            throw e
        } catch (t: Throwable) {
            setState(State.ERROR)
            throw t
        }
    }

    /**
     * Terminates an ongoing connection previously established by [connect].
     *
     * If no connection is ongoing, this does nothing.
     *
     * This function resets the pump state and undoes a [State.ERROR] state.
     * In case of an error, the user has to call [disconnect] to reset back
     * to the [State.DISCONNECTED] state. Afterwards, the user can try again
     * to establish a new connection.
     *
     * This sets [statusFlow] to null and [stateFlow] to [State.DISCONNECTED].
     */
    suspend fun disconnect() {
        if (stateFlow.value == State.DISCONNECTED) {
            logger(LogLevel.DEBUG) { "Ignoring disconnect() call since pump is already disconnected" }
            return
        }

        pumpIO.disconnect()
        _statusFlow.value = null
        setState(State.DISCONNECTED)
    }

    /**
     * [ProgressReporter] flow for keeping track of the progress of [setBasalProfile].
     */
    val setBasalProfileFlow = setBasalProfileReporter.progressFlow

    /**
     * [ProgressReporter] flow for keeping track of the progress of when the pump's basal profile is read.
     *
     * This happens when a [connect] call determines that reading
     * the profile from the pump is necessary at the time of that
     * function call.
     */
    val getBasalProfileFlow = getBasalProfileReporter.progressFlow

    /**
     * Sets [basalProfile] as the new basal profile to use in the pump.
     *
     * This programs the pump to use this basal profile by simulating user
     * interaction in the remote terminal mode. There is no command-mode
     * command to directly pass the 24 profile factors to the pump, so
     * it has to be set by doing the aforementioned simulation. This is
     * relatively slow, so it is recommended to use [setBasalProfileFlow]
     * to provide some form of progress indicator (like a progress bar)
     * to the user.
     *
     * If [currentBasalProfile] is not null, this function compares
     * [basalProfile] to that profile. If their factors equal, this
     * function does nothing. That way, redundant calls are caught and
     * ignored. If [currentBasalProfile] is null, or if its factors do
     * not match those of [basalProfile], then it is set to [basalProfile].
     *
     * If [carryOverLastFactor] is set to true (the default value), this function
     * moves between basal profile factors by pressing the UP and DOWN buttons
     * simultaneously instead of the MENU button. This copies over the last
     * factor that was being programmed in to the next factor. If this is false,
     * the MENU key is pressed. The pump then does not carry over anything to the
     * next screen; instead, the currently programmed in factor shows up.
     * Typically, carrying over the last factor is faster, which is why this is
     * set to true by default. There might be corner cases where setting this to
     * false results in faster execution, but at the moment, none are known.
     *
     * This also checks if setting the profile is actually necessary by comparing
     * [basalProfile] with [currentBasalProfile]. If these match, this function
     * does not set anything, and just returns false. Otherwise, it sets the
     * new profile, sets [basalProfile] as the new [currentBasalProfile],
     * and returns true. Note that a return value of false is _not_ an error.
     *
     * @param basalProfile New basal profile to program into the pump.
     * @param carryOverLastFactor If set to true, previously programmed in factors
     *   are carried to the next factor while navigating through the profile.
     * @return true if the profile was actually set, false otherwise.
     * @throws AlertScreenException if an alert occurs during this call.
     * @throws IllegalStateException if the current state is not
     *   [State.READY_FOR_COMMANDS].
     */
    suspend fun setBasalProfile(basalProfile: BasalProfile, carryOverLastFactor: Boolean = true) = executeCommand<Boolean>(
        pumpMode = PumpIO.Mode.REMOTE_TERMINAL,
        isIdempotent = true
    ) {
        if (basalProfile == currentBasalProfile) {
            logger(LogLevel.DEBUG) { "Current basal profile equals the profile that is to be set; ignoring redundant call"}
            return@executeCommand false
        }

        setBasalProfileReporter.reset(Unit)

        setBasalProfileReporter.setCurrentProgressStage(RTCommandProgressStage.SettingBasalProfile(0))

        try {
            navigateToRTScreen(rtNavigationContext, ParsedScreen.BasalRateFactorSettingScreen::class)

            // Store the hours at which the current basal rate factor
            // begins to ensure that during screen cycling we
            // actually get to the next factor (which begins at
            // different hours).
            var previousBeginHour = (parsedScreenFlow.first() as ParsedScreen.BasalRateFactorSettingScreen).beginTime.hour

            for (index in basalProfile) {
                val basalFactor = basalProfile[index]
                adjustQuantityOnScreen(rtNavigationContext, basalFactor) {
                    (it as ParsedScreen.BasalRateFactorSettingScreen).numUnits
                }

                setBasalProfileReporter.setCurrentProgressStage(RTCommandProgressStage.SettingBasalProfile(index + 1))

                // By pushing MENU or UP_DOWN we move to the next basal rate factor.
                // If we are at the last factor, and are about to transition back to
                // the first one again, we always press MENU to make sure the first
                // factor isn't overwritten by the last factor that got carried over.
                rtNavigationContext.shortPressButton(
                    if (carryOverLastFactor && (index != (basalProfile.size - 1)))
                        RTNavigationButton.UP_DOWN
                    else
                        RTNavigationButton.MENU
                )

                // Wait until we actually get a different BasalRateFactorSettingScreen.
                // The pump might send us the same screen multiple times, because it
                // might be blinking, so it is important to wait until the button press
                // above actually resulted in a change to the screen with the next factor.
                parsedScreenFlow
                    .first { parsedScreen ->
                        parsedScreen as ParsedScreen.BasalRateFactorSettingScreen
                        if (parsedScreen.beginTime.hour == previousBeginHour) {
                            false
                        } else {
                            previousBeginHour = parsedScreen.beginTime.hour
                            true
                        }
                    }
            }

            // All factors are set. Press CHECK once to get back to the total
            // basal rate screen, and then CHECK again to store the new profile
            // and return to the main menu.

            rtNavigationContext.shortPressButton(RTNavigationButton.CHECK)
            waitUntilScreenAppears(rtNavigationContext, ParsedScreen.BasalRateTotalScreen::class)

            rtNavigationContext.shortPressButton(RTNavigationButton.CHECK)
            waitUntilScreenAppears(rtNavigationContext, ParsedScreen.MainScreen::class)

            setBasalProfileReporter.setCurrentProgressStage(BasicProgressStage.Finished)

            return@executeCommand true
        } catch (t: Throwable) {
            setBasalProfileReporter.setCurrentProgressStage(BasicProgressStage.Aborted)
            throw t
        }
    }

    /**
     * [ProgressReporter] flow for keeping track of the progress of [setTbr].
     */
    val setTbrProgressFlow = setTbrProgressReporter.progressFlow

    /**
     * Sets the Combo's current temporary basal rate (TBR) via the remote terminal (RT) mode.
     *
     * This function suspends until the TBR is fully set. The [tbrProgressFlow]
     * can be used to get informed about the TBR setting progress. Since setting
     * a TBR can take a while, it is recommended to make use of this to show
     * some sort of progress indicator on a GUI.
     *
     * If [percentage] is 100, and [force100Percent] is true, any ongoing TBR will be
     * cancelled. The Combo will produce a W6 warning screen when this happens. This
     * screen is automatically dismissed by this function before it exits. If instead
     * [percentage] is 100 but [force100Percent] is false, this function will actually
     * start a 15-minute TBR of 90% or 110%, depending on the current TBR. (If the
     * current TBR is less than 100%, a 15-minute 110% TBR is started, otherwise a
     * 15-minute 90% TBR starts.) This is done to avoid the vibration that accompanies
     * the aforementioned W6 warning.
     *
     * [percentage] must be in the range 0-500 (specifying the % of the TBR),
     * and an integer multiple of 10.
     * [durationInMinutes] must be at least 15 (since the Combo cannot do TBRs
     * that are shorter than 15 minutes), and must an integer multiple of 15.
     * Maximum allowed duration is 24 hours, so the maximum valid value is 1440.
     * However, if [percentage] is 100, the value of [durationInMinutes]
     * is ignored.
     *
     * This also automatically cancels any TBR that may be ongoing, replacing it with
     * the newly set TBR. (This cancelling does not produce any W6 warnings, since
     * they are instantly replaced by the new TBR.)
     *
     * As soon as a TBR is started by this function, [Event.TbrStarted] is emitted
     * via the [onEvent] callback. Likewise, when a TBR finishes or is cancelled,
     * [Event.TbrEnded] is emitted.
     *
     * @param percentage TBR percentage to set.
     * @param durationInMinutes TBR duration in minutes to set.
     * @param force100Percent Whether to really set the TBR to 100% (= actually
     *   cancelling an ongoing TBR, which produces a W6 warning) or to fake a
     *   100% TBR by setting 90% / 110% TBRs (see above).
     * @throws IllegalArgumentException if the percentage is not in the 0-500 range,
     *   or if the percentage value is not an integer multiple of 10, or if
     *   the duration is <15 or not an integer multiple of 15 (see the note
     *   about duration being ignored with percentage 100 above though).
     * @throws UnexpectedTbrStateException if the TBR that is actually active
     *   after this function finishes does not match the specified percentage
     *   and duration.
     * @throws IllegalStateException if the current state is not
     *   [State.READY_FOR_COMMANDS], or if the pump is suspended after setting the TBR.
     * @throws AlertScreenException if alerts occurs during this call, and they
     *   aren't a W6 warning (those are handled by this function).
     */
    suspend fun setTbr(percentage: Int, durationInMinutes: Int, force100Percent: Boolean = false) = executeCommand(
        pumpMode = PumpIO.Mode.REMOTE_TERMINAL,
        isIdempotent = true
    ) {
        // NOTE: Not using the Tbr class directly as a function argument since
        // the timestamp property of that class is not useful here. The Tbr
        // class is rather meant for TBR events.

        val currentStatus = statusFlow.value ?: throw IllegalStateException("Cannot start TBR without a known pump status")
        var expectedTbrPercentage = 0
        var expectedTbrDuration = 0

        // In the code below, we always create a Tbr object _before_ calling
        // setCurrentTbr to make use of the checks in the Tbr constructor.
        // If percentage and/or durationInMinutes are invalid, these checks
        // will throw an IllegalArgumentException. We want to do this
        // before actually setting the TBR.

        if (percentage == 100) {
            if (currentStatus.tbrPercentage != 100) {
                if (force100Percent) {
                    setCurrentTbr(100, 0)
                    reportOngoingTbrAsStopped()
                    expectedTbrPercentage = 100
                    expectedTbrDuration = 0
                } else {
                    val newPercentage = if (currentStatus.tbrPercentage < 100) 110 else 90
                    val tbr = Tbr(timestamp = Clock.System.now(), percentage = newPercentage, durationInMinutes = 15)
                    setCurrentTbr(percentage = newPercentage, durationInMinutes = 15)
                    reportStartedTbr(tbr)
                    expectedTbrPercentage = newPercentage
                    expectedTbrDuration = 15
                }
            }
        } else {
            val tbr = Tbr(timestamp = Clock.System.now(), percentage = percentage, durationInMinutes = durationInMinutes)
            setCurrentTbr(percentage = percentage, durationInMinutes = durationInMinutes)
            reportStartedTbr(tbr)
            expectedTbrPercentage = percentage
            expectedTbrDuration = durationInMinutes
        }

        // We just set the TBR. Now check the main screen contents to see if
        // the TBR was actually set, and if so, whether it was set correctly.
        // If not, throw an exception, since this is an error.

        val mainScreen = waitUntilScreenAppears(rtNavigationContext, ParsedScreen.MainScreen::class)
        val mainScreenContent = when (mainScreen) {
            is ParsedScreen.MainScreen -> mainScreen.content
            else -> throw NoUsableRTScreenException()
        }
        logger(LogLevel.DEBUG) { "Main screen content after setting TBR: $mainScreenContent" }
        when (mainScreenContent) {
            is MainScreenContent.Stopped ->
                throw IllegalStateException("Combo is in the stopped state after setting TBR")

            is MainScreenContent.Normal -> {
                if (expectedTbrPercentage != 100) {
                    // We expected a TBR to be active, but there isn't any;
                    // we aren't seen any TBR main screen contents.
                    throw UnexpectedTbrStateException(
                        expectedTbrPercentage = expectedTbrPercentage,
                        expectedTbrDuration = expectedTbrDuration,
                        actualTbrPercentage = 100,
                        actualTbrDuration = 0
                    )
                }
            }

            is MainScreenContent.Tbr -> {
                if (expectedTbrPercentage == 100) {
                    // We expected the TBR to be cancelled, but it isn't.
                    throw UnexpectedTbrStateException(
                        expectedTbrPercentage = 100,
                        expectedTbrDuration = 0,
                        actualTbrPercentage = mainScreenContent.tbrPercentage,
                        actualTbrDuration = mainScreenContent.remainingTbrDurationInMinutes
                    )
                } else if ((expectedTbrDuration - mainScreenContent.remainingTbrDurationInMinutes) > 2) {
                    // The current TBR duration does not match the programmed one.
                    // We allow a tolerance range of 2 minutes since a little while
                    // may have passed between setting the TBR and reaching this
                    // location in the code.
                    throw UnexpectedTbrStateException(
                        expectedTbrPercentage = expectedTbrPercentage,
                        expectedTbrDuration = expectedTbrDuration,
                        actualTbrPercentage = mainScreenContent.tbrPercentage,
                        actualTbrDuration = mainScreenContent.remainingTbrDurationInMinutes
                    )
                }
            }
        }
    }

    /**
     * [ProgressReporter] flow for keeping track of the progress of [deliverBolus].
     */
    val bolusDeliveryProgressFlow = bolusDeliveryProgressReporter.progressFlow

    /**
     * Instructs the pump to deliver the specified bolus amount.
     *
     * This function only delivers a standard bolus, no multi-wave / extended ones.
     * It is currently not known how to command the Combo to deliver those types.
     *
     * The function suspends until the bolus was fully delivered or an error occurred.
     * In the latter case, an exception is thrown. During the delivery, the current
     * status is periodically retrieved from the pump. [bolusStatusUpdateIntervalInMs]
     * controls the status update interval. At each update, the bolus state is checked
     * (that is, whether it is delivering, or it is done, or an error occurred etc.)
     * The bolus amount that was delivered by that point is communicated via the
     * [bolusDeliveryProgressFlow].
     *
     * To cancel the bolus, simply cancel the coroutine that is suspended by this function.
     *
     * Prior to the delivery, the number of units available in the reservoir is checked
     * by looking at [statusFlow]. If there aren't enough IU in the reservoir, this
     * function throws [InsufficientInsulinAvailableException].
     *
     * After the delivery, this function looks at the Combo's bolus history delta. That
     * delta is expected to contain exactly one entry - the bolus that was just delivered.
     * The details in that history delta entry are then emitted as
     * [Event.StandardBolusInfused] via [onEvent].
     * If there is no entry, [BolusNotDeliveredException] is thrown. If more than one
     * bolus entry is detected, [UnaccountedBolusDetectedException] is thrown (this
     * second case is not expected to ever happen, but is possible in theory). The
     * history delta is looked at even if an exception is thrown (unless it is one
     * of the exceptions that were just mentioned). This is because if there is an
     * error _during_ a bolus delivery, then some insulin might have still be
     * delivered, and there will be a [Event.StandardBolusInfused] history entry,
     * probably just not with the insulin amount that was originally planned.
     * It is still important to report that (partial) delivery, which is done
     * via [onEvent] just as described above.
     *
     * Once that is completed, this function calls [updateStatus] to make sure the
     * contents of [statusFlow] are up-to-date. A bolus delivery will at least
     * change the value of [Status.availableUnitsInReservoir] (unless perhaps it
     * is a very small bolus like 0.1 IU, since that value is given in whole IU units).
     *
     * @param bolusAmount Bolus amount to deliver. Note that this is given
     *   in 0.1 IU units, so for example, "57" means 5.7 IU. Valid range
     *   is 0.0 IU to 25.0 IU (that is, integer values 0-250).
     * @param bolusStatusUpdateIntervalInMs Interval between status updates,
     *   in milliseconds. Must be at least 1
     * @throws BolusNotDeliveredException if the pump did not deliver the bolus.
     *   This typically happens because the pump is currently stopped.
     * @throws BolusCancelledByUserException when the bolus was cancelled by the user.
     * @throws BolusAbortedDueToErrorException when the bolus delivery failed due
     *   to an error.
     * @throws UnaccountedBolusDetectedException if after the bolus delivery
     *   more than one bolus is reported in the Combo's bolus history delta.
     * @throws InsufficientInsulinAvailableException if the reservoir does not
     *   have enough IUs left for this bolus.
     * @throws IllegalArgumentException if [bolusAmount] is not in the 0-250 range,
     *   or if [bolusStatusUpdateIntervalInMs] is less than 1.
     * @throws IllegalStateException if the current state is not
     *   [State.READY_FOR_COMMANDS].
     * @throws AlertScreenException if alerts occurs during this call, and they
     *   aren't a W6 warning (those are handled by this function).
     */
    suspend fun deliverBolus(bolusAmount: Int, bolusStatusUpdateIntervalInMs: Long = 250) = executeCommand(
        // Instruct executeCommand() to not set the mode on its own.
        // This function itself switches manually between the
        // command and remote terminal modes.
        pumpMode = null,
        isIdempotent = false
    ) {
        require((bolusAmount > 0) && (bolusAmount <= 250)) {
            "Invalid bolus amount $bolusAmount (${bolusAmount.toStringWithDecimal(1)} IU)"
        }
        require(bolusStatusUpdateIntervalInMs >= 1) {
            "Invalid bolus status update interval $bolusStatusUpdateIntervalInMs"
        }

        // Check that there's enough insulin in the reservoir.
        statusFlow.value?.let { status ->
            if (status.availableUnitsInReservoir < bolusAmount)
                throw InsufficientInsulinAvailableException(bolusAmount, status.availableUnitsInReservoir)
        } ?: throw IllegalStateException("Cannot deliver bolus without a known pump status")

        // Switch to COMMAND mode for the actual bolus delivery
        // and for tracking the bolus progress below.
        pumpIO.switchMode(PumpIO.Mode.COMMAND)

        logger(LogLevel.DEBUG) { "Beginning bolus delivery of ${bolusAmount.toStringWithDecimal(1)} IU" }
        val didDeliver = pumpIO.deliverCMDStandardBolus(bolusAmount)
        if (!didDeliver) {
            logger(LogLevel.ERROR) { "Bolus delivery did not commence" }
            throw BolusNotDeliveredException(bolusAmount)
        }

        bolusDeliveryProgressReporter.reset(Unit)

        logger(LogLevel.DEBUG) { "Waiting until bolus delivery is complete" }

        var bolusFinishedCompletely = false

        // The Combo does not send bolus progress information on its own. Instead,
        // we have to regularly poll the current bolus status. Do that in this loop.
        // The bolusStatusUpdateIntervalInMs value controls how often we poll.
        try {
            while (true) {
                delay(bolusStatusUpdateIntervalInMs)

                val status = pumpIO.getCMDCurrentBolusDeliveryStatus()

                logger(LogLevel.VERBOSE) { "Got current bolus delivery status: $status" }

                val deliveredAmount = when (status.deliveryState) {
                    ApplicationLayer.CMDBolusDeliveryState.DELIVERING -> bolusAmount - status.remainingAmount
                    ApplicationLayer.CMDBolusDeliveryState.DELIVERED -> bolusAmount
                    ApplicationLayer.CMDBolusDeliveryState.CANCELLED_BY_USER -> {
                        logger(LogLevel.DEBUG) { "Bolus cancelled by user" }
                        throw BolusCancelledByUserException(
                            deliveredAmount = bolusAmount - status.remainingAmount,
                            totalAmount = bolusAmount
                        )
                    }
                    ApplicationLayer.CMDBolusDeliveryState.ABORTED_DUE_TO_ERROR -> {
                        logger(LogLevel.ERROR) { "Bolus aborted due to a delivery error" }
                        throw BolusAbortedDueToErrorException(
                            deliveredAmount = bolusAmount - status.remainingAmount,
                            totalAmount = bolusAmount
                        )
                    }
                    else -> continue
                }

                bolusDeliveryProgressReporter.setCurrentProgressStage(
                    RTCommandProgressStage.DeliveringBolus(
                        deliveredAmount = deliveredAmount,
                        totalAmount = bolusAmount
                    )
                )

                if (deliveredAmount >= bolusAmount) {
                    bolusDeliveryProgressReporter.setCurrentProgressStage(BasicProgressStage.Finished)
                    break
                }
            }

            bolusFinishedCompletely = true
        } catch (e: BolusDeliveryException) {
            // Handle BolusDeliveryException subclasses separately,
            // since these exceptions are thrown when the delivery
            // was cancelled by the user or aborted due to an error.
            // The code further below tries to cancel in case of any
            // exception, which would make no sense with these.
            bolusDeliveryProgressReporter.setCurrentProgressStage(BasicProgressStage.Aborted)
            throw e
        } catch (e: Exception) {
            bolusDeliveryProgressReporter.setCurrentProgressStage(BasicProgressStage.Aborted)
            try {
                pumpIO.cancelCMDStandardBolus()
            } catch (cancelBolusExc: Exception) {
                logger(LogLevel.ERROR) { "Silently discarding caught exception while cancelling bolus: $cancelBolusExc" }
            }
            throw e
        } finally {
            // After either the bolus is finished or an error occurred,
            // check the history delta here. Any bolus entries in the
            // delta will be communicated to the outside via the onEvent
            // callback.
            // Also, if we reach this point after the bolus finished
            // successfully (so, bolusFinishedCompletely will be true),
            // check for discrepancies in the history delta. We expect
            // the delta to contain exactly one StandardBolusInfused
            // entry. If there are none, or there are more than one,
            // or there are other bolus entries, something isn't right,
            // and we throw exceptions. They are _not_ thrown if we reach
            // this finally block after an exception occurred above
            // though, since in that case, we just want to look at the
            // delta to see what happened, whether any (partial) bolus
            // was delivered. We still need to communicate such events
            // to the outside even if the bolus delivery did not succeed.

            try {
                val historyDelta = fetchHistoryDelta()

                if (historyDelta.isEmpty()) {
                    if (bolusFinishedCompletely) {
                        logger(LogLevel.ERROR) { "Bolus delivery did not actually occur" }
                        throw BolusNotDeliveredException(bolusAmount)
                    }
                } else {
                    var numStandardBolusInfusedEntries = 0
                    var unexpectedBolusEntriesDetected = false
                    scanHistoryDeltaForBolusToEmit(historyDelta) { entry ->
                        when (val detail = entry.detail) {
                            is CMDHistoryEventDetail.StandardBolusInfused -> {
                                numStandardBolusInfusedEntries++
                                if (numStandardBolusInfusedEntries > 1)
                                    unexpectedBolusEntriesDetected = true
                            }

                            // We ignore this. It always accompanies StandardBolusInfused.
                            is CMDHistoryEventDetail.StandardBolusRequested ->
                                Unit

                            else -> {
                                if (detail.isBolusDetail)
                                    unexpectedBolusEntriesDetected = true
                            }
                        }
                    }

                    if (bolusFinishedCompletely) {
                        if (numStandardBolusInfusedEntries == 0) {
                            logger(LogLevel.ERROR) { "History delta did not contain an entry about bolus infusion" }
                            throw BolusNotDeliveredException(bolusAmount)
                        } else if (unexpectedBolusEntriesDetected) {
                            logger(LogLevel.ERROR) { "History delta contained unexpected additional bolus entries" }
                            throw UnaccountedBolusDetectedException()
                        }
                    }
                }
            } finally {
                // Re-read pump status. At the very least, the number of available
                // IUs in the reservoir will have changed, so we must update the
                // status both to make sure that future bolus calls operate with
                // an up-to-date status and to let the user know the updated
                // reservoir level via the statusFlow.
                // We always re-read the pump status, even if the history delta
                // checks above detected discrepancies, to make sure the status
                // is up-to-date.
                pumpIO.switchMode(PumpIO.Mode.REMOTE_TERMINAL)
                // Not calling updateStatusImpl(), instead calling this directly.
                // That's because updateStatusImpl() calls executeCommand(),
                // and here, we already are running in a lambda that's run
                // by executeCommand().
                updateStatusByReadingMainAndQuickinfoScreens(switchStatesIfNecessary = true)
            }
        }
    }

    /**
     * Total daily dosage (TDD) history entry.
     *
     * @property date Date of the TDD.
     * @property totalDailyAmount Total amount of insulin used in that day.
     *           Stored as an integer-encoded-decimal; last 3 digits of that
     *           integer are the 3 most significant fractional digits of the
     *           decimal amount.
     */
    data class TDDHistoryEntry(val date: LocalDate, val totalDailyAmount: Int)

    /**
     * [ProgressReporter] flow for keeping track of the progress of [fetchTDDHistory].
     */
    val tddHistoryProgressFlow = tddHistoryProgressReporter.progressFlow

    /**
     * Fetches the TDD history.
     *
     * This suspends the calling coroutine until the entire TDD history
     * is fetched, an error occurs, or the coroutine is cancelled.
     *
     * @throws IllegalStateException if the current state is not
     *   [State.READY_FOR_COMMANDS].
     * @throws AlertScreenException if alerts occurs during this call, and
     *   they aren't a W6 warning (those are handled by this function).
     */
    suspend fun fetchTDDHistory() = executeCommand<List<TDDHistoryEntry>>(
        pumpMode = PumpIO.Mode.REMOTE_TERMINAL,
        isIdempotent = true
    ) {
        tddHistoryProgressReporter.reset(Unit)

        try {
            val tddHistoryEntries = mutableListOf<TDDHistoryEntry>()

            navigateToRTScreen(rtNavigationContext, ParsedScreen.MyDataDailyTotalsScreen::class)

            longPressRTButtonUntil(rtNavigationContext, RTNavigationButton.DOWN) { parsedScreen ->
                if (parsedScreen !is ParsedScreen.MyDataDailyTotalsScreen) {
                    logger(LogLevel.DEBUG) { "Got a non-TDD screen ($parsedScreen) ; stopping TDD history scan" }
                    return@longPressRTButtonUntil LongPressRTButtonsCommand.ReleaseButton
                }

                tddHistoryEntries.add(
                    TDDHistoryEntry(
                        date = parsedScreen.date,
                        totalDailyAmount = parsedScreen.totalDailyAmount
                    )
                )

                logger(LogLevel.DEBUG) {
                    "Got TDD history entry ${parsedScreen.index} / ${parsedScreen.totalNumEntries} ; " +
                    "date = ${parsedScreen.date} ; " +
                    "TDD = ${parsedScreen.totalDailyAmount.toStringWithDecimal(3)}"
                }

                tddHistoryProgressReporter.setCurrentProgressStage(
                    RTCommandProgressStage.FetchingTDDHistory(parsedScreen.index, parsedScreen.totalNumEntries)
                )

                return@longPressRTButtonUntil if (parsedScreen.index >= parsedScreen.totalNumEntries)
                    LongPressRTButtonsCommand.ReleaseButton
                else
                    LongPressRTButtonsCommand.ContinuePressingButton
            }

            return@executeCommand tddHistoryEntries
        } catch (e: Exception) {
            tddHistoryProgressReporter.setCurrentProgressStage(BasicProgressStage.Aborted)
            throw e
        }
    }

    /**
     * Updates the value of [statusFlow].
     *
     * This can be called by the user in the [State.SUSPENDED] and [State.READY_FOR_COMMANDS]
     * states. Additionally, the status is automatically updated by [connect]
     * and after [deliverBolus] finishes (both if bolus delivery succeeds and
     * if an exception is thrown by that function). This reads information from
     * the main screen and the quickinfo screen, so it should not be called more
     * than necessary, since reading remote terminal screens takes some time.
     *
     * @throws IllegalStateException if the current state is not
     *   [State.SUSPENDED] or [State.READY_FOR_COMMANDS].
     * @throws AlertScreenException if alerts occurs during this call, and
     *   they aren't a W6 warning (those are handled by this function).
     */
    suspend fun updateStatus() = updateStatusImpl(
        allowExecutionWhileSuspended = true,
        allowExecutionWhileChecking = false,
        switchStatesIfNecessary = true
    )

    // The functions below are not part of the normal Pump API. They instead
    // are meant for interactive test applications whose UI contains widgets
    // for pressing the UP button etc. See PumpIO for a documentation of
    // what these functions do.

    suspend fun sendShortRTButtonPress(buttons: List<ApplicationLayer.RTButton>) {
        pumpIO.switchMode(PumpIO.Mode.REMOTE_TERMINAL)
        pumpIO.sendShortRTButtonPress(buttons)
    }

    suspend fun sendShortRTButtonPress(button: ApplicationLayer.RTButton) =
        sendShortRTButtonPress(listOf(button))

    suspend fun startLongRTButtonPress(buttons: List<ApplicationLayer.RTButton>, keepGoing: (suspend () -> Boolean)? = null) {
        pumpIO.switchMode(PumpIO.Mode.REMOTE_TERMINAL)
        pumpIO.startLongRTButtonPress(buttons, keepGoing)
    }

    suspend fun startLongRTButtonPress(button: ApplicationLayer.RTButton, keepGoing: (suspend () -> Boolean)? = null) =
        startLongRTButtonPress(listOf(button), keepGoing)

    suspend fun stopLongRTButtonPress() =
        pumpIO.stopLongRTButtonPress()

    suspend fun waitForLongRTButtonPressToFinish() =
        pumpIO.waitForLongRTButtonPressToFinish()

    suspend fun switchMode(mode: PumpIO.Mode) =
        pumpIO.switchMode(mode)

    /*************************************
     *** PRIVATE FUNCTIONS AND CLASSES ***
     *************************************/

    private inline fun <reified ProgressStageSubtype : ProgressStage> createBasalProgressReporter() =
        ProgressReporter(
            listOf(
                ProgressStageSubtype::class
            ),
            Unit
        ) { _: Int, _: Int, stage: ProgressStage, _: Unit ->
            // Basal profile access progress is determined by the single
            // stage in the reporter, which is SettingBasalProfile or
            // GettingBasalProfile. That stage contains how many basal
            // profile factors have been accessed so far, which is
            // suitable for a progress indicator, so we use that for
            // the overall progress.
            when (stage) {
                BasicProgressStage.Finished,
                BasicProgressStage.Aborted -> 1.0
                is RTCommandProgressStage.SettingBasalProfile ->
                    stage.numSetFactors.toDouble() / NUM_COMBO_BASAL_PROFILE_FACTORS.toDouble()
                is RTCommandProgressStage.GettingBasalProfile ->
                    stage.numSetFactors.toDouble() / NUM_COMBO_BASAL_PROFILE_FACTORS.toDouble()
                else -> 0.0
            }
        }

    private fun setState(newState: State) {
        val oldState = _stateFlow.value

        if (oldState == newState)
            return

        _stateFlow.value = newState

        logger(LogLevel.DEBUG) { "Setting Combo driver state:  old: $oldState  new: $newState" }
    }

    private suspend fun <T> executeCommand(
        pumpMode: PumpIO.Mode?,
        isIdempotent: Boolean,
        allowExecutionWhileSuspended: Boolean = false,
        allowExecutionWhileChecking: Boolean = false,
        block: suspend CoroutineScope.() -> T
    ): T {
        check(
            (stateFlow.value == State.READY_FOR_COMMANDS) ||
            (allowExecutionWhileSuspended && (stateFlow.value == State.SUSPENDED)) ||
            (allowExecutionWhileChecking && (stateFlow.value == State.CHECKING_PUMP))
        ) { "Cannot execute command in the ${stateFlow.value} state" }

        val previousState = stateFlow.value
        if (stateFlow.value != State.CHECKING_PUMP)
            setState(State.EXECUTING_COMMAND)

        try {
            // Verify that there have been no errors/warnings since the last time
            // a command was executed. The Combo is not capable of pushing a
            // notification to ComboCtl. Instead, ComboCtl has to check for the
            // presence of command mode error/warning flags and/or look for the
            // presence of alert screens manually.
            checkForAlerts()

            var retval: T? = null

            var needsToReconnect = false

            // Reset these to guarantee that the handleAlertScreenContent()
            // calls don't use stale states.
            dismissalCount = 0
            lastObservedAlertScreenContent = null

            // A command execution is attempted a number of times. That number
            // depends on whether it is an idempotent command. If it is, then
            // it is possible to retry multiple times if command execution
            // failed due to certain specific exceptions. (Any other exceptions
            // are just rethrown; no more attempts are made then.)
            var attemptNr = 0
            val maxNumAttempts = if (isIdempotent) NUM_IDEMPOTENT_COMMAND_DISPATCH_ATTEMPTS else 1
            var doAlertCheck = false
            var commandSucceeded = false

            while (!commandSucceeded && (attemptNr < maxNumAttempts)) {
                try {
                    if (needsToReconnect) {
                        // Wait a while before attempting to reconnect. IO failure
                        // typically happens due to Bluetooth problems (including
                        // non-technical ones like when the pump is out of reach)
                        // and pump specific cases like when the user presses a
                        // button on the pump and enables its local UI (this
                        // terminates the Bluetooth connection). In these cases,
                        // it is useful to wait a bit to give the pump and/or the
                        // Bluetooth stack some time to recover. This also
                        // prevents busy loops that use 100% CPU.
                        delay(DELAY_IN_MS_BETWEEN_COMMAND_DISPATCH_ATTEMPTS)
                        reconnect()
                        // Check for alerts right after reconnect since the earlier
                        // disconnect may have produced an alert. For example, if
                        // a TBR was being set, and the pump got disconnected, a
                        // W6 alert will have been triggered.
                        checkForAlerts()
                        needsToReconnect = false
                        logger(LogLevel.DEBUG) { "Pump successfully reconnected" }
                    }

                    if (pumpMode != null)
                        pumpIO.switchMode(pumpMode)

                    retval = coroutineScope {
                        block.invoke(this)
                    }

                    doAlertCheck = true

                    commandSucceeded = true
                } catch (e: CancellationException) {
                    // Do this check after cancelling, since when some commands
                    // are cancelled (like a TBR for example), warnings can appear.
                    doAlertCheck = true
                    throw e
                } catch (e: AlertScreenException) {
                    // We enter this catch block if any alert screens appear
                    // _during_ the command execution. (doAlertCheck is about
                    // alerts that happen _after_ command execution, like a W6
                    // that appears after setting a 100% TBR.) In such a case,
                    // the command is considered aborted, and we have to try again
                    // (if isIdempotent is set to true).
                    handleAlertScreen(e.alertScreenContent)
                } catch (e: TransportLayer.PacketReceiverException) {
                    val pumpTerminatedConnection = (e.cause is ApplicationLayer.ErrorCodeException) &&
                            (e.cause.appLayerPacket.command == ApplicationLayer.Command.CTRL_DISCONNECT)

                    // Packet receiver exceptions can happen for a number of reasons.
                    // To be on the safe side, we only try to reconnect if the exception
                    // happened due to the Combo terminating the connection on its end.

                    if (pumpTerminatedConnection) {
                        if (isIdempotent) {
                            logger(LogLevel.DEBUG) { "Pump terminated connection; will try to reconnect since this is an idempotent command" }
                            needsToReconnect = true
                        } else {
                            logger(LogLevel.DEBUG) {
                                "Pump terminated connection, but will not try to reconnect since this is a non-idempotent command"
                            }
                            throw e
                        }
                    } else
                        throw e
                } catch (e: ComboIOException) {
                    // IO exceptions typically happen because of connection failure.
                    // This includes cases like when the pump and phone are out of
                    // reach. Try to reconnect if this is an idempotent command.

                    if (isIdempotent) {
                        logger(LogLevel.DEBUG) { "Combo IO exception $e occurred; will try to reconnect since this is an idempotent command" }
                        needsToReconnect = true
                    } else {
                        // Don't bother if this command is not idempotent, since in that
                        // case, we can only perform one single attempt anyway.
                        logger(LogLevel.DEBUG) {
                            "Combo IO exception $e occurred, but will not try to reconnect since this is a non-idempotent command"
                        }
                        throw e
                    }
                } finally {
                    if (doAlertCheck) {
                        // Post-command check in case something went wrong
                        // and an alert screen appeared after the command ran.
                        // Most commonly, these are benign warning screens,
                        // especially W6, W7, W8.
                        // Using a NonCancellable context in case the command
                        // was aborted by cancellation (like a cancelled bolus).
                        // Without this context, the checkForAlerts() call would
                        // not actually do anything.
                        withContext(NonCancellable) {
                            checkForAlerts()
                        }
                    }
                }

                attemptNr++
            }

            if (commandSucceeded) {
                setState(previousState)
                // retval is non-null precisely when the command succeeded.
                return retval!!
            } else throw CommandExecutionAttemptsFailedException()
        } catch (e: AlertScreenException) {
            if (e.alertScreenContent is AlertScreenContent.Error) {
                // If we reach this point, then an alert screen with an error
                // code showed up. That screen was dismissed and an exception
                // was thrown to inform us about that error. Importantly, after
                // such an error screen, the Combo automatically switches to
                // its stopped (= suspended) state. And during this state,
                // the Combo suspends all insulin delivery, effectively behaving
                // like a 0% TBR. Report this state as such to the caller
                // via onEvent().
                reportPumpSuspendedTbr()
            }
            setState(State.ERROR)
            throw e
        } catch (t: Throwable) {
            setState(State.ERROR)
            throw t
        }
    }

    // This is separate from updateStatus() to prevent that call from
    // being made during the CHECKING state by the user.
    // Internally, we sometimes have to update the status during that
    // state, and this is why this function exists - internal status
    // updates are then done by calling this instead (with
    // allowExecutionWhileChecking set to true).
    private suspend fun updateStatusImpl(
        allowExecutionWhileSuspended: Boolean,
        allowExecutionWhileChecking: Boolean,
        switchStatesIfNecessary: Boolean
    ) = executeCommand(
        pumpMode = PumpIO.Mode.REMOTE_TERMINAL,
        isIdempotent = true,
        allowExecutionWhileSuspended = allowExecutionWhileSuspended,
        allowExecutionWhileChecking = allowExecutionWhileChecking
    ) {
        updateStatusByReadingMainAndQuickinfoScreens(switchStatesIfNecessary)
    }

    private suspend fun checkForAlerts() {
        // First check the error/warning status flags in the command
        // mode. Only look at the RT screen if at least one of these
        // flags is set. This is because retrieving the flags is done
        // very quickly - much quicker than reading the RT screen.
        // Most of the time, there's no alert, so this allows us to
        // only perform the slower RT screen operation if it is
        // really necessary.

        pumpIO.switchMode(PumpIO.Mode.COMMAND)
        val pumpStatus = pumpIO.readCMDErrorWarningStatus()

        if (pumpStatus.warningOccurred || pumpStatus.errorOccurred) {
            pumpIO.switchMode(PumpIO.Mode.REMOTE_TERMINAL)

            handleAlertScreen()
        }
    }

    private suspend fun handleAlertScreen() {
        // Recreate the parsedScreenFlow for duration of this function. This
        // is done to (a) make sure the parsedScreenFlow does not process
        // alert screens (since we are processing one here already) and (b)
        // prevent another flow from processing those. That's why we don't
        // just create a custom parsedScreenFlow in parallel to the normal
        // one, and instead temporarily replace the normal parsedScreenFlow.
        parsedScreenFlow = rtNavigationContext.getParsedScreenFlow(processAlertScreens = false)

        try {
            parsedScreenFlow.first { parsedScreen ->
                when (parsedScreen) {
                    is ParsedScreen.AlertScreen -> {
                        logger(LogLevel.DEBUG) {
                            "Got alert screen with content ${parsedScreen.content}"
                        }
                        handleAlertScreen(parsedScreen.content)
                        false
                    }
                    else -> true
                }
            }
        } finally {
            parsedScreenFlow = rtNavigationContext.getParsedScreenFlow()
        }
    }

    private suspend fun handleAlertScreen(alertScreenContent: AlertScreenContent) {
        when (alertScreenContent) {
            // Alert screens blink. When the content is "blinked out",
            // the warning/error code is hidden, and the screen contents
            // cannot be recognized. We just ignore such blinked-out alert
            // screens, since they are not an error. The next time
            // handleAlertScreenContent() is called, we hopefully
            // get recognizable content.
            is AlertScreenContent.None -> Unit

            // Error screen contents always cause a rethrow since all error
            // screens are considered non-recoverable errors that must not
            // be ignored / dismissed. Instead, let the code fail by rethrowing
            // the exception. The user needs to check out the error manually.
            is AlertScreenContent.Error -> throw AlertScreenException(alertScreenContent)

            is AlertScreenContent.Warning -> {
                // Check if the alert screen content changed in case
                // several warnings appear one after the other. In
                // such a case, we need to reset the dismissal count
                // to be able to properly dismiss followup warnings.
                if (lastObservedAlertScreenContent != alertScreenContent) {
                    lastObservedAlertScreenContent = alertScreenContent
                    dismissalCount = 0
                }

                val warningCode = alertScreenContent.code

                // W1 is the "reservoir almost empty" warning. Notify the caller
                // about this, then dismiss it.
                // W2 is the "battery almost empty" warning. Notify the caller
                // about this, then dismiss it.
                // W6 informs about an aborted TBR.
                // W7 informs about a finished TBR. (This warning can be turned
                // off permanently through the Accu-Check 360 software, but
                // in case it wasn't turned off, we still handle it here.)
                // W8 informs about an aborted bolus.
                // W3 alerts that date and time need to be reviewed.
                // W6, W7, W8 are purely informational, and can be dismissed
                // and ignored.
                // Any other warnings are intentionally rethrown for safety.
                when (warningCode) {
                    1 -> onEvent(Event.ReservoirLow)
                    2 -> onEvent(Event.BatteryLow)
                    3, 6, 7, 8 -> Unit
                    else -> throw AlertScreenException(alertScreenContent)
                }

                // Warning screens are dismissed by pressing CHECK twice.
                // First time, the CHECK button press transitions the state
                // on that screen from alert to confirm. Second time, the
                // screen is finally dismissed. Due to the blinking screen
                // though, we might end up getting the warning screen more
                // than twice, so use a counter to not accidentally press
                // CHECK more than twice.
                if (dismissalCount < 2) {
                    logger(LogLevel.DEBUG) { "Dismissing W$warningCode by short-pressing CHECK" }
                    rtNavigationContext.shortPressButton(RTNavigationButton.CHECK)
                    dismissalCount++
                }

            }
        }
    }

    // Utility code to add a log line that specifically records
    // that this is a *re*connect attempt.
    private suspend fun reconnect() {
        logger(LogLevel.DEBUG) { "Reconnecting Combo with address ${bluetoothDevice.address}" }
        disconnect()
        connect()
    }

    // The block allows callers to perform their own processing for each
    // history delta entry, for example to check for unaccounted boluses.
    private fun scanHistoryDeltaForBolusToEmit(
        historyDelta: List<ApplicationLayer.CMDHistoryEvent>,
        block: (historyEntry: ApplicationLayer.CMDHistoryEvent) -> Unit = { }
    ) {
        var lastBolusId = 0L
        var lastBolusAmount = 0
        var lastBolusInfusionTimestamp: Instant? = null

        historyDelta.onEach { entry ->
            block(entry)

            val timestamp = entry.timestamp.toInstant(currentPumpUtcOffset!!)

            when (val detail = entry.detail) {
                is CMDHistoryEventDetail.QuickBolusRequested ->
                    onEvent(Event.QuickBolusRequested(
                        bolusId = entry.eventCounter,
                        timestamp = timestamp,
                        bolusAmount = detail.bolusAmount
                    ))
                is CMDHistoryEventDetail.QuickBolusInfused -> {
                    onEvent(Event.QuickBolusInfused(
                        bolusId = entry.eventCounter,
                        timestamp = timestamp,
                        bolusAmount = detail.bolusAmount
                    ))
                    lastBolusId = entry.eventCounter
                    lastBolusAmount = detail.bolusAmount
                    lastBolusInfusionTimestamp = timestamp
                }
                is CMDHistoryEventDetail.StandardBolusRequested ->
                    onEvent(Event.StandardBolusRequested(
                        bolusId = entry.eventCounter,
                        timestamp = timestamp,
                        manual = detail.manual,
                        bolusAmount = detail.bolusAmount
                    ))
                is CMDHistoryEventDetail.StandardBolusInfused -> {
                    onEvent(Event.StandardBolusInfused(
                        bolusId = entry.eventCounter,
                        timestamp = timestamp,
                        manual = detail.manual,
                        bolusAmount = detail.bolusAmount
                    ))
                    lastBolusId = entry.eventCounter
                    lastBolusAmount = detail.bolusAmount
                    lastBolusInfusionTimestamp = timestamp
                }
                is CMDHistoryEventDetail.ExtendedBolusStarted ->
                    onEvent(Event.ExtendedBolusStarted(
                        bolusId = entry.eventCounter,
                        timestamp = timestamp,
                        totalBolusAmount = detail.totalBolusAmount,
                        totalDurationMinutes = detail.totalDurationMinutes
                    ))
                is CMDHistoryEventDetail.ExtendedBolusEnded -> {
                    onEvent(Event.ExtendedBolusEnded(
                        bolusId = entry.eventCounter,
                        timestamp = timestamp,
                        totalBolusAmount = detail.totalBolusAmount,
                        totalDurationMinutes = detail.totalDurationMinutes
                    ))
                }
                is CMDHistoryEventDetail.MultiwaveBolusStarted ->
                    onEvent(Event.MultiwaveBolusStarted(
                        bolusId = entry.eventCounter,
                        timestamp = timestamp,
                        totalBolusAmount = detail.totalBolusAmount,
                        immediateBolusAmount = detail.immediateBolusAmount,
                        totalDurationMinutes = detail.totalDurationMinutes
                    ))
                is CMDHistoryEventDetail.MultiwaveBolusEnded -> {
                    onEvent(Event.MultiwaveBolusEnded(
                        bolusId = entry.eventCounter,
                        timestamp = timestamp,
                        totalBolusAmount = detail.totalBolusAmount,
                        immediateBolusAmount = detail.immediateBolusAmount,
                        totalDurationMinutes = detail.totalDurationMinutes
                    ))
                }
                else -> Unit
            }
        }

        lastBolusInfusionTimestamp?.let { timestamp ->
            _lastBolusFlow.value = LastBolus(
                bolusId = lastBolusId,
                bolusAmount = lastBolusAmount,
                timestamp = timestamp
            )
        }
    }

    private suspend fun performOnConnectChecks() {
        require(currentPumpUtcOffset != null)

        // Read history delta, quickinfo etc. as a preparation
        // for further evaluating the current pump state.
        val historyDelta = fetchHistoryDelta()

        // This reads information from the main screen and quickinfo screen.
        // Don't switch states. The caller does that.
        updateStatusImpl(
            allowExecutionWhileSuspended = true,
            allowExecutionWhileChecking = true,
            switchStatesIfNecessary = false
        )
        // Read the timestamp when the update is read to be able to determine
        // below what factor of the current basal profile corresponds to the
        // factor we see on screen. This is distinct from the other datetimes
        // we fetch later below, since several operations are in between here
        // and there, and these operations can take some time to finish.
        val timestampOfStatusUpdate = pumpIO.readCMDDateTime()

        // Scan history delta for unaccounted bolus(es). Report all discovered ones.
        // If at least one was found, throw an exception for safety reasons.

        var foundUnaccountedBolus = false

        scanHistoryDeltaForBolusToEmit(historyDelta) { entry ->
            foundUnaccountedBolus = foundUnaccountedBolus or entry.detail.isBolusDetail
        }

        if (foundUnaccountedBolus) {
            logger(LogLevel.ERROR) {
                "Unaccounted bolus(es) detected; cannot execute commands, " +
                "since commands may be issues based on incorrect IOB assumptions"
            }
            throw UnaccountedBolusDetectedException()
        }

        if (pumpSuspended) {
            // If the pump is suspended, no insulin is delivered. This behaves like
            // a 0% TBR. Announce such a "fake 0% TBR" via onEvent to allow the
            // caller to keep track of these no-delivery situations.
            reportPumpSuspendedTbr()
        } else {
            // Get the current TBR state as recorded in the pump state store, then
            // retrieve the current status that was updated above by the updateStatusImpl()
            // call. The status gives us information about what's on the main screen.
            // If a TBR is currently ongoing, it will show up on the main screen.
            val currentTbrState = pumpStateStore.getCurrentTbrState(bluetoothDevice.address)
            val status = statusFlow.value
            require(status != null)

            // Handle the following four cases:
            //
            // 1. currentTbrState is TbrStarted, and no TBR information is shown on the main screen.
            //    Since currentTbrState indicates a started TBR, and the main screen no longer shows an
            //    active TBR, this means that the TBR ended some time ago. Announce the ended TBR as an
            //    event, then set currentTbrState to NoTbrOngoing.
            // 2. currentTbrState is TbrStarted, and TBR information is shown on the main screen.
            //    Do nothing in that case, since we know the TBR started earlier and is still ongoing,
            //    so nothing needs to be done.
            // 3. currentTbrState is NoTbrOngoing, and no TBR information is shown on the main screen.
            //    Do nothing in that case, since we already know that no TBR was ongoing.
            // 4. currentTbrState is NoTbrOngoing, and TBR information is shown on the main screen.
            //    This is an error - a TBR is ongoing that we don't know about. We did not start it!
            //    End it immediately, then throw an UnknownTbrDetectedException, since we cannot
            //    just continue due to the situation now being unclear. Ideally, this exception leads
            //    to an alert shown on the UI. Also, in this case, we do a hard TBR cancel, which
            //    triggers W6, but this is an unusual situation, so the extra vibration is okay.
            when (currentTbrState) {
                is CurrentTbrState.TbrStarted -> {
                    if (status.tbrPercentage == 100) {
                        // TODO: Once kotlinx.datetime is updated to version 0.3.2 or later,
                        // replace this with duration based arithmetic to calculate the
                        // end TBR timestamp.
                        val endTbrTimestamp = Instant.fromEpochMilliseconds(
                            currentTbrState.tbr.timestamp.toEpochMilliseconds() +
                            currentTbrState.tbr.durationInMinutes * 60 * 1000
                        )

                        val tbr = Tbr(
                            timestamp = endTbrTimestamp,
                            percentage = currentTbrState.tbr.percentage,
                            durationInMinutes = currentTbrState.tbr.durationInMinutes
                        )
                        pumpStateStore.setCurrentTbrState(bluetoothDevice.address, CurrentTbrState.NoTbrOngoing)
                        onEvent(Event.TbrEnded(tbr))
                    }
                }

                is CurrentTbrState.NoTbrOngoing -> {
                    if (status.tbrPercentage != 100) {
                        pumpIO.switchMode(PumpIO.Mode.REMOTE_TERMINAL)
                        setCurrentTbr(percentage = 100, durationInMinutes = 0)
                        throw UnknownTbrDetectedException(status.tbrPercentage)
                    }
                }
            }
        }

        // Make sure that (a) we have a known current basal profile and
        // (b) that any existing current basal profile is valid.
        if (currentBasalProfile == null) {
            logger(LogLevel.DEBUG) { "No current basal profile known; reading the pump's profile now"}
            currentBasalProfile = getBasalProfile()
        } else {
            // Compare the basal factor shown on the RT main screen against the current
            // factor from the basal profile. If we detect a mismatch, then the profile
            // that is stored in currentBasalProfile is incorrect and needs to be read
            // from the pump.
            val currentBasalRateFactor = statusFlow.value?.currentBasalRateFactor ?: 0
            if (currentBasalRateFactor != 0) {
                var currentFactorFromProfile = currentBasalProfile!![timestampOfStatusUpdate.hour]
                logger(LogLevel.DEBUG) {
                    "Current basal rate factor according to profile: $currentFactorFromProfile; current one" +
                            " according to pump: $currentBasalRateFactor"
                }

                // We don't read the profile from the pump right away, and instead retry
                // the check. This is because of an edge case: If we happen to check for
                // a mismatch at the same moment when the next hour starts and the pump
                // moves on to the next basal rate factor, we might have gotten a current
                // pump time that corresponds to one hour and a factor on screen that
                // corresponds to another hour, leading to a false mismatch. The solution
                // is to fetch again the pump's current datetime and retry the check.
                // If there is again a mismatch, then it is a real one.
                if (currentBasalRateFactor != currentFactorFromProfile) {
                    logger(LogLevel.DEBUG) { "Factors do not match; checking again"}

                    val currentPumpTime = pumpIO.readCMDDateTime()
                    currentFactorFromProfile = currentBasalProfile!![currentPumpTime.hour]

                    if (currentBasalRateFactor != currentFactorFromProfile) {
                        logger(LogLevel.DEBUG) { "Second check showed again a factor mismatch; reading basal profile"}
                        currentBasalProfile = getBasalProfile()
                    }
                }
            }
        }

        // Get current pump and system datetime _after_ all operations above
        // finished in case those operations take some time to finish. We need
        // the datetimes to be as current as possible for the checks below.
        val currentPumpLocalDateTime = pumpIO.readCMDDateTime()
        val currentPumpDateTime = currentPumpLocalDateTime.toInstant(currentPumpUtcOffset!!)
        val currentSystemDateTime = Clock.System.now()
        val currentSystemTimeZone = TimeZone.currentSystemDefault()
        val currentSystemUtcOffset =  currentSystemTimeZone.offsetAt(currentSystemDateTime)

        logger(LogLevel.DEBUG) { "History delta size: ${historyDelta.size}" }
        logger(LogLevel.DEBUG) { "Pump local datetime: $currentPumpLocalDateTime with UTC offset: $currentPumpDateTime" }
        logger(LogLevel.DEBUG) { "Current system datetime: $currentSystemDateTime" }

        // The following checks update the UTC offset in the pump state and
        // the datetime in the pump. This is done *after* all the checks above
        // because all the timestamps that we read from the pump's history delta
        // used a localtime that was tied to the current UTC offset that is
        // stored in the pump state. The entry.timestamp.toInstant() above must
        // use this current UTC offset to produce correct results. This is
        // particularly important during daylight savings changes. Only *after*
        // the Instant timestamps were all created we can proceed and update the
        // pump state's UTC offset.
        // TBRs are not affected by this, because the TBR info we store in the
        // pump state is already stored as an Instant, so it stores the timezone
        // offset along with the actual timestamp.
        // For the same reason, we *first* update the pump's datetime (if there
        // is a deviation from the system datetime) and *then* update the UTC
        // offset. The pump is still running with the localtime that is tied
        // to the old UTC offset.

        // Check if the system's current datetime and the pump's are at least
        // 2 minutes apart. If so, update the pump's current datetime.
        // We use a threshold of 2 minutes (= 120 seconds) since (a) the
        // pump datetime can only be set with a granularity at the minute
        // level (while getting its current datetime returns seconds), and
        // (b) setting datetime takes a while because it has to be done
        // via the RT mode. Having this threshold avoids too frequent
        // pump datetime updates (which, as said, are rather slow).
        // TODO: Once kotlinx.datetime is updated to version 0.3.2 or later,
        // replace this epochSeconds based arithmetic with a Duration based one,
        // which is marked as stable starting from that version.
        if ((currentSystemDateTime.epochSeconds - currentPumpDateTime.epochSeconds).absoluteValue >= 120) {
            logger(LogLevel.INFO) {
                "Current system datetime differs from pump's: system datetime: $currentSystemDateTime; " +
                "pump datetime: $currentPumpDateTime; updating pump datetime"
            }
            // TODO: If the difference between currentSystemDateTime and currentPumpDateTime
            // is such that the updatePumpDateTime() is likely to take a while, add an
            // offset to currentSystemDateTime in the updatePumpDateTime() call to
            // account for that while and reduce the likelihood of having to adjust
            // the datetime later on again. Try to do this after kotlinx.datetime is
            // updated to version 0.3.2 or later, since doing that with Duration
            // based arithmetic is the best and simplest approach.
            updatePumpDateTime(currentSystemDateTime.toLocalDateTime(currentSystemTimeZone))
        }

        // Check if the pump's current UTC offset matches that of the system.

        if (currentSystemUtcOffset != currentPumpUtcOffset!!) {
            logger(LogLevel.INFO) {
                "System UTC offset differs from pump's; system timezone: $currentSystemTimeZone; " +
                "system UTC offset: $currentSystemUtcOffset; pump state UTC offset: ${currentPumpUtcOffset!!}; " +
                "updating pump state"
            }
            pumpStateStore.setCurrentUtcOffset(bluetoothDevice.address, currentSystemUtcOffset)
            currentPumpUtcOffset = currentSystemUtcOffset
        }
    }

    private suspend fun fetchHistoryDelta(): List<ApplicationLayer.CMDHistoryEvent> {
        pumpIO.switchMode(PumpIO.Mode.COMMAND)
        return pumpIO.getCMDHistoryDelta()
    }

    private suspend fun getBasalProfile(): BasalProfile = executeCommand(
        pumpMode = PumpIO.Mode.REMOTE_TERMINAL,
        isIdempotent = true,
        // Allow this since getBasalProfile can be called by connect() during the pump checks.
        allowExecutionWhileChecking = true
    ) {
        getBasalProfileReporter.reset(Unit)

        getBasalProfileReporter.setCurrentProgressStage(RTCommandProgressStage.GettingBasalProfile(0))

        try {
            val basalProfileFactors = MutableList(NUM_COMBO_BASAL_PROFILE_FACTORS) { -1 }

            navigateToRTScreen(rtNavigationContext, ParsedScreen.BasalRateFactorSettingScreen::class)

            var numObservedScreens = 0
            var numRetrievedFactors = 0

            // Do a long RT MENU button press to quickly navigate
            // through all basal profile factors, keeping count on
            // all observed screens and all retrieved factors to
            // be able to later check if all factors were observed.
            longPressRTButtonUntil(rtNavigationContext, RTNavigationButton.MENU) { parsedScreen ->
                if (parsedScreen !is ParsedScreen.BasalRateFactorSettingScreen) {
                    logger(LogLevel.ERROR) { "Got a non-profile screen ($parsedScreen)" }
                    throw UnexpectedRTScreenException(
                        ParsedScreen.BasalRateFactorSettingScreen::class,
                        parsedScreen::class
                    )
                }

                numObservedScreens++

                val factorIndexOnScreen = parsedScreen.beginTime.hour

                // numUnits null means the basal profile factor
                // is currently not shown due to blinking.
                if (parsedScreen.numUnits == null)
                    return@longPressRTButtonUntil LongPressRTButtonsCommand.ContinuePressingButton

                // If the factor in the profile is >= 0,
                // it means it was already read earlier.
                if (basalProfileFactors[factorIndexOnScreen] >= 0)
                    return@longPressRTButtonUntil LongPressRTButtonsCommand.ContinuePressingButton

                val factor = parsedScreen.numUnits
                basalProfileFactors[factorIndexOnScreen] = factor
                logger(LogLevel.DEBUG) { "Got basal profile factor #$factorIndexOnScreen : $factor" }

                getBasalProfileReporter.setCurrentProgressStage(
                    RTCommandProgressStage.GettingBasalProfile(numRetrievedFactors)
                )

                numRetrievedFactors++

                return@longPressRTButtonUntil if (numObservedScreens >= NUM_COMBO_BASAL_PROFILE_FACTORS)
                    LongPressRTButtonsCommand.ReleaseButton
                else
                    LongPressRTButtonsCommand.ContinuePressingButton
            }

            // Failsafe in the unlikely case that the longPressRTButtonUntil()
            // call above skipped over some basal profile factors. In such
            // a case, numRetrievedFactors will be less than 24 (the value of
            // NUM_COMBO_BASAL_PROFILE_FACTORS).
            // The corresponding items in the basalProfile int list will be set to
            // -1, since those items will have been skipped as well. Therefore,
            // for each negative item, revisit the corresponding screen.
            if (numRetrievedFactors < NUM_COMBO_BASAL_PROFILE_FACTORS) {
                for (index in basalProfileFactors.indices) {
                    // We are only interested in those entries that have been
                    // skipped. Those entries are set to their initial value (-1).
                    if (basalProfileFactors[index] >= 0)
                        continue

                    logger(LogLevel.DEBUG) { "Re-reading missing basal profile factor $index" }

                    shortPressRTButtonsUntil(rtNavigationContext) { parsedScreen ->
                        if (parsedScreen !is ParsedScreen.BasalRateFactorSettingScreen) {
                            logger(LogLevel.ERROR) { "Got a non-profile screen ($parsedScreen)" }
                            throw UnexpectedRTScreenException(
                                ParsedScreen.BasalRateFactorSettingScreen::class,
                                parsedScreen::class
                            )
                        }

                        val factorIndexOnScreen = parsedScreen.beginTime.hour

                        if (factorIndexOnScreen == index) {
                            // Do nothing if the factor is currently not
                            // shown due to blinking.  Eventually, the
                            // factor becomes visible again.
                            val factor = parsedScreen.numUnits ?: return@shortPressRTButtonsUntil ShortPressRTButtonsCommand.DoNothing

                            basalProfileFactors[index] = factor
                            logger(LogLevel.DEBUG) { "Got basal profile factor #$index : $factor" }

                            // We got the factor, so we can stop short-pressing the RT button.
                            return@shortPressRTButtonsUntil ShortPressRTButtonsCommand.Stop
                        } else {
                            // This is not the correct basal profile factor, so keep
                            // navigating through them to find the correct factor.
                            return@shortPressRTButtonsUntil ShortPressRTButtonsCommand.PressButton(
                                RTNavigationButton.MENU)
                        }
                    }

                    getBasalProfileReporter.setCurrentProgressStage(
                        RTCommandProgressStage.GettingBasalProfile(numRetrievedFactors)
                    )
                    numRetrievedFactors++
                }
            }

            // All factors retrieved. Press CHECK once to get back to the total
            // basal rate screen, and then CHECK again to return to the main menu.

            rtNavigationContext.shortPressButton(RTNavigationButton.CHECK)
            waitUntilScreenAppears(rtNavigationContext, ParsedScreen.BasalRateTotalScreen::class)

            rtNavigationContext.shortPressButton(RTNavigationButton.CHECK)
            waitUntilScreenAppears(rtNavigationContext, ParsedScreen.MainScreen::class)

            getBasalProfileReporter.setCurrentProgressStage(BasicProgressStage.Finished)

            return@executeCommand BasalProfile(basalProfileFactors)
        } catch (e: Exception) {
            getBasalProfileReporter.setCurrentProgressStage(BasicProgressStage.Aborted)
            throw e
        }
    }

    // NOTE: The reportPumpSuspendedTbr() and reportStartedTbr() functions
    // do NOT call setCurrentTbr() themselves. They just report TBR changes,
    // and do nothing else.

    // If the pump is suspended, there is no insulin delivery. Model this
    // as a 0% TBR that started just now and lasts for 15 minutes.
    private fun reportPumpSuspendedTbr() =
        reportStartedTbr(Tbr(timestamp = Clock.System.now(), percentage = 0, durationInMinutes = 15))

    private fun reportStartedTbr(tbr: Tbr) {
        // If a TBR is already ongoing, it will be aborted. We have to
        // take this into account here, and report the old TBR as ended.
        reportOngoingTbrAsStopped()

        pumpStateStore.setCurrentTbrState(bluetoothDevice.address, CurrentTbrState.TbrStarted(tbr))
        onEvent(Event.TbrStarted(tbr))
    }

    private fun reportOngoingTbrAsStopped() {
        val currentTbrState = pumpStateStore.getCurrentTbrState(bluetoothDevice.address)
        if (currentTbrState is CurrentTbrState.TbrStarted) {
            // In a TemporaryBasalRateEnded event, the timestamp indicates the
            // time when a TBR ended. The ongoing TBR we know of may already have
            // expired. If so, we have to be careful to use the correct timestamp.
            // Compare the duration between the TBR start and now with the duration
            // as indicated by the TBR's durationInMinutes field. If the duration
            // between TBR start and now is longer than durationInMinutes, then
            // the TBR ended a while ago, and the timestamp has to reflect that,
            // meaning that using the current time as the timestamp would be wrong
            // in this case. If however the duration between TBR start and now
            // is _shorter_ than durationInMinutes, it means that we stopped TBR
            // before its planned end, so using the current time as timestamp
            // is the correct approach then.
            //
            // TODO: Once kotlinx.datetime is updated to version 0.3.2 or later,
            // replace this with duration based arithmetic to calculate the
            // end TBR timestamp.
            val now = Clock.System.now()
            val tbr = currentTbrState.tbr
            val timestamp = if ((now.epochSeconds - tbr.timestamp.epochSeconds) > (tbr.durationInMinutes * 60))
                Instant.fromEpochMilliseconds(tbr.timestamp.toEpochMilliseconds() + tbr.durationInMinutes * 60 * 1000)
            else
                now

            onEvent(Event.TbrEnded(Tbr(
                timestamp = timestamp,
                percentage = tbr.percentage,
                durationInMinutes = tbr.durationInMinutes
            )))
        }
    }

    private suspend fun updatePumpDateTime(
        newPumpLocalDateTime: LocalDateTime
    ) = executeCommand(
        pumpMode = PumpIO.Mode.REMOTE_TERMINAL,
        isIdempotent = true,
        allowExecutionWhileSuspended = true,
        allowExecutionWhileChecking = true
    ) {
        setDateTimeProgressReporter.reset(Unit)

        try {
            setDateTimeProgressReporter.setCurrentProgressStage(RTCommandProgressStage.SettingDateTimeHour)

            // Navigate from our current location to the first screen - the hour screen.
            navigateToRTScreen(rtNavigationContext, ParsedScreen.TimeAndDateSettingsHourScreen::class)
            adjustQuantityOnScreen(rtNavigationContext, newPumpLocalDateTime.hour, cyclicQuantityRange = 24) { parsedScreen ->
                (parsedScreen as ParsedScreen.TimeAndDateSettingsHourScreen).hour
            }

            // From here on, we just need to press MENU to move to the next datetime screen.

            setDateTimeProgressReporter.setCurrentProgressStage(RTCommandProgressStage.SettingDateTimeMinute)
            rtNavigationContext.shortPressButton(RTNavigationButton.MENU)
            waitUntilScreenAppears(rtNavigationContext, ParsedScreen.TimeAndDateSettingsMinuteScreen::class)
            adjustQuantityOnScreen(rtNavigationContext, newPumpLocalDateTime.minute, cyclicQuantityRange = 60) { parsedScreen ->
                (parsedScreen as ParsedScreen.TimeAndDateSettingsMinuteScreen).minute
            }

            setDateTimeProgressReporter.setCurrentProgressStage(RTCommandProgressStage.SettingDateTimeYear)
            rtNavigationContext.shortPressButton(RTNavigationButton.MENU)
            waitUntilScreenAppears(rtNavigationContext, ParsedScreen.TimeAndDateSettingsYearScreen::class)
            adjustQuantityOnScreen(rtNavigationContext, newPumpLocalDateTime.year) { parsedScreen ->
                (parsedScreen as ParsedScreen.TimeAndDateSettingsYearScreen).year
            }

            setDateTimeProgressReporter.setCurrentProgressStage(RTCommandProgressStage.SettingDateTimeMonth)
            rtNavigationContext.shortPressButton(RTNavigationButton.MENU)
            waitUntilScreenAppears(rtNavigationContext, ParsedScreen.TimeAndDateSettingsMonthScreen::class)
            adjustQuantityOnScreen(rtNavigationContext, newPumpLocalDateTime.monthNumber) { parsedScreen ->
                (parsedScreen as ParsedScreen.TimeAndDateSettingsMonthScreen).month
            }

            setDateTimeProgressReporter.setCurrentProgressStage(RTCommandProgressStage.SettingDateTimeDay)
            rtNavigationContext.shortPressButton(RTNavigationButton.MENU)
            waitUntilScreenAppears(rtNavigationContext, ParsedScreen.TimeAndDateSettingsDayScreen::class)
            adjustQuantityOnScreen(rtNavigationContext, newPumpLocalDateTime.dayOfMonth) { parsedScreen ->
                (parsedScreen as ParsedScreen.TimeAndDateSettingsDayScreen).day
            }

            // Everything configured. Press CHECK to confirm the new datetime.
            rtNavigationContext.shortPressButton(RTNavigationButton.CHECK)

            setDateTimeProgressReporter.setCurrentProgressStage(BasicProgressStage.Finished)
        } catch (e: Exception) {
            setDateTimeProgressReporter.setCurrentProgressStage(BasicProgressStage.Aborted)
            throw e
        }
    }

    private suspend fun setCurrentTbr(
        percentage: Int, durationInMinutes: Int
    ) {
        setTbrProgressReporter.reset(Unit)

        setTbrProgressReporter.setCurrentProgressStage(RTCommandProgressStage.SettingTBRPercentage(0))

        try {
            var initialQuantityDistance: Int? = null

            // First, set the TBR percentage.
            navigateToRTScreen(rtNavigationContext, ParsedScreen.TemporaryBasalRatePercentageScreen::class)
            adjustQuantityOnScreen(rtNavigationContext, percentage) {
                val currentPercentage = (it as ParsedScreen.TemporaryBasalRatePercentageScreen).percentage

                // Calculate the progress out of the "distance" from the
                // current percentage to the target percentage. As we adjust
                // the quantity, that "distance" shrinks. When it is 0, we
                // consider the adjustment to be complete, or in other words,
                // the settingProgress to be at 100.
                // In the corner case where the current percentage displayed
                // on screen is already the target percentage, we just set
                // settingProgress straight to 100.
                if (currentPercentage != null) {
                    if (initialQuantityDistance == null) {
                        initialQuantityDistance = currentPercentage - percentage
                    } else {
                        val settingProgress = if (initialQuantityDistance == 0) {
                            100
                        } else {
                            val currentQuantityDistance = currentPercentage - percentage
                            (100 - currentQuantityDistance * 100 / initialQuantityDistance!!).coerceIn(0, 100)
                        }
                        setTbrProgressReporter.setCurrentProgressStage(RTCommandProgressStage.SettingTBRPercentage(settingProgress))
                    }
                }

                currentPercentage
            }

            // If the percentage is 100%, we are done (and navigating to
            // the duration screen is not possible). Otherwise, continue.
            if (percentage != 100) {
                initialQuantityDistance = null

                setTbrProgressReporter.setCurrentProgressStage(RTCommandProgressStage.SettingTBRDuration(0))

                navigateToRTScreen(rtNavigationContext, ParsedScreen.TemporaryBasalRateDurationScreen::class)

                adjustQuantityOnScreen(rtNavigationContext, durationInMinutes) {
                    val currentDuration = (it as ParsedScreen.TemporaryBasalRateDurationScreen).durationInMinutes

                    // Do the settingProgress calculation just like before when setting the percentage.
                    if (currentDuration != null) {
                        if (initialQuantityDistance == null) {
                            initialQuantityDistance = currentDuration - durationInMinutes
                        } else {
                            val settingProgress = if (initialQuantityDistance == 0) {
                                100
                            } else {
                                val currentQuantityDistance = currentDuration - durationInMinutes
                                (100 - currentQuantityDistance * 100 / initialQuantityDistance!!).coerceIn(0, 100)
                            }
                            setTbrProgressReporter.setCurrentProgressStage(RTCommandProgressStage.SettingTBRDuration(settingProgress))
                        }
                    }

                    currentDuration
                }
            }

            setTbrProgressReporter.setCurrentProgressStage(RTCommandProgressStage.SettingTBRDuration(100))

            // TBR set. Press CHECK to confirm it and exit back to the main menu.
            rtNavigationContext.shortPressButton(RTNavigationButton.CHECK)

            setTbrProgressReporter.setCurrentProgressStage(BasicProgressStage.Finished)
        } catch (e: Exception) {
            setTbrProgressReporter.setCurrentProgressStage(BasicProgressStage.Aborted)
            throw e
        }
    }

    private suspend fun updateStatusByReadingMainAndQuickinfoScreens(switchStatesIfNecessary: Boolean) {
        val mainScreen = navigateToRTScreen(rtNavigationContext, ParsedScreen.MainScreen::class)

        val mainScreenContent = when (mainScreen) {
            is ParsedScreen.MainScreen -> mainScreen.content
            else -> throw NoUsableRTScreenException()
        }

        val quickinfoScreen = navigateToRTScreen(rtNavigationContext, ParsedScreen.QuickinfoMainScreen::class)

        val quickinfo = when (quickinfoScreen) {
            is ParsedScreen.QuickinfoMainScreen -> {
                // After parsing the quickinfo screen, exit back to the main screen by pressing BACK.
                rtNavigationContext.shortPressButton(RTNavigationButton.BACK)
                quickinfoScreen.quickinfo
            }
            else -> throw NoUsableRTScreenException()
        }

        _statusFlow.value = when (mainScreenContent) {
            is MainScreenContent.Normal -> {
                pumpSuspended = false
                Status(
                    availableUnitsInReservoir = quickinfo.availableUnits,
                    activeBasalRateNumber = mainScreenContent.activeBasalRateNumber,
                    currentBasalRateFactor = mainScreenContent.currentBasalRateFactor,
                    tbrOngoing = false,
                    remainingTbrDurationInMinutes = 0,
                    tbrPercentage = 100,
                    reservoirState = quickinfo.reservoirState,
                    batteryState = mainScreenContent.batteryState
                )
            }

            is MainScreenContent.Stopped -> {
                pumpSuspended = true
                Status(
                    availableUnitsInReservoir = quickinfo.availableUnits,
                    activeBasalRateNumber = 0,
                    // The stopped screen does not show any basal rate
                    // factor. Set this to 0 to let the caller know
                    // that the current factor is unknown.
                    currentBasalRateFactor = 0,
                    tbrOngoing = false,
                    remainingTbrDurationInMinutes = 0,
                    tbrPercentage = 0,
                    reservoirState = quickinfo.reservoirState,
                    batteryState = mainScreenContent.batteryState
                )
            }

            is MainScreenContent.Tbr -> {
                pumpSuspended = false
                Status(
                    availableUnitsInReservoir = quickinfo.availableUnits,
                    activeBasalRateNumber = mainScreenContent.activeBasalRateNumber,
                    // The main screen shows the basal rate factor with the TBR
                    // percentage applied (= multiplied) to it. Undo this operation
                    // to get the original basal rate factor. We can't undo a
                    // multiplication by zero though, so just set the rate to 0
                    // if TBR is 0%.
                    currentBasalRateFactor = if (mainScreenContent.tbrPercentage != 0)
                        mainScreenContent.currentBasalRateFactor * 100 / mainScreenContent.tbrPercentage
                    else
                        0,
                    tbrOngoing = true,
                    remainingTbrDurationInMinutes = mainScreenContent.remainingTbrDurationInMinutes,
                    tbrPercentage = mainScreenContent.tbrPercentage,
                    reservoirState = quickinfo.reservoirState,
                    batteryState = mainScreenContent.batteryState
                )
            }
        }

        if (switchStatesIfNecessary) {
            // See if the pump was suspended and now isn't anymore, or vice versa.
            // In these cases, we must update the current state.
            if (pumpSuspended && (stateFlow.value == State.READY_FOR_COMMANDS))
                setState(State.SUSPENDED)
            else if (!pumpSuspended && (stateFlow.value == State.SUSPENDED))
                setState(State.READY_FOR_COMMANDS)
        }
    }
}
