package org.senatov.mimitrends.text

object HtmlEntities {
    fun decode(value: String): String = ENTITY.replace(value) { match ->
        val token = match.groupValues[1]
        when {
            token.startsWith("#x", ignoreCase = true) -> token.drop(2).toIntOrNull(16)?.asUnicode()
            token.startsWith('#') -> token.drop(1).toIntOrNull()?.asUnicode()
            else -> NAMED[token.lowercase()]
        } ?: match.value
    }

    private fun Int.asUnicode(): String? = takeIf(Character::isValidCodePoint)
        ?.let(Character::toChars)?.concatToString()

    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[A-Za-z]+);")
    private val NAMED = mapOf(
        "amp" to "&", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "szlig" to "ß",
        "aacute" to "á", "eacute" to "é", "iacute" to "í", "oacute" to "ó", "uacute" to "ú",
        "euml" to "ë"
    )
}
