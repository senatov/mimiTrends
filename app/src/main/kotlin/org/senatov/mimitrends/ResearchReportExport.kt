package org.senatov.mimitrends

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
