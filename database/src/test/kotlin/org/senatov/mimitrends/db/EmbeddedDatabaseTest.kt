@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbeddedDatabaseTest {
    @Test
    fun `shares one connection and keeps it alive until the last lease closes`() {
        val path = Files.createTempDirectory("mimitrends-shared-db").resolve("test.db")
        val first = EmbeddedDatabase.open(path)
        val second = EmbeddedDatabase.open(path)
        assertTrue(first.connection === second.connection)

        first.locked { it.createStatement().use { statement -> statement.execute("CREATE TABLE shared(value INTEGER)") } }
        first.close()
        val count = second.locked { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM shared").use { result -> result.next(); result.getInt(1) }
            }
        }

        assertEquals(0, count)
        second.close()
    }

    @Test
    fun `checks integrity and creates at most one daily backup`() {
        val path = Files.createTempDirectory("mimitrends-backup").resolve("test.db")
        val database = EmbeddedDatabase.open(path)
        database.locked { it.createStatement().use { statement -> statement.execute("CREATE TABLE sample(value TEXT)") } }

        assertEquals("ok", database.quickCheck())
        val backup = assertNotNull(database.backupIfDue())
        assertTrue(Files.size(backup) > 0)
        assertNull(database.backupIfDue())
        database.close()
    }
}
