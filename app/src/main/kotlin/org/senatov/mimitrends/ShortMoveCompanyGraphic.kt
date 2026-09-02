package org.senatov.mimitrends

import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.layout.HBox

internal object ShortMoveCompanyGraphic {
    fun create(move: ShortMove, name: String, watchlist: InstrumentWatchlistActions): HBox {
        val displayedName = if (move.pattern == ShortMovePattern.RECURRING_SHARP_JUMP) "⚠ $name" else name
        val content = HBox(5.0, Label(displayedName)).apply { alignment = Pos.CENTER_LEFT }
        val venue = MarketVenuePresentation.forInstrument(move.symbol, watchlist.liveSource(move.symbol))
        content.children += MarketVenueFlag.create(venue)
        if (watchlist.contains(move.symbol)) {
            content.children += Label("◆").apply { styleClass += "watchlist-marker" }
        }
        return content
    }
}
