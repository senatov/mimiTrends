package org.senatov.mimitrends

internal object ShortMoveSort {
    val direction = Comparator<ShortMove> { first, second ->
        directionPosition(first).compareTo(directionPosition(second))
    }
    val priceChange = Comparator<ShortMove> { first, second ->
        first.changePercent.compareTo(second.changePercent)
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

    private fun directionPosition(move: ShortMove): Int = when {
        move.pattern != ShortMovePattern.DIRECTIONAL -> -2
        move.changePercent < 0.0 -> -1
        else -> 1
    }

    private fun periodMidpoint(move: ShortMove): Long = move.startedAtEpochSeconds +
        (move.endedAtEpochSeconds - move.startedAtEpochSeconds) / 2L
}
