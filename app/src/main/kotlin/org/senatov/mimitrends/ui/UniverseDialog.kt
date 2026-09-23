package org.senatov.mimitrends.ui

import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.stage.Window
import org.senatov.mimitrends.scanner.DynamicUniverseSelection
import org.senatov.mimitrends.shared.UniversePanel

internal class UniverseDialog(
    private val onCountChanged: (Int) -> Unit
) {
    private val panel = UniversePanel()
    private var latest: DynamicUniverseSelection? = null
    private var dialog: Dialog<ButtonType>? = null

    fun update(selection: DynamicUniverseSelection) {
        latest = selection
        panel.show(selection)
        onCountChanged(selection.symbols.size)
    }

    fun show(owner: Window?) {
        dialog?.takeIf { it.isShowing }?.dialogPane?.requestFocus() ?: Dialog<ButtonType>().also { created ->
            owner?.let(created::initOwner)
            WorkspaceDialogAppearance.apply(created, owner)
            created.title = "Liquid universe"
            created.headerText = latest?.let { selection ->
                val us = selection.symbols.count { !it.contains('.') }
                "${selection.symbols.size} rotating symbols · US $us · Europe ${selection.symbols.size - us}"
            } ?: "Waiting for the first universe refresh"
            created.dialogPane.content = panel
            created.dialogPane.buttonTypes += ButtonType.CLOSE
            created.dialogPane.prefWidth = 440.0
            created.dialogPane.prefHeight = 520.0
            created.setOnHidden { dialog = null }
            dialog = created
            created.show()
        }
    }
}
