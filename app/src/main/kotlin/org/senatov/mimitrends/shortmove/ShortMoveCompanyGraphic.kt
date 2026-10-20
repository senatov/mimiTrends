package org.senatov.mimitrends.shortmove

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

import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.HBox
import java.io.ByteArrayInputStream

internal object ShortMoveCompanyGraphic {
    fun create(move: ShortMove, name: String, logoBytes: ByteArray?, watchlist: InstrumentWatchlistActions): HBox {
        val content = HBox(6.0).apply { alignment = Pos.CENTER_LEFT }
        logoBytes?.let { bytes ->
            val image = Image(ByteArrayInputStream(bytes))
            if (!image.isError) content.children += ImageView(image).apply {
                fitWidth = 18.0; fitHeight = 18.0; isPreserveRatio = true; isSmooth = true
                styleClass += "company-logo"
            }
        }
        if (move.pattern == ShortMovePattern.RAPID_CRASH) {
            content.children += Label("!").apply { styleClass += "short-move-warning-mark" }
        }
        content.children += Label(name).apply { styleClass += "short-move-company-name" }
        val venue = MarketVenuePresentation.forInstrument(move.symbol, watchlist.liveSource(move.symbol))
        content.children += MarketVenueFlag.create(venue)
        if (watchlist.contains(move.symbol)) {
            content.children += Label("◆").apply { styleClass += "watchlist-marker" }
        }
        return content
    }
}
