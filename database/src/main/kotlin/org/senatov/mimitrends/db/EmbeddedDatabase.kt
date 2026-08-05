@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class EmbeddedDatabase private constructor(private val state: State) : AutoCloseable {
    private val closed = AtomicBoolean()
    internal val connection: Connection get() = state.connection

    fun <T> locked(block: (Connection) -> T): T {
        check(!closed.get()) { "Embedded database lease is closed" }
        val started = System.nanoTime()
        state.lock.lock()
        state.lockWaitNanos.add(System.nanoTime() - started)
        state.operations.increment()
        return try {
            block(state.connection)
        } finally {
            state.lock.unlock()
        }
    }

    fun quickCheck(): String = locked { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA quick_check").use { result ->
                buildList { while (result.next()) add(result.getString(1)) }.joinToString("; ")
            }
        }
    }

    fun stats(): EmbeddedDatabaseStats {
        val operations = state.operations.sum()
        val averageWaitMicros = if (operations == 0L) 0L else state.lockWaitNanos.sum() / operations / 1_000L
        val walPath = state.path.resolveSibling("${state.path.fileName}-wal")
        val walBytes = if (Files.isRegularFile(walPath)) Files.size(walPath) else 0L
        return EmbeddedDatabaseStats(operations, averageWaitMicros, Files.size(state.path), walBytes)
    }

    fun backupIfDue(retainedCopies: Int = 3): Path? = locked { connection ->
        val backupDirectory = state.path.parent.resolve("backups")
        val target = backupDirectory.resolve("mimitrends-${LocalDate.now()}.db")
        if (Files.isRegularFile(target)) return@locked null
        Files.createDirectories(backupDirectory)
        val temporary = backupDirectory.resolve(".${target.fileName}.tmp")
        Files.deleteIfExists(temporary)
        connection.createStatement().use { statement ->
            statement.execute("VACUUM INTO '${temporary.toString().replace("'", "''")}'")
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target)
        }
        Files.list(backupDirectory).use { files ->
            files.filter { it.fileName.toString().matches(BACKUP_NAME) }
                .sorted(Comparator.reverseOrder()).skip(retainedCopies.coerceAtLeast(1).toLong())
                .forEach(Files::deleteIfExists)
        }
        target
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(states) {
            if (--state.references > 0) return
            states.remove(state.path)
            state.lock.withLock {
                runCatching { state.connection.createStatement().use { it.execute("PRAGMA optimize") } }
                runCatching { state.connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") } }
                state.connection.close()
            }
        }
    }

    private class State(val path: Path, val connection: Connection) {
        val lock = ReentrantLock(true)
        val lockWaitNanos = LongAdder()
        val operations = LongAdder()
        var references = 1
    }

    companion object {
        private val states = mutableMapOf<Path, State>()
        private val BACKUP_NAME = Regex("mimitrends-\\d{4}-\\d{2}-\\d{2}\\.db")

        fun open(path: Path = defaultPath()): EmbeddedDatabase = synchronized(states) {
            val normalized = path.toAbsolutePath().normalize()
            states[normalized]?.let { state ->
                state.references++
                return@synchronized EmbeddedDatabase(state)
            }
            Files.createDirectories(normalized.parent)
            val connection = DriverManager.getConnection("jdbc:sqlite:$normalized")
            configure(connection)
            verifyIntegrity(connection)
            State(normalized, connection).also { states[normalized] = it }.let(::EmbeddedDatabase)
        }

        fun defaultPath(): Path = Path.of(System.getProperty("user.home"), ".mimi", "trends", "mimitrends.db")

        private fun configure(connection: Connection) {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA synchronous=NORMAL")
                statement.execute("PRAGMA busy_timeout=10000")
                statement.execute("PRAGMA wal_autocheckpoint=2000")
                statement.execute("PRAGMA foreign_keys=ON")
                statement.execute("PRAGMA temp_store=MEMORY")
                statement.execute("PRAGMA cache_size=-20000")
                statement.execute("PRAGMA mmap_size=268435456")
            }
        }

        private fun verifyIntegrity(connection: Connection) {
            val result = connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA quick_check").use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }.joinToString("; ")
                }
            }
            if (!result.equals("ok", ignoreCase = true)) {
                connection.close()
                error("SQLite integrity check failed: $result")
            }
        }
    }
}

data class EmbeddedDatabaseStats(
    val operations: Long,
    val averageLockWaitMicros: Long,
    val databaseBytes: Long,
    val walBytes: Long
)
