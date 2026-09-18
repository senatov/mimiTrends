package org.senatov.mimitrends.services

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

import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.marketdata.CompanyLogoClient

internal fun persistentCompanyLogoClient(repository: MarketRepository) = CompanyLogoClient(
    repository::loadCompanyDomain,
    repository::upsertCompanyDomain
)
