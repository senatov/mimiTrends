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

    @Test fun `old hourly spike is demoted when latest minutes are quiet`() {
        val bars = (0..180).map { minute ->
            MinuteBar(
                "TEST", minute * 60L, 100.0, 100.1, 99.9, 100.0,
                if (minute == 130) 5_000.0 else 100.0
            )
        }
        val result = requireNotNull(ScannerEngine().evaluate(
            "TEST", bars, ScannerCriteria(anomalyWindow = AnomalyWindow.HOUR, minPrice = 0.0)
        ))
        assertTrue(result.volumeAnomaly > 1.5)
        assertTrue(result.anomalyScore <= 1.1, "quiet recent activity must demote an old spike")
    }

    @Test fun `hourly anomaly remains ranked while latest minutes are active`() {
        val bars = (0..180).map { minute ->
            MinuteBar(
                "TEST", minute * 60L, 100.0, 100.1, 99.9, 100.0,
                if (minute >= 176) 1_000.0 else 100.0
            )
        }
        val result = requireNotNull(ScannerEngine().evaluate(
            "TEST", bars, ScannerCriteria(anomalyWindow = AnomalyWindow.HOUR, minPrice = 0.0)
        ))
        assertTrue(result.anomalyScore > 1.5)
    }
}
