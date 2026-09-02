package org.senatov.mimitrends.marketdata

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompanyDomainMatcherTest {
    private val mapper = ObjectMapper()

    @Test
    fun `chooses the matching company instead of the first search result`() {
        val results = mapper.readTree(
            """[
                {"name":"Snap-on Incorporated","domain":"snapon.com"},
                {"name":"Snap Inc.","domain":"https://www.snap.com/about"}
            ]"""
        )

        assertEquals("snap.com", CompanyDomainMatcher.select(results, "Snap Inc.")?.domain)
    }

    @Test
    fun `rejects an unrelated search result`() {
        val results = mapper.readTree("""[{"name":"International Paper","domain":"internationalpaper.com"}]""")

        assertNull(CompanyDomainMatcher.select(results, "International Business Machines Corporation"))
    }

    @Test
    fun `rejects malformed domains`() {
        val results = mapper.readTree("""[{"name":"Snap Inc.","domain":"javascript:alert(1)"}]""")

        assertNull(CompanyDomainMatcher.select(results, "Snap Inc."))
    }
}