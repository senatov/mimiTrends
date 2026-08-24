package org.senatov.mimitrends.scanner

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.ScannerCriteria
import org.senatov.mimitrends.model.TableAppearance
import org.senatov.mimitrends.model.UiDensity
import org.senatov.mimitrends.model.UiTheme
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScannerSettingsServiceTest {
    @Test
    fun `persists workspace theme and density`() {
        val path = Files.createTempDirectory("mimitrends-appearance").resolve("scanner.properties")
        val service = ScannerSettingsService(path)

        service.save(
            ScannerCriteria(
                tableAppearance = TableAppearance(
                    theme = UiTheme.DARK, density = UiDensity.COMFORTABLE
                )
            )
        )

        assertEquals(UiTheme.DARK, service.load().tableAppearance.theme)
        assertEquals(UiDensity.COMFORTABLE, service.load().tableAppearance.density)
    }

    @Test
    fun `persists additional provider configuration`() {
        val path = Files.createTempDirectory("mimitrends-settings").resolve("scanner.properties")
        val service = ScannerSettingsService(path)

        service.save(ScannerCriteria(
            tradegateEnabled = true, tradegateRequestIntervalMillis = 1_750,
            euronextEnabled = true, euronextRequestIntervalMillis = 2_250
        ))
        val restored = service.load()

        assertTrue(restored.tradegateEnabled)
        assertEquals(1_750, restored.tradegateRequestIntervalMillis)
        assertTrue(restored.euronextEnabled)
        assertEquals(2_250, restored.euronextRequestIntervalMillis)
    }

    @Test
    fun `migrates the former default selection fill to pale yellow`() {
        val path = Files.createTempDirectory("mimitrends-selection-color").resolve("scanner.properties")
        Files.writeString(path, "table.selectionColor=#DCE8F6\n")

        val restored = ScannerSettingsService(path).load()

        assertEquals("#FFFDE1", restored.tableAppearance.selectionColor)
    }

    @Test
    fun `migrates renamed tickers in a persisted watchlist`() {
        val path = Files.createTempDirectory("mimitrends-ticker-migration").resolve("scanner.properties")
        Files.writeString(path, "symbols=AAPL,MMC,FI,SQ,MRSH,FISV,XYZ\n")

        val restored = ScannerSettingsService(path).load()

        assertEquals(listOf("AAPL", "MRSH", "FISV", "XYZ"), restored.symbols)
    }

    @Test
    fun `replaces the former Helsinki block in a persisted standard universe`() {
        val path = Files.createTempDirectory("mimitrends-helsinki-migration").resolve("scanner.properties")
        Files.writeString(path, "symbols=AAPL,NOKIA.HE,KNEBV.HE,FORTUM.HE,UPM.HE,NESTE.HE,SAMPO.HE,WRT1V.HE,METSO.HE\n")

        val restored = ScannerSettingsService(path).load()

        assertTrue(restored.symbols.none { it.endsWith(".HE") })
        assertTrue(setOf("RHM.DE", "CBK.DE")
            .all(restored.symbols::contains))
        assertTrue(setOf("SGO.PA", "LR.PA").none(restored.symbols::contains))
        assertTrue(restored.symbols.none { it.endsWith(".MI") })
        assertTrue(restored.symbols.none { it.contains('.') && !it.endsWith(".DE") })
    }

    @Test
    fun `removes transaction-taxed shares from a persisted watchlist`() {
        val path = Files.createTempDirectory("mimitrends-tax-exclusions").resolve("scanner.properties")
        Files.writeString(path, "symbols=AAPL,TTE.PA,MC.PA,AIR.PA,STMPA.PA\n")

        val restored = ScannerSettingsService(path).load()

        assertEquals(listOf("AAPL"), restored.symbols)
    }
}