@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DuckDbAnalyticsStoreTest {
    @Test fun `copies SQLite analytics and serves aggregate and downside queries`() {
        val directory = Files.createTempDirectory("duckdb-analytics")
        val sqlitePath = directory.resolve("source.db")
        DriverManager.getConnection("jdbc:sqlite:$sqlitePath").use { source ->
            createSourceSchema(source)
            source.createStatement().use { statement ->
                statement.execute("INSERT INTO aggregate_bars VALUES('AAPL',60,1000,10,12,9,11,5000)")
                statement.execute("INSERT INTO research_samples VALUES(1,0,'AAPL',1000,10,'Control',1,1,1," +
                    "'HISTORICAL',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL," +
                    "NULL,'USD','INFERRED',10,1,1000)")
                statement.execute("INSERT INTO research_outcomes VALUES(1,90,10.1,1,90,1.2,-0.4,6400)")
            }

            DuckDbAnalyticsStore.open(sqlitePath).use { duck ->
                duck.synchronize(source)

                assertEquals(11.0, duck.loadAggregatedBars("AAPL", 60, 0).single().close)
                assertEquals(1, duck.stats().researchOutcomes)
                assertTrue(duck.downsideSafetyCalibration(european = false).probability > 0.5)
                assertTrue(duck.aggregateArchiveVerified(source))
            }
        }
    }

    private fun createSourceSchema(connection: java.sql.Connection) = connection.createStatement().use { statement ->
        statement.execute("""CREATE TABLE aggregate_bars(symbol TEXT, resolution_minutes INTEGER,
            bucket_epoch INTEGER, open REAL, high REAL, low REAL, close REAL, volume REAL)""")
        statement.execute("""CREATE TABLE research_samples(id INTEGER, run_id INTEGER, symbol TEXT,
            observed_epoch INTEGER, entry_price REAL, family TEXT, direction INTEGER, accepted INTEGER,
            published INTEGER, source TEXT, score REAL, jump_z REAL, range_z REAL, volume_z REAL, rvol REAL,
            return_1m REAL, return_3m REAL, return_5m REAL, return_10m REAL, return_30m REAL, return_60m REAL,
            range_10m REAL, volatility_30m REAL, vwap_distance REAL, session_high_distance REAL,
            session_low_distance REAL, volume_ratio_10m REAL, trend_efficiency_10m REAL, entry_currency TEXT,
            currency_status TEXT, entry_price_eur REAL, fx_rate REAL, fx_rate_epoch INTEGER)""")
        statement.execute("""CREATE TABLE research_outcomes(sample_id INTEGER, horizon_minutes INTEGER,
            observed_price REAL, return_percent REAL, elapsed_minutes REAL, maximum_return_percent REAL,
            minimum_return_percent REAL, observed_at INTEGER)""")
    }
}
