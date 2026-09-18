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

import javafx.beans.value.ChangeListener
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination

internal object WorkspaceShortcuts {
    val refresh: KeyCombination = KeyCodeCombination(KeyCode.R, KeyCombination.SHORTCUT_DOWN)
    val settings: KeyCombination = KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN)
    val importTrades: KeyCombination = KeyCodeCombination(
        KeyCode.I, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN
    )
    val about: KeyCombination = KeyCodeCombination(KeyCode.F1)
    val findSignals: KeyCombination = KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN)
    val findMoves: KeyCombination = KeyCodeCombination(
        KeyCode.F, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN
    )

    fun install(root: Parent, actions: Map<KeyCombination, () -> Unit>) {
        fun apply(scene: Scene?, add: Boolean) {
            actions.forEach { (shortcut, action) ->
                if (add) scene?.accelerators?.put(shortcut, Runnable { action() })
                else scene?.accelerators?.remove(shortcut)
            }
        }

        val listener = ChangeListener<Scene?> { _, previous, current ->
            apply(previous, false)
            apply(current, true)
        }
        root.sceneProperty().addListener(listener)
        apply(root.scene, true)
    }
}
