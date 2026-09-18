package org.senatov.mimitrends.scanner

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

import java.util.concurrent.CompletableFuture

data class InstrumentWatchlistActions(
    val search: (String) -> CompletableFuture<List<TableSearchSuggestion>> = { CompletableFuture.completedFuture(emptyList()) },
    val add: (String) -> Unit = {},
    val remove: (String) -> Unit = {},
    val contains: (String) -> Boolean = { false },
    val liveSource: (String) -> String = { "CACHE" }
)
