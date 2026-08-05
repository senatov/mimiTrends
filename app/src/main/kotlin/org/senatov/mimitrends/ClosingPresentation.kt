package org.senatov.mimitrends

import javafx.scene.control.Button

internal object ClosingPresentation {
    fun show(scannerPanel: ScannerPanel, actions: Collection<Button>) {
        scannerPanel.showClosing()
        actions.forEach { it.isDisable = true }
    }
}
