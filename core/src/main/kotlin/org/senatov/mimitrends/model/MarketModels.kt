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
    val volume: Double,
    val volumeStatus: VolumeStatus = VolumeStatus.REPORTED
)

data class ProviderMinuteBar(
    val provider: String,
    val symbol: String,
    val identifier: String,
    val mic: String,
    val currency: String,
    val bar: MinuteBar,
    val observedAtMillis: Long
)

data class ProviderInstrument(
    val provider: String,
    val symbol: String,
    val identifier: String,
    val mic: String,
    val currency: String,
    val resolvedName: String,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

data class ProviderQuoteSnapshot(
    val provider: String,
    val symbol: String,
    val identifier: String,
    val currency: String,
    val last: Double,
    val bid: Double?,
    val ask: Double?,
    val bidSize: Double?,
    val askSize: Double?,
    val sessionVolume: Double?,
    val sessionTurnover: Double?,
    val averagePrice: Double?,
    val executions: Long?,
    val sessionHigh: Double?,
    val sessionLow: Double?,
    val previousClose: Double?,
    val observedAtMillis: Long
)

enum class VolumeStatus {
    REPORTED,
    ZERO,
    MISSING,
    ESTIMATED;

    val isReliable: Boolean get() = this == REPORTED

    companion object {
        fun aggregate(statuses: List<VolumeStatus>): VolumeStatus = when {
            statuses.isEmpty() -> MISSING
            statuses.all { it == REPORTED } -> REPORTED
            statuses.all { it == ZERO } -> ZERO
            statuses.none(VolumeStatus::isReliable) && statuses.any { it == MISSING } -> MISSING
            else -> ESTIMATED
        }
    }
}

data class MarketSeries(
    val symbol: String,
    val bars: List<MinuteBar>,
    val companyName: String,
    val exchange: String,
    val currency: String,
    val events: List<MarketEvent> = emptyList()
)

data class MarketEvent(
    val type: String,
    val epochSeconds: Long,
    val ratio: Double? = null,
    val amount: Double? = null,
    val currency: String? = null
)

enum class DisplayCurrency(val symbol: String) { EUR("€"), USD("$") }
enum class MarketDataSource { SQLITE, YAHOO, FINNHUB, TRADEGATE, EURONEXT, BOERSE_DE, BNP_PARIBAS }
enum class MarketObservationQuality { FULL_OHLCV, QUOTE_SNAPSHOT }
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
    val selectionColor: String = "#FFFDE1",
    val gridColor: String = "#9CA9B5"
)

data class ScannerCriteria(
    val anomalyWindow: AnomalyWindow = AnomalyWindow.HOUR,
    val marketRegion: MarketRegion = MarketRegion.BOTH,
    val scanIntervalSeconds: Long = 180,
    val resultLimit: Int = 15,
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
    val tradegateEnabled: Boolean = false,
    val tradegateRequestIntervalMillis: Long = 1_000,
    val euronextEnabled: Boolean = false,
    val euronextRequestIntervalMillis: Long = 1_500,
    val tableAppearance: TableAppearance = TableAppearance(),
    val symbols: List<String> = DefaultSymbolUniverse.symbols
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
    val signalWindowLabel: String = "1m",
    val signalPrice: Double = price,
    val signalEpochMillis: Long = updatedAtMillis,
    val continuationProbability: Double = Double.NaN,
    val calibrationSamples: Int = 0,
    val calibrationHorizonMinutes: Int = 10,
    val continuationLowerBound: Double = Double.NaN,
    val continuationUpperBound: Double = Double.NaN,
    val medianNetReturnPercent: Double = Double.NaN,
    val lowerQuartileNetReturnPercent: Double = Double.NaN,
    val upperQuartileNetReturnPercent: Double = Double.NaN,
    val medianFavorableExcursionPercent: Double = Double.NaN,
    val medianAdverseExcursionPercent: Double = Double.NaN
)

data class CompanyProfile(
    val symbol: String,
    val name: String,
    val exchange: String,
    val logoUrl: String?,
    val logoBytes: ByteArray? = null,
    val updatedAtMillis: Long = System.currentTimeMillis()
)
