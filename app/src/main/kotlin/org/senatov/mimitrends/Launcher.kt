package org.senatov.mimitrends

import javafx.application.Application

/**
 * Non-JavaFX launcher class.
 *
 * Keeping the main function outside [App] prevents the JDK launcher from
 * trying to resolve JavaFX modules before Gradle's runtime classpath is active.
 */
object Launcher {
    @JvmStatic
    fun main(args: Array<String>) {
        Application.launch(App::class.java, *args)
    }
}
