package org.senatov.mimitrends

import org.senatov.mimitrends.marketdata.LangSchwarzListing

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
        return listings.map { it to nameScore(query, normalized(it.name)) }
            .filter { (_, score) -> score >= MIN_NAME_SCORE }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    private fun germanWkn(identifier: String): String? =
        GERMAN_ISIN.matchEntire(identifier.trim().uppercase())?.groupValues?.get(1)

    private fun nameScore(query: String, candidate: String): Int = when {
        query == candidate -> 1_000 + query.length
        candidate.startsWith(query) -> 700 + query.length
        query.startsWith(candidate) -> 600 + candidate.length
        candidate.contains(query) -> 500 + query.length
        else -> query.split(' ').filter { it.length >= 3 }.count(candidate::contains) * 100
    }

    private fun normalized(value: String): String = value.uppercase()
        .replace(Regex("[^A-Z0-9]+"), " ")
        .trim()

    private val GERMAN_ISIN = Regex("DE000([A-Z0-9]{6})[0-9]")
    private const val MIN_NAME_SCORE = 200
}
