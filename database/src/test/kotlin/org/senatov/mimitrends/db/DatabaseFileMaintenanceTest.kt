package org.senatov.mimitrends.db

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseFileMaintenanceTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `removes stale generated files while preserving active storage and recovery copies`() {
        val now = Instant.parse("2026-09-21T12:00:00Z")
        val database = create("mimitrends.db", now.minus(Duration.ofDays(100)))
        val analytics = create("mimitrends-analytics.duckdb", now.minus(Duration.ofDays(100)))
        val recentLegacy = create("mimitrends-analytics.duckdb.recent.bak", now.minus(Duration.ofDays(10)))
        val expiredLegacy = create("mimitrends-analytics.duckdb.old.bak", now.minus(Duration.ofDays(31)))
        val recentTemporary = create(".active.db.tmp", now.minus(Duration.ofHours(2)))
        val expiredTemporary = create("mimitrends-copy.tmp", now.minus(Duration.ofDays(2)))
        val backups = Files.createDirectories(directory.resolve("backups"))
        val daily = (1..5).map { day ->
            create(backups.resolve("mimitrends-2026-09-${day.toString().padStart(2, '0')}.db"), now)
        }
        val newestMigration = create(
            backups.resolve("mimitrends-before-newest.db"), now.minus(Duration.ofDays(31))
        )
        val expiredMigration = create(
            backups.resolve("mimitrends-before-old.db"), now.minus(Duration.ofDays(40))
        )

        val result = DatabaseFileMaintenance.clean(database, now)

        assertEquals(DatabaseCleanupResult(2, 1, 1, 1), result)
        assertEquals(daily.takeLast(3), daily.filter(Files::exists))
        assertTrue(Files.exists(database))
        assertTrue(Files.exists(analytics))
        assertTrue(Files.exists(recentLegacy))
        assertFalse(Files.exists(expiredLegacy))
        assertTrue(Files.exists(recentTemporary))
        assertFalse(Files.exists(expiredTemporary))
        assertTrue(Files.exists(newestMigration))
        assertFalse(Files.exists(expiredMigration))
    }

    private fun create(name: String, modifiedAt: Instant): Path = create(directory.resolve(name), modifiedAt)

    private fun create(path: Path, modifiedAt: Instant): Path {
        Files.writeString(path, "test")
        Files.setLastModifiedTime(path, FileTime.from(modifiedAt))
        return path
    }
}
