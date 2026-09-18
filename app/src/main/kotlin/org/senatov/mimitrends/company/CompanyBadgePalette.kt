package org.senatov.mimitrends.company

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

internal object CompanyBadgePalette {
    private val colors = listOf(
        Colors("#DDEBFA", "#174A7C"), Colors("#E2F2E9", "#17613D"),
        Colors("#F5E8D5", "#7A4A12"), Colors("#EAE3F7", "#55358A"),
        Colors("#F6E1E7", "#82334B"), Colors("#DCEFF0", "#1D5D62")
    )

    fun forSymbol(symbol: String): Colors = colors[Math.floorMod(symbol.uppercase().hashCode(), colors.size)]

    data class Colors(val background: String, val foreground: String)
}
