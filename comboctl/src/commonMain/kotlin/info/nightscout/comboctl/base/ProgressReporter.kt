package info.nightscout.comboctl.base

import kotlin.reflect.KClassifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private val logger = Logger.get("Pump")

/**
 * Base class for specifying a stage for a [ProgressReporter] instance.
 *
 * @property id ID string, useful for serialization and localization.
 */
open class ProgressStage(val id: String)

/**
 * Progress stages for basic operations.
 */
object BasicProgressStage {
    // Fundamental stages, used for starting / ending a progress sequence.
    object Idle : ProgressStage("idle")
    object Aborted : ProgressStage("aborted")
    object Finished : ProgressStage("finished")

    // Connection related stages.
    object StartingConnectionSetup : ProgressStage("startingConnectionSetup")
    /**
     * Bluetooth connection establishing stage.
     *
     * The connection setup may require several attempts on some platforms.
     * If the number of attempts so far exceeds the total number, the
     * connection attempt fails.
     *
     * @property currentAttemptNr Current attempt number, starting at 1.
     * @property totalNumAttempts Total number of attempts that will be done.
     */
    data class EstablishingBtConnection(val currentAttemptNr: Int, val totalNumAttempts: Int) :
        ProgressStage("establishingBtConnection")
    object PerformingConnectionHandshake : ProgressStage("performingConnectionHandshake")

    // Pairing related stages.
    object ComboPairingStarting : ProgressStage("comboPairingStarting")
    object ComboPairingKeyAndPinRequested : ProgressStage("comboPairingKeyAndPinRequested")
    object ComboPairingFinishing : ProgressStage("comboPairingFinishing")
}

/**
 * Report with updated progress information.
 *
 * @property stepNumber Current progress step number, starting at 0.
 *           If stepNumber == numSteps, then the stage is always
 *           [BasicProgressStage.Finished] or [BasicProgressStage.Aborted].
 * @property numSteps Total number of steps in the progress sequence.
 * @property stage Information about the current stage.
 */
data class ProgressReport(val stepNumber: Int, val numSteps: Int, val stage: ProgressStage)

/**
 * Class for reporting progress updates.
 *
 * "Progress" is defined here as a planned sequence of [ProgressStage] instances.
 * These stages describe information about the current progress. Stage instances
 * can contain varying information, such as the index of the factor that is
 * currently being set in a basal profile, or the IUs of a bolus that were
 * administered so far.
 *
 * A sequence always begins with [BasicProgressStage.Idle] and ends with either
 * [BasicProgressStage.Aborted] or [BasicProgressStage.Finished]. These are special
 * in that they are never explicitly specified in the sequence. [BasicProgressStage.Idle]
 * is always set as the current flow value when the reporter is created and when
 * [reset] is called. The other two are passed to [setCurrentProgressStage], which
 * then immediately forwards them in a [ProgressReport] instance, with that instance's
 * step number set to [numSteps] (since both of these stages define the end of a sequence).
 *
 * In code that reports progress, the [setCurrentProgressStage] function is called
 * to deliver updates to the reporter, which then forwards that update to subscribers
 * of the [progressFlow]. The reporter takes care of checking where in the sequence
 * that stage is. The index of the stage in the sequence is called a "step". The
 * size of the sequence equals [numSteps].
 *
 * Updates to the flow are communicated as [ProgressReport] instances. They provide
 * subscribers with the necessary information to show details about the current
 * stage and to compute a progress percentage (useful for GUI progress bar elements).
 *
 * Example of how to use this class:
 *
 * First, the reporter is instantiated, like this:
 *
 * ```
 * val reporter = ProgressReporter(listOf(
 *     BasicProgressStage.StartingConnectionSetup::class,
 *     BasicProgressStage.EstablishingBtConnection::class,
 *     BasicProgressStage.PerformingConnectionHandshake::class
 * ))
 * ```
 *
 * Code can then report an update like this:
 *
 * ```
 * reporter.setCurrentProgressStage(BasicProgressStage.EstablishingBtConnection(1, 4))
 * ```
 *
 * This will cause the reporter to publish a [ProgressReport] instance through its
 * [progressFlow], looking like this:
 *
 * ```
 * ProgressReport(stepNumber = 1, numSteps = 3, stage = BasicProgressStage.EstablishingBtConnection(1, 4))
 * ```
 *
 * This allows code to report progress without having to know what its current
 * step number is (so it does not have to concern itself about providing a correct
 * progress percentage). Also, that way, code that reports progress can be combined.
 * For example, if function A contains setCurrentProgressStage calls, then the
 * function that called A can continue to report progress. And, the setCurrentProgressStage
 * calls from A can also be used to report progress in an entirely different function.
 * One actual example is the progress reported when a Bluetooth connection is being
 * established. This is used both during pairing and when setting up a regular
 * connection, without having to write separate progress report code for both.
 *
 * @param plannedSequence The planned progress sequence, as a list of ProgressStage
 *        classes. This never contains [BasicProgressStage.Idle],
 *        [BasicProgressStage.Aborted], or [BasicProgressStage.Finished].
 */
class ProgressReporter(private val plannedSequence: List<KClassifier>) {
    private var currentStepNumber = 0

    private val mutableProgressFlow = MutableStateFlow(ProgressReport(0, plannedSequence.size, BasicProgressStage.Idle))

    /**
     * Flow for getting progress reports.
     */
    val progressFlow = mutableProgressFlow.asStateFlow()

    /**
     * Total number of steps in the sequence.
     */
    val numSteps = plannedSequence.size

    /**
     * Resets the reporter to its initial state.
     *
     * The flow's state will be set to a report whose stage is [BasicProgressStage.Idle].
     */
    fun reset() {
        currentStepNumber = 0
        mutableProgressFlow.value = ProgressReport(0, numSteps, BasicProgressStage.Idle)
    }

    /**
     * Sets the current stage and triggers an update via a [ProgressReport] instance through the [progressFlow].
     *
     * If the process that is being tracked by this reported was cancelled
     * or aborted due to an error, pass [BasicProgressStage.Aborted] as
     * the stage argument. This will trigger a report with the step number
     * set to the total number of steps (to signify that the work is over)
     * and the stage set to [BasicProgressStage.Aborted].
     *
     * If the process finished successfully, do the same as written above,
     * except using [BasicProgressStage.Finished] as the stage instead.
     *
     * @param stage Stage of the progress to report.
     */
    fun setCurrentProgressStage(stage: ProgressStage) {
        when (stage) {
            is BasicProgressStage.Finished,
            is BasicProgressStage.Aborted -> {
                currentStepNumber = numSteps
                mutableProgressFlow.value = ProgressReport(currentStepNumber, numSteps, stage)
                return
            }
            else -> Unit
        }

        if (stage::class != plannedSequence[currentStepNumber]) {
            // Search forward first. Typically, this succeeds, since stages
            // are reported in the order specified in the sequence.
            var succeedingStepNumber = plannedSequence.subList(currentStepNumber + 1, numSteps).indexOfFirst {
                stage::class == it
            }

            currentStepNumber = if (succeedingStepNumber == -1) {
                // Unusual case: An _earlier_ stage was reported. This is essentially
                // a backwards progress (= a regress?). It is not unthinkable that
                // this can happen, but it should be rare. In that case, we have
                // to search backwards in the sequence.
                val precedingStepNumber = plannedSequence.subList(0, currentStepNumber).indexOfFirst {
                    stage::class == it
                }

                // If the progress info was not found in the sequence, log this and exit.
                // Do not throw; a missing progress info ID in the sequence is not
                // a fatal error, so do not break the application because of it.
                if (precedingStepNumber == -1) {
                    logger(LogLevel.WARN) { "Progress stage \"$stage\" not found in stage sequence; not passing it to flow" }
                    return
                }

                precedingStepNumber
            } else {
                // Need to add (currentStepNumber + 1) as an offset, since the indexOfFirst
                // call returns indices that are based on the sub list, not the entire list.
                succeedingStepNumber + (currentStepNumber + 1)
            }
        }

        mutableProgressFlow.value = ProgressReport(currentStepNumber, numSteps, stage)
    }
}
