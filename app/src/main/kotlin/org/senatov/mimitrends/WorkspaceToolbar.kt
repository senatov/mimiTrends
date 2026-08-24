package org.senatov.mimitrends

import javafx.scene.Parent
import javafx.scene.control.Button

internal object WorkspaceToolbar {
    fun configure(
        root: Parent,
        refresh: Button,
        settings: Button,
        importTrades: Button,
        about: Button,
        onRefresh: () -> Unit,
        onSettings: () -> Unit,
        onImport: () -> Unit,
        onAbout: () -> Unit
    ) {
        configure(refresh, ToolbarIcon.REFRESH, "Refresh local chart", WorkspaceShortcuts.refresh, onRefresh)
        configure(settings, ToolbarIcon.SETTINGS, "Scanner and currency settings", WorkspaceShortcuts.settings, onSettings)
        configure(importTrades, ToolbarIcon.IMPORT, "Import Scalable transactions CSV", WorkspaceShortcuts.importTrades, onImport)
        configure(about, ToolbarIcon.ABOUT, "About MiMiTrends", WorkspaceShortcuts.about, onAbout)
        WorkspaceShortcuts.install(
            root, linkedMapOf(
                WorkspaceShortcuts.refresh to refresh::fire,
                WorkspaceShortcuts.settings to settings::fire,
                WorkspaceShortcuts.importTrades to importTrades::fire,
                WorkspaceShortcuts.about to about::fire
            )
        )
    }

    private fun configure(
        button: Button,
        icon: ToolbarIcon,
        tooltip: String,
        shortcut: javafx.scene.input.KeyCombination,
        action: () -> Unit
    ) {
        ToolbarIconButton.configure(button, icon, tooltip, shortcut)
        button.setOnAction { action() }
    }
}