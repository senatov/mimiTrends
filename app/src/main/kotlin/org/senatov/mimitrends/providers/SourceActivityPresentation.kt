package org.senatov.mimitrends.providers

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal enum class SourceHealth(val label: String, val styleClass: String) {
    LIVE("ONLINE", "source-card-live"),
    QUIET("QUIET", "source-card-quiet"),
    WAITING("WAITING", "source-card-waiting"),
    DISABLED("DISABLED", "source-card-disabled"),
    ATTENTION("ATTENTION", "source-card-attention")
}

internal object SourceActivityPresentation {
    private const val QUIET_AFTER_MILLIS = 15 * 60_000L
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    fun health(snapshot: SourceActivitySnapshot, nowMillis: Long): SourceHealth = when {
        snapshot.failed || snapshot.status == "Login needed" -> SourceHealth.ATTENTION
        snapshot.status == "Disabled" -> SourceHealth.DISABLED
        snapshot.lastContactMillis == null -> SourceHealth.WAITING
        nowMillis - snapshot.lastContactMillis > QUIET_AFTER_MILLIS -> SourceHealth.QUIET
        else -> SourceHealth.LIVE
    }

    fun age(lastMillis: Long?, nowMillis: Long): String {
        if (lastMillis == null) return "No contact"
        val seconds = ((nowMillis - lastMillis).coerceAtLeast(0L) / 1_000L)
        return when {
            seconds < 60 -> "<1 min ago"
            seconds < 3_600 -> "${seconds / 60} min ago"
            seconds < 86_400 -> "${seconds / 3_600} h ago"
            else -> "${seconds / 86_400} d ago"
        }
    }

    fun exactTime(epochMillis: Long?): String = epochMillis?.let { time.format(Instant.ofEpochMilli(it)) } ?: "—"

    fun unit(source: String): String = when (source) {
        "Yahoo" -> "minute bars"
        "Finnhub" -> "trades"
        "wallstreetONLINE", "TraderFox" -> "discovery entries"
        else -> "quotes"
    }
}
