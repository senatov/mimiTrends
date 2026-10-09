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

class RecentEventRetainerTest {
    @Test
    fun `keeps an inactive signal without a time limit`() {
        val retainer = RecentEventRetainer()
        retainer.merge(listOf(result("SAP.DE", 8.0)), 30)

        val displayed = retainer.merge(emptyList(), 30)

        assertEquals("SAP.DE", displayed.single().symbol)
        assertTrue(displayed.single().isRetained)
    }

    @Test
    fun `returns a reactivated signal to the live section`() {
        val retainer = RecentEventRetainer()
        retainer.merge(listOf(result("SAP.DE", epoch = 100L)), 30)
        retainer.merge(emptyList(), 30)
        val displayed = retainer.merge(listOf(result("SAP.DE", epoch = 200L)), 30)
        assertEquals("SAP.DE", displayed.first().symbol)
        assertTrue(!displayed.first().isRetained)
    }

    @Test
    fun `live results precede grey results and oldest grey result leaves at capacity`() {
        val retainer = RecentEventRetainer()
        val first = (1..30).map { index -> result("STOCK$index", epoch = index.toLong()) }
        retainer.merge(first, 30)
        val displayed = retainer.merge(listOf(result("NEW", epoch = 31L)), 30)
        assertEquals(30, displayed.size)
        assertEquals("NEW", displayed.first().symbol)
        assertEquals((30 downTo 2).map { "STOCK$it" }, displayed.drop(1).map { it.symbol })
        assertTrue(displayed.drop(1).all { it.isRetained })
    }

    @Test
    fun `opposite v reversal updates the same episode`() {
        val retainer = RecentEventRetainer()
        retainer.merge(listOf(result("SAP.DE", source = "V-Reversal ↑")), 30)

        val updated = retainer.merge(
            listOf(result("SAP.DE", source = "V-Reversal ↓")), 30
        ).single()

        assertEquals("V-Reversal ↓ after ↑", updated.signalSource)
    }

    @Test
    fun `priority miss retains the signal in the grey section`() {
        val retainer = RecentEventRetainer()
        retainer.merge(listOf(result("SAP.DE", 8.0)), 30)

        val displayed = retainer.priorityUpdate("SAP.DE", null)

        assertTrue(displayed!!.isRetained)
    }

    private fun result(symbol: String, score: Double = 4.0, source: String = "Impulse ↑", epoch: Long = 100L) =
        TestScanResult.create(anomalyScore = score, signalSource = source, symbol = symbol)
            .copy(signalEpochMillis = epoch, updatedAtMillis = epoch)
}
