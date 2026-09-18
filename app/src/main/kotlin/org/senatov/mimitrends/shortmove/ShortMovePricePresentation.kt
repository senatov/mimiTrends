package org.senatov.mimitrends.shortmove

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

import java.util.Locale

internal object ShortMovePricePresentation {
    fun text(move: ShortMove): String = "${format(move.open)} →\n${format(move.close)}"

    private fun format(value: Double): String = String.format(Locale.ROOT, "%,.2f", value)
}
