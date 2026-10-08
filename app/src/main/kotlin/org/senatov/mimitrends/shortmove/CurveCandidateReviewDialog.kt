package org.senatov.mimitrends.shortmove

import javafx.scene.control.Alert
import javafx.scene.control.ButtonType
import org.senatov.mimitrends.model.CurveDirection
import java.util.Locale

internal object CurveCandidateReviewDialog {
    fun ask(move: ShortMove): Boolean? {
        val candidate = move.curveCandidate ?: return null
        val kind = if (candidate.direction == CurveDirection.RISE) "rapid rise" else "rapid drop"
        val dialog = Alert(Alert.AlertType.CONFIRMATION).apply {
            title = "Review curve break"
            headerText = "Is this move useful to show as a $kind?"
            contentText = String.format(
                Locale.ROOT,
                "%s · %+.2f%% in %d min\nVolatility %.2f%% · acceleration %+.2f%%/min\n" +
                        "Path efficiency %.0f%% · reversal %.2f%%\nReported-volume bars %d/%d · turnover %,.0f\n\n" +
                        "Yes saves your approval and displays the rapid move. No hides this event.",
                candidate.symbol, candidate.changePercent, candidate.durationMinutes,
                candidate.volatilityPercent, candidate.accelerationPercentPerMinute,
                candidate.pathEfficiency * 100.0, candidate.reversalPercent,
                candidate.reportedVolumeBars, candidate.barCount, candidate.turnover
            )
            buttonTypes.setAll(ButtonType.YES, ButtonType.NO, ButtonType.CANCEL)
        }
        val answer = dialog.showAndWait().orElse(ButtonType.CANCEL)
        return if (answer == ButtonType.YES) true else if (answer == ButtonType.NO) false else null
    }
}
