package org.senatov.mimitrends

import org.senatov.mimitrends.model.CompanyProfile

internal object CompanyProfileMerger {
    fun merge(stored: CompanyProfile?, loaded: CompanyProfile): CompanyProfile = loaded.copy(
        name = stored?.name?.takeUnless { it == loaded.symbol } ?: loaded.name,
        exchange = stored?.exchange?.takeUnless { it == "Yahoo Finance" } ?: loaded.exchange,
        logoUrl = stored?.logoUrl ?: loaded.logoUrl,
        logoBytes = stored?.logoBytes ?: loaded.logoBytes
    )
}
