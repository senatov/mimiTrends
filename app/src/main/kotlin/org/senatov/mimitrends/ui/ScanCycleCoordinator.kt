package org.senatov.mimitrends.ui

import javafx.application.Platform
import org.senatov.mimitrends.db.AnalyticsRepository
import org.senatov.mimitrends.market.*
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.*
import org.senatov.mimitrends.providers.*
import org.senatov.mimitrends.scanner.*
import org.senatov.mimitrends.services.*
import org.senatov.mimitrends.shared.*
import org.senatov.mimitrends.shortmove.*
import org.slf4j.Logger
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal class ScanCycleCoordinator(
    private val criteriaProvider: () -> ScannerCriteria,
    private val dynamicUniverse: DynamicMarketUniverse,
    private val analytics: AnalyticsRepository,
    private val configureProviderUniverse: (List<String>) -> Unit,
    private val liveTicks: Map<String, Long>,
    private val shortMoveRefresh: ShortMoveRefreshCoordinator,
    private val priorityScanner: PriorityScanCoordinator,
    private val savedResultQuotes: SavedResultQuoteRefresher,
    private val resultDeduplicator: InstrumentResultDeduplicator,
    private val marketData: MarketDataService,
    private val presentUniverse: (DynamicUniverseSelection) -> Unit,
    private val shortMovePanel: ShortMovePanel,
    private val scannerPanel: ScannerPanel,
    private val status: MainStatusController,
    private val scheduler: ScheduledExecutorService,
    private val scannerBatch: ScannerBatchService,
    private val watchlistSymbols: () -> Set<String>,
    private val shortMoveLoader: ShortMoveLoader,
    private val recentEvents: RecentEventRetainer,
    private val scalableProvider: ScalablePollingService,
    private val detectedTodayCount: () -> Int,
    private val isClosing: () -> Boolean,
    private val log: Logger
) {
    private val generation = AtomicLong()
    private val planner = ScanCyclePlanner()
    private var rotationTask: ScheduledFuture<*>? = null

    fun start() {
        val criteria = criteriaProvider()
        log.debug(LogTag.API, "startScanner(symbols={})", criteria.symbols.size)
        priorityScanner.replaceCandidates(emptyList())
        priorityScanner.clearUrgentSymbols()
        recentEvents.clear()
        planner.reset()
        val activeGeneration = generation.incrementAndGet()
        rotationTask?.cancel(false)
        lateinit var scan: () -> Unit
        scan = { runCycle(activeGeneration, criteria, scan) }
        scheduler.execute {
            runCatching(scan).onFailure { log.error(LogTag.API, "initial scanner cycle failed", it) }
        }
    }

    fun stop() {
        generation.incrementAndGet()
        rotationTask?.cancel(false)
        rotationTask = null
    }

    private fun runCycle(activeGeneration: Long, criteria: ScannerCriteria, scan: () -> Unit) {
        if (isClosing()) return
        val cycleStartedNanos = System.nanoTime()
        val universe = dynamicUniverse.select(criteriaProvider())
        if (isClosing() || activeGeneration != generation.get()) return
        val selectedSymbols = universe.symbols
        analytics.recordUniverseSelection(universe.ranks, universe.discovered)
        Platform.runLater { presentUniverse(universe) }
        val nowMillis = System.currentTimeMillis()
        val symbols = planner.order(selectedSymbols.filter { symbol ->
            ScanMarketEligibility.isActive(symbol, liveTicks[symbol], nowMillis)
        })
        configureProviderUniverse(symbols)
        shortMoveRefresh.replaceSymbols(symbols)
        log.info(
            LogTag.API,
            "scan started symbols={} discovered={} recentWindow={}m",
            symbols.size, universe.discovered.size, criteria.maxSignalAgeMinutes
        )
        if (symbols.isEmpty()) {
            rotationTask = handleClosedMarkets(selectedSymbols, criteria, scan)
            return
        }
        beginVisibleScan(symbols, selectedSymbols.size)
        val batch = scannerBatch.execute(
            symbols, criteria,
            { activeGeneration == generation.get() && !isClosing() },
            { completed, symbol ->
                Platform.runLater {
                    shortMovePanel.showScanProgress(completed, symbols.size, selectedSymbols.size)
                    status.update("Market data: analyzed $completed/${symbols.size} · $symbol")
                }
            },
            watchlistSymbols()
        ) ?: return
        val active = resultDeduplicator.deduplicate(batch.active)
        val shortMoves = updateScanState(symbols, active, batch.coverage)
        val displayed = recentEvents.merge(active, System.currentTimeMillis(), criteria.resultLimit)
        replaceProviderSymbols(displayed)
        priorityScanner.replaceCandidates(active)
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cycleStartedNanos)
        val nextDelayMillis = ScanCyclePresentation.nextDelayMillis(criteria.scanIntervalSeconds, elapsedMillis)
        publishCompletedScan(
            activeGeneration, symbols, active, displayed, shortMoves, batch.errors,
            detectedTodayCount(), elapsedMillis, nextDelayMillis, criteria.resultLimit, batch
        )
        if (activeGeneration != generation.get()) return
        rotationTask = scheduler.schedule(
            { runCatching(scan).onFailure { log.error(LogTag.API, "scheduled Yahoo scan failed", it) } },
            nextDelayMillis, TimeUnit.MILLISECONDS
        )
    }

    private fun beginVisibleScan(symbols: List<String>, universeSize: Int) = Platform.runLater {
        scannerPanel.beginScan(1, 1, symbols)
        shortMovePanel.showScanProgress(0, symbols.size, universeSize)
        status.update("Scanning ${symbols.size}/$universeSize liquid symbols · corridors and rapid crashes")
    }

    private fun updateScanState(
        symbols: List<String>,
        active: List<ScanResult>,
        coverage: List<ScanResult>
    ): List<ShortMove> {
        dynamicUniverse.record(coverage)
        planner.replacePriority(active.map(ScanResult::symbol))
        val shortMoves = shortMoveLoader.load(symbols)
        priorityScanner.addUrgentSymbols(shortMoves.rapidCrashSymbols())
        return shortMoves
    }

    private fun replaceProviderSymbols(displayed: List<ScanResult>) {
        val symbols = displayed.map(ScanResult::symbol)
        scalableProvider.replaceSymbols(symbols)
    }

    private fun handleClosedMarkets(
        selectedSymbols: List<String>,
        criteria: ScannerCriteria,
        scan: () -> Unit
    ): ScheduledFuture<*> {
        priorityScanner.replaceCandidates(emptyList())
        priorityScanner.clearUrgentSymbols()
        val now = Instant.now()
        val nextOpening = MarketCalendar.nextOpening(selectedSymbols, now)
        val delaySeconds = nextOpening?.let { Duration.between(now, it.instant).seconds.coerceAtLeast(1) + 5L }
            ?: criteria.scanIntervalSeconds
        val resumeText = nextOpening?.let(MarketHoursFormatter::nextOpening) ?: "market schedule unavailable"
        val persisted = savedResultQuotes.refresh(analytics.loadLatestPublishedResults(criteria.resultLimit))
        val saved = resultDeduplicator.deduplicate(
            persisted.ifEmpty { marketData.closedMarketSnapshot(MarketUniverseSelector.select(criteria), criteria) }
        )
        val userZone = ZoneId.systemDefault()
        val marketHours = MarketHoursFormatter.priceData(selectedSymbols, now, userZone)
        val brokerHours = MarketHoursFormatter.scalable(now, userZone)
        val localZoneName = DateTimeFormatter.ofPattern("z").format(now.atZone(userZone))
        Platform.runLater {
            scannerPanel.beginScan(1, 1, emptyList())
            saved.forEach(scannerPanel::update)
            scannerPanel.completeScan(criteria.resultLimit)
            scannerPanel.showCountdown(delaySeconds, showIdleStatus = false)
            scannerPanel.showMarketClosed(
                saved.size, persisted.isNotEmpty(), resumeText, localZoneName, marketHours, brokerHours
            )
            status.update(closedMarketStatus(saved.size, persisted.isNotEmpty(), resumeText))
        }
        return scheduler.schedule(
            { runCatching(scan).onFailure { log.error(LogTag.API, "scheduled market-open resume failed", it) } },
            delaySeconds, TimeUnit.SECONDS
        )
    }

    private fun closedMarketStatus(savedCount: Int, persisted: Boolean, resumeText: String): String = when {
        savedCount == 0 -> "All selected markets are closed · scanner paused until $resumeText"
        persisted -> "Markets closed · showing $savedCount saved results · resumes $resumeText"
        else -> "Markets closed · showing $savedCount cached results · resumes $resumeText"
    }

    private fun publishCompletedScan(
        activeGeneration: Long,
        symbols: List<String>,
        active: List<ScanResult>,
        displayed: List<ScanResult>,
        shortMoves: List<ShortMove>,
        errors: List<String>,
        detectedCount: Int,
        elapsedMillis: Long,
        nextDelayMillis: Long,
        resultLimit: Int,
        batch: ScannerBatchResult
    ) {
        if (errors.isNotEmpty()) {
            log.warn(LogTag.API, "scan completed with failures count={} sample={}", errors.size, errors.take(3).joinToString("; "))
        }
        val nextDelaySeconds = ScanCyclePresentation.countdownSeconds(nextDelayMillis)
        val diagnostics = ScanCyclePresentation.diagnostics(batch, elapsedMillis)
        Platform.runLater {
            if (activeGeneration != generation.get()) return@runLater
            shortMovePanel.show(shortMoves)
            if (active.isEmpty() && errors.size == symbols.size && symbols.isNotEmpty()) {
                scannerPanel.abortScan()
                status.update("Yahoo scan produced no data; previous table retained", true, errors.joinToString("\n"))
                return@runLater
            }
            displayed.forEach(scannerPanel::update)
            scannerPanel.completeScan(resultLimit)
            scannerPanel.setDetectedTodayCount(detectedCount)
            val marketState = "${active.size} live corridor/crash setups"
            status.update(
                if (active.isEmpty()) "No current candidates · $diagnostics · next in ${nextDelaySeconds}s"
                else "Focused scan complete · $marketState · $diagnostics · next in ${nextDelaySeconds}s"
            )
            scannerPanel.showCountdown(nextDelaySeconds)
        }
    }
}

private fun Collection<ShortMove>.rapidCrashSymbols(): List<String> = asSequence()
    .filter { it.pattern == ShortMovePattern.RAPID_CRASH }
    .map(ShortMove::symbol)
    .toList()
