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

import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.layout.HBox

internal object ShortMoveCompanyGraphic {
    fun create(move: ShortMove, name: String, watchlist: InstrumentWatchlistActions): HBox {
        val displayedName = if (move.pattern == ShortMovePattern.RAPID_CRASH) "⚠ $name" else name
        val content = HBox(5.0, Label(displayedName)).apply { alignment = Pos.CENTER_LEFT }
        val venue = MarketVenuePresentation.forInstrument(move.symbol, watchlist.liveSource(move.symbol))
        content.children += MarketVenueFlag.create(venue)
        if (watchlist.contains(move.symbol)) {
            content.children += Label("◆").apply { styleClass += "watchlist-marker" }
        }
        return content
    }
}
