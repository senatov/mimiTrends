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
                CompletableFuture.completedFuture(presentProfile(symbol, stored))
            } else {
                if (remote == null) return@thenCompose CompletableFuture.completedFuture(
                    presentProfile(symbol, stored ?: CompanyProfile(symbol, symbol, "Yahoo Finance", null))
                )
                log.debug(LogTag.API, "company profile cache miss symbol={}", symbol)
                remote.load(symbol).thenApply { loaded ->
                    presentProfile(symbol, CompanyProfileMerger.merge(stored, loaded))
                }.exceptionally { error ->
                    if (stored != null) {
                        log.warn(LogTag.API, "using stale company profile symbol={}", symbol, error)
                        presentProfile(symbol, stored)
                    } else {
                        throw error
                    }
                }
            }
        }.whenComplete(BiConsumer<CompanyProfile?, Throwable?> { _, error ->
            if (error != null) requests.remove(symbol)
        })

    private fun presentProfile(symbol: String, profile: CompanyProfile): CompanyProfile {
        repository.upsertCompanyProfile(profile)
        if (profile.logoBytes == null) logos.load(symbol, profile.name).whenComplete { logo, error ->
            if (error != null) log.warn(LogTag.API, "background logo load failed symbol={}", symbol, error)
            if (logo != null) {
                val enriched = profile.copy(
                    logoUrl = logo.sourceUrl, logoBytes = logo.pngBytes,
                    updatedAtMillis = System.currentTimeMillis()
                )
                repository.upsertCompanyProfile(enriched)
                requests[symbol] = CompletableFuture.completedFuture(enriched)
            }
        }
        return profile
    }
}