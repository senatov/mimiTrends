package org.senatov.mimitrends

import javafx.scene.control.Dialog
import javafx.stage.Window

internal object WorkspaceDialogAppearance {
    private val resources = listOf("MiMiTrends.css", "Workspace.css", "ChartWorkspace.css", "DarkWorkspace.css")
        .map { name -> requireNotNull(javaClass.getResource("/org/senatov/mimitrends/$name")).toExternalForm() }

    fun apply(dialog: Dialog<*>, owner: Window?) {
        val pane = dialog.dialogPane
        val ownerStylesheets = owner?.scene?.stylesheets.orEmpty()
        pane.stylesheets.setAll(if (ownerStylesheets.isEmpty()) resources else ownerStylesheets)
        pane.styleClass += "workspace-dialog"
        val inheritedVariants = owner?.scene?.root?.styleClass.orEmpty().filter {
            it.startsWith("theme-") || it.startsWith("density-")
        }
        pane.styleClass.removeAll("theme-light", "theme-dark", "density-compact", "density-comfortable")
        pane.styleClass += inheritedVariants.ifEmpty { listOf("theme-light", "density-compact") }
    }
}
