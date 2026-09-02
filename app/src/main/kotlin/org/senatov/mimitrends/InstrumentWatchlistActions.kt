package org.senatov.mimitrends

import java.util.concurrent.CompletableFuture

data class InstrumentWatchlistActions(
    val search: (String) -> CompletableFuture<List<TableSearchSuggestion>> = { CompletableFuture.completedFuture(emptyList()) },
    val add: (String) -> Unit = {},
    val remove: (String) -> Unit = {},
    val contains: (String) -> Boolean = { false },
    val liveSource: (String) -> String = { "CACHE" }
)
