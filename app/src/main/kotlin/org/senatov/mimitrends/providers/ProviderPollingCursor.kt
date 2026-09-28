package org.senatov.mimitrends.providers

/** Keeps the next eligible symbol in view when the rotating universe changes. */
internal object ProviderPollingCursor {
    fun nextIndex(previous: List<String>, index: Int, current: List<String>): Int {
        if (current.isEmpty()) return 0
        val nextSymbol = previous.getOrNull(index)
        return nextSymbol?.let(current::indexOf)?.takeIf { it >= 0 }
            ?: index.coerceIn(0, current.lastIndex)
    }
}
