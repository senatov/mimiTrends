package org.senatov.mimitrends

import java.util.Locale

internal object ShortMovePricePresentation {
    fun text(move: ShortMove): String = "${format(move.open)} →\n${format(move.close)}"

    private fun format(value: Double): String = String.format(Locale.ROOT, "%,.2f", value)
}
