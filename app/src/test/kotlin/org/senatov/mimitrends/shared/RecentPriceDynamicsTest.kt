package org.senatov.mimitrends.shared

import org.senatov.mimitrends.application.*
import org.senatov.mimitrends.ui.*
import org.senatov.mimitrends.scanner.*
import org.senatov.mimitrends.shortmove.*
import org.senatov.mimitrends.signals.*
import org.senatov.mimitrends.research.*
import org.senatov.mimitrends.market.*
import org.senatov.mimitrends.providers.*
import org.senatov.mimitrends.company.*
import org.senatov.mimitrends.services.*
import org.senatov.mimitrends.shared.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.senatov.mimitrends.model.MinuteBar

class RecentPriceDynamicsTest {
    @Test
    fun `measures the latest three and five minutes rather than the earlier jump`() {
        val closes = listOf(100.0, 102.0, 101.9, 101.8, 101.7, 101.6, 101.5)
        val bars = closes.mapIndexed { index, close -> bar(index, close) }

        val result = RecentPriceDynamics.apply(TestScanResult.create(), bars)

        assertTrue(result.recentThreeMinutePercent < 0.0)
        assertTrue(result.recentFiveMinutePercent < 0.0)
    }

    @Test
    fun `counts alternating changes in a cyclic tail`() {
        val closes = listOf(100.0, 100.1, 100.0, 100.1, 100.0, 100.1)

        val result = RecentPriceDynamics.apply(
            TestScanResult.create(), closes.mapIndexed { index, close -> bar(index, close) }
        )

        assertEquals(4, result.recentDirectionChanges)
    }

    private fun bar(minute: Int, close: Double) =
        MinuteBar("TEST", minute * 60L, close, close, close, close, 1_000.0)
}