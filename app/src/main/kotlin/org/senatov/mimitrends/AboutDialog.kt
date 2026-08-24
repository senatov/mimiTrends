package org.senatov.mimitrends

import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.TextArea
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.stage.Window

internal object AboutDialog {
    fun show(owner: Window?, onPredictionDiagnostics: () -> Unit) {
        val diagnostics = ButtonType("Prediction diagnostics")
        val dialog = Dialog<ButtonType>().apply {
            owner?.let(::initOwner)
            WorkspaceDialogAppearance.apply(this, owner)
            title = "About MiMiTrends"
            dialogPane.buttonTypes.setAll(diagnostics, ButtonType.OK)
            dialogPane.headerText = "MiMiTrends ${BuildInfo.displayVersion}"
            javaClass.getResourceAsStream("/icons/icon_128x128.png")?.use { stream ->
                dialogPane.graphic = ImageView(Image(stream)).apply {
                    fitWidth = 72.0; fitHeight = 72.0; isPreserveRatio = true
                }
            }
            dialogPane.content = TabPane(
                tab("Overview", """
                    Local-first fresh market impulse scanner for macOS, Linux, and Windows.

                    Market history    Yahoo Finance and SQLite
                    Live market data  Finnhub WebSocket (optional, user-owned API key)
                    Currency rates    European Central Bank
                    Local database    ~/.mimi/trends/mimitrends.db
                    Settings          ~/.mimi/trends/
                    Log file          /tmp/MiMiTrends.log

                    Read-only demonstration application. It does not place orders.
                    © 2026 MiMiTrends
                """.trimIndent()),
                tab("Libraries", """
                    Kotlin Standard Library ${KotlinVersion.CURRENT} — Apache License 2.0
                    JavaFX ${System.getProperty("javafx.runtime.version", "26")} — GPLv2 with Classpath Exception
                    AtlantaFX 2.1.0 — MIT License
                    JFreeChart 1.5.6 — LGPL 2.1 or later
                    JFreeChart-FX 2.0.2 — LGPL 2.1 or later
                    SQLite JDBC 3.50.3.0 — Apache License 2.0
                    Smile Core 6.2.5 — Apache License 2.0
                    Jackson Databind 2.22.1 — Apache License 2.0
                    SLF4J API 2.0.17 — MIT License
                    Apache Log4j 2.26.1 — Apache License 2.0

                    Data and branding services are not bundled libraries. Their availability and
                    terms are governed by the respective providers.
                """.trimIndent()),
                tab("System", """
                    Application       ${BuildInfo.displayVersion}
                    Build type        ${BuildInfo.buildType}
                    Built             ${BuildInfo.buildTime}
                    Build host        ${BuildInfo.buildHost}
                    Java runtime      ${System.getProperty("java.runtime.version")}
                    Java VM           ${System.getProperty("java.vm.name")}
                    JavaFX runtime    ${System.getProperty("javafx.runtime.version", "26")}
                    Operating system  ${System.getProperty("os.name")} ${System.getProperty("os.version")}
                    Architecture      ${System.getProperty("os.arch")}
                    Locale            ${java.util.Locale.getDefault().toLanguageTag()}
                """.trimIndent())
            ).apply { tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE }
            dialogPane.prefWidth = 680.0
            dialogPane.prefHeight = 520.0
            isResizable = true
        }
        if (dialog.showAndWait().orElse(null) == diagnostics) onPredictionDiagnostics()
    }

    private fun tab(title: String, text: String) = Tab(title, TextArea(text).apply {
        isEditable = false
        isWrapText = true
        style = "-fx-font-family: 'SF Pro Display'; -fx-font-size: 13px;"
    })
}
