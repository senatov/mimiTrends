package org.senatov.mimitrends.market

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

internal object OpenMarketDataFreshness {
    fun isUsable(observedEpochSeconds: Long?, nowEpochSeconds: Long): Boolean {
        val observed = observedEpochSeconds ?: return false
        val age = nowEpochSeconds - observed
        return age >= -FUTURE_TOLERANCE_SECONDS && age <= MAX_AGE_SECONDS
    }

    const val MAX_AGE_SECONDS = 3 * 60L
    private const val FUTURE_TOLERANCE_SECONDS = 60L
}
