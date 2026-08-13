package org.senatov.mimitrends

import javafx.application.Platform
import javafx.scene.Parent
import javafx.scene.control.*
import javafx.scene.layout.*
import org.senatov.mimitrends.charts.TrendChartView
import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.db.AnalyticsRepository
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.*
import org.senatov.mimitrends.scanner.ScannerEngine
import org.senatov.mimitrends.scanner.ScannerSettingsService
import org.senatov.mimitrends.scanner.MarketCalendar
import org.senatov.mimitrends.ws.FinnhubProfileClient
import org.senatov.mimitrends.ws.FinnhubWebSocketClient
import org.senatov.mimitrends.ws.FinnhubMinuteAggregator
import org.senatov.mimitrends.marketdata.YahooFinanceClient
import org.senatov.mimitrends.marketdata.CompanyLogoClient
import org.slf4j.LoggerFactory
import javafx.util.Duration
import java.util.concurrent.*
import java.util.function.BiConsumer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

class MainController(private val apiKey: String?, initialSymbol: String = "AAPL", initialRange: String = "3M",
    initialDividerPosition: Double = 0.34, scannerColumns: String = "", shortMoveColumns: String = "",
    initialTableDivider: Double = 0.68) {
    private companion object { const val MARKET_OPEN_GRACE_SECONDS = 5L }
    private val log = LoggerFactory.getLogger(MainController::class.java)
    private val repository = MarketRepository()
    private val analytics = AnalyticsRepository()
    private val exchangeRates = ExchangeRateService()
    private val savedResultQuotes = SavedResultQuoteRefresher(repository)
    private val shortMoveLoader = ShortMoveLoader(repository, exchangeRates)
    private var currentSymbol = initialSymbol
    private var currentSignal: ScanResult? = null
    private var selectedRangeValue = initialRange.takeIf { it in setOf("1D", "5D", "1M", "3M", "6M", "1Y") } ?: "3M"
    private val refreshButton = Button("↻")
    private val settingsButton = Button("⚙")
    private val aboutButton = Button("ⓘ")
    private val importTradesButton = Button("⇩")
    private val requestStatus = RequestStatusPane { selectedRangeValue }
    private val trendChart = TrendChartView()
    private val scannerSettings = ScannerSettingsService()
    private var scannerCriteria: ScannerCriteria = scannerSettings.load()
    private val currencyConverter = ScanResultCurrencyConverter(exchangeRates) { scannerCriteria }
    private val status = MainStatusController(requestStatus, trendChart, refreshButton, log)
    private val scannerEngine = ScannerEngine()
    private val yahooFinance = YahooFinanceClient()
    private var profileService = CompanyProfileService(
        repository, apiKey?.let(::FinnhubProfileClient), CompanyLogoClient()
    )
    private val shortMovePanel = ShortMovePanel(
        { symbol, moveEpochSeconds ->
            trendChart.showSignalFocus(moveEpochSeconds)
            shortMoveSelection.open(symbol)
        },
        shortMoveColumns, { symbol -> profileService.load(symbol) }, ClipboardText::copy
    )
    private val scannerPanel = ScannerPanel(
        onOpen = ::openScannerResult,
        shortMovePanel = shortMovePanel,
        savedColumns = scannerColumns,
        initialTableDivider = initialTableDivider,
        loadProfile = { symbol -> profileService.load(symbol) }
    )
    private val shortMoveRefresh = ShortMoveRefreshCoordinator(shortMoveLoader::load, log) { moves ->
        Platform.runLater { if (!closing.get()) shortMovePanel.show(moves) }
    }
    private val batchScheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mimitrends-scanner-rotation").apply { isDaemon = true }
    }
    private val scalableImport = ScalableImportAction(analytics, batchScheduler)
    private val researchReport = ResearchReportAction(
        analytics, repository, scannerEngine, { scannerCriteria }, status::update
    )
    private val scalableImportResults = ScalableImportResultHandler(
        importTradesButton, status::update, requestStatus::formatError, log
    )
    private var rotationTask: ScheduledFuture<*>? = null
    private val scanGeneration = AtomicLong()
    private val scanCyclePlanner = ScanCyclePlanner()
    private val closing = AtomicBoolean()
    private val chartDataLoader = ChartDataLoader(repository, analytics, exchangeRates)
    private val initialDivider = initialDividerPosition.coerceIn(0.15, 0.75)
    private val contentSplitPane = SplitPane()
    private var finnhubClient: FinnhubWebSocketClient? = null
    private val liveTicks = ConcurrentHashMap<String, Long>()
    private val feedStatus = FeedStatusResolver(liveTicks)
    private val marketData = MarketDataService(repository, analytics, scannerEngine, yahooFinance, feedStatus::status)
    private val scannerBatch = ScannerBatchService(marketData::loadAndEvaluate, analytics, repository, feedStatus::status)
    private val observationBus = MarketObservationBus()
    private val observationUiBridge = MarketObservationUiBridge(observationBus.observations, ::applyProviderObservation)
    private val tradegateProvider = TradegatePollingService(repository, observationSink = observationBus)
    private val euronextProvider = EuronextPollingService(repository, observationSink = observationBus)
    private val tableQuoteProviders = TableQuoteProviderGroup(repository, observationBus)
    private val arivaReferences = ArivaReferenceService(repository)
    private val recentEvents = RecentEventRetainer()
    private val priorityScanner = PriorityScanCoordinator(
        { symbol -> marketData.loadPriorityResult(symbol, scannerCriteria) },
        { symbol, result ->
            val retained = recentEvents.priorityUpdate(symbol, result, System.currentTimeMillis())
            Platform.runLater { scannerPanel.applyPriorityResult(symbol, retained) }
        }
    )
    private val focusedSignals = FocusedSignalController(
        evaluate = { symbol -> marketData.loadPriorityResult(symbol, scannerCriteria) },
        panel = scannerPanel,
        isMarketOpen = MarketCalendar::isOpen,
        onSelectedResult = ::applyFocusedSelection,
        setStatus = status::update,
        formatError = requestStatus::formatError,
        log = log
    )
    private val shortMoveSelection = ShortMoveSelectionController(
        { marketData.loadPriorityResult(it, scannerCriteria) }, { it == currentSymbol && !closing.get() },
        {
            currentSymbol = it
            status.setLoading(true)
            status.update("Refreshing market data: $it")
        }
    ) { symbol, result, error ->
        if (error != null) {
            log.warn(LogTag.API, "short-move chart refresh failed symbol={}", symbol, error)
            status.update("Market refresh failed: $symbol · showing cached chart", true, requestStatus.formatError(symbol, error))
        }
        currentSignal = result
        loadLocalChart(symbol)
    }
    private val liveAggregator = FinnhubMinuteAggregator { bar ->
        repository.upsertMinuteBar(bar)
        liveTicks[bar.symbol] = System.currentTimeMillis()
    }
    init { scannerPanel.onInspect = focusedSignals::request }
    fun createView(): Parent {
        log.debug(LogTag.UI, "createView()")
        scannerPanel.setCurrency(scannerCriteria.displayCurrency, currencyConverter::price)
        scannerPanel.setAppearance(scannerCriteria.tableAppearance)
        tradegateProvider.configure(scannerCriteria)
        euronextProvider.configure(scannerCriteria)
        ToolbarIconButton.configure(refreshButton, "Refresh local chart", rotateOnHover = true)
        refreshButton.setOnAction { loadLocalChart(currentSymbol) }
        ToolbarIconButton.configure(settingsButton, "Scanner and currency settings")
        settingsButton.setOnAction { showScannerSettings() }
        ToolbarIconButton.configure(aboutButton, "About MiMiTrends")
        aboutButton.setOnAction { AboutDialog.show(aboutButton.scene?.window) { researchReport.show(aboutButton.scene?.window) } }
        ToolbarIconButton.configure(importTradesButton, "Import Scalable transactions CSV")
        importTradesButton.setOnAction { scalableImport.chooseAndImport(importTradesButton.scene?.window, ::handleScalableImport) }
        researchReport.start()
        val appLayers = MainViewFactory.create(refreshButton, settingsButton, importTradesButton,
            aboutButton, scannerPanel, trendChart, contentSplitPane, requestStatus, initialDivider)
        apiKey?.takeIf(String::isNotBlank)?.let(::restartFinnhubLive)
        analytics.applyRetention()
        DatabaseStartupMaintenance.schedule(analytics, batchScheduler, log)
        batchScheduler.execute {
            val saved = savedResultQuotes.refresh(analytics.loadLatestPublishedResults(scannerCriteria.resultLimit))
            Platform.runLater { scannerPanel.showSnapshot(saved, scannerCriteria.resultLimit) }
        }
        batchScheduler.execute {
            marketData.ensureCachedInstrumentMetadata()
            if (analytics.stats().aggregateBars == 0L) marketData.backfillCachedAnalytics()
        }
        startScanner()
        Platform.runLater { loadLocalChart(currentSymbol) }
        status.update("Requesting ECB EUR/USD reference rate")
        exchangeRates.refresh().whenComplete(BiConsumer<Double?, Throwable?> { rate, error ->
            if (error != null) log.warn(LogTag.API, "ECB exchange-rate refresh failed; cached rate remains active", error)
            if (error == null && rate != null) analytics.recordFxRate("EUR", "USD", rate, "ECB")
            Platform.runLater {
                scannerPanel.setCurrency(scannerCriteria.displayCurrency, currencyConverter::price)
                loadLocalChart(currentSymbol)
                if (error == null && rate != null) status.update("Read ECB EUR/USD reference rate: $rate")
            }
        })
        return appLayers
    }
    private fun handleScalableImport(event: ScalableImportEvent) {
        scalableImportResults.handle(event)
    }
    fun showClosing() {
        ClosingPresentation.show(scannerPanel,
            listOf(refreshButton, settingsButton, aboutButton, importTradesButton))
    }
    fun close() {
        log.debug(LogTag.UI, "close()")
        if (!closing.compareAndSet(false, true)) return
        scanGeneration.incrementAndGet()
        rotationTask?.cancel(false)
        researchReport.close()
        observationUiBridge.close()
        try {
            shortMoveRefresh.close()
            ApplicationResourceCloser.close(focusedSignals, priorityScanner, tradegateProvider, euronextProvider,
                tableQuoteProviders, arivaReferences,
                { finnhubClient?.close() }, batchScheduler, repository, analytics, log)
        } finally {
            observationBus.close()
        }
    }

    fun selectedSymbol(): String = currentSymbol.ifEmpty { "AAPL" }
    fun selectedRange(): String = selectedRangeValue
    fun dividerPosition(): Double = contentSplitPane.dividers.firstOrNull()?.position ?: initialDivider
    fun scannerColumnLayout(): String = scannerPanel.savedColumnLayout()
    fun shortMoveColumnLayout(): String = shortMovePanel.savedColumnLayout()
    fun tableDividerPosition(): Double = scannerPanel.tableDividerPosition()

    private fun startScanner() {
        log.debug(LogTag.API, "startScanner(symbols={})", scannerCriteria.symbols.size)
        priorityScanner.replaceCandidates(emptyList())
        recentEvents.clear()
        scanCyclePlanner.reset()
        val generation = scanGeneration.incrementAndGet()
        val criteria = scannerCriteria
        rotationTask?.cancel(false)
        lateinit var scan: () -> Unit
        scan = scan@ {
            if (closing.get()) return@scan
            val cycleStartedNanos = System.nanoTime()
            val selectedSymbols = MarketUniverseSelector.select(scannerCriteria)
            shortMoveRefresh.replaceSymbols(selectedSymbols)
            val symbols = scanCyclePlanner.order(selectedSymbols.filter { MarketCalendar.isOpen(it) })
            log.info(LogTag.API, "scan started symbols={} recentWindow={}m", symbols.size, criteria.maxSignalAgeMinutes)
            if (symbols.isEmpty()) {
                priorityScanner.replaceCandidates(emptyList())
                val now = java.time.Instant.now()
                val nextOpening = MarketCalendar.nextOpening(selectedSymbols, now)
                val resumeDelaySeconds = nextOpening?.let {
                    java.time.Duration.between(now, it.instant).seconds.coerceAtLeast(1) + MARKET_OPEN_GRACE_SECONDS
                } ?: criteria.scanIntervalSeconds
                val resumeText = nextOpening?.let(MarketHoursFormatter::nextOpening) ?: "market schedule unavailable"
                val persisted = savedResultQuotes.refresh(analytics.loadLatestPublishedResults(criteria.resultLimit))
                val saved = if (persisted.isNotEmpty()) persisted else marketData.closedMarketSnapshot(
                    MarketUniverseSelector.select(scannerCriteria), criteria)
                val userZone = java.time.ZoneId.systemDefault()
                val marketHours = MarketHoursFormatter.priceData(selectedSymbols, now, userZone)
                val brokerHours = MarketHoursFormatter.scalable(now, userZone)
                val localZoneName = java.time.format.DateTimeFormatter.ofPattern("z").format(now.atZone(userZone))
                log.info(LogTag.DB, "closed-market snapshot source={} results={}",
                    if (persisted.isNotEmpty()) "PERSISTED" else "CLOSED_CACHE", saved.size)
                Platform.runLater {
                    scannerPanel.beginScan(1, 1, emptyList())
                    saved.forEach(scannerPanel::update)
                    scannerPanel.completeScan(criteria.resultLimit)
                    scannerPanel.showCountdown(resumeDelaySeconds)
                    scannerPanel.showMarketClosed(saved.size, persisted.isNotEmpty(), resumeText,
                        localZoneName, marketHours, brokerHours)
                    status.update(if (saved.isEmpty())
                        "All selected markets are closed · scanner paused until $resumeText"
                    else if (persisted.isNotEmpty())
                        "Markets closed · showing ${saved.size} saved results · resumes $resumeText"
                    else "Markets closed · showing ${saved.size} cached results · resumes $resumeText")
                }
                rotationTask = batchScheduler.schedule(
                    { runCatching(scan).onFailure { log.error(LogTag.API, "scheduled market-open resume failed", it) } },
                    resumeDelaySeconds, TimeUnit.SECONDS
                )
                return@scan
            }
            Platform.runLater {
                scannerPanel.beginScan(1, 1, symbols)
                status.update("Finnhub live + Yahoo/SQLite: scanning ${symbols.size} symbols · fresh impulses only")
            }
            val batch = scannerBatch.execute(symbols, criteria,
                { generation == scanGeneration.get() && !closing.get() },
                { completed, symbol -> Platform.runLater {
                    status.update("Market data: analyzed $completed/${symbols.size} · $symbol")
                } }
            ) ?: return@scan
            val errors = batch.errors
            if (errors.isNotEmpty()) {
                log.warn(LogTag.API, "scan completed with failures count={} sample={}", errors.size, errors.take(3).joinToString("; "))
            }
            val active = batch.active
            scanCyclePlanner.replacePriority(active.map(ScanResult::symbol))
            val shortMoves = shortMoveLoader.load(symbols)
            val retained = recentEvents.merge(active, System.currentTimeMillis(), criteria.resultLimit)
            val displayed = retained
            tableQuoteProviders.replaceSymbols(displayed.map(ScanResult::symbol))
            arivaReferences.replaceSymbols(displayed.map(ScanResult::symbol))
            priorityScanner.replaceCandidates(active)
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cycleStartedNanos)
            val nextDelayMillis = ScanCyclePresentation.nextDelayMillis(criteria.scanIntervalSeconds, elapsedMillis)
            val nextDelaySeconds = ScanCyclePresentation.countdownSeconds(nextDelayMillis)
            val diagnostics = ScanCyclePresentation.diagnostics(batch, elapsedMillis)
            Platform.runLater {
                if (generation != scanGeneration.get()) return@runLater
                shortMovePanel.show(shortMoves)
                if (active.isEmpty() && errors.size == symbols.size && symbols.isNotEmpty()) {
                    scannerPanel.abortScan()
                    status.update("Yahoo scan produced no data; previous table retained", true, errors.joinToString("\n"))
                } else {
                    displayed.forEach(scannerPanel::update)
                    scannerPanel.completeScan(criteria.resultLimit)
                    scannerPanel.showCountdown(nextDelaySeconds)
                    val marketState = if (symbols.isEmpty()) "all selected markets closed"
                        else "${batch.strictCount.coerceAtMost(active.size)} strict impulses + ${batch.adaptiveCount} adaptive"
                    log.info(LogTag.API, "scan completed: {} diagnostics={}", marketState, diagnostics)
                    status.update(if (active.isEmpty()) "No current candidates · $diagnostics · next in ${nextDelaySeconds}s"
                    else "Hybrid scan complete · $marketState · $diagnostics · next in ${nextDelaySeconds}s")
                }
            }
            if (generation != scanGeneration.get()) return@scan
            rotationTask = batchScheduler.schedule(
                { runCatching(scan).onFailure { log.error(LogTag.API, "scheduled Yahoo scan failed", it) } },
                nextDelayMillis, TimeUnit.MILLISECONDS
            )
        }
        batchScheduler.execute { runCatching(scan).onFailure { log.error(LogTag.API, "initial Yahoo scan failed", it) } }
    }

    private fun applyProviderObservation(observation: ProviderMinuteBar) {
        scannerPanel.applyMarketObservation(
            observation.symbol, observation.bar.close, observation.observedAtMillis, observation.provider
        )
        currentSignal?.takeIf {
            it.symbol == observation.symbol && observation.observedAtMillis > it.updatedAtMillis
        }?.let { signal ->
            currentSignal = signal.copy(
                price = observation.bar.close,
                updatedAtMillis = observation.observedAtMillis,
                dataStatus = observation.provider
            )
        }
        shortMoveRefresh.request()
    }

    private fun openScannerResult(result: ScanResult) {
        log.debug(LogTag.UI, "openScannerResult(symbol={}, age={})", result.symbol, result.signalAgeMinutes)
        currentSymbol = result.symbol
        currentSignal = result
        trendChart.showSignalFocus()
        loadLocalChart(result.symbol)
    }
    private fun applyFocusedSelection(result: ScanResult) {
        if (currentSymbol != result.symbol || closing.get()) return
        currentSignal = result
        loadLocalChart(result.symbol)
    }

    private fun showScannerSettings() {
        log.debug(LogTag.UI, "showScannerSettings()")
        ScannerSettingsDialog(refreshButton.scene?.window, scannerCriteria, scannerSettings, ApiKeyResolver.resolve() != null)
            .showAndWait()?.let { result ->
            result.finnhubApiKey?.let { key ->
                ApiKeyResolver.saveLocal(key, ApiKeyResolver.resolveWebhookSecret())
                restartFinnhubLive(key)
            }
            scannerCriteria = result.criteria; scannerSettings.save(result.criteria)
            tradegateProvider.configure(result.criteria)
            euronextProvider.configure(result.criteria)
            scannerPanel.setCurrency(result.criteria.displayCurrency, currencyConverter::price)
            scannerPanel.setAppearance(result.criteria.tableAppearance)
            loadLocalChart(currentSymbol)
            startScanner()
        }
    }

    private fun restartFinnhubLive(key: String) {
        log.debug(LogTag.API, "restartFinnhubLive(keyPresent={})", key.isNotBlank())
        profileService = CompanyProfileService(repository, key.takeIf(String::isNotBlank)?.let(::FinnhubProfileClient),
            CompanyLogoClient())
        finnhubClient = FinnhubLiveStarter.restart(key, finnhubClient, scannerCriteria, liveTicks,
            liveAggregator, log, status::update)
    }

    private fun loadLocalChart(symbol: String) {
        log.debug(LogTag.UI, "loadLocalChart(symbol={})", symbol)
        if (symbol.isBlank()) return
        status.setLoading(true)
        val days = ChartRange.days(selectedRangeValue)
        status.update("Requesting SQLite: $symbol · $selectedRangeValue")
        CompletableFuture.supplyAsync {
            chartDataLoader.load(symbol, days, scannerCriteria.displayCurrency)
        }.whenComplete(BiConsumer<ChartData?, Throwable?> { chartData, error ->
                Platform.runLater {
                    status.setLoading(false)
                    if (error != null) {
                        log.error(LogTag.DB, "local chart load failed symbol={}", symbol, error)
                        status.update("SQLite read failed: ${error.message ?: "unknown error"}", true, requestStatus.formatError(symbol, error))
                    } else if (chartData != null && chartData.bars.isNotEmpty()) {
                        val bars = chartData.bars
                        val currency = scannerCriteria.displayCurrency
                        trendChart.renderMinuteBars(
                            symbol, bars, selectedRangeValue, 1.0, currency.symbol,
                            currentSignal?.takeIf { it.symbol == symbol }?.let(currencyConverter::result),
                            chartData.companyName, chartData.trades
                        )
                        status.update("Read SQLite: $symbol · ${bars.size} minute bars · $selectedRangeValue")
                    } else {
                        trendChart.clear()
                        status.update("Read SQLite: no collected minute bars for $symbol · $selectedRangeValue")
                    }
                }
            })
    }
}
