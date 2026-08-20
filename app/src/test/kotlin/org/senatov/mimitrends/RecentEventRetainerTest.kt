package org.senatov.mimitrends

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecentEventRetainerTest {
    @Test
    fun `keeps a briefly missing candidate stable between refreshes`() {
        val retainer = RecentEventRetainer()
        retainer.merge(listOf(result("SAP.DE", 8.0)), 0L, 15)

        val displayed = retainer.merge(emptyList(), 30_000L, 15)

        assertEquals("SAP.DE", displayed.single().symbol)
    }

    @Test
    fun `does not retain an inactive event as a candidate`() {
        val retainer = RecentEventRetainer(retentionMillis = 20 * MINUTE)
        retainer.merge(listOf(result("SAP.DE", 8.0)), 0L, 15)

        val displayed = retainer.merge(emptyList(), 10 * MINUTE, 15)

        assertTrue(displayed.isEmpty())
    }

    @Test
    fun `expires event at retention boundary`() {
        val retainer = RecentEventRetainer(retentionMillis = 20 * MINUTE)
        retainer.merge(listOf(result("SAP.DE")), 0L, 15)

        assertTrue(retainer.merge(emptyList(), 20 * MINUTE, 15).isEmpty())
    }

    @Test
    fun `active results take available places before cooling events`() {
        val retainer = RecentEventRetainer()
        retainer.merge(listOf(result("SAP.DE", 20.0)), 0L, 15)

        val displayed = retainer.merge(
            listOf(result("AAPL", 1.0), result("MSFT", 2.0)), MINUTE, 2
        )

        assertEquals(listOf("MSFT", "AAPL"), displayed.map { it.symbol })
    }

    @Test
    fun `opposite v reversal updates the same episode`() {
        val retainer = RecentEventRetainer()
        retainer.merge(listOf(result("SAP.DE", source = "V-Reversal ↑")), 0L, 15)

        val updated = retainer.merge(
            listOf(result("SAP.DE", source = "V-Reversal ↓")), MINUTE, 15
        ).single()

        assertEquals("V-Reversal ↓ after ↑", updated.signalSource)
    }

    @Test
    fun `priority miss removes the event`() {
        val retainer = RecentEventRetainer()
        retainer.merge(listOf(result("SAP.DE", 8.0)), 0L, 15)

        val displayed = retainer.priorityUpdate("SAP.DE", null, 5 * MINUTE)

        assertEquals(null, displayed)
    }

    private fun result(symbol: String, score: Double = 4.0, source: String = "Impulse ↑") =
        TestScanResult.create(anomalyScore = score, signalSource = source, symbol = symbol)

    private companion object {
        const val MINUTE = 60_000L
    }
}
