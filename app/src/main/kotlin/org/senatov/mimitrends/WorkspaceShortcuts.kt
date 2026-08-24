package org.senatov.mimitrends

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