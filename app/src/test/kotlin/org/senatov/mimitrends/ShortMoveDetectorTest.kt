package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShortMoveDetectorTest {
    @Test fun `selects holding gains and moderate positive moves`() {
        val selected = ModeratePositiveCandidateSelector.select(listOf(
            move("STEADY", 0.55).copy(barCount = 5, safetyScore = 76, safetyConfidence = 80,
                entryQualityScore = 75),
            move("FLAT", 0.05).copy(barCount = 5, safetyScore = 62, safetyConfidence = 60,
                entryQualityScore = 68),
            move("JUMP", 2.0).copy(barCount = 5, safetyScore = 90, safetyConfidence = 90,
                entryQualityScore = 80),
            move("DOWN", -0.4).copy(barCount = 5, safetyScore = 40, safetyConfidence = 80,
                entryQualityScore = 70),
            move("SPECIAL", 0.6).copy(barCount = 5, pattern = ShortMovePattern.RECURRING_SHARP_JUMP,
                safetyScore = 82, safetyConfidence = 80, entryQualityScore = 80)
        ))

        assertEquals(listOf("STEADY", "FLAT"), selected.map(ShortMove::symbol))
        assertEquals(listOf(76, 62), selected.map(ModeratePositiveCandidateSelector::positivityPercent))
    }

    @Test
    fun `retains an actionable opportunity for twenty minutes after it disappears`() {
        val retainer = ShortMoveEventRetainer()
        val initial = opportunityMove("NDA.DE", event = 1_000L, close = 104.0, score = 72)

        retainer.merge(listOf(initial), 1_000L)
        val retained = retainer.merge(emptyList(), 1_900L)

        assertEquals(104.0, retained.single().close)
        assertTrue(retained.single().isRetained)
        kotlin.test.assertFalse(retainer.merge(emptyList(), 2_201L).any { it.symbol == "NDA.DE" })
    }

    @Test
    fun `drops a retained corridor immediately after a lower edge break`() {
        val retainer = ShortMoveEventRetainer()
        val corridor = opportunityMove("IFX.DE", event = 1_000L, close = 55.93, score = 72).copy(
            open = 55.50, corridorLower = 55.50, corridorUpper = 55.93
        )
        retainer.merge(listOf(corridor), 1_000L)
        val broken = move("IFX.DE", -0.4).copy(close = 55.30, endedAtEpochSeconds = 1_060L)

        val result = retainer.merge(listOf(broken), 1_060L)

        assertTrue(result.none { it.pattern == ShortMovePattern.TRADABLE_CORRIDOR })
        assertEquals(listOf(ShortMovePattern.DIRECTIONAL), result.map(ShortMove::pattern))
    }

    @Test
    fun `replaces a retained opportunity when a newer one occurs`() {
        val retainer = ShortMoveEventRetainer()
        retainer.merge(listOf(opportunityMove("NDA.DE", event = 1_000L, close = 104.0, score = 72)), 1_000L)

        val result = retainer.merge(
            listOf(opportunityMove("NDA.DE", event = 1_600L, close = 109.0, score = 81)), 1_600L
        )

        assertEquals(1_600L, result.single().eventEpochSeconds)
        assertEquals(109.0, result.single().close)
    }

    @Test
    fun `refreshes an active opportunity and restarts its retention window`() {
        val retainer = ShortMoveEventRetainer()
        retainer.merge(listOf(opportunityMove("NDA.DE", event = 1_000L, close = 104.0, score = 72)), 1_000L)

        val refreshed = retainer.merge(
            listOf(opportunityMove("NDA.DE", event = 1_000L, close = 106.0, score = 80)), 1_900L
        )

        assertEquals(106.0, refreshed.single().close)
        assertEquals(80, refreshed.single().opportunityScore)
        kotlin.test.assertFalse(refreshed.single().isRetained)
        assertTrue(retainer.merge(emptyList(), 3_100L).any { it.symbol == "NDA.DE" })
        kotlin.test.assertFalse(retainer.merge(emptyList(), 3_101L).any { it.symbol == "NDA.DE" })
    }

    @Test
    fun `orders retained and current opportunities by opportunity score`() {
        val retainer = ShortMoveEventRetainer()
        retainer.merge(listOf(opportunityMove("LOW", event = 1_000L, close = 104.0, score = 42)), 1_000L)

        val result = retainer.merge(
            listOf(opportunityMove("HIGH", event = 1_100L, close = 105.0, score = 88)), 1_100L
        )

        assertEquals(listOf("HIGH", "LOW"), result.map(ShortMove::symbol))
    }

    @Test
    fun `does not retain non-actionable diagnostics`() {
        val retainer = ShortMoveEventRetainer()
        val diagnostic = ShortMove(
            "NDA.DE", 4.0, 100.0, 104.0, 940L, 1_000L, 2,
            ShortMovePattern.RECURRING_SHARP_JUMP, 1_000L
        )
        retainer.merge(listOf(diagnostic), 1_000L)

        assertTrue(retainer.merge(emptyList(), 1_001L).isEmpty())
    }

    @Test fun `detects a new sharp rise when similar rises recur across days`() {
        val day = 86_400L
        val now = 3 * day + 180L
        val bars = listOf(
            bar("NDA.DE", 60L, 100.0, 100.0), bar("NDA.DE", 120L, 104.0, 104.0),
            bar("NDA.DE", day + 60L, 96.0, 96.0), bar("NDA.DE", day + 120L, 100.0, 100.0),
            bar("NDA.DE", 3 * day + 60L, 100.0, 100.0),
            bar("NDA.DE", 3 * day + 120L, 105.0, 105.0)
        )

        val result = ShortMoveDetector.rank(mapOf("NDA.DE" to bars), now).single()

        assertEquals(ShortMovePattern.RECURRING_SHARP_JUMP, result.pattern)
        assertEquals(3 * day + 120L, result.eventEpochSeconds)
    }

    @Test fun `detects a new sharp drop in the same recurring movement pattern`() {
        val day = 86_400L
        val now = 3 * day + 180L
        val bars = listOf(
            bar("NDA.DE", 60L, 100.0, 100.0), bar("NDA.DE", 120L, 104.0, 104.0),
            bar("NDA.DE", day + 60L, 100.0, 100.0), bar("NDA.DE", day + 120L, 96.0, 96.0),
            bar("NDA.DE", 3 * day + 60L, 100.0, 100.0),
            bar("NDA.DE", 3 * day + 120L, 95.0, 95.0)
        )

        val result = ShortMoveDetector.rank(mapOf("NDA.DE" to bars), now).single()

        assertEquals(ShortMovePattern.RECURRING_SHARP_JUMP, result.pattern)
        assertTrue(result.changePercent < 0.0)
    }

    @Test
    fun `ranks upward and downward candles by absolute move`() {
        val now = 10_000L
        val ranked = ShortMoveDetector.rank(mapOf(
            "UP" to bars("UP", now, 103.0),
            "DOWN" to bars("DOWN", now, 94.0),
            "FLAT" to bars("FLAT", now, 100.5)
        ), now, limit = 2)

        assertEquals(listOf("DOWN", "UP"), ranked.map(ShortMove::symbol))
        assertTrue(ranked.first().changePercent < 0.0)
        assertEquals(5, ranked.first().barCount)
    }

    @Test
    fun `ignores stale and single-bar symbols`() {
        val now = 10_000L
        val ranked = ShortMoveDetector.rank(mapOf(
            "STALE" to bars("STALE", now - 11 * 60, 110.0),
            "ONE" to listOf(bar("ONE", now, 100.0, 101.0))
        ), now)

        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `detects a completed drop followed by small mixed moves`() {
        val now = 20_000L
        val closes = listOf(100.0, 99.8, 96.9, 96.5, 96.8, 96.4, 96.7)
        val bars = closes.mapIndexed { index, close ->
            bar("BATTLE", now - (closes.lastIndex - index) * 60, if (index == 0) 100.0 else closes[index - 1], close)
        }

        val result = ShortMoveDetector.rank(mapOf("BATTLE" to bars), now).single()

        assertEquals(ShortMovePattern.POST_DROP_STRUGGLE, result.pattern)
        assertTrue(result.changePercent < -3.0)
    }

    @Test
    fun `does not classify a strong v recovery as post drop struggle`() {
        val now = 20_000L
        val closes = listOf(100.0, 96.0, 96.3, 98.5, 99.5)
        val bars = closes.mapIndexed { index, close ->
            bar("RECOVERY", now - (closes.lastIndex - index) * 60, if (index == 0) 100.0 else closes[index - 1], close)
        }

        val result = ShortMoveDetector.rank(mapOf("RECOVERY" to bars), now).single()

        assertEquals(ShortMovePattern.DIRECTIONAL, result.pattern)
    }

    @Test
    fun `keeps the full retained drop after volatile trading near the low`() {
        val now = 30_000L
        val closes = listOf(470.0, 470.5, 459.0, 457.5, 461.0, 456.5, 459.8)
        val recent = closes.mapIndexed { index, close ->
            bar("LVMH", now - (closes.lastIndex - index) * 60,
                if (index == 0) 470.0 else closes[index - 1], close)
        }

        val result = ShortMoveDetector.rank(mapOf("LVMH" to recent), now).single()

        assertEquals(ShortMovePattern.POST_DROP_STRUGGLE, result.pattern)
        assertTrue(result.changePercent < -2.0)
        assertEquals(470.5, result.open)
        assertEquals(459.8, result.close)
        assertEquals((459.8 / 470.5 - 1.0) * 100.0, result.changePercent, 1e-9)
    }

    @Test
    fun `expires an opening collapse after the recent battle window`() {
        val now = 50_000L
        val opening = listOf(470.0, 458.0, 456.0, 459.0)
        val later = (1..20).map { index -> 458.0 + (index % 3) * 0.2 }
        val closes = opening + later
        val session = closes.mapIndexed { index, close ->
            bar("LVMH", now - (closes.lastIndex - index) * 60,
                if (index == 0) 470.0 else closes[index - 1], close)
        }

        val result = ShortMoveDetector.rank(mapOf("LVMH" to session), now).single()

        assertEquals(ShortMovePattern.DIRECTIONAL, result.pattern)
        assertTrue(kotlin.math.abs(result.changePercent) < 1.0)
    }

    @Test
    fun `does not turn a gradual session drift into a sudden post drop`() {
        val now = 60_000L
        val session = (0 until 30).map { index ->
            val open = 100.0 - index * 0.1
            bar("DRIFT", now - (29 - index) * 60, open, open - 0.1)
        }

        val result = ShortMoveDetector.rank(mapOf("DRIFT" to session), now).single()

        assertEquals(ShortMovePattern.DIRECTIONAL, result.pattern)
        assertTrue(result.changePercent > -1.0)
    }

    @Test
    fun `confirms a retained extended decline after twenty five minutes`() {
        val now = 80_000L
        val bars = (0..36).map { index ->
            val close = when {
                index <= 11 -> 119.44 - index * (1.0 / 11.0)
                else -> 118.44 + (index - 11) * (0.30 / 25.0)
            }
            val previous = if (index == 0) 119.44 else when {
                index - 1 <= 11 -> 119.44 - (index - 1) * (1.0 / 11.0)
                else -> 118.44 + (index - 12) * (0.30 / 25.0)
            }
            bar("PEP", now - (36 - index) * 60, previous, close)
        }

        val result = ShortMoveDetector.rank(mapOf("PEP" to bars), now).single()

        assertEquals(ShortMovePattern.CONFIRMED_EXTENDED_DROP, result.pattern)
        assertEquals(119.44, result.open, 1e-9)
        assertEquals(118.74, result.close, 1e-9)
        assertTrue(result.changePercent < -0.5)
    }

    @Test
    fun `does not confirm an extended decline before observation period matures`() {
        val now = 90_000L
        val bars = (0..30).map { index ->
            val close = 120.0 - minOf(index, 10) * 0.1
            bar("EARLY", now - (30 - index) * 60, close, close)
        }

        val result = ShortMoveDetector.rank(mapOf("EARLY" to bars), now).single()

        assertEquals(ShortMovePattern.DIRECTIONAL, result.pattern)
    }

    @Test
    fun `detects danaher recovery after an extended drop at sixteen fifty eight`() {
        val now = 100_000L
        val prices = buildList {
            repeat(16) { index -> add(178.59 - index * (2.38 / 15.0)) }
            repeat(30) { index -> add(176.21 + index * (0.61 / 29.0)) }
            addAll(listOf(176.82, 177.03, 177.12, 177.38, 177.33, 177.32, 177.27, 177.37, 177.45))
        }
        val bars = prices.mapIndexed { index, close ->
            bar("DHR", now - (prices.lastIndex - index) * 60,
                if (index == 0) close else prices[index - 1], close)
        }

        val result = ShortMoveDetector.rank(mapOf("DHR" to bars), now).single()

        assertEquals(ShortMovePattern.RECOVERY_AFTER_EXTENDED_DROP, result.pattern)
        assertTrue(result.changePercent < -0.5)
    }

    @Test
    fun `detects palantir recovery with one quarter of the drop retained at seventeen forty nine`() {
        val now = 110_000L
        val prices = (0..50).map { index ->
            when {
                index <= 20 -> 147.72 - index * (1.67 / 20.0)
                index <= 40 -> 146.05 + (index - 20) * (0.85 / 20.0)
                else -> 146.90 + (index - 40) * (0.38 / 10.0)
            }
        }
        val bars = prices.mapIndexed { index, close ->
            bar("PLTR", now - (prices.lastIndex - index) * 60,
                if (index == 0) close else prices[index - 1], close)
        }

        val result = ShortMoveDetector.rank(mapOf("PLTR" to bars), now).single()

        assertEquals(ShortMovePattern.RECOVERY_AFTER_EXTENDED_DROP, result.pattern)
        assertEquals(147.28, result.close, 1e-9)
        assertTrue(result.changePercent < -0.25)
    }

    @Test
    fun `does not treat distant sparse bars as a five minute collapse`() {
        val now = 70_000L
        val session = listOf(
            bar("SPARSE", now - 30 * 60, 100.0, 100.0),
            bar("SPARSE", now - 4 * 60, 90.0, 90.0),
            bar("SPARSE", now - 2 * 60, 90.0, 90.0),
            bar("SPARSE", now, 90.0, 90.0)
        )

        val result = ShortMoveDetector.rank(mapOf("SPARSE" to session), now).single()

        assertEquals(ShortMovePattern.DIRECTIONAL, result.pattern)
        assertEquals(0.0, result.changePercent, 1e-9)
    }

    @Test
    fun `keeps only the strongest share class for one company`() {
        val moves = listOf(
            move("GOOG", -3.13),
            move("GOOGL", -2.89),
            move("PLTR", -2.74)
        )

        val distinct = ShortMoveCompanyRanking.distinct(moves, 10) { symbol ->
            if (symbol.startsWith("GOOG")) "Alphabet Inc." else "Palantir Technologies Inc."
        }

        assertEquals(listOf("GOOG", "PLTR"), distinct.map(ShortMove::symbol))
    }

    private fun bars(symbol: String, end: Long, close: Double): List<MinuteBar> =
        (0 until 5).map { index ->
            val open = 100.0
            val stepOpen = open + (close - open) * index / 5.0
            val stepClose = open + (close - open) * (index + 1) / 5.0
            bar(symbol, end - (4 - index) * 60, stepOpen, stepClose)
        }

    private fun bar(symbol: String, time: Long, open: Double, close: Double) =
        MinuteBar(symbol, time, open, maxOf(open, close), minOf(open, close), close, 100.0)

    private fun move(symbol: String, change: Double) =
        ShortMove(symbol, change, 100.0, 100.0 + change, 0L, 60L, 2)

    private fun opportunityMove(symbol: String, event: Long, close: Double, score: Int) = ShortMove(
        symbol, 4.0, 100.0, close, event - 60L, event, 45,
        ShortMovePattern.TRADABLE_CORRIDOR, event, opportunityScore = score
    )
}
