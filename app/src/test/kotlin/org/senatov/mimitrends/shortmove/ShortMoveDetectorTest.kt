package org.senatov.mimitrends.shortmove

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.VolumeStatus
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShortMoveDetectorTest {
    @Test
    fun `detects rapid crash at zero point three percent threshold`() {
        val now = 12_000L
        val bars = prices("THRESHOLD", now, 100.0, 100.15, 99.90, 99.70)
        val result = ShortMoveDetector.rank(mapOf("THRESHOLD" to bars), now).single()
        assertEquals(ShortMovePattern.RAPID_CRASH, result.pattern)
        assertEquals(-0.30, result.changePercent, 1e-9)
    }

    @Test
    fun `does not emit sub-threshold moves or rapid rises`() {
        val now = 13_000L
        val ranked = ShortMoveDetector.rank(
            mapOf(
                "SMALL_DROP" to prices("SMALL_DROP", now, 100.0, 99.90, 99.80, 99.701),
                "RISE" to prices("RISE", now, 100.0, 100.2, 100.7, 101.2)
            ), now
        )
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `detects crash without requiring consecutive falling closes`() {
        val now = 13_500L
        val bars = listOf(bar("MIXED", now - 180, 100.0), bar("MIXED", now - 60, 100.3), bar("MIXED", now, 99.4))
        val result = ShortMoveDetector.rank(mapOf("MIXED" to bars), now).single()
        assertEquals(ShortMovePattern.RAPID_CRASH, result.pattern)
        assertEquals(-0.6, result.changePercent, 1e-9)
    }

    @Test
    fun `finds a recent qualifying crash after delayed data refresh`() {
        val now = 30_000L
        val bars = prices(
            "AAPL", now - 4 * 60L,
            341.46, 340.45, 339.52, 339.82, 339.57, 338.95, 338.85, 338.92, 338.65, 337.61, 337.27
        )

        val result = ShortMoveDetector.rank(mapOf("AAPL" to bars), now).single()

        assertEquals(ShortMovePattern.RAPID_CRASH, result.pattern)
        assertEquals(-1.227, result.changePercent, 0.001)
        assertEquals(10 * 60L, result.endedAtEpochSeconds - result.startedAtEpochSeconds)
    }

    @Test
    fun `does not turn an ordinary fifteen minute decline into a crash`() {
        val now = 40_000L
        val bars = prices(
            "ORDINARY", now,
            100.0, 99.95, 99.90, 99.85, 99.80, 99.75, 99.70, 99.65,
            99.60, 99.55, 99.50, 99.45, 99.40, 99.35, 99.30, 99.20
        )

        assertTrue(ShortMoveDetector.rank(mapOf("ORDINARY" to bars), now).isEmpty())
    }

    @Test
    fun `detects an accelerating sustained decline at zero point six percent`() {
        val now = 41_000L
        val bars = prices(
            "ACCELERATING", now,
            100.0, 99.99, 99.98, 99.97, 99.96, 99.95, 99.94, 99.93,
            99.92, 99.91, 99.90, 99.88, 99.83, 99.72, 99.55, 99.40
        )

        val result = ShortMoveDetector.rank(mapOf("ACCELERATING" to bars), now).single()
        assertEquals(ShortMovePattern.RAPID_CRASH, result.pattern)
        assertEquals(-0.60, result.changePercent, 1e-9)
    }

    @Test
    fun `rejects low priced and thinly traded crashes without broker data`() {
        val now = 42_000L
        val cheap = prices("CHEAP", now, 1.83, 1.82, 1.80, 1.79)
            .map { it.copy(volume = 100_000.0) }
        val thin = prices("THIN", now, 75.0, 74.9, 74.8, 74.7)
            .map { it.copy(volume = 10.0) }

        assertTrue(ShortMoveDetector.rank(mapOf("CHEAP" to cheap, "THIN" to thin), now).isEmpty())
    }

    @Test
    fun `requires reported volume and a continuous crash window`() {
        val now = 43_000L
        val missingVolume = prices("MISSING", now, 100.0, 99.9, 99.8, 99.7)
            .map { it.copy(volume = 0.0, volumeStatus = VolumeStatus.MISSING) }
        val sparse = listOf(
            bar("SPARSE", now - 9 * 60L, 100.0),
            bar("SPARSE", now - 5 * 60L, 99.9),
            bar("SPARSE", now - 4 * 60L, 99.8),
            bar("SPARSE", now, 99.0)
        )

        assertTrue(ShortMoveDetector.rank(mapOf("MISSING" to missingVolume, "SPARSE" to sparse), now).isEmpty())
    }

    @Test
    fun `does not report a reversal of an upward spike as a crash`() {
        val now = 60_000L
        val bars = listOf(
            bar("NEM.DE", now - 20 * 60L, 63.20),
            bar("NEM.DE", now - 18 * 60L, 64.55),
            bar("NEM.DE", now - 17 * 60L, 64.675),
            bar("NEM.DE", now - 15 * 60L, 63.625),
            bar("NEM.DE", now - 12 * 60L, 63.825),
            bar("NEM.DE", now - 5 * 60L, 63.325),
            bar("NEM.DE", now, 63.375)
        )

        assertTrue(ShortMoveDetector.rank(mapOf("NEM.DE" to bars), now).isEmpty())
    }

    @Test
    fun `detects a crash after an upward move when it breaks the preceding low`() {
        val now = 61_000L
        val bars = listOf(
            bar("BREAK", now - 12 * 60L, 100.0),
            bar("BREAK", now - 4 * 60L, 101.0),
            bar("BREAK", now - 3 * 60L, 100.7),
            bar("BREAK", now - 2 * 60L, 100.2),
            bar("BREAK", now, 99.8)
        )

        val result = ShortMoveDetector.rank(mapOf("BREAK" to bars), now).single()
        assertEquals(ShortMovePattern.RAPID_CRASH, result.pattern)
        assertTrue(result.close < 100.0)
    }

    @Test
    fun `ignores stale and single-bar symbols`() {
        val now = 20_000L
        val ranked = ShortMoveDetector.rank(
            mapOf(
                "STALE" to prices("STALE", now - 11 * 60, 100.0, 99.0),
                "ONE" to listOf(bar("ONE", now, 99.0))
            ), now
        )
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `retains a corridor briefly and drops it after a boundary break`() {
        val retainer = ShortMoveEventRetainer()
        val corridor = corridor("IFX.DE", 1_000L, 55.50, 55.93, 72)
        retainer.merge(listOf(corridor), 1_000L)
        assertTrue(retainer.merge(emptyList(), 1_900L).single().isRetained)
        val crash = ShortMove(
            "IFX.DE", -0.6, 55.50, 55.30, 1_000L, 1_060L, 4,
            ShortMovePattern.RAPID_CRASH, 1_060L
        )
        assertEquals(listOf(ShortMovePattern.RAPID_CRASH), retainer.merge(listOf(crash), 1_060L).map { it.pattern })
    }

    @Test
    fun `keeps only the strongest share class for one company`() {
        val moves = listOf(crash("GOOG", -3.13), crash("GOOGL", -2.89), crash("PLTR", -2.74))
        val distinct = ShortMoveCompanyRanking.distinct(moves, 10) { symbol ->
            if (symbol.startsWith("GOOG")) "Alphabet Inc." else "Palantir Technologies Inc."
        }
        assertEquals(listOf("GOOG", "PLTR"), distinct.map(ShortMove::symbol))
    }

    private fun prices(symbol: String, end: Long, vararg closes: Double) = closes.mapIndexed { index, close ->
        bar(symbol, end - (closes.lastIndex - index) * 60L, close)
    }

    private fun bar(symbol: String, time: Long, close: Double) =
        MinuteBar(symbol, time, close, close, close, close, 1_000.0)

    private fun crash(symbol: String, change: Double) = ShortMove(
        symbol, change, 100.0, 100.0 + change, 0L, 60L, 2, ShortMovePattern.RAPID_CRASH
    )

    private fun corridor(symbol: String, event: Long, lower: Double, upper: Double, score: Int) = ShortMove(
        symbol, (upper / lower - 1.0) * 100.0, lower, upper, event - 60L, event, 45,
        ShortMovePattern.TRADABLE_CORRIDOR, event, opportunityScore = score,
        corridorLower = lower, corridorUpper = upper
    )
}
