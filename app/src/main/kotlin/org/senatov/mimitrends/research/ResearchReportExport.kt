package org.senatov.mimitrends.research

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

import javafx.stage.FileChooser
import javafx.stage.Window
import org.senatov.mimitrends.db.WalkForwardResearchReport
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

internal object ResearchReportExport {
    fun choose(owner: Window?): Path? = FileChooser().apply {
        title = "Export prediction research"
        initialFileName = "mimitrends-research-${LocalDate.now()}.csv"
        extensionFilters += FileChooser.ExtensionFilter("CSV files", "*.csv")
    }.showSaveDialog(owner)?.toPath()

    fun write(path: Path, reports: Collection<WalkForwardResearchReport>) {
        Files.writeString(path, ResearchReportCsv.format(reports))
    }
}
