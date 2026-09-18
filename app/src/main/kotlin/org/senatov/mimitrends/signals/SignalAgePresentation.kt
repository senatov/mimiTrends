package org.senatov.mimitrends.signals

import org.senatov.mimitrends.application.*
import org.senatov.mimitrends.ui.*
import org.senatov.mimitrends.scanner.*
import org.senatov.mimitrends.shortmove.*
import org.senatov.mimitrends.signals.*
import org.senatov.mimitrends.research.*
import org.senatov.mimitrends.market.*
import org.senatov.mimitrends.providers.*
import org.senatov.mimitrends.company.*
import org.senatov.mimitrends.services.*
import org.senatov.mimitrends.shared.*

internal object SignalAgePresentation {
    fun label(ageMinutes: Int): String {
        val bounded = ageMinutes.coerceAtLeast(0)
        return "%02d:%02d".format(bounded / 60, bounded % 60)
    }
}
