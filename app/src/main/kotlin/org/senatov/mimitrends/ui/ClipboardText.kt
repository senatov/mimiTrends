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

import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent

internal object ClipboardText {
    var onCopied: (String) -> Unit = {}

    fun copy(value: String) {
        val copied = Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(value) })
        if (copied) onCopied(value)
    }
}
