package org.senatov.mimitrends.marketdata

import java.net.http.HttpHeaders
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class ProviderHttpException(
    val statusCode: Int,
    val retryAfterMillis: Long?,
    operation: String
) : RuntimeException("$operation returned HTTP $statusCode") {
    companion object {
        private const val serialVersionUID: Long = 1L

        fun from(statusCode: Int, headers: HttpHeaders, operation: String): ProviderHttpException =
            ProviderHttpException(statusCode, parseRetryAfter(headers.firstValue("Retry-After").orElse(null)), operation)

        internal fun parseRetryAfter(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
            val trimmed = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
            trimmed.toLongOrNull()?.let { return (it.coerceAtLeast(0) * 1_000L).coerceAtMost(MAX_RETRY_MILLIS) }
            return runCatching {
                (ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis)
                    .coerceIn(0L, MAX_RETRY_MILLIS)
            }.getOrNull()
        }

        private const val MAX_RETRY_MILLIS = 24 * 60 * 60_000L
    }
}
