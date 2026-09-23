package org.senatov.mimitrends.shortmove

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

internal object ShortMoveSort {
    val direction = Comparator<ShortMove> { first, second ->
        directionPosition(first).compareTo(directionPosition(second))
    }
    val priceRange = Comparator<ShortMove> { first, second ->
        compareValuesBy(first, second, ShortMove::open, ShortMove::close)
    }
    val period = Comparator<ShortMove> { first, second ->
        compareValuesBy(
            first, second,
            { periodMidpoint(it) },
            ShortMove::startedAtEpochSeconds,
            ShortMove::endedAtEpochSeconds
        )
    }

    fun apply(moves: MutableList<ShortMove>, comparator: Comparator<ShortMove>?) {
        if (comparator != null) moves.sortWith(comparator)
    }

    private fun directionPosition(move: ShortMove): Int = when (move.pattern) {
        ShortMovePattern.RAPID_CRASH -> 0
        ShortMovePattern.TRADABLE_CORRIDOR -> 1
    }

    private fun periodMidpoint(move: ShortMove): Long = move.startedAtEpochSeconds +
            (move.endedAtEpochSeconds - move.startedAtEpochSeconds) / 2L
}
