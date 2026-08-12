package org.senatov.mimitrends

import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent

internal object ClipboardText {
    fun copy(value: String) {
        Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(value) })
    }
}
