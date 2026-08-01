package org.senatov.mimitrends.model

import java.time.ZoneId

object MarketTimeZone {
    fun forSymbol(symbol: String): ZoneId = when {
        symbol.endsWith(".HE", ignoreCase = true) -> HELSINKI
        symbol.contains('.') -> CENTRAL_EUROPE
        else -> NEW_YORK
    }

    private val NEW_YORK = ZoneId.of("America/New_York")
    private val CENTRAL_EUROPE = ZoneId.of("Europe/Berlin")
    private val HELSINKI = ZoneId.of("Europe/Helsinki")
}
