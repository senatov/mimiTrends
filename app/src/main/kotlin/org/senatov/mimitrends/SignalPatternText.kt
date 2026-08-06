package org.senatov.mimitrends

internal data class SignalPatternText(val primary: String, val qualifiers: String?) {
    companion object {
        fun parse(source: String): SignalPatternText {
            val parts = source.split(" · ").map(String::trim).filter(String::isNotEmpty)
            return SignalPatternText(parts.firstOrNull().orEmpty(), parts.drop(1).takeIf { it.isNotEmpty() }?.joinToString(" · "))
        }
    }
}
