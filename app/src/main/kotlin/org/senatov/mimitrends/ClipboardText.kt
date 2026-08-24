package org.senatov.mimitrends

import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent

internal object ClipboardText {
    var onCopied: (String) -> Unit = {}

    fun copy(value: String) {
        val copied = Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(value) })
        if (copied) onCopied(value)
    }
}
