package org.senatov.mimitrends.shortmove

import java.util.Locale

internal object ShortMovePresentation {
    fun movement(move: ShortMove): String = when (move.pattern) {
        ShortMovePattern.RAPID_CRASH -> "%+.2f%% / %dm".format(Locale.ROOT, move.changePercent, durationMinutes(move))
        ShortMovePattern.TRADABLE_CORRIDOR -> {
            val width = corridorWidthPercent(move)
            "%.2f%% wide · %+.2f%% room".format(Locale.ROOT, width, move.changePercent)
        }
    }

    fun currentPrice(move: ShortMove): Double = when (move.pattern) {
        ShortMovePattern.RAPID_CRASH -> move.close
        ShortMovePattern.TRADABLE_CORRIDOR -> move.open
    }

    fun age(move: ShortMove, nowEpochSeconds: Long): String {
        val seconds = (nowEpochSeconds - move.eventEpochSeconds).coerceAtLeast(0L)
        return when {
            seconds < 60L -> "${seconds}s"
            seconds < 3_600L -> "${seconds / 60L}m"
            else -> "${seconds / 3_600L}h"
        }
    }

    fun details(move: ShortMove): String = when (move.pattern) {
        ShortMovePattern.RAPID_CRASH ->
            "Confirmed close-to-close decline of ${"%+.2f".format(Locale.ROOT, move.changePercent)}% " +
                    "in ${durationMinutes(move)} minutes."

        ShortMovePattern.TRADABLE_CORRIDOR -> move.opportunityDetails.substringBeforeLast('\n').ifBlank {
            "Stable two-hour corridor with ${"%+.2f".format(Locale.ROOT, move.changePercent)}% room to the upper edge."
        }
    }

    private fun durationMinutes(move: ShortMove): Long =
        ((move.endedAtEpochSeconds - move.startedAtEpochSeconds) / 60L).coerceAtLeast(1L)

    private fun corridorWidthPercent(move: ShortMove): Double {
        val lower = move.corridorLower ?: return 0.0
        val upper = move.corridorUpper ?: return 0.0
        return if (lower > 0.0 && upper > lower) (upper / lower - 1.0) * 100.0 else 0.0
    }
}
