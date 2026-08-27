package org.senatov.mimitrends.model

/** Markets excluded from opportunity scanning because the broker may apply country-specific fees or taxes. */
object FinancialTransactionTaxExclusions {
    private val symbols = setOf(
        "MC.PA", "OR.PA", "TTE.PA", "BNP.PA", "SAN.PA", "SU.PA", "RMS.PA", "DG.PA",
        "CS.PA", "RI.PA", "ACA.PA", "VIE.PA", "KER.PA", "CAP.PA", "ORA.PA", "GLE.PA",
        "ENGI.PA", "DSY.PA", "HO.PA", "PUB.PA", "ML.PA", "CA.PA", "SGO.PA", "LR.PA"
    )

    private val blockedMarketSuffixes = setOf(".PA", ".MI", ".HE", ".CO")

    fun contains(symbol: String): Boolean {
        val normalized = symbol.uppercase()
        return normalized in symbols || blockedMarketSuffixes.any(normalized::endsWith)
    }

    fun removeFrom(values: List<String>): List<String> = values.filterNot(::contains)
}
