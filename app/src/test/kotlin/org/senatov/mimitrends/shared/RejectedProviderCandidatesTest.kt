package org.senatov.mimitrends.shared

import org.senatov.mimitrends.application.*
import org.senatov.mimitrends.ui.*
import org.senatov.mimitrends.scanner.*
import org.senatov.mimitrends.shortmove.*
import org.senatov.mimitrends.signals.*
import org.senatov.mimitrends.research.*
import org.senatov.mimitrends.market.*
import org.senatov.mimitrends.providers.*
import org.senatov.mimitrends.company.*
import org.senatov.mimitrends.services.*
import org.senatov.mimitrends.shared.*

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RejectedProviderCandidatesTest {
    @Test
    fun `remembers a rejected provider path for the same instrument identity`() {
        val rejected = RejectedProviderCandidates()

        assertTrue(rejected.reject("sie.de", "de0007236101", "/siemens-energy-aktie"))
        assertTrue(rejected.contains("SIE.DE", "DE0007236101", "/siemens-energy-aktie"))
        assertFalse(rejected.reject("SIE.DE", "DE0007236101", "/siemens-energy-aktie"))
    }

    @Test
    fun `does not suppress another path or a corrected identity`() {
        val rejected = RejectedProviderCandidates()
        rejected.reject("BMW.DE", "DE0005190003", "/bayer-aktie")

        assertFalse(rejected.contains("BMW.DE", "DE0005190003", "/bmw-aktie"))
        assertFalse(rejected.contains("BMW.DE", "DE000NEW0001", "/bayer-aktie"))
    }
}