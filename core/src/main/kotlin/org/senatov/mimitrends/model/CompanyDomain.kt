package org.senatov.mimitrends.model

enum class CompanyDomainSource {
    SEED,
    PROVIDER,
    SEARCH,
    MANUAL
}

data class CompanyDomain(
    val symbol: String,
    val domain: String,
    val source: CompanyDomainSource,
    val confidence: Double,
    val verifiedAtMillis: Long? = null,
    val lastSuccessAtMillis: Long? = null,
    val failureCount: Int = 0,
    val updatedAtMillis: Long = System.currentTimeMillis()
)