package org.senatov.mimitrends

internal object SignalAgePresentation {
    private val duration = Regex("^\\s*(\\d+)\\s*(m|minutes?|sessions?)", RegexOption.IGNORE_CASE)

    fun label(value: String): String = duration.find(value)?.let { match ->
        val number = match.groupValues[1]
        val unit = match.groupValues[2].lowercase()
        if (unit == "m") "$number$unit" else "$number $unit"
    } ?: value.substringBefore(' ').ifBlank { "—" }
}
