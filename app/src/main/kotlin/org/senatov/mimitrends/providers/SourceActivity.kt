package org.senatov.mimitrends.providers

import java.util.concurrent.ConcurrentHashMap

internal data class SourceActivitySnapshot(
    val source: String,
    val lastContactMillis: Long? = null,
    val processed: Int? = null,
    val accepted: Int? = null,
    val failed: Boolean = false,
    val status: String? = null
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
        latest[source] = SourceActivitySnapshot(
            source, System.currentTimeMillis(), processed, accepted, failed, status
        )
    }

    fun markStatus(source: String, status: String, failed: Boolean = false) {
        latest.compute(source) { _, previous ->
            SourceActivitySnapshot(source, previous?.lastContactMillis, failed = failed, status = status)
        }
    }

    fun clearStatus(source: String) {
        latest.computeIfPresent(source) { _, previous -> previous.copy(status = null, failed = false) }
    }

    fun snapshot(): List<SourceActivitySnapshot> = names.map { latest[it] ?: SourceActivitySnapshot(it) }
}
