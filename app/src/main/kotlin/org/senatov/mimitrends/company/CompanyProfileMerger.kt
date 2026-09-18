package org.senatov.mimitrends.company

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

import org.senatov.mimitrends.model.CompanyProfile

internal object CompanyProfileMerger {
    fun merge(stored: CompanyProfile?, loaded: CompanyProfile): CompanyProfile = loaded.copy(
        name = stored?.name?.takeUnless { it == loaded.symbol } ?: loaded.name,
        exchange = stored?.exchange?.takeUnless { it == "Yahoo Finance" } ?: loaded.exchange,
        logoUrl = stored?.logoUrl ?: loaded.logoUrl,
        logoBytes = stored?.logoBytes ?: loaded.logoBytes
    )
}
