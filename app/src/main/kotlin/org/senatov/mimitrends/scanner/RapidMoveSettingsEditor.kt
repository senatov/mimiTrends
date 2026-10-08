package org.senatov.mimitrends.scanner

import javafx.geometry.Insets
import javafx.scene.control.Label
import javafx.scene.control.Spinner
import javafx.scene.layout.VBox
import org.senatov.mimitrends.model.RapidMoveSettings

internal class RapidMoveSettingsEditor(current: RapidMoveSettings) {
    private val crashPercent = Spinner<Double>(0.05, 20.0, current.crashPercent, 0.05).apply { isEditable = true }
    private val crashWindow = Spinner<Int>(4, 15, current.crashWindowMinutes, 1).apply { isEditable = true }
    private val sustainedCrashPercent = Spinner<Double>(0.05, 20.0, current.sustainedCrashPercent, 0.05).apply { isEditable = true }
    private val sustainedCrashWindow = Spinner<Int>(5, 30, current.sustainedCrashWindowMinutes, 1).apply { isEditable = true }
    private val risePercent = Spinner<Double>(0.05, 20.0, current.risePercent, 0.05).apply { isEditable = true }
    private val riseWindow = Spinner<Int>(1, 15, current.riseWindowMinutes, 1).apply { isEditable = true }
    private val movePrice = Spinner<Double>(0.0, 10_000.0, current.minimumPrice, 0.5).apply { isEditable = true }
    private val moveTurnover = Spinner<Double>(0.0, 10_000_000.0, current.minimumTurnover, 25_000.0).apply { isEditable = true }

    val content = VBox(
        14.0,
        SettingsNavigation.section(
            "Rapid crashes",
            SettingsNavigation.row("Short drop (%)", "Minimum close-to-close decline in the short window.", crashPercent),
            SettingsNavigation.row("Short window (min)", "Maximum duration of the short decline.", crashWindow),
            SettingsNavigation.row("Sustained drop (%)", "Minimum decline for an accelerating longer crash.", sustainedCrashPercent),
            SettingsNavigation.row("Long window (min)", "Maximum duration of an accelerating crash.", sustainedCrashWindow)
        ),
        SettingsNavigation.section(
            "Rapid rises",
            SettingsNavigation.row(
                "Rise (%)",
                "Minimum gain from a recent close to a minute-bar high. A rebound below the preceding high must also close above this threshold.",
                risePercent
            ),
            SettingsNavigation.row("Rise window (min)", "Maximum duration of the upward move.", riseWindow)
        ),
        SettingsNavigation.section(
            "Shared quality checks",
            SettingsNavigation.row("Minimum price", "Reject low-priced instruments.", movePrice),
            SettingsNavigation.row("30-minute turnover", "Minimum reported price × volume across the last 30 minutes.", moveTurnover)
        ),
        Label("Crashes require three continuous bars, two reported-volume bars, and acceleration for longer drops. Rises require two reported-volume bars; rebounds below an earlier high must close strongly. Both require three active bars in 30 minutes.").apply {
            isWrapText = true; styleClass += "settings-footnote"
        }
    ).apply { padding = Insets(18.0) }

    fun restoreDefaults() {
        val defaults = RapidMoveSettings()
        crashPercent.valueFactory.value = defaults.crashPercent
        crashWindow.valueFactory.value = defaults.crashWindowMinutes
        sustainedCrashPercent.valueFactory.value = defaults.sustainedCrashPercent
        sustainedCrashWindow.valueFactory.value = defaults.sustainedCrashWindowMinutes
        risePercent.valueFactory.value = defaults.risePercent
        riseWindow.valueFactory.value = defaults.riseWindowMinutes
        movePrice.valueFactory.value = defaults.minimumPrice
        moveTurnover.valueFactory.value = defaults.minimumTurnover
    }

    fun value(): RapidMoveSettings = RapidMoveSettings(
        crashPercent.value, crashWindow.value, sustainedCrashPercent.value, sustainedCrashWindow.value,
        risePercent.value, riseWindow.value, movePrice.value, moveTurnover.value
    )
}
