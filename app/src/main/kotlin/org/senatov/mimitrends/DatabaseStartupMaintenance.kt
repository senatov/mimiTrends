package org.senatov.mimitrends

import org.senatov.mimitrends.db.AnalyticsRepository
import org.senatov.mimitrends.log.LogTag
import org.slf4j.Logger
import java.util.concurrent.Executor

internal object DatabaseStartupMaintenance {
    fun schedule(analytics: AnalyticsRepository, executor: Executor, log: Logger) {
        executor.execute {
            runCatching {
                val integrity = analytics.quickCheck()
                check(integrity.equals("ok", ignoreCase = true)) { "SQLite quick_check failed: $integrity" }
                log.info(LogTag.DB, "database integrity check completed result={}", integrity)
                analytics.backupIfDue()?.let { log.info(LogTag.DB, "database backup created path={}", it) }
                analytics.databaseStats().let { stats -> log.info(LogTag.DB,
                    "database stats size={}MiB wal={}KiB operations={} averageLockWait={}us",
                    stats.databaseBytes / 1_048_576, stats.walBytes / 1_024,
                    stats.operations, stats.averageLockWaitMicros) }
            }.onFailure { error ->
                log.error(LogTag.DB, "database startup maintenance failed", error)
            }
        }
    }
}
