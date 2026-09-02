package org.senatov.mimitrends

import org.senatov.mimitrends.db.InstrumentCatalogEntry

internal class InstrumentSearchIndex(entries: Collection<InstrumentCatalogEntry>) {
    private val entries = entries.distinctBy(InstrumentCatalogEntry::symbol)

    fun search(query: String, limit: Int = 12): List<TableSearchSuggestion> {
        val normalized = query.trim().lowercase()
        if (normalized.length < 2) return emptyList()
        return entries.asSequence()
            .mapNotNull { entry -> rank(entry, normalized)?.let { it to entry } }
            .sortedWith(compareBy<Pair<Int, InstrumentCatalogEntry>> { it.first }.thenBy { it.second.symbol })
            .take(limit)
            .map { (_, entry) -> TableSearchSuggestion(entry.symbol, entry.name, entry.exchange) }
            .toList()
    }

    private fun rank(entry: InstrumentCatalogEntry, query: String): Int? {
        val symbol = entry.symbol.lowercase()
        val name = entry.name.lowercase()
        return when {
            symbol == query -> 0
            symbol.startsWith(query) -> 1
            name.startsWith(query) -> 2
            symbol.contains(query) -> 3
            name.contains(query) -> 4
            else -> null
        }
    }
}
