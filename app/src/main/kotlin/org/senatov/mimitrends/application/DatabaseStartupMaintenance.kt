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
                analytics.databaseStats().let { stats ->
                    log.info(
                        LogTag.DB,
                        "database stats size={}MiB wal={}KiB operations={} averageLockWait={}us",
                        stats.databaseBytes / 1_048_576, stats.walBytes / 1_024,
                        stats.operations, stats.averageLockWaitMicros
                    )
                }
            }.onFailure { error ->
                log.error(LogTag.DB, "database startup maintenance failed", error)
            }
        }
    }
}