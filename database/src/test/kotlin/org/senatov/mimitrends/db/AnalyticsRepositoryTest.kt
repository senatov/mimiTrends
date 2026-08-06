@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ProviderInstrument
import org.senatov.mimitrends.model.ScanResult
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyticsRepositoryTest {
    @Test fun `loads the latest published result for each symbol across scan runs`() {
        val path = Files.createTempDirectory("mimitrends-published").resolve("test.db")
        MarketRepository(path).close()
        AnalyticsRepository(path).use { analytics ->
            val firstRun = analytics.beginScan("EUROPE", 1, 180)
            analytics.recordScanCandidate(firstRun, "SAP.DE", result(1_800_000_000L), null, "YAHOO")
            analytics.completeScan(firstRun, listOf("SAP.DE"), 0)
            val secondRun = analytics.beginScan("EUROPE", 1, 180)
            analytics.recordScanCandidate(secondRun, "SIE.DE",
                result(1_800_000_060L).copy(symbol = "SIE.DE"), null, "YAHOO")
            analytics.completeScan(secondRun, listOf("SIE.DE"), 0)

            assertEquals(listOf("SIE.DE", "SAP.DE"),
                analytics.loadLatestPublishedResults(10).map(ScanResult::symbol))
        }
    }

    @Test fun `recovers interrupted scans and expires old provider observations`() {
        val path = Files.createTempDirectory("mimitrends-recovery").resolve("test.db")
        MarketRepository(path).close()
        AnalyticsRepository(path).close()
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("""INSERT INTO scan_runs
                    (started_at, region, requested_symbols, interval_seconds, status)
                    VALUES (100, 'EUROPE', 1, 180, 'RUNNING')""")
                statement.executeUpdate("""INSERT INTO provider_minute_bars
                    (provider, symbol, identifier, mic, currency, minute_epoch, open, high, low, close,
                     volume, volume_status, observed_at)
                    VALUES ('TRADEGATE', 'TEST.DE', 'DE0000000001', 'XGAT', 'EUR', 100,
                            10, 10, 10, 10, 0, 'MISSING', 100000)""")
            }
        }

        val analytics = AnalyticsRepository(path)
        analytics.applyRetention(nowEpoch = 100 + 91L * 86_400L)
        analytics.close()

        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT status, completed_at FROM scan_runs").use { result ->
                    assertTrue(result.next())
                    assertEquals("ABORTED", result.getString(1))
                    assertTrue(result.getLong(2) > 0L)
                }
                statement.executeQuery("SELECT COUNT(*) FROM provider_minute_bars").use { result ->
                    assertTrue(result.next())
                    assertEquals(0, result.getInt(1))
                }
            }
        }
    }

    @Test fun `resolves isin by company name and reconstructs broker trade`() {
        val directory = Files.createTempDirectory("mimitrends-trades")
        val database = directory.resolve("test.db")
        val csv = directory.resolve("transactions.csv")
        Files.writeString(csv, """date;time;status;reference;description;assetType;type;isin;shares;price;amount;fee;tax;currency
2026-07-31;18:00:00;Executed;BUY-1;Apple;Security;Buy;US0378331005;2;100,00;-200,00;1,00;0,00;EUR
2026-07-31;19:00:00;Executed;SELL-1;Apple;Security;Sell;US0378331005;2;110,00;220,00;1,00;0,00;EUR
""")
        val analytics = AnalyticsRepository(database)
        analytics.upsertInstrument(InstrumentMetadata("AAPL", "Apple Inc.", "NASDAQ", "USD", "America/New_York"))
        analytics.importScalableTransactions(csv)
        val trade = analytics.loadBrokerTrades("AAPL", "Apple Inc.").single()
        assertEquals(18.0, requireNotNull(trade.profitAmount), 0.000_001)
        assertEquals(18.0 / 201.0 * 100.0, requireNotNull(trade.profitPercent), 0.000_001)
        analytics.close()
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().executeQuery("SELECT isin FROM instrument_metadata WHERE symbol='AAPL'").use {
                it.next(); assertEquals("US0378331005", it.getString(1))
            }
        }
    }

    @Test fun `does not rewrite the symbol of a transaction already linked to a scan candidate`() {
        val directory = Files.createTempDirectory("mimitrends-linked-trade")
        val database = directory.resolve("test.db")
        val csv = directory.resolve("transactions.csv")
        Files.writeString(csv, """date;time;status;reference;description;assetType;type;isin;shares;price;amount;fee;tax;currency
2026-07-31;18:00:00;Executed;BUY-GOOG;Alphabet;Security;Buy;US02079K3059;1;100,00;-100,00;0,00;0,00;EUR
""")
        val executionEpoch = ScalableCsvImporter.parse(csv).single().occurredAtEpochSeconds
        AnalyticsRepository(database).use { analytics ->
            analytics.upsertInstrument(InstrumentMetadata(
                "GOOGL", "Alphabet Inc.", "NASDAQ", "USD", "America/New_York", isin = "US02079K3059"
            ))
            val run = analytics.beginScan("US", 1, 180)
            analytics.recordScanCandidate(run, "GOOGL",
                result(executionEpoch - 60).copy(symbol = "GOOGL"), null, "TEST")
            analytics.completeScan(run, listOf("GOOGL"), 0)
            analytics.importScalableTransactions(csv)
            analytics.upsertInstrument(InstrumentMetadata(
                "GOOG", "Alphabet Inc.", "NASDAQ", "USD", "America/New_York", isin = "US02079K3059"
            ))

            analytics.loadBrokerTrades("GOOG", "Alphabet Inc.")
        }
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().executeQuery(
                "SELECT linked_symbol FROM broker_transactions WHERE reference='BUY-GOOG'"
            ).use { result ->
                assertTrue(result.next())
                assertEquals("GOOGL", result.getString(1))
            }
        }
    }

    @Test fun `repairs placeholder isin from a provider instrument when loading trades`() {
        val directory = Files.createTempDirectory("mimitrends-provider-isin")
        val database = directory.resolve("test.db")
        val csv = directory.resolve("transactions.csv")
        Files.writeString(csv, """date;time;status;reference;description;assetType;type;isin;shares;price;amount;fee;tax;currency
2026-07-31;18:00:00;Executed;BUY-1;Unrelated label;Security;Buy;DE000ENER6Y0;2;100,00;-200,00;0,00;0,00;EUR
2026-07-31;19:00:00;Executed;SELL-1;Unrelated label;Security;Sell;DE000ENER6Y0;2;110,00;220,00;0,00;0,00;EUR
""")
        MarketRepository(database).use { market ->
            market.upsertProviderInstrument(ProviderInstrument(
                "TRADEGATE", "ENR.DE", "DE000ENER6Y0", "XGAT", "EUR", "Siemens Energy", 1L
            ))
        }
        AnalyticsRepository(database).use { analytics ->
            analytics.upsertInstrument(InstrumentMetadata(
                "ENR.DE", "Siemens Energy AG", "XETRA", "EUR", "Europe/Berlin", isin = "ENR"
            ))
            analytics.importScalableTransactions(csv)

            assertEquals(1, analytics.loadBrokerTrades("ENR.DE", "Siemens Energy AG").size)
        }
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().executeQuery(
                "SELECT isin FROM instrument_metadata WHERE symbol='ENR.DE'"
            ).use { result ->
                assertTrue(result.next())
                assertEquals("DE000ENER6Y0", result.getString(1))
            }
        }
    }

    @Test fun `ignores repeated executions with the same timestamp side isin and quantity`() {
        val directory = Files.createTempDirectory("mimitrends-temporal-duplicate")
        val database = directory.resolve("test.db")
        val csv = directory.resolve("transactions.csv")
        Files.writeString(csv, """date;time;status;reference;description;assetType;type;isin;shares;price;amount;fee;tax;currency
2026-07-31;18:00:00;Executed;BUY-1;Apple;Security;Buy;US0378331005;2;100,00;-200,00;0,00;0,00;EUR
2026-07-31;19:00:00;Executed;SELL-OLD;Apple;Security;Sell;US0378331005;2;109,00;218,00;0,00;0,00;EUR
2026-07-31;19:00:00;Executed;SELL-LATEST;Apple;Security;Sell;US0378331005;2;110,00;220,00;0,00;0,00;EUR
""")
        AnalyticsRepository(database).use { analytics ->
            analytics.upsertInstrument(InstrumentMetadata(
                "AAPL", "Apple Inc.", "NASDAQ", "USD", "America/New_York", isin = "US0378331005"
            ))
            analytics.importScalableTransactions(csv)

            val trade = analytics.loadBrokerTrades("AAPL", "Apple Inc.").single()
            assertEquals(110.0, requireNotNull(trade.exitPrice), 0.000_001)
        }
    }

    @Test fun `imports scalable csv idempotently and parses european decimals`() {
        val directory = Files.createTempDirectory("mimitrends-scalable")
        val database = directory.resolve("test.db")
        val csv = directory.resolve("transactions.csv")
        Files.writeString(csv, """date;time;status;reference;description;assetType;type;isin;shares;price;amount;fee;tax;currency
2026-07-31;22:47:47;Executed;"SCAL-1";"Apple";Security;Sell;US0378331005;13;268,1653846154;3.486,1500000002;0,00;0,00;EUR
2026-07-31;18:25:11;Cancelled;"SCAL-2";"Amazon.com";Security;Buy;US0231351067;0;0,00;0,00;0,00;0,00;EUR
2026-06-08;02:00:00;Executed;"CASH-1";"Broker deposit";Cash;Deposit;;;;2.000,00;0,00;;EUR
""")
        val analytics = AnalyticsRepository(database)
        val first = analytics.importScalableTransactions(csv)
        val second = analytics.importScalableTransactions(csv)
        assertEquals(3, first.parsed)
        assertEquals(3, first.imported)
        assertEquals(0, first.duplicates)
        assertEquals(0, second.imported)
        assertEquals(3, second.duplicates)
        assertEquals(3, analytics.stats().brokerTransactions)
        analytics.close()

        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT shares, price, amount FROM broker_transactions WHERE reference='SCAL-1'").use {
                    it.next(); assertEquals(13.0, it.getDouble(1)); assertEquals(268.1653846154, it.getDouble(2), 0.000_000_000_1)
                    assertEquals(3486.1500000002, it.getDouble(3), 0.000_000_000_1)
                }
                statement.executeQuery("SELECT shares, price, amount, tax FROM broker_transactions WHERE reference='CASH-1'").use {
                    it.next(); assertEquals(0.0, it.getDouble(1)); assertEquals(0.0, it.getDouble(2))
                    assertEquals(2000.0, it.getDouble(3)); assertEquals(0.0, it.getDouble(4))
                }
            }
        }
    }

    @Test fun `migrates and stores derived analytics and scan history`() {
        val path = Files.createTempDirectory("mimitrends-analytics").resolve("test.db")
        MarketRepository(path).close()
        val analytics = AnalyticsRepository(path)
        analytics.upsertInstrument(InstrumentMetadata("TEST", "Test Corp", "NASDAQ", "USD", "America/New_York"))
        analytics.upsertCorporateAction(CorporateAction("TEST", "SPLIT", 1_000, ratio = 2.0, source = "TEST"))
        analytics.recordFxRate("EUR", "USD", 1.15, "TEST", 86_400)

        val bars = (0 until 180).map { minute ->
            val open = 100.0 + minute * 0.01
            MinuteBar("TEST", 1_800_000_000L + minute * 60L, open, open + 0.2, open - 0.1, open + 0.1, 1_000.0 + minute)
        }
        analytics.refreshDerived("TEST", bars, "TEST")
        analytics.recordDataQuality("TEST", "TEST", "REALTIME", bars.last().minuteEpochSeconds, bars.size)
        assertTrue(analytics.loadAggregatedBars("TEST", 5, 0).isNotEmpty())

        val signalEpoch = Instant.now().epochSecond - 5 * 60L
        val run = analytics.beginScan("US", 1, 180)
        analytics.recordScanCandidate(run, "TEST", result(signalEpoch), null, "TEST")
        analytics.completeScan(run, listOf("TEST"), 0)
        val saved = analytics.loadLatestPublishedResults(10).single()
        assertEquals("TEST", saved.symbol)
        assertEquals("TEST", saved.dataStatus)
        assertEquals(result(signalEpoch).updatedAtMillis, saved.updatedAtMillis)
        analytics.recordSignalOutcomes("TEST", 103.0, signalEpoch + 2 * 60L)
        analytics.recordSignalOutcomes("TEST", 99.0, signalEpoch + 4 * 60L)
        analytics.recordSignalOutcomes("TEST", 102.0, signalEpoch + 5 * 60L)
        val stats = analytics.stats()
        assertEquals(1, stats.instruments)
        assertEquals(1, stats.scanRuns)
        assertEquals(1, stats.scanCandidates)
        assertTrue(stats.aggregateBars > 0)
        assertTrue(stats.baselines > 0)
        assertTrue(stats.outcomes > 0)
        analytics.close()

        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM corporate_actions").use { it.next(); assertEquals(1, it.getInt(1)) }
                statement.executeQuery("SELECT COUNT(*) FROM trading_sessions").use { it.next(); assertTrue(it.getInt(1) > 0) }
                statement.executeQuery("SELECT COUNT(*) FROM market_calendar_rules").use { it.next(); assertEquals(4, it.getInt(1)) }
                statement.executeQuery("SELECT published FROM scan_candidates").use { it.next(); assertEquals(1, it.getInt(1)) }
                statement.executeQuery("SELECT signal_epoch, entry_price, data_epoch FROM scan_candidates").use {
                    it.next(); assertEquals(signalEpoch, it.getLong(1)); assertEquals(100.0, it.getDouble(2))
                    assertEquals(result(signalEpoch).updatedAtMillis / 1_000L, it.getLong(3))
                }
                statement.executeQuery("""SELECT entry_price, observed_price, return_percent, elapsed_minutes,
                    maximum_return_percent, minimum_return_percent FROM signal_outcomes""").use {
                    it.next(); assertEquals(100.0, it.getDouble(1)); assertEquals(102.0, it.getDouble(2))
                    assertEquals(2.0, it.getDouble(3), 0.000_001)
                    assertEquals(5.0, it.getDouble(4), 0.000_001)
                    assertEquals(3.0, it.getDouble(5), 0.000_001)
                    assertEquals(-1.0, it.getDouble(6), 0.000_001)
                }
            }
        }
        AnalyticsRepository(path).close()
    }

    private fun result(signalEpoch: Long) = ScanResult(
        symbol = "TEST", price = 101.0, anomalyScore = 3.0, priceAnomaly = 4.0,
        volumeAnomaly = 2.0, rangeAnomaly = 3.0, relativeVolume = 2.0,
        candleBodyRatio = 0.8, windowChangePercent = 1.2, windowVolume = 1_000.0,
        sessionVolume = 100_000.0, sessionTurnover = 10_000_000.0,
        signalAgeMinutes = 0, signalSource = "Impulse ↑", updatedAtMillis = 1_000,
        dataStatus = "TEST", signalWindowLabel = "latest",
        signalPrice = 100.0, signalEpochMillis = signalEpoch * 1_000L
    )
}
