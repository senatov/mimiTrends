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
        assertNotNull(rows["Yahoo"]?.lastContactMillis)
        assertEquals(1, rows["Tradegate"]?.accepted)
        assertFalse(rows["Tradegate"]?.failed ?: true)
    }
}
