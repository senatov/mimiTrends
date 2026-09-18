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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UiUpdateBatcherTest {
    @Test
    fun `coalesces repeated symbol updates before the ui pulse`() {
        val tasks = mutableListOf<() -> Unit>()
        val consumed = mutableListOf<List<String>>()
        val batcher = UiUpdateBatcher<String, String>(tasks::add) { consumed += it.sorted() }

        batcher.offer("SAP.DE", "old")
        batcher.offer("SAP.DE", "new")
        batcher.offer("BAS.DE", "bas")

        assertEquals(1, tasks.size)
        tasks.removeFirst().invoke()
        assertEquals(listOf(listOf("bas", "new")), consumed)
    }

    @Test
    fun `schedules a follow-up pulse when updates arrive while consuming`() {
        val tasks = mutableListOf<() -> Unit>()
        val consumed = mutableListOf<String>()
        lateinit var batcher: UiUpdateBatcher<String, String>
        batcher = UiUpdateBatcher(tasks::add) { batch ->
            consumed += batch
            if (batch.single() == "first") batcher.offer("SAP.DE", "second")
        }

        batcher.offer("SAP.DE", "first")
        tasks.removeFirst().invoke()
        tasks.removeFirst().invoke()

        assertEquals(listOf("first", "second"), consumed)
    }
}
