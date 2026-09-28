package org.senatov.mimitrends.marketdata

/** A successful HTTP response whose body is not the provider format required by the caller. */
class ProviderResponseException(operation: String) : RuntimeException("$operation returned an invalid response") {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
