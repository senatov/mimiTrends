package org.senatov.mimitrends.market

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.senatov.mimitrends.db.AnalyticsRepository
import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.model.DisplayCurrency
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ProviderMinuteBar
import org.senatov.mimitrends.model.VolumeStatus
import org.senatov.mimitrends.services.ExchangeRateService
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChartDataLoaderTest {
    @Test
    fun `loads live provider candles so a recent alert has a chart focus bar`(@TempDir directory: Path) {
        val path = directory.resolve("market.db")
        val now = Instant.now().epochSecond / 60L * 60L
        MarketRepository(path).use { market ->
            AnalyticsRepository(path).use { analytics ->
                market.upsertMinuteBar(bar(now - 12 * 60L, 53.70, VolumeStatus.REPORTED))
                val signalEpoch = now - 3 * 60L
                market.upsertProviderMinuteBar(
                    ProviderMinuteBar(
                        "SCALABLE", "IFX.DE", "DE0006231004", "XGAT", "EUR",
                        bar(signalEpoch, 53.69, VolumeStatus.MISSING), signalEpoch * 1_000L
                    )
                )

                val loaded = ChartDataLoader(market, analytics, ExchangeRateService())
                    .load("IFX.DE", "5D", DisplayCurrency.EUR)

                assertEquals(listOf(now - 12 * 60L, signalEpoch), loaded.bars.map(MinuteBar::minuteEpochSeconds))
                assertTrue(loaded.bars.any { it.minuteEpochSeconds == signalEpoch && it.close == 53.69 })
            }
        }
    }

    private fun bar(epoch: Long, price: Double, volumeStatus: VolumeStatus) =
        MinuteBar("IFX.DE", epoch, price, price, price, price, 0.0, volumeStatus)
}
