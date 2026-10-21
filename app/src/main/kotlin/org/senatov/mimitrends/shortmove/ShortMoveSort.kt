package org.senatov.mimitrends.shortmove

import org.senatov.mimitrends.model.CurveDecision

import org.senatov.mimitrends.application.*
import org.senatov.mimitrends.ui.*
import org.senatov.mimitrends.scanner.*
import org.senatov.mimitrends.shortmove.*
import org.senatov.mimitrends.signals.*
import org.senatov.mimitrends.market.*
import org.senatov.mimitrends.providers.*
import org.senatov.mimitrends.company.*
import org.senatov.mimitrends.services.*
import org.senatov.mimitrends.shared.*

internal object ShortMoveSort {
    val relevance: Comparator<ShortMove> = compareByDescending<ShortMove> {
        it.sessionTurnover >= 50_000_000.0
    }.thenByDescending { kotlin.math.abs(it.changePercent) }
        .thenByDescending(ShortMove::sessionTurnover)
        .thenBy(::shortMoveAlertPriority)

    val direction = Comparator<ShortMove> { first, second ->
        directionPosition(first).compareTo(directionPosition(second))
    }
    fun apply(moves: MutableList<ShortMove>, comparator: Comparator<ShortMove>?) {
        if (comparator != null) moves.sortWith(comparator)
    }

    private fun directionPosition(move: ShortMove): Int =
        if (move.reviewDecision == CurveDecision.PENDING) 2 else when (move.pattern) {
        ShortMovePattern.RAPID_CRASH -> 0
        ShortMovePattern.RAPID_RISE -> 1
        ShortMovePattern.SUSTAINED_DROP, ShortMovePattern.SUSTAINED_RISE -> 2
            ShortMovePattern.TRADABLE_CORRIDOR -> 3
    }
}
