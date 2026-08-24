package org.senatov.mimitrends

internal object CompanyBadgePalette {
    private val colors = listOf(
        Colors("#DDEBFA", "#174A7C"), Colors("#E2F2E9", "#17613D"),
        Colors("#F5E8D5", "#7A4A12"), Colors("#EAE3F7", "#55358A"),
        Colors("#F6E1E7", "#82334B"), Colors("#DCEFF0", "#1D5D62")
    )

    fun forSymbol(symbol: String): Colors = colors[Math.floorMod(symbol.uppercase().hashCode(), colors.size)]

    data class Colors(val background: String, val foreground: String)
}
