package org.senatov.mimitrends.scanner

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.ScannerCriteria
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScannerSettingsServiceTest {
    @Test
    fun `persists additional provider configuration`() {
        val path = Files.createTempDirectory("mimitrends-settings").resolve("scanner.properties")
        val service = ScannerSettingsService(path)

        service.save(ScannerCriteria(
            tradegateEnabled = true, tradegateRequestIntervalMillis = 1_750,
            euronextEnabled = true, euronextRequestIntervalMillis = 2_250
        ))
        val restored = service.load()

        assertTrue(restored.tradegateEnabled)
        assertEquals(1_750, restored.tradegateRequestIntervalMillis)
        assertTrue(restored.euronextEnabled)
        assertEquals(2_250, restored.euronextRequestIntervalMillis)
    }
}
