package org.senatov.mimitrends.market

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YahooSymbolCooldownTest {
    @Test
    fun `retries an unavailable symbol after the cooldown`() {
        var now = 1_000L
        val cooldown = YahooSymbolCooldown({ now }, retryMillis = 900L)

        assertFalse(cooldown.isCooling("BLL"))
        cooldown.pause("BLL")
        assertTrue(cooldown.isCooling("BLL"))
        assertFalse(cooldown.isCooling("ANTM"))

        now += 900L
        assertFalse(cooldown.isCooling("BLL"))
    }
}
