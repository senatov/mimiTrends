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

data class MarketSeries(
    val symbol: String,
    val bars: List<MinuteBar>,
    val companyName: String,
    val exchange: String,
    val currency: String
)

enum class DisplayCurrency(val symbol: String) { EUR("€"), USD("$") }
enum class AnomalyWindow(val label: String, val seconds: Long?) {
    MINUTE("1 minute", 60), HOUR("1 hour", 3_600), SESSION("Current session", null);
    override fun toString(): String = label
}
enum class MarketRegion(val label: String) {
    BOTH("US + Europe"), US("United States"), EUROPE("Europe");
    override fun toString(): String = label
}

data class TableAppearance(
    val fontFamily: String = "SF Pro Display",
    val fontSize: Double = 12.0,
    val textColor: String = "#263238",
    val evenRowColor: String = "#FAFAFA",
    val oddRowColor: String = "#F0F0F0",
    val selectionColor: String = "#DCE8F6",
    val gridColor: String = "#9CA9B5"
)

data class ScannerCriteria(
    val anomalyWindow: AnomalyWindow = AnomalyWindow.HOUR,
    val marketRegion: MarketRegion = MarketRegion.BOTH,
    val scanIntervalSeconds: Long = 180,
    val resultLimit: Int = 50,
    val minPrice: Double = 2.0,
    val minSessionTurnover: Double = 0.0,
    val baselineSessions: Int = 5,
    val maxSignalAgeMinutes: Int = 2,
    val minJumpZ: Double = 3.0,
    val minRangeZ: Double = 3.5,
    val minVolumeZ: Double = 2.0,
    val minRelativeVolume: Double = 1.8,
    val minBodyRatio: Double = 0.55,
    val minAbsoluteMovePercent: Double = 0.20,
    val minimumTableResults: Int = 12,
    val trendWindowMinutes: Int = 180,
    val minTrendReturnPercent: Double = 0.45,
    val minTrendEfficiency: Double = 0.08,
    val displayCurrency: DisplayCurrency = DisplayCurrency.EUR,
    val tableAppearance: TableAppearance = TableAppearance(),
    val symbols: List<String> = listOf(
        "AAPL", "MSFT", "NVDA", "AMZN", "META", "GOOGL", "TSLA", "AVGO", "JPM", "V",
        "MA", "LLY", "WMT", "ORCL", "NFLX", "AMD", "COST", "HD", "BAC", "XOM",
        "CVX", "CRM", "KO", "PEP", "DIS",
        "SAP.DE", "SIE.DE", "ALV.DE", "DTE.DE", "BMW.DE", "MBG.DE", "BAS.DE", "RWE.DE", "DBK.DE", "DHL.DE",
        "ASML.AS", "INGA.AS", "AD.AS", "UNA.AS", "PHIA.AS", "MC.PA", "OR.PA", "TTE.PA", "AIR.PA", "BNP.PA",
        "SAN.PA", "SU.PA", "ENEL.MI", "ISP.MI", "STLAM.MI",
        "UBER", "ABNB", "PYPL", "QCOM", "INTC", "MU", "AMAT", "TSM", "NVO", "PFE",
        "MRK", "ABBV", "GS", "MS", "C", "CAT", "GE", "BA", "NKE", "SBUX",
        "SHOP", "PLTR", "COIN", "SOFI", "SNAP",
        "ADS.DE", "IFX.DE", "VOW3.DE", "HEN3.DE", "BEI.DE", "BAYN.DE", "MTX.DE", "ZAL.DE", "FRE.DE", "EOAN.DE",
        "VNA.DE", "RMS.PA", "DG.PA", "CS.PA", "RI.PA", "ACA.PA", "STMPA.PA", "ASM.AS", "HEIA.AS", "KPN.AS",
        "PRX.AS", "UCG.MI", "ENI.MI", "G.MI", "NOKIA.HE"
    )
)

data class ScanResult(
    val symbol: String,
    val price: Double,
    val anomalyScore: Double,
    val priceAnomaly: Double,
    val volumeAnomaly: Double,
    val rangeAnomaly: Double,
    val relativeVolume: Double,
    val candleBodyRatio: Double,
    val windowChangePercent: Double,
    val windowVolume: Double,
    val sessionVolume: Double,
    val sessionTurnover: Double,
    val signalAgeMinutes: Int,
    val signalSource: String,
    val updatedAtMillis: Long,
    val dataStatus: String = "CACHE",
    val signalWindowLabel: String = "1m"
)

data class CompanyProfile(
    val symbol: String,
    val name: String,
    val exchange: String,
    val logoUrl: String?,
    val logoBytes: ByteArray? = null,
    val updatedAtMillis: Long = System.currentTimeMillis()
)
