package org.senatov.mimitrends.marketdata

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProviderUrlsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `normalizes provider roots without discarding their path`() {
        assertEquals("https://example.org/aktien/", ProviderUrls.validate(ProviderUrl.TRADERFOX,
            " https://example.org/aktien "))
        assertEquals("https://example.org/europa", ProviderUrls.validate(ProviderUrl.LANG_SCHWARZ_EUROPE,
            "https://example.org/europa/"))
    }

    @Test
    fun `rejects insecure or ambiguous provider roots`() {
        listOf("http://example.org", "https://name:secret@example.org", "https://example.org/?q=1")
            .forEach { assertFailsWith<IllegalArgumentException> { ProviderUrls.validate(ProviderUrl.EURONEXT, it) } }
    }

    @Test
    fun `persists changes and loads defaults for unspecified providers`() {
        val path = directory.resolve("provider-urls.properties")
        val store = ProviderUrlStore(path)
        store.save(mapOf(ProviderUrl.EURONEXT to "https://example.org/"))

        val reloaded = ProviderUrlStore(path)
        assertEquals("https://example.org", reloaded.get(ProviderUrl.EURONEXT))
        assertEquals(ProviderUrl.TRADEGATE.default, reloaded.get(ProviderUrl.TRADEGATE))
    }
}
