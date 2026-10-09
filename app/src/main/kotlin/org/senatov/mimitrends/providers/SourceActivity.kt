package org.senatov.mimitrends.providers

import java.util.concurrent.ConcurrentHashMap

internal data class SourceActivitySnapshot(
    val source: String,
    val lastContactMillis: Long? = null,
    val processed: Int? = null,
    val accepted: Int? = null,
    val failed: Boolean = false,
    val status: String? = null,
    val operations: Long = 0,
    val failures: Long = 0,
    val lastSuccessMillis: Long? = null
)

/** Keeps the outcome of the latest actual source operation, never a cumulative session total. */
internal class SourceActivity {
    private val names = listOf(
        "Yahoo", "Finnhub", "Tradegate", "Euronext", "Scalable",
        "Lang & Schwarz", "wallstreetONLINE", "TraderFox"
    )
    private val latest = ConcurrentHashMap<String, SourceActivitySnapshot>()

    fun record(source: String, processed: Int, accepted: Int, failed: Boolean = false, status: String? = null) {
        require(processed >= 0 && accepted in 0..processed)
        val now = System.currentTimeMillis()
        latest.compute(source) { _, previous ->
            SourceActivitySnapshot(
                source, now, processed, accepted, failed, status,
                operations = (previous?.operations ?: 0) + 1,
                failures = (previous?.failures ?: 0) + if (failed) 1 else 0,
                lastSuccessMillis = if (failed) previous?.lastSuccessMillis else now
            )
        }
    }

    fun markStatus(source: String, status: String, failed: Boolean = false) {
        latest.compute(source) { _, previous ->
            (previous ?: SourceActivitySnapshot(source)).copy(failed = failed, status = status)
        }
    }

    fun clearStatus(source: String) {
        latest.computeIfPresent(source) { _, previous -> previous.copy(status = null, failed = false) }
    }

    fun snapshot(): List<SourceActivitySnapshot> = names.map { latest[it] ?: SourceActivitySnapshot(it) }
}
