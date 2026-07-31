package org.senatov.mimitrends.scanner

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.AnomalyWindow
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ScannerCriteria
import kotlin.test.assertTrue

class ScannerEngineTest {
    @Test fun `ranks a minute volume spike above its normal baseline`() {
        val bars = (0..12).map { minute ->
            val volume = if (minute == 12) 1_000.0 else 10.0
            MinuteBar("TEST", minute * 60L, 100.0, 100.2, 99.8, 100.1, volume)
        }
        val result = requireNotNull(ScannerEngine().evaluate(
            "TEST", bars, ScannerCriteria(anomalyWindow = AnomalyWindow.MINUTE, minPrice = 0.0)
        ))
        assertTrue(result.volumeAnomaly > 10.0)
        assertTrue(result.anomalyScore >= result.volumeAnomaly)
    }
}
