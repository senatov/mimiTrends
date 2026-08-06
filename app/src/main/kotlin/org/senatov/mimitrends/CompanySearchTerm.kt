package org.senatov.mimitrends

internal object CompanySearchTerm {
    fun normalizeDisplay(companyName: String): String = companyName.trim().replace(WHITESPACE, " ")

    fun from(companyName: String, symbol: String): String {
        val words = normalizeDisplay(companyName).split(WHITESPACE).map { it.trim(*EDGE_PUNCTUATION) }
            .filter { word -> word.any(Char::isLetterOrDigit) }.toMutableList()
        while (words.isNotEmpty() && isRemovableSuffix(words.last())) words.removeLast()
        return words.joinToString(" ").takeIf(String::isNotBlank) ?: symbol.substringBefore('.').trim()
    }

    private fun isRemovableSuffix(word: String): Boolean {
        val normalized = word.filter(Char::isLetterOrDigit).uppercase()
        return normalized in LEGAL_SUFFIXES || normalized.matches(SHARE_CLASS)
    }

    private val WHITESPACE = Regex("\\s+")
    private val SHARE_CLASS = Regex("[A-Z0-9]")
    private val LEGAL_SUFFIXES = setOf(
        "AG", "SE", "INC", "INCORPORATED", "CORP", "CORPORATION", "PLC", "LTD", "LIMITED",
        "SA", "SAS", "SPA", "NV", "BV", "GMBH", "KG", "KGaA", "CO", "COMPANY"
    ).map(String::uppercase).toSet()
    private val EDGE_PUNCTUATION = charArrayOf(',', '.', ';', ':', '(', ')', '[', ']')
}
