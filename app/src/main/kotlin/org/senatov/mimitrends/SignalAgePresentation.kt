package org.senatov.mimitrends

internal object SignalAgePresentation {
    fun label(ageMinutes: Int): String = if (ageMinutes <= 0) "now" else "${ageMinutes}m"
}
