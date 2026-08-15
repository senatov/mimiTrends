package org.senatov.mimitrends

internal object ShortMoveSort {
    val direction = Comparator<ShortMove> { first, second ->
        directionPosition(first).compareTo(directionPosition(second))
    }
    val priceRange = Comparator<ShortMove> { first, second ->
        compareValuesBy(first, second, ShortMove::open, ShortMove::close)
    }
    val period = Comparator<ShortMove> { first, second ->
        compareValuesBy(first, second,
            { periodMidpoint(it) },
            ShortMove::startedAtEpochSeconds,
            ShortMove::endedAtEpochSeconds)
    }

    fun apply(moves: MutableList<ShortMove>, comparator: Comparator<ShortMove>?) {
        if (comparator != null) moves.sortWith(comparator)
    }

    private fun directionPosition(move: ShortMove): Int = when (move.pattern) {
        ShortMovePattern.RECURRING_SHARP_JUMP -> -4
        ShortMovePattern.CONFIRMED_EXTENDED_DROP -> -3
        ShortMovePattern.POST_DROP_STRUGGLE -> -2
        ShortMovePattern.DIRECTIONAL -> if (move.changePercent < 0.0) -1 else 1
        ShortMovePattern.RECOVERY_AFTER_EXTENDED_DROP -> 2
    }

    private fun periodMidpoint(move: ShortMove): Long = move.startedAtEpochSeconds +
        (move.endedAtEpochSeconds - move.startedAtEpochSeconds) / 2L
}
