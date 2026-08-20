package org.senatov.mimitrends

internal object SignalAgePresentation {
    fun label(ageMinutes: Int): String {
        val bounded = ageMinutes.coerceAtLeast(0)
        return "%02d:%02d".format(bounded / 60, bounded % 60)
    }
}
