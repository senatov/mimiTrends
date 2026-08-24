package org.senatov.mimitrends

import java.util.concurrent.atomic.AtomicLong

internal class LatestRequestGate<K : Any> {
    private val generation = AtomicLong()

    fun begin(key: K): Request<K> = Request(key, generation.incrementAndGet())

    fun accepts(request: Request<K>, currentKey: K): Boolean =
        request.generation == generation.get() && request.key == currentKey

    fun invalidate() {
        generation.incrementAndGet()
    }

    data class Request<K>(val key: K, val generation: Long)
}
