package org.senatov.mimitrends

import org.senatov.mimitrends.model.ProviderInstrument

internal object ProviderInstrumentSelector {
    fun select(
        symbol: String,
        companyName: String?,
        candidates: Collection<ProviderInstrument>,
        isEligible: (ProviderInstrument) -> Boolean
    ): ProviderInstrument? = candidates.asSequence()
        .filter(isEligible)
        .filter { companyName.isNullOrBlank() || matchesCompany(symbol, companyName, it.resolvedName) }
        .minByOrNull { providerPriority(symbol, it.provider) }

    fun matchesCompany(symbol: String, expectedName: String?, candidateName: String): Boolean {
        if (expectedName.isNullOrBlank()) return true
        val expected = normalizedCore(expectedName, symbol)
        val candidate = normalizedCore(candidateName, symbol)
        if (expected.isBlank() || candidate.isBlank()) return false
        return expected == candidate ||
            (expected.length >= MIN_CONTAINED_NAME_LENGTH && candidate.contains(expected)) ||
            (candidate.length >= MIN_CONTAINED_NAME_LENGTH && expected.contains(candidate))
    }

    private fun normalizedCore(name: String, symbol: String): String =
        CompanySearchTerm.from(name, symbol).uppercase().replace(NON_ALPHANUMERIC, " ")
            .split(WHITESPACE).filter { it.length >= MIN_TOKEN_LENGTH }.joinToString(" ")

    private fun providerPriority(symbol: String, provider: String): Int {
        val preferred = PREFERRED_PROVIDER_BY_SUFFIX[symbol.substringAfterLast('.', "").uppercase()]
        return if (provider.equals(preferred, ignoreCase = true)) 0 else 1
    }

    private val NON_ALPHANUMERIC = Regex("[^A-Z0-9]+")
    private val WHITESPACE = Regex("\\s+")
    private const val MIN_TOKEN_LENGTH = 2
    private const val MIN_CONTAINED_NAME_LENGTH = 4
    private val PREFERRED_PROVIDER_BY_SUFFIX = mapOf(
        "MI" to "EURONEXT",
        "PA" to "EURONEXT",
        "AS" to "EURONEXT",
        "BR" to "EURONEXT",
        "LS" to "EURONEXT",
        "HE" to "EURONEXT"
    )
}
