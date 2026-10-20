package org.senatov.mimitrends.scanner

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.Spinner
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import org.senatov.mimitrends.marketdata.ProviderUrl
import org.senatov.mimitrends.marketdata.ProviderUrlChecker
import org.senatov.mimitrends.marketdata.ProviderUrls
import java.util.concurrent.CompletableFuture

internal class ProviderSettingsPane(
    langSchwarzEnabled: CheckBox,
    tradegateEnabled: CheckBox,
    tradegateInterval: Spinner<Int>,
    euronextEnabled: CheckBox,
    euronextInterval: Spinner<Int>,
    private val stockSearchUrl: TextField,
    private val validateStockSearchUrl: (String) -> Unit,
    private val finnhubKey: () -> String?
) {
    private val fields = ProviderUrl.entries.associateWith { TextField(ProviderUrls.get(it)) }
    private val checker = ProviderUrlChecker()

    val content: VBox = VBox(
        16.0,
        Label("Edit a provider address, then use Check to test the value before saving. Changes take effect on the next request.")
            .apply { isWrapText = true; styleClass += "settings-footnote" },
        SettingsNavigation.section("Finnhub",
            urlRow(ProviderUrl.FINNHUB_REST), urlRow(ProviderUrl.FINNHUB_STREAM)),
        SettingsNavigation.section("Lang & Schwarz",
            SettingsNavigation.row("Personal-use fallback", "Used only when Scalable is unavailable.", langSchwarzEnabled),
            urlRow(ProviderUrl.LANG_SCHWARZ_GERMANY),
            urlRow(ProviderUrl.LANG_SCHWARZ_STOXX),
            urlRow(ProviderUrl.LANG_SCHWARZ_EUROPE)),
        SettingsNavigation.section("Tradegate",
            SettingsNavigation.row("Public quote collector", "Search instruments and collect EUR quotes.", tradegateEnabled),
            SettingsNavigation.row("Request interval", "Milliseconds between requests.", tradegateInterval),
            urlRow(ProviderUrl.TRADEGATE)),
        SettingsNavigation.section("Euronext",
            SettingsNavigation.row("Public quote collector", "Search instruments and collect delayed quotes.", euronextEnabled),
            SettingsNavigation.row("Request interval", "Milliseconds between requests.", euronextInterval),
            urlRow(ProviderUrl.EURONEXT)),
        SettingsNavigation.section("Yahoo Finance",
            urlRow(ProviderUrl.YAHOO_PRIMARY), urlRow(ProviderUrl.YAHOO_ALTERNATE)),
        SettingsNavigation.section("Discovery and external charts",
            urlRow(ProviderUrl.WALLSTREET_ONLINE), urlRow(ProviderUrl.TRADERFOX),
            stockSearchRow()),
        Label("Checks make a bounded live request. A successful check does not guarantee future availability or market hours.")
            .apply { isWrapText = true; styleClass += "settings-footnote" }
    ).apply { padding = Insets(18.0) }

    fun currentUrls(): Map<ProviderUrl, String> = fields.mapValues { it.value.text }

    fun restoreDefaults() {
        fields.forEach { (key, field) -> field.text = key.default }
    }

    private fun urlRow(key: ProviderUrl): VBox =
        urlRow(key.label + " URL", fields.getValue(key)) { value -> checker.check(key, value, finnhubKey()) }

    private fun stockSearchRow(): VBox =
        urlRow("Stock search URL", stockSearchUrl) { value ->
            validateStockSearchUrl(value)
            "Stock search OK"
        }

    private fun urlRow(title: String, field: TextField, check: (String) -> String): VBox {
        val status = Label("Not checked").apply { styleClass += "settings-url-status" }
        val button = Button("Check").apply { styleClass += "settings-check-button" }
        field.maxWidth = Double.MAX_VALUE
        HBox.setHgrow(field, Priority.ALWAYS)
        field.textProperty().addListener { _, _, _ ->
            status.text = "Not checked"
            status.styleClass.removeAll("settings-url-success", "settings-url-error")
        }
        button.setOnAction {
            val candidate = field.text.trim()
            button.isDisable = true
            status.text = "Checking…"
            CompletableFuture.supplyAsync { check(candidate) }.whenComplete { result, error ->
                Platform.runLater {
                    button.isDisable = false
                    if (field.text.trim() != candidate) return@runLater
                    status.styleClass.removeAll("settings-url-success", "settings-url-error")
                    if (error == null) {
                        status.text = result
                        status.styleClass += "settings-url-success"
                    } else {
                        status.text = error.cause?.message ?: error.message ?: "Check failed"
                        status.styleClass += "settings-url-error"
                    }
                }
            }
        }
        return VBox(5.0,
            Label(title).apply { styleClass += "settings-row-title" },
            HBox(8.0, field, button), status
        ).apply { styleClass += "settings-url-row" }
    }
}
