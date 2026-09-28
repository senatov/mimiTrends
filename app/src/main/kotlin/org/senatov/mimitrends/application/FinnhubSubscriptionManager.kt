package org.senatov.mimitrends.application

/** Mirrors the active US scan rotation on the live WebSocket connection. */
internal class FinnhubSubscriptionManager(
    private val subscribe: (String) -> Unit,
    private val unsubscribe: (String) -> Unit
) {
    private var subscribed = emptySet<String>()

    @Synchronized
    fun replace(symbols: Collection<String>) {
        val desired = symbols.asSequence().map(String::trim).map(String::uppercase)
            .filter { it.isNotEmpty() && '.' !in it }.distinct()
            .take(MAX_SUBSCRIPTIONS).toCollection(linkedSetOf())
        (subscribed - desired).forEach(unsubscribe)
        (desired - subscribed).forEach(subscribe)
        subscribed = desired
    }

    @Synchronized
    fun reset() {
        subscribed = emptySet()
    }

    private companion object {
        const val MAX_SUBSCRIPTIONS = 200
    }
}
