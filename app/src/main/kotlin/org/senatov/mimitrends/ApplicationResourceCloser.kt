package org.senatov.mimitrends

import org.senatov.mimitrends.db.AnalyticsRepository
import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.log.LogTag
import org.slf4j.Logger
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

internal object ApplicationResourceCloser {
    fun close(
        priorityScanner: AutoCloseable,
        tradegateProvider: AutoCloseable,
        euronextProvider: AutoCloseable,
        boerseDeProvider: AutoCloseable,
        closeFinnhub: () -> Unit,
        batchScheduler: ExecutorService,
        repository: MarketRepository,
        analytics: AnalyticsRepository,
        log: Logger
    ) {
        priorityScanner.close()
        tradegateProvider.close()
        euronextProvider.close()
        boerseDeProvider.close()
        closeFinnhub()
        batchScheduler.shutdownNow()
        awaitTermination(batchScheduler, log)
        repository.close()
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
