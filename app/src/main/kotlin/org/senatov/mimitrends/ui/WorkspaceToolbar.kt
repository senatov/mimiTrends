package org.senatov.mimitrends.ui

import org.senatov.mimitrends.application.*
import org.senatov.mimitrends.ui.*
import org.senatov.mimitrends.scanner.*
import org.senatov.mimitrends.shortmove.*
import org.senatov.mimitrends.signals.*
import org.senatov.mimitrends.research.*
import org.senatov.mimitrends.market.*
import org.senatov.mimitrends.providers.*
import org.senatov.mimitrends.company.*
import org.senatov.mimitrends.services.*
import org.senatov.mimitrends.shared.*

import javafx.scene.Parent

internal object WorkspaceToolbar {
    fun configure(
        root: Parent,
        buttons: WorkspaceActionButtons,
        onRefresh: () -> Unit,
        onSettings: () -> Unit,
        onImport: () -> Unit,
        onAbout: () -> Unit
    ) {
        configure(buttons.refresh, ToolbarIcon.REFRESH, "Refresh local chart", WorkspaceShortcuts.refresh, onRefresh)
        configure(buttons.settings, ToolbarIcon.SETTINGS, "Scanner and currency settings", WorkspaceShortcuts.settings, onSettings)
        configure(
            buttons.importTrades,
            ToolbarIcon.IMPORT,
            "Import Scalable transactions CSV",
            WorkspaceShortcuts.importTrades,
            onImport
        )
        configure(buttons.about, ToolbarIcon.ABOUT, "About MiMiTrends", WorkspaceShortcuts.about, onAbout)
        WorkspaceShortcuts.install(
            root, linkedMapOf(
                WorkspaceShortcuts.refresh to buttons.refresh::fire,
                WorkspaceShortcuts.settings to buttons.settings::fire,
                WorkspaceShortcuts.importTrades to buttons.importTrades::fire,
                WorkspaceShortcuts.about to buttons.about::fire
            )
        )
    }

    private fun configure(
        button: javafx.scene.control.Button,
        icon: ToolbarIcon,
        tooltip: String,
        shortcut: javafx.scene.input.KeyCombination,
        action: () -> Unit
    ) {
        ToolbarIconButton.configure(button, icon, tooltip, shortcut)
        button.setOnAction { action() }
    }
}
