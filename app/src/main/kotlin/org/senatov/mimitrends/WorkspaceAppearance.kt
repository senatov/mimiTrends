package org.senatov.mimitrends

import javafx.scene.Parent
import org.senatov.mimitrends.model.TableAppearance
import org.senatov.mimitrends.model.UiDensity
import org.senatov.mimitrends.model.UiTheme

internal object WorkspaceAppearance {
    fun apply(root: Parent, appearance: TableAppearance) {
        root.styleClass.removeAll("theme-light", "theme-dark", "density-compact", "density-comfortable")
        root.styleClass += if (appearance.theme == UiTheme.DARK) "theme-dark" else "theme-light"
        root.styleClass += if (appearance.density == UiDensity.COMFORTABLE) {
            "density-comfortable"
        } else {
            "density-compact"
        }
    }
}
