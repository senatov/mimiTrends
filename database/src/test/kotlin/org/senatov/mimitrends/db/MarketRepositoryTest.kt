@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.CompanyProfile
import org.senatov.mimitrends.model.VolumeStatus
import org.senatov.mimitrends.model.ProviderInstrument
import org.senatov.mimitrends.model.ProviderMinuteBar
import org.senatov.mimitrends.model.ProviderQuoteSnapshot
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarketRepositoryTest {
    @Test fun `provider bars reject stale observations and merge newer minute values`() {
        val repository = MarketRepository(Files.createTempDirectory("mimitrends-provider-bars").resolve("test.db"))
        repository.upsertProviderInstrument(ProviderInstrument(
            "TRADEGATE", "LRCX", "US5128073062", "XGAT", "EUR", "Lam Research Corp.", 1_000
        ))
        fun observation(price: Double, observedAt: Long) = ProviderMinuteBar(
            "TRADEGATE", "LRCX", "US5128073062", "XGAT", "EUR",
            MinuteBar("LRCX", 60, price, price, price, price, 0.0, VolumeStatus.MISSING), observedAt
        )

        assertEquals(true, repository.upsertProviderMinuteBar(observation(100.0, 10_000)))
        assertEquals(true, repository.upsertProviderMinuteBar(observation(103.0, 20_000)))
        assertEquals(false, repository.upsertProviderMinuteBar(observation(90.0, 15_000)))

        val stored = repository.loadProviderMinuteBars("TRADEGATE", "LRCX", 0).single()
        assertEquals(100.0, stored.bar.open)
        assertEquals(103.0, stored.bar.high)
        assertEquals(100.0, stored.bar.low)
        assertEquals(103.0, stored.bar.close)
        assertEquals(20_000, stored.observedAtMillis)
        val newerProvider = ProviderMinuteBar(
            "EURONEXT", "LRCX", "US5128073062", "XPAR", "EUR",
            MinuteBar("LRCX", 120, 104.0, 104.0, 104.0, 104.0, 0.0, VolumeStatus.MISSING), 125_000
        )
        repository.upsertProviderMinuteBar(newerProvider)
        assertEquals("EURONEXT", repository.loadLatestProviderMinuteBar("lrcx", 100)?.provider)
        assertEquals(null, repository.loadLatestProviderMinuteBar("LRCX", 121))
        assertEquals("US5128073062", repository.loadProviderInstrument("TRADEGATE", "LRCX")?.identifier)
        assertTrue(repository.deleteProviderInstrument("tradegate", "lrcx"))
        assertEquals(null, repository.loadProviderInstrument("TRADEGATE", "LRCX"))
        repository.close()
    }

    @Test fun `provider quote snapshot cannot move backwards`() {
        val database = Files.createTempDirectory("mimitrends-provider-quotes").resolve("test.db")
        val repository = MarketRepository(database)
        fun quote(last: Double, observedAt: Long) = ProviderQuoteSnapshot(
            "TRADEGATE", "LRCX", "US5128073062", "EUR", last, last - 0.1, last + 0.1,
            10.0, 20.0, 1_000.0, 250_000.0, last - 0.5, 42, last + 1.0, last - 1.0, last - 2.0, observedAt
        )
        assertTrue(repository.upsertProviderQuote(quote(101.0, 20_000)))
        assertFalse(repository.upsertProviderQuote(quote(99.0, 10_000)))
        repository.close()

        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().executeQuery(
                "SELECT last, observed_at FROM provider_quotes WHERE provider='TRADEGATE' AND symbol='LRCX'"
            ).use { result ->
                assertTrue(result.next())
                assertEquals(101.0, result.getDouble(1))
                assertEquals(20_000, result.getLong(2))
            }
        }
    }

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
