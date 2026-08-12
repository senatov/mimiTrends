package org.senatov.mimitrends

internal data class SignalPatternText(val primary: String, val qualifiers: String?, val watchLabel: String?) {
    fun measurementText(scoreLabel: String): String = buildList {
        add(primary)
        watchLabel?.let { add("$it $scoreLabel") }
            ?: qualifiers?.let { add("$it $scoreLabel") }
            ?: add(scoreLabel)
        if (watchLabel != null) qualifiers?.let(::add)
    }.joinToString("\n")

    companion object {
        fun parse(source: String): SignalPatternText {
            val parts = source.split(" · ").map(String::trim).filter(String::isNotEmpty)
            val primary = parts.firstOrNull().orEmpty()
            val hasWatch = parts.drop(1).any { it.contains("watch", ignoreCase = true) }
            val qualifiers = parts.drop(1).filterNot { it.contains("watch", ignoreCase = true) }
                .takeIf(List<String>::isNotEmpty)?.joinToString(" · ")
            return SignalPatternText(primary, qualifiers, if (hasWatch) watchLabel(primary, parts) else null)
        }

        private fun watchLabel(primary: String, parts: List<String>): String = when {
            primary.startsWith("Early recovery", ignoreCase = true) ||
                primary.startsWith("Recovery", ignoreCase = true) -> "* Recovery watch *"
            primary.startsWith("Oversold", ignoreCase = true) -> "* Oversold watch *"
            parts.any { it.contains("downside watch", ignoreCase = true) } -> "* Downside watch *"
            else -> "* Watch *"
        }
    }
}
