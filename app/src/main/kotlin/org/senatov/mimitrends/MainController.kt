package org.senatov.mimitrends
import javafx.application.Platform
import javafx.scene.Parent
import javafx.scene.control.*
import javafx.scene.layout.*
import org.senatov.mimitrends.charts.TrendChartView
import org.senatov.mimitrends.db.*
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.*
import org.senatov.mimitrends.scanner.*
import org.senatov.mimitrends.ws.*
import org.senatov.mimitrends.marketdata.*
import org.slf4j.LoggerFactory
import javafx.util.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import java.util.concurrent.ConcurrentHashMap
class MainController(private val apiKey: String?, initialSymbol: String = "AAPL", initialRange: String = "3M",
    initialDividerPosition: Double = 0.34, scannerColumns: String = "", shortMoveColumns: String = "",
                     initialTableDivider: Double = 0.68,
                     initialSidebarVisible: Boolean = true,
                     private val openExternal: (String) -> Unit = {}
) {
    private val log = LoggerFactory.getLogger(MainController::class.java)
    private val repository = MarketRepository()
    private val analytics = AnalyticsRepository()
    private val exchangeRates = ExchangeRateService()
    private val savedResultQuotes = SavedResultQuoteRefresher(repository)
    private val resultDeduplicator = InstrumentResultDeduplicator(
        repository::loadInstrumentIsin,
        { symbol -> repository.loadCompanyProfile(symbol)?.name }
    )
    private val shortMoveLoader = ShortMoveLoader(repository, analytics, exchangeRates)
    private var currentSymbol = initialSymbol
    private var currentSignal: ScanResult? = null
    private val refreshButton = Button("↻")
    private val settingsButton = Button("⚙")
    private val aboutButton = Button("ⓘ")
    private val importTradesButton = Button("⇩")
    private val requestStatus = RequestStatusPane { chartSelection.selectedRange }
    private val trendChart = TrendChartView { chartSelection.selectRange(it) }
    private val scannerSettings = ScannerSettingsService()
    private var scannerCriteria: ScannerCriteria = scannerSettings.load()
    private val currencyConverter = ScanResultCurrencyConverter(exchangeRates) { scannerCriteria }
    private val status = MainStatusController(requestStatus, trendChart, refreshButton, log)
    private val scannerEngine = ScannerEngine()
    private val yahooFinance = YahooFinanceClient()
    private val wallstreetOnlineClient = WallstreetOnlineMarketDataClient()
    private val wallstreetOnlineDiscovery = WallstreetOnlineDiscoveryService(wallstreetOnlineClient, yahooFinance)
    private val dynamicUniverse = DynamicMarketUniverse(wallstreetOnlineDiscovery::discover)
    private var profileService = CompanyProfileService(
        repository, apiKey?.let(::FinnhubProfileClient), CompanyLogoClient()
    )
    private val stockPageOpener = StockPageOpener(
        repository, wallstreetOnlineClient, { scannerCriteria.stockSearchUrl }, openExternal,
        { message, error, details -> status.update(message, error, details) },
        { symbol, error -> requestStatus.formatError(symbol, error) }, log
    )
    private val shortMovePanel = ShortMovePanel(
        { symbol, moveEpochSeconds ->
            trendChart.showSignalFocus(moveEpochSeconds)
            shortMoveSelection.open(symbol)
        },
        shortMoveColumns, { symbol -> profileService.load(symbol) }, ClipboardText::copy,
        stockPageOpener::open
    )
    private val moderateCandidatePanel = ModerateCandidatePanel(
        { symbol, moveEpochSeconds ->
            trendChart.showSignalFocus(moveEpochSeconds)
            shortMoveSelection.open(symbol)
        },
        { symbol -> profileService.load(symbol) }
    )
    private val insightSidebar = InsightSidebar(moderateCandidatePanel)
    private val insightSidebarHost = InsightSidebarHost(insightSidebar, initialSidebarVisible)
    private val scannerPanel = ScannerPanel(
        onOpen = ::openScannerResult,
        shortMovePanel = shortMovePanel,
        savedColumns = scannerColumns,
        initialTableDivider = initialTableDivider,
        loadProfile = { symbol -> profileService.load(symbol) },
        openStock = stockPageOpener::open,
        onShowDetectedToday = { detectedToday.show() }
    )
    private val detectedToday: DetectedTodayController by lazy { DetectedTodayController(analytics, batchScheduler, scannerPanel) }
    private val exchangeRateStartup by lazy {
        ExchangeRateStartup(exchangeRates, analytics, scannerPanel, { scannerCriteria.displayCurrency },
            currencyConverter::price, { loadLocalChart(currentSymbol) }, status::update, log)
    }
    private val shortMoveRefresh = ShortMoveRefreshCoordinator(shortMoveLoader::load, log) { moves ->
        Platform.runLater { if (!closing.get()) {
            shortMovePanel.show(moves)
            moderateCandidatePanel.show(moves)
        } }
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
    private val chartSelection: ChartSelectionController by lazy {
        ChartSelectionController(initialRange, trendChart, chartDataLoader,
            { scannerCriteria.displayCurrency }, { currentSymbol }, { currentSignal }, currencyConverter::result,
            closing::get, status, { symbol, error -> requestStatus.formatError(symbol, error) }, log)
    }
    private val initialDivider = initialDividerPosition.coerceIn(0.15, 0.75)
    private val contentSplitPane = SplitPane()
    private var finnhubClient: FinnhubWebSocketClient? = null
    private val liveTicks = ConcurrentHashMap<String, Long>()
    private val feedStatus = FeedStatusResolver(liveTicks)
    private val marketData = MarketDataService(repository, analytics, scannerEngine, yahooFinance, feedStatus::status)
    private val scannerBatch = ScannerBatchService(marketData::loadAndEvaluate, analytics, repository, feedStatus::status)
    private val observationBus = MarketObservationBus()
    private val observationPresenter = ProviderObservationPresenter(
        scannerPanel, { currentSignal }, { currentSignal = it }, shortMoveRefresh::request
    )
    private val observationUiBridge = MarketObservationUiBridge(observationBus.observations, observationPresenter::apply)
    private val tradegateProvider = TradegatePollingService(repository, observationSink = observationBus)
    private val euronextProvider = EuronextPollingService(repository, observationSink = observationBus)
    private val langSchwarzProvider = LangSchwarzPollingService(repository, observationBus)
    private val wallstreetOnlineProvider = WallstreetOnlinePollingService(repository, observationBus)
    private val arivaReferences = ArivaReferenceService(repository)
    private val recentEvents = RecentEventRetainer()
    private val priorityScanner = PriorityScanCoordinator(
        { symbol -> marketData.loadPriorityResult(symbol, scannerCriteria) },
        { symbol, result ->
            val retained = recentEvents.priorityUpdate(symbol, result, System.currentTimeMillis())
            Platform.runLater {
                scannerPanel.applyPriorityResult(symbol, retained)
                moderateCandidatePanel.setAnomalyPresent(symbol, retained != null)
            }
        }
    )
    private val focusedSignals = FocusedSignalController(
        evaluate = { symbol -> marketData.loadPriorityResult(symbol, scannerCriteria) },
        refreshQuote = { savedResultQuotes.refresh(listOf(it)).single() },
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
            loadLocalChart(it)
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

    init {
        ClipboardText.onCopied = { status.transientSuccess("Copied to clipboard") }
        scannerPanel.onInspect = focusedSignals::request
    }
    fun createView(): Parent {
        log.debug(LogTag.UI, "createView()")
        scannerPanel.setCurrency(scannerCriteria.displayCurrency, currencyConverter::price)
        scannerPanel.setAppearance(scannerCriteria.tableAppearance)
        tradegateProvider.configure(scannerCriteria)
        euronextProvider.configure(scannerCriteria)
        researchReport.start()
        val appLayers = MainViewFactory.create(refreshButton, settingsButton, importTradesButton,
            aboutButton, scannerPanel, trendChart, insightSidebarHost, contentSplitPane, requestStatus, initialDivider
        )
        WorkspaceToolbar.configure(
            appLayers, refreshButton, settingsButton, importTradesButton, aboutButton,
            { loadLocalChart(currentSymbol) }, ::showScannerSettings,
            { scalableImport.chooseAndImport(importTradesButton.scene?.window, scalableImportResults::handle) },
            { AboutDialog.show(aboutButton.scene?.window) { researchReport.show(aboutButton.scene?.window) } })
        WorkspaceAppearance.apply(appLayers, scannerCriteria.tableAppearance)
        trendChart.setDarkTheme(scannerCriteria.tableAppearance.theme == UiTheme.DARK)
        apiKey?.takeIf(String::isNotBlank)?.let(::restartFinnhubLive)
        analytics.applyRetention()
        DatabaseStartupMaintenance.schedule(analytics, batchScheduler, log)
        batchScheduler.execute {
            val saved = resultDeduplicator.deduplicate(
                savedResultQuotes.refresh(analytics.loadLatestPublishedResults(scannerCriteria.resultLimit))
            )
            Platform.runLater {
                scannerPanel.showSnapshot(saved, scannerCriteria.resultLimit)
                moderateCandidatePanel.setAnomalySymbols(saved.map(ScanResult::symbol))
            }
            detectedToday.refreshCount()
        }
        batchScheduler.execute {
            marketData.ensureCachedInstrumentMetadata()
            if (analytics.stats().aggregateBars == 0L) marketData.backfillCachedAnalytics()
        }
        startScanner()
        Platform.runLater { loadLocalChart(currentSymbol) }
        exchangeRateStartup.start()
        return appLayers
    }
    fun showClosing() {
        ClosingPresentation.show(scannerPanel,
            listOf(refreshButton, settingsButton, aboutButton, importTradesButton))
    }
    fun close() {
        log.debug(LogTag.UI, "close()")
        if (!closing.compareAndSet(false, true)) return
        scanGeneration.incrementAndGet()
        chartSelection.invalidate()
        rotationTask?.cancel(false)
        researchReport.close()
        observationUiBridge.close()
        try {
            shortMoveRefresh.close()
            ApplicationResourceCloser.close(focusedSignals, priorityScanner, tradegateProvider, euronextProvider,
                langSchwarzProvider, wallstreetOnlineProvider, arivaReferences,
                { finnhubClient?.close() }, batchScheduler, repository, analytics, log)
        } finally {
            observationBus.close()
        }
    }
    fun selectedSymbol(): String = currentSymbol.ifEmpty { "AAPL" }
    fun selectedRange(): String = chartSelection.selectedRange
    fun dividerPosition(): Double = contentSplitPane.dividers.firstOrNull()?.position ?: initialDivider
    fun scannerColumnLayout(): String = scannerPanel.savedColumnLayout()
    fun shortMoveColumnLayout(): String = shortMovePanel.savedColumnLayout()
    fun tableDividerPosition(): Double = scannerPanel.tableDividerPosition()
    fun sidebarVisible(): Boolean = insightSidebarHost.isExpanded
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
            val universe = dynamicUniverse.select(scannerCriteria)
            val selectedSymbols = universe.symbols
            analytics.recordUniverseSelection(universe.ranks, universe.discovered)
            Platform.runLater {
                insightSidebar.showUniverse(universe)
                moderateCandidatePanel.showBuildingContext(selectedSymbols.size)
            }
            val symbols = scanCyclePlanner.order(selectedSymbols.filter { MarketCalendar.isOpen(it) })
            shortMoveRefresh.replaceSymbols(symbols)
            log.info(LogTag.API, "scan started symbols={} discovered={} recentWindow={}m",
                symbols.size, universe.discovered.size, criteria.maxSignalAgeMinutes)
            if (symbols.isEmpty()) {
                priorityScanner.replaceCandidates(emptyList())
                val now = java.time.Instant.now()
                val nextOpening = MarketCalendar.nextOpening(selectedSymbols, now)
                val resumeDelaySeconds = nextOpening?.let {
                    java.time.Duration.between(now, it.instant).seconds.coerceAtLeast(1) + 5L
                } ?: criteria.scanIntervalSeconds
                val resumeText = nextOpening?.let(MarketHoursFormatter::nextOpening) ?: "market schedule unavailable"
                val persisted = savedResultQuotes.refresh(analytics.loadLatestPublishedResults(criteria.resultLimit))
                val saved = resultDeduplicator.deduplicate(if (persisted.isNotEmpty()) persisted
                    else marketData.closedMarketSnapshot(MarketUniverseSelector.select(scannerCriteria), criteria))
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
                    moderateCandidatePanel.setAnomalySymbols(saved.map(ScanResult::symbol))
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
            val detectedTodayCount = analytics.loadTodayDetections().size
            val errors = batch.errors
            if (errors.isNotEmpty()) {
                log.warn(LogTag.API, "scan completed with failures count={} sample={}", errors.size, errors.take(3).joinToString("; "))
            }
            val active = resultDeduplicator.deduplicate(batch.active)
            scanCyclePlanner.replacePriority(active.map(ScanResult::symbol))
            val shortMoves = shortMoveLoader.load(symbols)
            val retained = recentEvents.merge(active, System.currentTimeMillis(), criteria.resultLimit)
            val displayed = retained
            langSchwarzProvider.replaceSymbols(displayed.map(ScanResult::symbol))
            wallstreetOnlineProvider.replaceSymbols(displayed.map(ScanResult::symbol))
            arivaReferences.replaceSymbols(displayed.map(ScanResult::symbol))
            priorityScanner.replaceCandidates(active)
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cycleStartedNanos)
            val nextDelayMillis = ScanCyclePresentation.nextDelayMillis(criteria.scanIntervalSeconds, elapsedMillis)
            val nextDelaySeconds = ScanCyclePresentation.countdownSeconds(nextDelayMillis)
            val diagnostics = ScanCyclePresentation.diagnostics(batch, elapsedMillis)
            Platform.runLater {
                if (generation != scanGeneration.get()) return@runLater
                shortMovePanel.show(shortMoves)
                moderateCandidatePanel.show(shortMoves)
                moderateCandidatePanel.setAnomalySymbols(displayed.map(ScanResult::symbol))
                if (active.isEmpty() && errors.size == symbols.size && symbols.isNotEmpty()) {
                    scannerPanel.abortScan()
                    status.update("Yahoo scan produced no data; previous table retained", true, errors.joinToString("\n"))
                } else {
                    displayed.forEach(scannerPanel::update)
                    scannerPanel.completeScan(criteria.resultLimit)
                    scannerPanel.setDetectedTodayCount(detectedTodayCount)
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
    private fun openScannerResult(result: ScanResult) {
        log.debug(LogTag.UI, "openScannerResult(symbol={}, age={})", result.symbol, result.signalAgeMinutes)
        currentSymbol = result.symbol
        currentSignal = result
        insightSidebar.showSignal(result)
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
        ScannerSettingsDialog(refreshButton.scene?.window, scannerCriteria, scannerSettings,
            ApiKeyResolver.resolve() != null, wallstreetOnlineClient::validateStockSearchUrl)
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
                settingsButton.scene?.root?.let { WorkspaceAppearance.apply(it, result.criteria.tableAppearance) }
                trendChart.setDarkTheme(result.criteria.tableAppearance.theme == UiTheme.DARK)
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
    private fun loadLocalChart(symbol: String) = chartSelection.load(symbol)
}