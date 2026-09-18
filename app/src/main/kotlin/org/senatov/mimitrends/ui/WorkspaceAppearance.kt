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
