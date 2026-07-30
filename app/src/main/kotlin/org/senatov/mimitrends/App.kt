package org.senatov.mimitrends

import atlantafx.base.theme.CupertinoLight
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.scene.text.Font
import javafx.stage.Stage

class App : Application() {
    override fun start(stage: Stage) {
        Application.setUserAgentStylesheet(CupertinoLight().userAgentStylesheet)
        loadFont("/fonts/SF-Pro-Display-Light.otf")
        loadFont("/fonts/SF-Pro-Display-Medium.otf")

        val apiKey = ApiKeyResolver.resolve()
            ?: FinnhubSetupDialog(stage, hostServices).showAndSave()
        val controller = MainController(apiKey)
        val scene = Scene(controller.createView(), 1120.0, 720.0)
        scene.stylesheets += requireNotNull(javaClass.getResource("/org/senatov/mimitrends/MiMiTrends.css")).toExternalForm()

        stage.title = "MiMiTrends"
        listOf("/icons/icon_512x512.png", "/icons/icon_128x128.png").forEach { path ->
            javaClass.getResourceAsStream(path)?.use { stage.icons += Image(it) }
        }
        stage.minWidth = 860.0
        stage.minHeight = 560.0
        stage.scene = scene
        stage.show()
    }

    private fun loadFont(path: String) {
        javaClass.getResourceAsStream(path)?.use { Font.loadFont(it, 14.0) }
    }

}
