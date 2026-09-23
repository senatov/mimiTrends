package org.senatov.mimitrends.application

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

import org.senatov.mimitrends.db.AnalyticsRepository
import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.log.LogTag
import org.slf4j.Logger
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

internal object ApplicationResourceCloser {
    fun close(
        focusedSignalRefresher: AutoCloseable,
        priorityScanner: AutoCloseable,
        tradegateProvider: AutoCloseable,
        euronextProvider: AutoCloseable,
        scalableProvider: AutoCloseable,
        langSchwarzProvider: AutoCloseable,
        closeFinnhub: () -> Unit,
        batchScheduler: ExecutorService,
        repository: MarketRepository,
        analytics: AnalyticsRepository,
        log: Logger
    ) {
        focusedSignalRefresher.close()
        priorityScanner.close()
        tradegateProvider.close()
        euronextProvider.close()
        scalableProvider.close()
        langSchwarzProvider.close()
        closeFinnhub()
        batchScheduler.shutdownNow()
        awaitTermination(batchScheduler, log)
        repository.close()
        runCatching(analytics::performShutdownMaintenance).onFailure { error ->
            log.warn(LogTag.DB, "database shutdown maintenance failed", error)
        }
        analytics.close()
    }

    private fun awaitTermination(executor: ExecutorService, log: Logger) {
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn(LogTag.APP, "shutdown timed out component=scanner rotation timeout={}s", SHUTDOWN_TIMEOUT_SECONDS)
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn(LogTag.APP, "shutdown interrupted component=scanner rotation")
        }
    }

    private const val SHUTDOWN_TIMEOUT_SECONDS = 20L
}
