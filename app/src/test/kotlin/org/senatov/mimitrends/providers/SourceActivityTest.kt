package org.senatov.mimitrends.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SourceActivityTest {
    @Test
    fun `latest result replaces previous counts without dropping other sources`() {
        val activity = SourceActivity()
        assertTrue(activity.snapshot().all { it.lastContactMillis == null })

        activity.record("Yahoo", 12, 10)
        activity.record("Tradegate", 1, 1)
        activity.record("Yahoo", 3, 0, failed = true)

        val rows = activity.snapshot().associateBy(SourceActivitySnapshot::source)
        assertEquals(8, rows.size)
        assertEquals(3, rows["Yahoo"]?.processed)
        assertEquals(0, rows["Yahoo"]?.accepted)
        assertTrue(rows["Yahoo"]?.failed == true)
        assertEquals(2, rows["Yahoo"]?.operations)
        assertEquals(1, rows["Yahoo"]?.failures)
        assertNotNull(rows["Yahoo"]?.lastSuccessMillis)
        assertNotNull(rows["Yahoo"]?.lastContactMillis)
        assertEquals(1, rows["Tradegate"]?.accepted)
        assertFalse(rows["Tradegate"]?.failed ?: true)
    }

    @Test
    fun `status explains an idle or unavailable source without inventing contact`() {
        val activity = SourceActivity()
        activity.markStatus("Scalable", "No signals")
        assertEquals(null, activity.snapshot().first { it.source == "Scalable" }.lastContactMillis)
        assertEquals("No signals", activity.snapshot().first { it.source == "Scalable" }.status)

        activity.record("Scalable", 0, 0, failed = true, status = "Login needed")
        activity.clearStatus("Scalable")
        val current = activity.snapshot().first { it.source == "Scalable" }
        assertNotNull(current.lastContactMillis)
        assertEquals(null, current.status)
        assertFalse(current.failed)
        assertEquals(1, current.operations)
        assertEquals(1, current.failures)
    }

    @Test
    fun `health and age distinguish live quiet and attention states`() {
        val now = 1_000_000L
        assertEquals(SourceHealth.WAITING, SourceActivityPresentation.health(SourceActivitySnapshot("Yahoo"), now))
        assertEquals(SourceHealth.LIVE, SourceActivityPresentation.health(
            SourceActivitySnapshot("Yahoo", lastContactMillis = now - 60_000L), now
        ))
        assertEquals(SourceHealth.QUIET, SourceActivityPresentation.health(
            SourceActivitySnapshot("Yahoo", lastContactMillis = now - 16 * 60_000L), now
        ))
        assertEquals(SourceHealth.ATTENTION, SourceActivityPresentation.health(
            SourceActivitySnapshot("Yahoo", failed = true), now
        ))
        assertEquals("1 min ago", SourceActivityPresentation.age(now - 60_000L, now))
    }
}
