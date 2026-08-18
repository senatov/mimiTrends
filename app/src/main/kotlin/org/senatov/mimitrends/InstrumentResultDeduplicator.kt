package org.senatov.mimitrends

import org.senatov.mimitrends.model.ScanResult

/** Collapses multiple exchange listings of the same instrument into one scanner result. */
internal class InstrumentResultDeduplicator(
    private val loadIsin: (String) -> String?,
    private val loadCompanyName: (String) -> String?
) {
    fun deduplicate(results: Collection<ScanResult>): List<ScanResult> {
        val retained = mutableListOf<IdentifiedResult>()
        results.forEach { result ->
            val identified = identify(result)
            val duplicateIndex = retained.indexOfFirst { existing -> sameInstrument(existing, identified) }
            if (duplicateIndex < 0) {
                retained += identified
            } else if (isPreferred(identified.result, retained[duplicateIndex].result)) {
                retained[duplicateIndex] = identified
            }
        }
        return retained.map(IdentifiedResult::result)
    }

    private fun identify(result: ScanResult): IdentifiedResult {
        val isin = loadIsin(result.symbol)?.trim()?.uppercase()?.takeIf(String::isNotBlank)
        val name = loadCompanyName(result.symbol)?.let { CompanySearchTerm.from(it, result.symbol) }
            ?.trim()?.lowercase()?.takeIf(String::isNotBlank)
        return IdentifiedResult(result, isin, name)
    }

    private fun sameInstrument(left: IdentifiedResult, right: IdentifiedResult): Boolean {
        if (left.isin != null && right.isin != null) return left.isin == right.isin
        return left.name != null && left.name == right.name
    }

    private fun isPreferred(candidate: ScanResult, retained: ScanResult): Boolean =
        candidate.anomalyScore > retained.anomalyScore ||
            candidate.anomalyScore == retained.anomalyScore && candidate.updatedAtMillis > retained.updatedAtMillis

    private data class IdentifiedResult(val result: ScanResult, val isin: String?, val name: String?)
}
