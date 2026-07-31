package org.senatov.mimitrends

import atlantafx.base.theme.CupertinoLight
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.scene.text.Font
import javafx.stage.Stage
import org.senatov.mimitrends.log.LogTag
import org.slf4j.LoggerFactory

class App : Application() {
    private val log = LoggerFactory.getLogger(App::class.java)

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            LoggerFactory.getLogger(App::class.java).debug(LogTag.APP, "main(args={})", args.contentToString())
            launch(App::class.java, *args)
        }
    }

    override fun start(stage: Stage) {
        log.debug(LogTag.APP, "start(stage={})", stage)
        Application.setUserAgentStylesheet(CupertinoLight().userAgentStylesheet)
        loadFont("/fonts/SF-Pro-Display-Light.otf")
        loadFont("/fonts/SF-Pro-Display-Medium.otf")

        val apiKey = ApiKeyResolver.resolve()
            ?: FinnhubSetupDialog(stage, hostServices).showAndSave()
        val uiStateService = UiStateService()
        val uiState = uiStateService.load()
        val controller = MainController(apiKey, uiState.symbol, uiState.range)
        val scene = Scene(controller.createView(), 1120.0, 720.0)
        scene.stylesheets += requireNotNull(javaClass.getResource("/org/senatov/mimitrends/MiMiTrends.css")).toExternalForm()

        stage.title = "MiMiTrends"
        listOf("/icons/icon_512x512.png", "/icons/icon_128x128.png").forEach { path ->
            javaClass.getResourceAsStream(path)?.use { stage.icons += Image(it) }
        }
        stage.minWidth = 860.0
        stage.minHeight = 560.0
        stage.scene = scene
        uiStateService.restore(stage, uiState)
        stage.setOnCloseRequest {
            uiStateService.save(stage, controller.selectedSymbol(), controller.selectedRange())
            controller.close()
        }
        stage.show()
        log.info(LogTag.APP, "stage shown width={} height={}", stage.width, stage.height)
    }

    private fun loadFont(path: String) {
        log.debug(LogTag.IO, "loadFont(path={})", path)
        javaClass.getResourceAsStream(path)?.use { Font.loadFont(it, 14.0) }
            ?: log.warn(LogTag.IO, "font resource missing path={}", path)
    }

}
