package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.ScanResult
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PriorityScanCoordinatorTest {
    @Test fun `scans strong and extreme results only`() {
        assertFalse(PriorityScanCoordinator.requiresPriorityScan(result(3.99)))
        assertTrue(PriorityScanCoordinator.requiresPriorityScan(result(4.0)))
        assertTrue(PriorityScanCoordinator.requiresPriorityScan(result(6.0)))
    }

    @Test fun `rejects a non finite score`() {
        assertFalse(PriorityScanCoordinator.requiresPriorityScan(result(Double.NaN)))
    }

    @Test fun `publishes the weaker result and stops tracking its symbol`() {
        val updates = mutableListOf<ScanResult?>()
        PriorityScanCoordinator(
            evaluate = { result(3.5) },
            onResult = { _, scanResult -> updates += scanResult }
        ).use { coordinator ->
            coordinator.replaceCandidates(listOf(result(6.0)))

            coordinator.runOnce()

            assertEquals(3.5, updates.single()?.anomalyScore)
            assertTrue(coordinator.trackedSymbols().isEmpty())
        }
    }

    private fun result(score: Double) = TestScanResult.create(anomalyScore = score)
}
