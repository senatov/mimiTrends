package org.senatov.mimitrends

import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.marketdata.CompanyLogoClient

internal fun persistentCompanyLogoClient(repository: MarketRepository) = CompanyLogoClient(
    repository::loadCompanyDomain,
    repository::upsertCompanyDomain
)
