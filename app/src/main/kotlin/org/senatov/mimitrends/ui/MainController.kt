package org.senatov.mimitrends.ui

import org.senatov.mimitrends.application.*
import org.senatov.mimitrends.ui.*
import org.senatov.mimitrends.scanner.*
import org.senatov.mimitrends.shortmove.*
import org.senatov.mimitrends.signals.*
import org.senatov.mimitrends.research.*
import org.senatov.mimitrends.market.*
import org.senatov.mimitrends.providers.*
import org.senatov.mimitrends.company.*
import org.senatov.mimitrends.services.*
import org.senatov.mimitrends.shared.*
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

class MainController(
    private val apiKey: String?, initialSymbol: String = "AAPL", initialRange: String = "3M",
    initialDividerPosition: Double = 0.52, scannerColumns: String = "", shortMoveColumns: String = "",
    initialTableDivider: Double = 0.60, initialChartVisible: Boolean = false,
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
    private val shortMoveLoader = ShortMoveLoader(repository, exchangeRates)
    private var currentSymbol = initialSymbol
    private var currentSignal: ScanResult? = null
    private val actions = WorkspaceActionButtons()
    private val requestStatus = RequestStatusPane { chartSelection.selectedRange }
    private val trendChart = TrendChartView({ chartSelection.selectRange(it) }, { loadLocalChart(currentSymbol) })
    private val scannerSettings = ScannerSettingsService()
    private var scannerCriteria: ScannerCriteria = scannerSettings.load()
    private val currencyConverter = ScanResultCurrencyConverter(exchangeRates) { scannerCriteria }
    private val status = MainStatusController(requestStatus, trendChart, actions.refresh, log)
    private val yahooFinance = YahooFinanceClient()
    private val wallstreetOnlineClient = WallstreetOnlineMarketDataClient()
    private val wallstreetOnlineDiscovery = WallstreetOnlineDiscoveryService(wallstreetOnlineClient, yahooFinance)
    private val weeklyMarketUniverse = WeeklyMarketUniverse()
    private val dynamicUniverse = DynamicMarketUniverse(discover = {
        weeklyMarketUniverse.symbols() + wallstreetOnlineDiscovery.discover()
    })
    private val userWatchlist: UserWatchlistController = UserWatchlistController(repository, dynamicUniverse, ::startScanner)
    private var profileService = CompanyProfileService(
        repository, apiKey?.let(::FinnhubProfileClient), persistentCompanyLogoClient(repository)
    )
    private val stockPageOpener = StockPageOpener(
        repository, wallstreetOnlineClient, { scannerCriteria.stockSearchUrl }, openExternal,
        { message, error, details -> status.update(message, error, details) },
        { symbol, error -> requestStatus.formatError(symbol, error) }, log
    )
    private val shortMovePanel: ShortMovePanel = ShortMovePanel(
        ::openShortMoveChart,
        shortMoveColumns, { symbol -> profileService.load(symbol) }, ClipboardText::copy,
        stockPageOpener::open, userWatchlist.actions
    )
    private val chartDrawer = ChartDrawer(trendChart, initialChartVisible)
    private val universeDialog = UniverseDialog { count -> actions.universe.text = "Pool $count" }
    private val scannerPanel: ScannerPanel = ScannerPanel(
        onOpen = ::openScannerResult,
        shortMovePanel = shortMovePanel,
        savedColumns = scannerColumns,
        initialTableDivider = initialTableDivider,
        loadProfile = { symbol -> profileService.load(symbol) },
        openStock = stockPageOpener::open,
        onShowDetectedToday = { detectedToday.show() },
        watchlist = userWatchlist.actions
    )
    private val detectedToday: DetectedTodayController by lazy { DetectedTodayController(analytics, batchScheduler, scannerPanel) }
    private val exchangeRateStartup by lazy {
        ExchangeRateStartup(
            exchangeRates, analytics, scannerPanel, { scannerCriteria.displayCurrency },
            currencyConverter::price, { loadLocalChart(currentSymbol) }, status::update, log
        )
    }
    private val shortMoveRefresh = ShortMoveRefreshCoordinator(shortMoveLoader::load, log, publish = ::publishShortMoves)
    private val batchScheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mimitrends-scanner-rotation").apply { isDaemon = true }
    }
    private val importExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mimitrends-priority-csv-import").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }
    }
    private val scalableImport = ScalableImportAction(analytics, importExecutor)
    private val scalableImportResults = ScalableImportResultHandler(
        actions.importTrades, actions.importStatus, status::update, requestStatus::formatError, log,
        {
            chartDrawer.show()
            loadLocalChart(currentSymbol)
        }
    )
    private var providerUniverse = emptyList<String>()
    private val closing = AtomicBoolean()
    private val chartDataLoader = ChartDataLoader(repository, analytics, exchangeRates)
    private val chartSelection: ChartSelectionController by lazy {
        ChartSelectionController(
            initialRange, trendChart, chartDataLoader,
            { scannerCriteria.displayCurrency }, { currentSymbol }, { currentSignal }, currencyConverter::result,
            closing::get, status, { symbol, error -> requestStatus.formatError(symbol, error) }, log
        )
    }
    private val initialDivider = initialDividerPosition.coerceIn(0.35, 0.72)
    private val contentSplitPane = SplitPane()
    private var finnhubClient: FinnhubWebSocketClient? = null
    private val liveTicks = ConcurrentHashMap<String, Long>()
    private val feedStatus = FeedStatusResolver(liveTicks)
    private val marketData = MarketDataService(repository, yahooFinance, feedStatus::status)
    private val scannerBatch = ScannerBatchService(marketData::loadAndEvaluate, analytics, repository, feedStatus::status)
    private val observationBus = MarketObservationBus()
    private val observationRecorder = ProviderObservationRecorder(repository, observationBus)
    private val observationPresenter = ProviderObservationPresenter(
        scannerPanel, { currentSignal }, { currentSignal = it }, shortMoveRefresh::request,
        userWatchlist::observe
    )
    private val observationUiBridge = MarketObservationUiBridge(observationBus.observations, observationPresenter::apply)
    private val tradegateProvider = TradegatePollingService(repository, observationSink = observationRecorder)
    private val euronextProvider = EuronextPollingService(repository, observationSink = observationRecorder)
    private val langSchwarzProvider = LangSchwarzPollingService(repository, observationRecorder)
    private val scalableProvider = ScalablePollingService(
        repository, observationRecorder, { symbols ->
            langSchwarzProvider.replaceSymbols(if (scannerCriteria.langSchwarzEnabled) symbols else emptyList())
        }
    )
    private val recentEvents = RecentEventRetainer()
    private val priorityScanner = PriorityScanCoordinator(
        { symbol -> marketData.loadPriorityResult(symbol, scannerCriteria) },
        { symbol, result ->
            val retained = recentEvents.priorityUpdate(symbol, result, System.currentTimeMillis())
            shortMoveRefresh.request()
            Platform.runLater {
                scannerPanel.applyPriorityResult(symbol, retained)
            }
        },
        isUrgent = { symbol ->
            shortMoveLoader.load(listOf(symbol)).any { it.pattern == ShortMovePattern.RAPID_CRASH }
        }
    )
    private val scanCycle by lazy {
        ScanCycleCoordinator(
            criteriaProvider = { scannerCriteria },
            dynamicUniverse = dynamicUniverse,
            analytics = analytics,
            configureProviderUniverse = ::configureProviderUniverse,
            liveTicks = liveTicks,
            shortMoveRefresh = shortMoveRefresh,
            priorityScanner = priorityScanner,
            savedResultQuotes = savedResultQuotes,
            resultDeduplicator = resultDeduplicator,
            marketData = marketData,
            presentUniverse = universeDialog::update,
            shortMovePanel = shortMovePanel,
            scannerPanel = scannerPanel,
            status = status,
            scheduler = batchScheduler,
            scannerBatch = scannerBatch,
            watchlistSymbols = { userWatchlist.symbols },
            shortMoveLoader = shortMoveLoader,
            recentEvents = recentEvents,
            scalableProvider = scalableProvider,
            detectedTodayCount = { analytics.loadTodayDetections().size },
            isClosing = closing::get,
            log = log
        )
    }
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
            scannerPanel.setRefreshing(it, true)
            loadLocalChart(it)
        }
    ) { symbol, result, error ->
        if (error != null) {
            log.warn(LogTag.API, "short-move chart refresh failed symbol={}", symbol, error)
            status.update("Market refresh failed: $symbol · showing cached chart", true, requestStatus.formatError(symbol, error))
        }
        result?.let { scannerPanel.applyPriorityResult(symbol, it) }
        scannerPanel.setRefreshing(symbol, false)
        currentSignal = result
        shortMoveRefresh.request()
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
        val appLayers = MainViewFactory.create(
            actions, scannerPanel, shortMovePanel,
            chartDrawer, contentSplitPane, requestStatus, initialDivider
        )
        WorkspaceToolbar.configure(
            appLayers, actions,
            { universeDialog.show(actions.universe.scene?.window) },
            { loadLocalChart(currentSymbol) }, ::showScannerSettings,
            { scalableImport.chooseAndImport(actions.importTrades.scene?.window, scalableImportResults::handle) },
            { AboutDialog.show(actions.about.scene?.window) })
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
            }
            detectedToday.refreshCount()
        }
        startScanner()
        weeklyMarketUniverse.refreshAsync(dynamicUniverse::invalidate)
        Platform.runLater { loadLocalChart(currentSymbol) }
        exchangeRateStartup.start()
        return appLayers
    }

    fun showClosing() {
        ClosingPresentation.show(scannerPanel, actions.all)
    }

    fun close() {
        log.debug(LogTag.UI, "close()")
        if (!closing.compareAndSet(false, true)) return
        scanCycle.stop()
        weeklyMarketUniverse.close()
        chartSelection.close()
        observationUiBridge.close()
        try {
            shortMoveRefresh.close()
            importExecutor.shutdownNow()
            ApplicationResourceCloser.close(
                focusedSignals, priorityScanner, tradegateProvider, euronextProvider,
                scalableProvider, langSchwarzProvider,
                { finnhubClient?.close() }, batchScheduler, repository, analytics, log
            )
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
    fun chartVisible(): Boolean = chartDrawer.isExpanded
    private fun startScanner() = scanCycle.start()

    private fun configureProviderUniverse(symbols: List<String>) {
        if (symbols == providerUniverse) return
        providerUniverse = symbols
        val providerCriteria = scannerCriteria.copy(symbols = symbols)
        tradegateProvider.configure(providerCriteria)
        euronextProvider.configure(providerCriteria)
        log.info(LogTag.API, "provider polling universe updated symbols={}", symbols.size)
    }

    private fun publishShortMoves(moves: List<ShortMove>) {
        if (closing.get()) return
        priorityScanner.addUrgentSymbols(rapidCrashSymbols(moves))
        Platform.runLater {
            if (!closing.get()) {
                shortMovePanel.show(moves)
            }
        }
    }

    private fun rapidCrashSymbols(moves: Collection<ShortMove>): List<String> = moves
        .asSequence()
        .filter { it.pattern == ShortMovePattern.RAPID_CRASH }
        .map(ShortMove::symbol)
        .toList()

    private fun openShortMoveChart(symbol: String, moveEpochSeconds: Long) {
        // Starting the load clears the previous instrument, so install its focus request afterwards.
        chartDrawer.show()
        shortMoveSelection.open(symbol)
        trendChart.showSignalFocus(moveEpochSeconds)
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
        ScannerSettingsDialog(
            actions.settings.scene?.window, scannerCriteria, scannerSettings,
            ApiKeyResolver.resolve() != null, wallstreetOnlineClient::validateStockSearchUrl
        )
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
                actions.settings.scene?.root?.let { WorkspaceAppearance.apply(it, result.criteria.tableAppearance) }
                trendChart.setDarkTheme(result.criteria.tableAppearance.theme == UiTheme.DARK)
                loadLocalChart(currentSymbol)
                startScanner()
            }
    }

    private fun restartFinnhubLive(key: String) {
        log.debug(LogTag.API, "restartFinnhubLive(keyPresent={})", key.isNotBlank())
        profileService = CompanyProfileService(
            repository, key.takeIf(String::isNotBlank)?.let(::FinnhubProfileClient),
            persistentCompanyLogoClient(repository)
        )
        finnhubClient = FinnhubLiveStarter.restart(
            key, finnhubClient, scannerCriteria, liveTicks,
            liveAggregator, log, status::update
        )
    }

    private fun loadLocalChart(symbol: String) = chartSelection.load(symbol)
}
