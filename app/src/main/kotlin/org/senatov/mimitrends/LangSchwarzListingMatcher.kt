package org.senatov.mimitrends

import org.senatov.mimitrends.marketdata.LangSchwarzListing
import java.text.Normalizer

internal object LangSchwarzListingMatcher {
    fun match(
        symbol: String,
        companyName: String,
        knownIdentifiers: Collection<String>,
        listings: Collection<LangSchwarzListing>
    ): LangSchwarzListing? {
        val knownWkn = knownIdentifiers.mapNotNull(::germanWkn).toSet()
        listings.firstOrNull { it.wkn in knownWkn }?.let { return it }
        val query = normalized(CompanySearchTerm.from(companyName, symbol))
        if (query.length < 3) return null
        val ranked = listings.map { it to nameScore(query, normalized(it.name)) }
            .sortedByDescending(Pair<LangSchwarzListing, Int>::second)
        val best = ranked.firstOrNull()?.takeIf { it.second >= MIN_NAME_SCORE } ?: return null
        val runnerUpScore = ranked.getOrNull(1)?.second
        return best.first.takeIf { runnerUpScore == null || best.second - runnerUpScore >= MIN_SCORE_MARGIN }
    }

    private fun germanWkn(identifier: String): String? =
        GERMAN_ISIN.matchEntire(identifier.trim().uppercase())?.groupValues?.get(1)

    private fun nameScore(query: String, candidate: String): Int = when {
        query == candidate -> 1_000 + query.length
        candidate.startsWith(query) -> 700 + query.length
        query.startsWith(candidate) -> 600 + candidate.length
        candidate.contains(query) -> 500 + query.length
        else -> {
            val queryTokens = tokens(query)
            val candidateTokens = tokens(candidate)
            val shared = queryTokens intersect candidateTokens
            if (shared.isEmpty()) 0 else 100 * shared.size - 25 * (queryTokens.size - shared.size)
        }
    }

    private fun tokens(value: String): Set<String> = value.split(' ').filter { it.length >= 3 }.toSet()

    private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .uppercase()
        .replace(Regex("[^A-Z0-9]+"), " ")
        .trim()

    private val GERMAN_ISIN = Regex("DE000([A-Z0-9]{6})[0-9]")
    private const val MIN_NAME_SCORE = 200
    private const val MIN_SCORE_MARGIN = 100
}
