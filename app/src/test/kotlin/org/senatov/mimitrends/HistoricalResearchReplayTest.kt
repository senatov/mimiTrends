package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ScannerCriteria
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoricalResearchReplayTest {
    @Test fun `never passes future bars into detector or feature extraction`() {
        val sessionStart = 1_767_615_600L
        val bars = (0 until 6).flatMap { day ->
            (0..60).map { minute ->
                val price = 100.0 + day + minute * 0.01
                MinuteBar("TEST", sessionStart + day * 86_400L + minute * 60L,
                    price, price + 0.02, price - 0.02, price, 1_000.0)
            }
        }
        val detectorCutoffs = mutableListOf<Long>()

        val samples = HistoricalResearchReplay.replay("TEST", bars, ScannerCriteria()) { _, history, _ ->
            detectorCutoffs += history.last().minuteEpochSeconds
            null
        }

        assertEquals(2, samples.size)
        assertEquals(samples.map { it.features.observedEpochSeconds }, detectorCutoffs)
        samples.forEach { sample ->
            assertEquals(listOf(5, 10, 30), sample.outcomes.map { it.horizonMinutes })
            assertTrue(sample.outcomes.all { it.observedEpochSeconds > sample.features.observedEpochSeconds })
        }
    }
}
