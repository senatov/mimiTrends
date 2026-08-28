package org.senatov.mimitrends.model

object InstrumentCurrency {
    fun infer(symbol: String): String =
        if (EURO_SUFFIXES.any(symbol.trim().uppercase()::endsWith)) "EUR" else "USD"

    private val EURO_SUFFIXES = listOf(".DE", ".F", ".PA", ".AS", ".MI", ".HE")
}
