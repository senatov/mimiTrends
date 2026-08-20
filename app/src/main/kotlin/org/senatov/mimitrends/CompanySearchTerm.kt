package org.senatov.mimitrends

internal object CompanySearchTerm {
    fun normalizeDisplay(companyName: String): String = cleanedWords(companyName)
        .joinToString(" ").let(::readableCase)

    fun from(companyName: String, symbol: String): String {
        return normalizeDisplay(companyName).takeIf(String::isNotBlank)
            ?: symbol.substringBefore('.').trim()
    }

    private fun cleanedWords(companyName: String): List<String> {
        val words = companyName.replace(PUNCTUATION, " ").trim().split(WHITESPACE)
            .filter { word -> word.any(Char::isLetterOrDigit) }.toMutableList()
        while (words.firstOrNull().equals("The", ignoreCase = true)) words.removeFirst()
        while (words.lastOrNull().equals("The", ignoreCase = true)) words.removeLast()
        while (words.isNotEmpty() && (isRemovableSuffix(words.last()) || words.last() == "&")) words.removeLast()
        return words
    }

    private fun isRemovableSuffix(word: String): Boolean {
        val normalized = word.filter(Char::isLetterOrDigit).uppercase()
        return normalized in LEGAL_SUFFIXES || normalized.matches(SHARE_CLASS) || normalized.matches(SHARE_LABEL)
    }

    private fun readableCase(value: String): String {
        if (value.any(Char::isLowerCase)) return value
        return value.split(WHITESPACE).joinToString(" ") { word ->
            if (word.count(Char::isLetter) <= ACRONYM_MAX_LENGTH) word
            else word.lowercase().replaceFirstChar(Char::uppercase)
        }
    }

    private val WHITESPACE = Regex("\\s+")
    private val SHARE_CLASS = Regex("[A-Z0-9]")
    private val SHARE_LABEL = Regex("(?:ACT|ACTION|ORD|ORDINARY)[A-Z0-9]?")
    private const val ACRONYM_MAX_LENGTH = 3
    private val LEGAL_SUFFIXES = setOf(
        "AG", "SE", "INC", "INCORPORATED", "CORP", "CORPORATION", "PLC", "LTD", "LIMITED",
        "SA", "SAS", "SPA", "NV", "BV", "GMBH", "KG", "KGaA", "CO", "COMPANY"
    ).map(String::uppercase).toSet()
    private val PUNCTUATION = Regex("[,.;:()\\[\\]]+")
}
