package org.senatov.mimitrends.shortmove

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.ProviderQuoteSnapshot
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShortMoveQuoteQualityTest {
    @Test
    fun `rejects expensive execution quotes despite a liquid market move`() {
        val quote = ProviderQuoteSnapshot(
            "SCALABLE", "AAPL", "instrument", "EUR", 100.0,
            99.9, 100.1, null, null, null, null, null, null,
            null, null, null, 1_000L
        )

        assertTrue(ShortMoveQuoteQuality.acceptable(quote, 100.0) { it })
        assertFalse(ShortMoveQuoteQuality.acceptable(quote.copy(bid = 99.0, ask = 101.0), 100.0) { it })
        assertFalse(ShortMoveQuoteQuality.acceptable(quote.copy(bid = 103.0, ask = 103.1), 100.0) { it })
    }
}
