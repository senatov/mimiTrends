package org.senatov.mimitrends

internal enum class MarketCountry {
    US,
    DE,
    FR,
    NL,
    IT,
    FI
}

internal data class MarketVenueVisual(val country: MarketCountry, val venue: String)

internal object MarketVenuePresentation {
    fun forInstrument(symbol: String, source: String): MarketVenueVisual {
        val normalized = source.trim().uppercase().replace(' ', '_')
        return when (normalized) {
            "TRADEGATE" -> MarketVenueVisual(MarketCountry.DE, "Tradegate · Germany")
            "LANG_SCHWARZ" -> MarketVenueVisual(MarketCountry.DE, "Lang & Schwarz · Germany")
            "WALLSTREET_ONLINE" -> MarketVenueVisual(MarketCountry.DE, "wallstreetONLINE · Germany")
            "EURONEXT" -> europeanSuffix(symbol, "Euronext")
            else -> if (symbol.contains('.')) europeanSuffix(symbol, source) else
                MarketVenueVisual(MarketCountry.US, venueName(source, "United States"))
        }
    }

    private fun europeanSuffix(symbol: String, source: String): MarketVenueVisual = when {
        symbol.endsWith(".PA", true) -> MarketVenueVisual(MarketCountry.FR, venueName(source, "France"))
        symbol.endsWith(".AS", true) -> MarketVenueVisual(MarketCountry.NL, venueName(source, "Netherlands"))
        symbol.endsWith(".MI", true) -> MarketVenueVisual(MarketCountry.IT, venueName(source, "Italy"))
        symbol.endsWith(".HE", true) -> MarketVenueVisual(MarketCountry.FI, venueName(source, "Finland"))
        else -> MarketVenueVisual(MarketCountry.DE, venueName(source, "Germany"))
    }

    private fun venueName(source: String, fallback: String): String =
        source.takeIf { it.isNotBlank() && !it.equals("CACHE", true) }?.replace('_', ' ') ?: fallback
}