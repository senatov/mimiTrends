package org.senatov.mimitrends.db

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

internal data class DatabaseCleanupResult(
    val dailyBackupsRemoved: Int,
    val migrationBackupsRemoved: Int,
    val legacyBackupsRemoved: Int,
    val temporaryFilesRemoved: Int
)

internal object DatabaseFileMaintenance {
    fun clean(databasePath: Path, now: Instant = Instant.now()): DatabaseCleanupResult {
        val storageDirectory = databasePath.toAbsolutePath().normalize().parent
            ?: return DatabaseCleanupResult(0, 0, 0, 0)
        val backupDirectory = storageDirectory.resolve("backups")
        val dailyRemoved = deleteExcessDailyBackups(backupDirectory)
        val migrationRemoved = deleteExpiredMigrationBackups(backupDirectory, now)
        val legacyRemoved = deleteExpiredLegacyBackups(storageDirectory, now)
        val temporaryRemoved = deleteExpiredTemporaryFiles(listOf(storageDirectory, backupDirectory), now)
        return DatabaseCleanupResult(dailyRemoved, migrationRemoved, legacyRemoved, temporaryRemoved)
    }

    private fun deleteExcessDailyBackups(directory: Path): Int {
        val backups = regularFiles(directory)
            .filter { it.fileName.toString().matches(DAILY_BACKUP_NAME) }
            .sortedByDescending { it.fileName.toString() }
        return delete(backups.drop(DAILY_BACKUPS_TO_KEEP))
    }

    private fun deleteExpiredMigrationBackups(directory: Path, now: Instant): Int {
        val backups = regularFiles(directory)
            .filter { it.fileName.toString().matches(MIGRATION_BACKUP_NAME) }
            .sortedByDescending(::modifiedAt)
        val expired = backups.drop(MIGRATION_BACKUPS_TO_KEEP).filter {
            modifiedAt(it).isBefore(now.minus(MIGRATION_BACKUP_MAX_AGE))
        }
        return delete(expired)
    }

    private fun deleteExpiredLegacyBackups(directory: Path, now: Instant): Int = delete(
        regularFiles(directory).filter {
            it.fileName.toString().matches(LEGACY_DUCKDB_BACKUP_NAME) &&
                    modifiedAt(it).isBefore(now.minus(LEGACY_BACKUP_MAX_AGE))
        }
    )

    private fun deleteExpiredTemporaryFiles(directories: Collection<Path>, now: Instant): Int = delete(
        directories.flatMap(::regularFiles).filter {
            it.fileName.toString().matches(TEMPORARY_FILE_NAME) &&
                    modifiedAt(it).isBefore(now.minus(TEMPORARY_FILE_MAX_AGE))
        }
    )

    private fun regularFiles(directory: Path): List<Path> {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return Files.list(directory).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.toList()
        }
    }

    private fun modifiedAt(path: Path): Instant = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant()

    private fun delete(paths: Collection<Path>): Int = paths.count(Files::deleteIfExists)

    private val DAILY_BACKUP_NAME = Regex("""mimitrends-\d{4}-\d{2}-\d{2}\.db""")
    private val MIGRATION_BACKUP_NAME = Regex("""mimitrends-before-.+\.db""")
    private val LEGACY_DUCKDB_BACKUP_NAME = Regex("""mimitrends-analytics\.duckdb\..+\.bak""")
    private val TEMPORARY_FILE_NAME = Regex("""(?:\..+|mimitrends.+)\.tmp""")
    private val MIGRATION_BACKUP_MAX_AGE: Duration = Duration.ofDays(30)
    private val LEGACY_BACKUP_MAX_AGE: Duration = Duration.ofDays(30)
    private val TEMPORARY_FILE_MAX_AGE: Duration = Duration.ofDays(1)
    private const val DAILY_BACKUPS_TO_KEEP = 3
    private const val MIGRATION_BACKUPS_TO_KEEP = 1
}