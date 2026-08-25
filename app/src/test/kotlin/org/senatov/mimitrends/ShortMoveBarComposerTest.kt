package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ProviderMinuteBar
import kotlin.test.assertEquals

class ShortMoveBarComposerTest {
    @Test
    fun `freshest provider tail extends primary series without replacing it`() {
        val now = 10_000L
        val primary = listOf(bar(now - 120, 100.0), bar(now - 60, 101.0))
        val providers = listOf(
            observation("TRADEGATE", now - 60, 102.0, 1_000L),
            observation("EURONEXT", now - 60, 103.0, 2_000L),
            observation("LANG_SCHWARZ", now, 104.0, 3_000L)
        )

        val result = ShortMoveBarComposer.compose(primary, providers, now)

        assertEquals(listOf(100.0, 101.0, 104.0), result.map(MinuteBar::close))
    }

    @Test
    fun `session provider observations are retained and future observations are ignored`() {
        val now = 10_000L
        val primary = emptyList<MinuteBar>()
        val providers = listOf(
            observation("TRADEGATE", now - 901, 90.0, 1_000L),
            observation("EURONEXT", now + 60, 110.0, 2_000L)
        )

        assertEquals(listOf(90.0), ShortMoveBarComposer.compose(primary, providers, now).map(MinuteBar::close))
    }

    @Test
    fun `fresh provider overwrites overlapping primary candle without mixing venues`() {
        val now = 20_000L
        val primary = listOf(bar(now - 60, 100.0))
        val providers = listOf(
            observation("TRADEGATE", now - 60, 80.0, 1_000L),
            observation("TRADEGATE", now, 99.0, 2_000L),
            observation("EURONEXT", now, 70.0, 1_500L)
        )

        val result = ShortMoveBarComposer.compose(primary, providers, now)

        assertEquals(listOf(80.0, 99.0), result.map(MinuteBar::close))
    }

    @Test
    fun `rejects provider tail belonging to a different instrument`() {
        val now = 30_000L
        val primary = listOf(bar(now - 60, 41.31), bar(now, 41.35))
        val providers = listOf(
            observation("SCALABLE", now - 60, 3.73, 1_000L),
            observation("SCALABLE", now, 3.74, 2_000L)
        )

        val result = ShortMoveBarComposer.compose(primary, providers, now)

        assertEquals(listOf(41.31, 41.35), result.map(MinuteBar::close))
    }

    private fun observation(provider: String, minute: Long, close: Double, observedAt: Long) =
        ProviderMinuteBar(provider, "SAP.DE", "id", "mic", "EUR", bar(minute, close), observedAt)

    private fun bar(minute: Long, close: Double) =
        MinuteBar("SAP.DE", minute, close, close, close, close, 0.0)
}
