package org.senatov.mimitrends.model

data class TradeTick(
    val symbol: String,
    val price: Double,
    val timestampMillis: Long,
    val volume: Double
)

data class MinuteBar(
    val symbol: String,
    val minuteEpochSeconds: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

enum class DisplayCurrency(val symbol: String) { EUR("€"), USD("$") }

data class ScannerCriteria(
    val minRelativeVolume: Double = 0.0,
    val minChange1mPercent: Double = -0.10,
    val minChange5mPercent: Double = -0.25,
    val minPrice: Double = 8.0,
    val minSessionVolume: Double = 20_000.0,
    val baselineSessions: Int = 20,
    val batchSize: Int = 50,
    val rotationSeconds: Long = 30,
    val displayCurrency: DisplayCurrency = DisplayCurrency.EUR,
    val symbols: List<String> = listOf(
        "AAPL", "ABBV", "ABT", "ADBE", "AMD", "AMGN", "AMZN", "AVGO", "AXP", "BA",
        "BAC", "CAT", "CMCSA", "COP", "COST", "CRM", "CSCO", "CVX", "DIS", "GOOG",
        "GOOGL", "GS", "HD", "IBM", "INTC", "JNJ", "JPM", "KO", "LLY", "MA",
        "MCD", "META", "MRK", "MSFT", "NFLX", "NKE", "NVDA", "ORCL", "PEP", "PFE",
        "PG", "QCOM", "SBUX", "T", "TSLA", "TXN", "UNH", "V", "VZ", "WMT",
        "ADS.DE", "AIR.DE", "ALV.DE", "BAS.DE", "BAYN.DE", "BEI.DE", "BMW.DE", "CON.DE", "DB1.DE", "DBK.DE",
        "DHL.DE", "DTE.DE", "ENR.DE", "FRE.DE", "HEI.DE", "HEN3.DE", "HNR1.DE", "IFX.DE", "MBG.DE", "MRK.DE",
        "MTX.DE", "MUV2.DE", "PAH3.DE", "P911.DE", "QIA.DE", "RWE.DE", "SAP.DE", "SHL.DE", "SIE.DE", "SY1.DE",
        "VNA.DE", "VOW3.DE", "ZAL.DE", "ASML.AS", "AD.AS", "INGA.AS", "PHIA.AS", "PRX.AS", "UNA.AS", "MC.PA",
        "OR.PA", "SAN.PA", "SU.PA", "TTE.PA", "BNP.PA", "DG.PA", "KER.PA", "RMS.PA", "ENEL.MI", "ISP.MI"
    )
)

data class ScanResult(
    val symbol: String,
    val price: Double,
    val relativeVolume: Double?,
    val change1mPercent: Double?,
    val change5mPercent: Double?,
    val sessionVolume: Double,
    val matches: Boolean,
    val updatedAtMillis: Long
)
