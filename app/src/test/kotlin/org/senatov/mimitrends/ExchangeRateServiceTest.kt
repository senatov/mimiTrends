package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.DisplayCurrency
import java.nio.file.Files
import kotlin.test.assertEquals

class ExchangeRateServiceTest {
    @Test
    fun `converts every candle price into euros with one factor`() {
        val cache = Files.createTempFile("mimitrends-rate", ".properties")
        Files.writeString(cache, "usdPerEur=1.25\n")
        val service = ExchangeRateService(cache)
        val source = MinuteBar("PEP", 1L, 125.0, 127.5, 122.5, 126.25, 100.0)

        val converted = service.convertBar("PEP", source)

        assertEquals(100.0, converted.open, 1e-9)
        assertEquals(102.0, converted.high, 1e-9)
        assertEquals(98.0, converted.low, 1e-9)
        assertEquals(101.0, converted.close, 1e-9)
        assertEquals(source.volume, converted.volume)
    }

    @Test
    fun `keeps explicit euro provider candle unchanged`() {
        val cache = Files.createTempFile("mimitrends-rate", ".properties")
        Files.writeString(cache, "usdPerEur=1.25\n")
        val service = ExchangeRateService(cache)
        val source = MinuteBar("PEP", 1L, 100.0, 101.0, 99.0, 100.5, 100.0)

        val converted = service.convertBar(source, "EUR", DisplayCurrency.EUR)

        assertEquals(source, converted)
    }

    @Test
    fun `recognizes euro listings from configured exchanges`() {
        val cache = Files.createTempFile("mimitrends-rate", ".properties")
        Files.writeString(cache, "usdPerEur=1.25\n")
        val service = ExchangeRateService(cache)

        assertEquals(100.0, service.convert("ENEL.MI", 100.0, DisplayCurrency.EUR), 1e-9)
        assertEquals(100.0, service.convert("NOKIA.HE", 100.0, DisplayCurrency.EUR), 1e-9)
        assertEquals(80.0, service.convert("PEP", 100.0, DisplayCurrency.EUR), 1e-9)
    }
}
