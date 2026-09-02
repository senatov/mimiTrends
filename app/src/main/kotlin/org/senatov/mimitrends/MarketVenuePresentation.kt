package org.senatov.mimitrends

internal data class MarketVenueVisual(val flag: String, val venue: String)

internal object MarketVenuePresentation {
    fun forInstrument(symbol: String, source: String): MarketVenueVisual {
        val normalized = source.trim().uppercase().replace(' ', '_')
        return when (normalized) {
            "TRADEGATE" -> MarketVenueVisual("🇩🇪", "Tradegate · Germany")
            "LANG_SCHWARZ" -> MarketVenueVisual("🇩🇪", "Lang & Schwarz · Germany")
            "WALLSTREET_ONLINE" -> MarketVenueVisual("🇩🇪", "wallstreetONLINE · Germany")
            "EURONEXT" -> europeanSuffix(symbol, "Euronext")
            else -> if (symbol.contains('.')) europeanSuffix(symbol, source) else
                MarketVenueVisual("🇺🇸", venueName(source, "United States"))
        }
    }

    private fun europeanSuffix(symbol: String, source: String): MarketVenueVisual = when {
        symbol.endsWith(".PA", true) -> MarketVenueVisual("🇫🇷", venueName(source, "France"))
        symbol.endsWith(".AS", true) -> MarketVenueVisual("🇳🇱", venueName(source, "Netherlands"))
        symbol.endsWith(".MI", true) -> MarketVenueVisual("🇮🇹", venueName(source, "Italy"))
        symbol.endsWith(".HE", true) -> MarketVenueVisual("🇫🇮", venueName(source, "Finland"))
        else -> MarketVenueVisual("🇩🇪", venueName(source, "Germany"))
    }

    private fun venueName(source: String, fallback: String): String =
        source.takeIf { it.isNotBlank() && !it.equals("CACHE", true) }?.replace('_', ' ') ?: fallback
}
