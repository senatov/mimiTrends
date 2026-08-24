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
            if (stored != null && stored.updatedAtMillis >= freshAfter) {
                log.debug(LogTag.DB, "company profile cache hit symbol={}", symbol)
                enrichLogo(symbol, stored)
            } else {
                if (remote == null) return@thenCompose enrichLogo(
                    symbol, stored ?: CompanyProfile(symbol, symbol, "Yahoo Finance", null)
                )
                log.debug(LogTag.API, "company profile cache miss symbol={}", symbol)
                remote.load(symbol).thenCompose { loaded ->
                    enrichLogo(symbol, CompanyProfileMerger.merge(stored, loaded))
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

    private fun enrichLogo(symbol: String, profile: CompanyProfile): CompletableFuture<CompanyProfile> {
        if (profile.logoBytes != null) return CompletableFuture.completedFuture(profile)
        return logos.load(symbol, profile.name).thenApply { logo ->
            val enriched = if (logo == null) profile else profile.copy(
                logoUrl = logo.sourceUrl,
                logoBytes = logo.pngBytes,
                updatedAtMillis = System.currentTimeMillis()
            )
            repository.upsertCompanyProfile(enriched)
            enriched
        }
    }
}
