package org.senatov.mimitrends

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
