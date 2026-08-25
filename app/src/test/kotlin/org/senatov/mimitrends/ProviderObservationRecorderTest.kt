package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.model.ProviderInstrument
import org.senatov.mimitrends.model.VolumeStatus
import java.nio.file.Files
import kotlin.test.assertEquals

class ProviderObservationRecorderTest {
    @Test
    fun `aggregates executable observations without inventing volume`() {
        repository().use { repository ->
            repository.upsertProviderInstrument(
                ProviderInstrument(
                    "TRADEGATE", "IFX.DE", "DE0006231004", "XGAT", "EUR", "Infineon"
                )
            )
            val forwarded = mutableListOf<MarketPriceObservation>()
            val recorder = ProviderObservationRecorder(repository, forwarded::add)

            recorder.publish(MarketPriceObservation("TRADEGATE", "IFX.DE", 55.10, 60_010))
            recorder.publish(MarketPriceObservation("TRADEGATE", "IFX.DE", 55.40, 80_000))
            recorder.publish(MarketPriceObservation("TRADEGATE", "IFX.DE", 55.20, 119_999))

            val stored = repository.loadProviderMinuteBars("TRADEGATE", "IFX.DE", 0).single()
            assertEquals(55.10, stored.bar.open)
            assertEquals(55.40, stored.bar.high)
            assertEquals(55.10, stored.bar.low)
            assertEquals(55.20, stored.bar.close)
            assertEquals(0.0, stored.bar.volume)
            assertEquals(VolumeStatus.MISSING, stored.bar.volumeStatus)
            assertEquals(3, forwarded.size)
        }
    }

    @Test
    fun `keeps providers and minutes separate`() {
        repository().use { repository ->
            listOf("TRADEGATE", "LANG_SCHWARZ").forEach { provider ->
                repository.upsertProviderInstrument(
                    ProviderInstrument(
                        provider, "IFX.DE", "DE0006231004", provider, "EUR", "Infineon"
                    )
                )
            }
            val recorder = ProviderObservationRecorder(repository) {}
            recorder.publish(MarketPriceObservation("TRADEGATE", "IFX.DE", 55.10, 60_000))
            recorder.publish(MarketPriceObservation("LANG_SCHWARZ", "IFX.DE", 55.20, 60_000))
            recorder.publish(MarketPriceObservation("TRADEGATE", "IFX.DE", 55.30, 120_000))

            assertEquals(3, repository.loadProviderMinuteBars("IFX.DE", 0).size)
        }
    }

    private fun repository() = MarketRepository(
        Files.createTempDirectory("mimitrends-provider-observations").resolve("test.db")
    )
}