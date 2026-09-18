package org.senatov.mimitrends.services

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
