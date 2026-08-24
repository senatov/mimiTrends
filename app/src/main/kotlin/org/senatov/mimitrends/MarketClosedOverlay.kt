package org.senatov.mimitrends

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox

internal class MarketClosedOverlay(private val returnFocus: () -> Unit) : StackPane() {
    private val closeButton = Button("Close").apply { styleClass += "market-closed-close" }
    private val subtitle = Label("Saved closing snapshot · not live").apply {
        styleClass += "market-closed-subtitle"
    }
    private val title = Label("ALL SELECTED MARKETS ARE CLOSED").apply { styleClass += "market-closed-title" }
    private val marketHoursTitle = Label().apply { styleClass += "market-hours-title" }
    private val marketHours = Label().apply { styleClass += "market-hours-list" }
    private val brokerHoursTitle = Label("SCALABLE VENUES").apply { styleClass += "market-hours-title" }
    private val brokerHours = Label().apply { styleClass += "market-hours-list" }
    private val hoursPanel = VBox(4.0, marketHoursTitle, marketHours,
        Region().apply { minHeight = 5.0 }, brokerHoursTitle, brokerHours).apply {
        alignment = Pos.TOP_LEFT
        minWidth = 270.0; prefWidth = 270.0; maxWidth = 270.0
        maxHeight = Region.USE_PREF_SIZE
        isMouseTransparent = true
        styleClass += "market-hours-panel"
    }
    private val footer = StackPane(closeButton).apply {
        alignment = Pos.BOTTOM_CENTER
        maxWidth = Double.MAX_VALUE
        maxHeight = Region.USE_PREF_SIZE
        styleClass += "market-closed-footer"
    }
    private var closing = false

    init {
        children += listOf(
            ImageView(Image(requireNotNull(javaClass.getResourceAsStream("/images/sleeping-dog-market-closed.png")))).apply {
                fitWidth = 590.0; fitHeight = 500.0; isPreserveRatio = true
                styleClass += "market-closed-dog"
            },
            VBox(7.0, title, subtitle).apply {
                alignment = Pos.TOP_CENTER
                styleClass += "market-closed-content"
            },
            hoursPanel,
            footer
        )
        alignment = Pos.CENTER
        maxWidth = 680.0; maxHeight = 570.0
        prefWidth = 680.0; prefHeight = 570.0
        isVisible = false; isManaged = false
        styleClass += "market-closed-overlay"
        StackPane.setAlignment(hoursPanel, Pos.TOP_LEFT)
        StackPane.setMargin(hoursPanel, Insets(108.0, 0.0, 0.0, 28.0))
        StackPane.setAlignment(footer, Pos.BOTTOM_CENTER)
        footer.prefHeightProperty().bind(heightProperty().multiply(0.15))
        closeButton.setOnAction { hide() }
        addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            if (event.code == KeyCode.ESCAPE) {
                hide()
                event.consume()
            }
        }
    }

    fun showSnapshot(nextOpening: String, localZone: String, market: List<String>, broker: List<String>) {
        if (closing) return
        title.text = "ALL SELECTED MARKETS ARE CLOSED"
        subtitle.text = "Saved closing snapshot · scanner resumes $nextOpening"
        marketHoursTitle.text = "PRICE DATA MARKETS · $localZone"
        marketHours.text = market.joinToString("\n")
        brokerHoursTitle.text = "SCALABLE VENUES · $localZone"
        brokerHours.text = broker.joinToString("\n")
        hoursPanel.isVisible = market.isNotEmpty() || broker.isNotEmpty()
        hoursPanel.isManaged = hoursPanel.isVisible
        showOverlay()
        Platform.runLater(closeButton::requestFocus)
    }

    fun showClosing() {
        closing = true
        title.text = "APPLICATION IS CLOSING"
        subtitle.text = "Finishing current transactions and saving market data…"
        hoursPanel.isVisible = false; hoursPanel.isManaged = false
        footer.isVisible = false; footer.isManaged = false
        showOverlay()
    }

    fun hide() {
        if (closing) return
        isVisible = false
        isManaged = false
        returnFocus()
    }

    private fun showOverlay() {
        isVisible = true
        isManaged = true
        toFront()
    }
}
