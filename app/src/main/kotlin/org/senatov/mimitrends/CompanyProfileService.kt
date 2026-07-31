package org.senatov.mimitrends

import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.CompanyProfile
import org.senatov.mimitrends.marketdata.CompanyLogoClient
import org.senatov.mimitrends.ws.FinnhubProfileClient
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiConsumer

class CompanyProfileService(
    private val repository: MarketRepository,
    private val remote: FinnhubProfileClient?,
    private val logos: CompanyLogoClient,
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
            if (stored != null && stored.logoBytes == null && stored.name != symbol) {
                return@thenCompose logos.load(symbol, stored.name).thenApply { logo ->
                    if (logo == null) stored else stored.copy(
                        logoUrl = logo.sourceUrl,
                        logoBytes = logo.pngBytes,
                        updatedAtMillis = System.currentTimeMillis()
                    ).also(repository::upsertCompanyProfile)
                }
            }
            if (stored != null && stored.updatedAtMillis >= freshAfter) {
                log.debug(LogTag.DB, "company profile cache hit symbol={}", symbol)
                CompletableFuture.completedFuture(stored)
            } else {
                if (remote == null) return@thenCompose CompletableFuture.completedFuture(
                    stored ?: CompanyProfile(symbol, symbol, "Yahoo Finance", null)
                )
                log.debug(LogTag.API, "company profile cache miss symbol={}", symbol)
                remote.load(symbol).thenApply { loaded ->
                    val merged = loaded.copy(
                        name = stored?.name?.takeUnless { it == symbol } ?: loaded.name,
                        exchange = stored?.exchange?.takeUnless { it == "Yahoo Finance" } ?: loaded.exchange
                    )
                    repository.upsertCompanyProfile(merged)
                    merged
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
