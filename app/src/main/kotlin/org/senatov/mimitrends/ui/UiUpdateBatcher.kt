package org.senatov.mimitrends.ui

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

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class UiUpdateBatcher<K : Any, V : Any>(
    private val dispatch: (() -> Unit) -> Unit,
    private val consume: (Collection<V>) -> Unit
) {
    private val pending = ConcurrentHashMap<K, V>()
    private val scheduled = AtomicBoolean()

    fun offer(key: K, value: V) {
        pending[key] = value
        schedule()
    }

    private fun schedule() {
        if (scheduled.compareAndSet(false, true)) dispatch(::drain)
    }

    private fun drain() {
        val batch = pending.keys.mapNotNull(pending::remove)
        try {
            if (batch.isNotEmpty()) consume(batch)
        } finally {
            scheduled.set(false)
            if (pending.isNotEmpty()) schedule()
        }
    }
}
