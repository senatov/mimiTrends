package org.senatov.mimitrends.db

import org.senatov.mimitrends.model.MinuteBar
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals

class MarketRepositoryTest {
    @Test fun `stores and updates minute bars`() {
        val repository = MarketRepository(Files.createTempDirectory("mimitrends-db").resolve("test.db"))
        repository.upsertMinuteBar(MinuteBar("SAP.DE", 60, 100.0, 102.0, 99.0, 101.0, 500.0))
        repository.upsertMinuteBar(MinuteBar("SAP.DE", 60, 100.0, 103.0, 99.0, 102.0, 750.0))
        val bars = repository.loadMinuteBars("SAP.DE", 0)
        assertEquals(1, bars.size); assertEquals(102.0, bars.single().close); assertEquals(750.0, bars.single().volume)
        repository.close()
    }
}
