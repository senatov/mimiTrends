package org.senatov.mimitrends

import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.CompanyProfile
import org.senatov.mimitrends.ws.FinnhubProfileClient
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiConsumer

class CompanyProfileService(
    private val repository: MarketRepository,
    private val remote: FinnhubProfileClient,
    private val maxAge: Duration = Duration.ofDays(7)
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val requests = ConcurrentHashMap<String, CompletableFuture<CompanyProfile>>()

    fun load(symbol: String): CompletableFuture<CompanyProfile> {
        log.debug(LogTag.DB, "load(symbol={})", symbol)
        val normalized = symbol.trim().uppercase()
        return requests.computeIfAbsent(normalized, ::loadFromDatabase)
    }

    private fun loadFromDatabase(symbol: String): CompletableFuture<CompanyProfile> =
        CompletableFuture.supplyAsync { repository.loadCompanyProfile(symbol) }.thenCompose { stored ->
            val freshAfter = System.currentTimeMillis() - maxAge.toMillis()
            if (stored != null && stored.updatedAtMillis >= freshAfter) {
                log.debug(LogTag.DB, "company profile cache hit symbol={}", symbol)
                CompletableFuture.completedFuture(stored)
            } else {
                log.debug(LogTag.API, "company profile cache miss symbol={}", symbol)
                remote.load(symbol).thenApply { loaded ->
                    repository.upsertCompanyProfile(loaded)
                    loaded
                }.exceptionally { error ->
                    if (stored != null) {
                        log.warn(LogTag.API, "using stale company profile symbol={}", symbol, error)
                        stored
                    } else {
                        throw error
                    }
                }
            }
        }.whenComplete(BiConsumer<CompanyProfile?, Throwable?> { _, error ->
            if (error != null) requests.remove(symbol)
        })
}
