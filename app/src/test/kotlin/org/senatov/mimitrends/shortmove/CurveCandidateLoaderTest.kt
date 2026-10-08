package org.senatov.mimitrends.shortmove

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.db.CurveCandidateStore
import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.model.CurveDecision
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.services.ExchangeRateService
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CurveCandidateLoaderTest {
    private val now = 20_000L

    @Test
    fun `approval promotes a candidate and rejection hides the same event`() {
        val path = Files.createTempDirectory("curve-loader").resolve("market.db")
        MarketRepository(path).use { repository ->
            CurveCandidateStore(path).use { store ->
                prices("RISE.DE", 100.0, 100.1, 100.2, 100.3, 100.4, 100.5)
                    .forEach(repository::upsertMinuteBar)
                prices("DROP.DE", 100.0, 99.95, 99.9, 99.79)
                    .forEach(repository::upsertMinuteBar)
                val loader = ShortMoveLoader(repository, ExchangeRateService(), store, CurveCandidateModel(emptyList()))

                val pending = loader.load(listOf("RISE.DE", "DROP.DE"), now)
                assertEquals(2, pending.size)
                assertTrue(pending.all { it.reviewDecision == CurveDecision.PENDING })
                val rise = pending.single { it.symbol == "RISE.DE" }.curveCandidate!!
                val drop = pending.single { it.symbol == "DROP.DE" }.curveCandidate!!
                assertTrue(store.review(rise, approved = true))
                assertTrue(store.review(drop, approved = false))

                val visible = loader.load(listOf("RISE.DE", "DROP.DE"), now)
                assertEquals(listOf("RISE.DE"), visible.map(ShortMove::symbol))
                assertEquals(CurveDecision.APPROVED, visible.single().reviewDecision)
            }
        }
    }

    private fun prices(symbol: String, vararg closes: Double) = closes.mapIndexed { index, close ->
        MinuteBar(symbol, now - (closes.lastIndex - index) * 60L, close, close, close, close, 1_000.0)
    }
}
