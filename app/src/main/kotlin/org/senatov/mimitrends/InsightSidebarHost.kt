package org.senatov.mimitrends

import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Region

internal class InsightSidebarHost(
    private val sidebar: InsightSidebar,
    initiallyExpanded: Boolean
) : HBox(4.0) {
    private val toggle = Button()
    var isExpanded: Boolean = initiallyExpanded
        private set

    init {
        alignment = Pos.CENTER_RIGHT
        maxWidth = Region.USE_PREF_SIZE
        styleClass += "insight-sidebar-host"
        toggle.styleClass += "insight-sidebar-toggle"
        toggle.accessibleText = "Show or hide analysis sidebar"
        toggle.setOnAction { setExpanded(!isExpanded) }
        children += listOf(toggle, sidebar)
        setExpanded(initiallyExpanded)
    }

    private fun setExpanded(expanded: Boolean) {
        isExpanded = expanded
        sidebar.isVisible = expanded
        sidebar.isManaged = expanded
        toggle.text = if (expanded) "›" else "‹"
        toggle.tooltip = Tooltip(if (expanded) "Hide analysis sidebar" else "Show analysis sidebar")
        toggle.accessibleHelp = toggle.tooltip.text
    }
}
