package org.senatov.mimitrends

import javafx.application.HostServices
import javafx.beans.binding.Bindings
import javafx.geometry.Insets
import javafx.scene.control.Alert
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane
import javafx.scene.layout.VBox
import javafx.stage.Window
import org.senatov.mimitrends.log.LogTag
import org.slf4j.LoggerFactory

class FinnhubSetupDialog(
    owner: Window,
    private val hostServices: HostServices
) {
    private val log = LoggerFactory.getLogger(FinnhubSetupDialog::class.java)
    private val dialog = Dialog<ButtonType>()
    private val apiKeyField = PasswordField()
    private val webhookField = PasswordField()
    private val saveButtonType = ButtonType("Save and continue", ButtonBar.ButtonData.OK_DONE)

    init {
        log.debug(LogTag.UI, "init(ownerHasScene={})", owner.scene != null)
        // JavaFX 26 cannot bind a heavyweight Dialog to a Stage before that Stage
        // has a Scene. The first-run dialog is intentionally ownerless at that point.
        if (owner.scene != null) dialog.initOwner(owner)
        dialog.title = "Connect MiMiTrends to Finnhub"
        dialog.headerText = "Finnhub credentials were not found"
        dialog.dialogPane.buttonTypes += listOf(saveButtonType, ButtonType.CANCEL)
        dialog.dialogPane.content = createContent()
        dialog.dialogPane.lookupButton(saveButtonType).disableProperty().bind(
            Bindings.createBooleanBinding(
                { apiKeyField.text.isNullOrBlank() },
                apiKeyField.textProperty()
            )
        )
        dialog.setOnShown { apiKeyField.requestFocus() }
    }

    fun showAndSave(): String? {
        log.debug(LogTag.UI, "showAndSave()")
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != saveButtonType) return null
        return try {
            val apiKey = apiKeyField.text.trim()
            ApiKeyResolver.saveLocal(apiKey, webhookField.text)
            log.info(LogTag.STATE, "first-run credentials accepted")
            apiKey
        } catch (error: Exception) {
            log.error(LogTag.STATE, "credential save failed", error)
            Alert(Alert.AlertType.ERROR).apply {
                initOwner(dialog.owner)
                title = "Could not save credentials"
                headerText = "MiMiTrends could not write its settings file"
                contentText = error.message ?: error.javaClass.simpleName
            }.showAndWait()
            null
        }
    }

    private fun createContent() = VBox(
        12.0,
        Label(
            "1. Create or open a Finnhub account.\n" +
                "2. Copy the API key from the dashboard.\n" +
                "3. Paste it below. The webhook secret is optional for this demo."
        ).apply {
            isWrapText = true
            maxWidth = 440.0
        },
        Hyperlink("Open finnhub.io/register").apply {
            setOnAction { hostServices.showDocument(REGISTER_URL) }
        },
        GridPane().apply {
            hgap = 12.0
            vgap = 10.0
            add(Label("API key"), 0, 0)
            add(apiKeyField.apply { promptText = "Required" }, 1, 0)
            add(Label("Webhook secret"), 0, 1)
            add(webhookField.apply { promptText = "Optional" }, 1, 1)
        },
        Label("Credentials will be saved locally in:\n${ApiKeyResolver.configFile}").apply {
            styleClass += "setup-path"
            isWrapText = true
        }
    ).apply {
        log.debug(LogTag.UI, "createContent()")
        padding = Insets(4.0)
        prefWidth = 480.0
    }

    companion object {
        private const val REGISTER_URL = "https://finnhub.io/register"
    }
}
