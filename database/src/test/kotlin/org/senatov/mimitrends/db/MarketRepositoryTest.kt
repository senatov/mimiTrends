@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.CompanyProfile
import org.senatov.mimitrends.model.VolumeStatus
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals

class MarketRepositoryTest {
    @Test fun `stores and updates minute bars`() {
        val repository = MarketRepository(Files.createTempDirectory("mimitrends-db").resolve("test.db"))
        repository.upsertMinuteBar(MinuteBar("SAP.DE", 60, 100.0, 102.0, 99.0, 101.0, 500.0))
        repository.upsertMinuteBar(MinuteBar("SAP.DE", 60, 100.0, 103.0, 99.0, 102.0, 750.0))
        val bars = repository.loadMinuteBars("SAP.DE", 0)
        assertEquals(1, bars.size); assertEquals(102.0, bars.single().close); assertEquals(750.0, bars.single().volume)
        assertEquals(listOf("SAP.DE"), repository.listSymbols())
        repository.close()
    }

    @Test fun `persists explicit volume quality`() {
        val repository = MarketRepository(Files.createTempDirectory("mimitrends-volume-status").resolve("test.db"))
        repository.upsertMinuteBar(MinuteBar("TEST", 60, 10.0, 10.1, 9.9, 10.0, 0.0, VolumeStatus.MISSING))

        assertEquals(VolumeStatus.MISSING, repository.loadMinuteBars("TEST", 0).single().volumeStatus)
        repository.close()
    }

    @Test fun `migrates legacy minute bars with inferred volume quality`() {
        val database = Files.createTempDirectory("mimitrends-legacy-volume").resolve("test.db")
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("""CREATE TABLE minute_bars(symbol TEXT NOT NULL, minute_epoch INTEGER NOT NULL,
                    open REAL NOT NULL, high REAL NOT NULL, low REAL NOT NULL, close REAL NOT NULL,
                    volume REAL NOT NULL, PRIMARY KEY(symbol, minute_epoch))""")
                statement.executeUpdate("INSERT INTO minute_bars VALUES ('TEST', 60, 10, 10, 10, 10, 100)")
                statement.executeUpdate("INSERT INTO minute_bars VALUES ('TEST', 120, 10, 10, 10, 10, 0)")
            }
        }

        val repository = MarketRepository(database)
        val bars = repository.loadMinuteBars("TEST", 0)

        assertEquals(VolumeStatus.REPORTED, bars[0].volumeStatus)
        assertEquals(VolumeStatus.MISSING, bars[1].volumeStatus)
        repository.close()
    }

    @Test fun `stores company profile and logo in a separate table`() {
        val repository = MarketRepository(Files.createTempDirectory("mimitrends-profiles").resolve("test.db"))
        val logo = byteArrayOf(1, 3, 5, 7)
        repository.upsertCompanyProfile(CompanyProfile("aapl", "Apple Inc", "NASDAQ", "https://logo", logo, 42))

        val stored = requireNotNull(repository.loadCompanyProfile("AAPL"))
        assertEquals("AAPL", stored.symbol)
        assertEquals("Apple Inc", stored.name)
        assertEquals("NASDAQ", stored.exchange)
        assertEquals("https://logo", stored.logoUrl)
        assertEquals(42, stored.updatedAtMillis)
        assertContentEquals(logo, stored.logoBytes)
        repository.close()
    }
}
