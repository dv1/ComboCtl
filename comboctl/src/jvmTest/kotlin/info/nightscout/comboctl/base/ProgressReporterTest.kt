package info.nightscout.comboctl.base

import kotlin.test.Test
import kotlin.test.assertEquals

class ProgressReporterTest {
    // NOTE: In the tests here, the progress sequences are fairly
    // arbitrary, and do _not_ reflect how actual sequences used
    // in pairing etc. look like.

    @Test
    fun testBasicProgress() {
        val progressReporter = ProgressReporter(
            listOf(
                BasicProgressStage.EstablishingBtConnection::class,
                BasicProgressStage.PerformingConnectionHandshake::class,
                BasicProgressStage.ComboPairingStarting::class,
                BasicProgressStage.ComboPairingKeyAndPinRequested::class,
                BasicProgressStage.ComboPairingFinishing::class
            )
        )

        assertEquals(
            ProgressReport(0, 5, BasicProgressStage.Idle),
            progressReporter.progressFlow.value
        )

        progressReporter.setCurrentProgressStage(
            BasicProgressStage.EstablishingBtConnection(1, 5)
        )
        assertEquals(
            ProgressReport(0, 5, BasicProgressStage.EstablishingBtConnection(1, 5)),
            progressReporter.progressFlow.value
        )

        progressReporter.setCurrentProgressStage(
            BasicProgressStage.EstablishingBtConnection(2, 5)
        )
        assertEquals(
            ProgressReport(0, 5, BasicProgressStage.EstablishingBtConnection(2, 5)),
            progressReporter.progressFlow.value
        )

        progressReporter.setCurrentProgressStage(BasicProgressStage.PerformingConnectionHandshake)
        assertEquals(
            ProgressReport(1, 5, BasicProgressStage.PerformingConnectionHandshake),
            progressReporter.progressFlow.value
        )

        progressReporter.setCurrentProgressStage(BasicProgressStage.ComboPairingStarting)
        assertEquals(
            ProgressReport(2, 5, BasicProgressStage.ComboPairingStarting),
            progressReporter.progressFlow.value
        )

        progressReporter.setCurrentProgressStage(BasicProgressStage.ComboPairingKeyAndPinRequested)
        assertEquals(
            ProgressReport(3, 5, BasicProgressStage.ComboPairingKeyAndPinRequested),
            progressReporter.progressFlow.value
        )

        progressReporter.setCurrentProgressStage(BasicProgressStage.ComboPairingFinishing)
        assertEquals(
            ProgressReport(4, 5, BasicProgressStage.ComboPairingFinishing),
            progressReporter.progressFlow.value
        )

        progressReporter.setCurrentProgressStage(BasicProgressStage.Finished)
        assertEquals(
            ProgressReport(5, 5, BasicProgressStage.Finished),
            progressReporter.progressFlow.value
        )
    }

    @Test
    fun testSkippedSteps() {
        val progressReporter = ProgressReporter(
            listOf(
                BasicProgressStage.EstablishingBtConnection::class,
                BasicProgressStage.PerformingConnectionHandshake::class,
                BasicProgressStage.ComboPairingStarting::class,
                BasicProgressStage.ComboPairingKeyAndPinRequested::class,
                BasicProgressStage.ComboPairingFinishing::class
            )
        )

        assertEquals(
            ProgressReport(0, 5, BasicProgressStage.Idle),
            progressReporter.progressFlow.value
        )

        progressReporter.setCurrentProgressStage(
            BasicProgressStage.EstablishingBtConnection(1, 5)
        )
        assertEquals(
            ProgressReport(0, 5, BasicProgressStage.EstablishingBtConnection(1, 5)),
            progressReporter.progressFlow.value
        )

        progressReporter.setCurrentProgressStage(BasicProgressStage.ComboPairingFinishing)
        assertEquals(
            ProgressReport(4, 5, BasicProgressStage.ComboPairingFinishing),
            progressReporter.progressFlow.value
        )

        progressReporter.setCurrentProgressStage(BasicProgressStage.Finished)
        assertEquals(
            ProgressReport(5, 5, BasicProgressStage.Finished),
            progressReporter.progressFlow.value
        )
    }

    @Test
    fun testBackwardsProgress() {
        val progressReporter = ProgressReporter(
            listOf(
                BasicProgressStage.EstablishingBtConnection::class,
                BasicProgressStage.PerformingConnectionHandshake::class,
                BasicProgressStage.ComboPairingStarting::class,
                BasicProgressStage.ComboPairingKeyAndPinRequested::class,
                BasicProgressStage.ComboPairingFinishing::class
            )
        )

        assertEquals(
            ProgressReport(0, 5, BasicProgressStage.Idle),
            progressReporter.progressFlow.value
        )

        progressReporter.setCurrentProgressStage(BasicProgressStage.ComboPairingFinishing)
        assertEquals(
            ProgressReport(4, 5, BasicProgressStage.ComboPairingFinishing),
            progressReporter.progressFlow.value
        )

        progressReporter.setCurrentProgressStage(
            BasicProgressStage.EstablishingBtConnection(1, 5)
        )
        assertEquals(
            ProgressReport(0, 5, BasicProgressStage.EstablishingBtConnection(1, 5)),
            progressReporter.progressFlow.value
        )

        progressReporter.setCurrentProgressStage(BasicProgressStage.Finished)
        assertEquals(
            ProgressReport(5, 5, BasicProgressStage.Finished),
            progressReporter.progressFlow.value
        )
    }

    @Test
    fun testAbort() {
        val progressReporter = ProgressReporter(
            listOf(
                BasicProgressStage.EstablishingBtConnection::class,
                BasicProgressStage.PerformingConnectionHandshake::class,
                BasicProgressStage.ComboPairingStarting::class,
                BasicProgressStage.ComboPairingKeyAndPinRequested::class,
                BasicProgressStage.ComboPairingFinishing::class
            )
        )

        assertEquals(
            ProgressReport(0, 5, BasicProgressStage.Idle),
            progressReporter.progressFlow.value
        )

        progressReporter.setCurrentProgressStage(
            BasicProgressStage.EstablishingBtConnection(1, 5)
        )
        assertEquals(
            ProgressReport(0, 5, BasicProgressStage.EstablishingBtConnection(1, 5)),
            progressReporter.progressFlow.value
        )

        progressReporter.setCurrentProgressStage(BasicProgressStage.Aborted)
        assertEquals(
            ProgressReport(5, 5, BasicProgressStage.Aborted),
            progressReporter.progressFlow.value
        )
    }
}
