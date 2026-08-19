package org.senatov.mimitrends.model

/** Symbols excluded because purchases are subject to the French financial transaction tax. */
object FinancialTransactionTaxExclusions {
    private val symbols = setOf(
        "MC.PA", "OR.PA", "TTE.PA", "BNP.PA", "SAN.PA", "SU.PA", "RMS.PA", "DG.PA",
        "CS.PA", "RI.PA", "ACA.PA", "VIE.PA", "KER.PA", "CAP.PA", "ORA.PA", "GLE.PA",
        "ENGI.PA", "DSY.PA", "HO.PA", "PUB.PA", "ML.PA", "CA.PA", "SGO.PA", "LR.PA"
    )

    fun contains(symbol: String): Boolean = symbol.uppercase() in symbols

    fun removeFrom(values: List<String>): List<String> = values.filterNot(::contains)
}
