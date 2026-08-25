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
    @Test
    fun `removes legacy Lang Schwarz midpoint bars while retaining instrument identity`() {
        val database = Files.createTempDirectory("mimitrends-lang-schwarz-bars").resolve("test.db")
        MarketRepository(database).use { repository ->
            repository.upsertProviderInstrument(
                ProviderInstrument(
                    "LANG_SCHWARZ", "ENR.DE", "1240969", "LSSI", "EUR", "Siemens Energy", 1_000
                )
            )
            repository.upsertProviderMinuteBar(
                ProviderMinuteBar(
                    "LANG_SCHWARZ", "ENR.DE", "1240969", "LSSI", "EUR",
                    MinuteBar("ENR.DE", 60, 151.0, 151.0, 151.0, 151.0, 0.0, VolumeStatus.MISSING), 60_000
                )
            )
        }

        MarketRepository(database).use { repository ->
            assertTrue(repository.loadProviderMinuteBars("LANG_SCHWARZ", "ENR.DE", 0).isEmpty())
            assertEquals("1240969", repository.loadProviderInstrument("LANG_SCHWARZ", "ENR.DE")?.identifier)
        }
    }

    @Test fun `uses unique provider isin when canonical metadata is absent`() {
        val database = Files.createTempDirectory("mimitrends-provider-isin").resolve("test.db")
        MarketRepository(database).use { repository ->
            repository.upsertProviderInstrument(ProviderInstrument(
                "EURONEXT", "EOAN.DE", "DE000ENAG999", "XETR", "EUR", "E.ON SE", 1_000
            ))
            repository.upsertProviderInstrument(ProviderInstrument(
                "LANG_SCHWARZ", "EOAN.DE", "1474998", "LSSI", "EUR", "E.ON SE", 1_000
            ))

            assertEquals("DE000ENAG999", repository.loadInstrumentIsin("EOAN.DE"))
        }
    }

    @Test fun `removes cached observations from retired providers`() {
        val database = Files.createTempDirectory("mimitrends-retired-providers").resolve("test.db")
        MarketRepository(database).use { repository ->
            repository.upsertProviderInstrument(ProviderInstrument(
                "TRADERFOX", "EOAN.DE", "DE000ENAG999", "TFX", "EUR", "E.ON SE", 1_000
            ))
            repository.upsertProviderMinuteBar(ProviderMinuteBar(
                "TRADERFOX", "EOAN.DE", "DE000ENAG999", "TFX", "EUR",
                MinuteBar("EOAN.DE", 60, 17.0, 17.0, 17.0, 17.0, 0.0, VolumeStatus.MISSING), 60_000
            ))
            repository.upsertProviderQuote(ProviderQuoteSnapshot(
                "TRADERFOX", "EOAN.DE", "DE000ENAG999", "EUR", 17.0,
                null, null, null, null, null, null, null, null, null, null, null, 60_000
            ))
        }

        MarketRepository(database).use { repository ->
            assertEquals(null, repository.loadProviderInstrument("TRADERFOX", "EOAN.DE"))
            assertEquals(emptyList(), repository.loadProviderMinuteBars("TRADERFOX", "EOAN.DE", 0))
        }
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().executeQuery(
                "SELECT COUNT(*) FROM provider_quotes WHERE provider='TRADERFOX'"
            ).use { result -> result.next(); assertEquals(0, result.getInt(1)) }
        }
    }

    @Test
    fun `stores explicit inferred currency for primary minute bars`() {
        val database = Files.createTempDirectory("mimitrends-currency").resolve("test.db")
        MarketRepository(database).use { repository ->
            repository.upsertMinuteBar(MinuteBar("PEP", 60L, 100.0, 101.0, 99.0, 100.5, 10.0))
            repository.upsertMinuteBar(MinuteBar("ENEL.MI", 60L, 10.0, 10.1, 9.9, 10.0, 10.0))
            repository.flushPending()
        }

        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("""SELECT symbol, source_currency, currency_status FROM minute_bars
                    ORDER BY symbol""").use { result ->
                    result.next(); assertEquals("ENEL.MI", result.getString(1)); assertEquals("EUR", result.getString(2))
                    assertEquals("INFERRED", result.getString(3))
                    result.next(); assertEquals("PEP", result.getString(1)); assertEquals("USD", result.getString(2))
                    assertEquals("INFERRED", result.getString(3))
                }
            }
        }
    }
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
        assertEquals(listOf("TRADEGATE", "EURONEXT"),
            repository.loadProviderMinuteBars("lrcx", 0).map(ProviderMinuteBar::provider))
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
        val loaded = repository.loadLatestProviderQuote("lrcx", 15_000)
        assertEquals(100.9, loaded?.bid)
        assertEquals(101.1, loaded?.ask)
        assertEquals(null, repository.loadLatestProviderQuote("LRCX", 20_001))
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
        val database = Files.createTempDirectory("mimitrends-db").resolve("test.db")
        val repository = MarketRepository(database)
        repository.upsertMinuteBar(MinuteBar("SAP.DE", 60, 100.0, 102.0, 99.0, 101.0, 500.0))
        repository.upsertMinuteBar(MinuteBar("SAP.DE", 60, 100.0, 103.0, 99.0, 102.0, 750.0))
        val bars = repository.loadMinuteBars("SAP.DE", 0)
        assertEquals(1, bars.size); assertEquals(102.0, bars.single().close); assertEquals(750.0, bars.single().volume)
        assertEquals(listOf("SAP.DE"), repository.listSymbols())
        repository.close()
        assertFalse(legacyMinuteIndexExists(database.toString()))
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
        repository.upsertCompanyProfile(CompanyProfile("sap.de", "SAP SE", "XETRA", null, null, 43))

        val stored = requireNotNull(repository.loadCompanyProfile("AAPL"))
        assertEquals("AAPL", stored.symbol)
        assertEquals("Apple Inc", stored.name)
        assertEquals("NASDAQ", stored.exchange)
        assertEquals("https://logo", stored.logoUrl)
        assertEquals(42, stored.updatedAtMillis)
        assertContentEquals(logo, stored.logoBytes)
        val catalog = repository.loadCompanyProfiles()
        assertEquals(setOf("AAPL", "SAP.DE"), catalog.keys)
        assertEquals("SAP SE", catalog.getValue("SAP.DE").name)
        repository.close()
    }

    private fun legacyMinuteIndexExists(database: String): Boolean =
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.prepareStatement("SELECT 1 FROM sqlite_schema WHERE type='index' AND name=?").use { statement ->
                statement.setString(1, "idx_minute_symbol_time")
                statement.executeQuery().use { it.next() }
            }
        }
}