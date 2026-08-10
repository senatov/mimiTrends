package org.senatov.mimitrends.db

import org.senatov.mimitrends.model.ScanResult
import java.sql.Connection

internal class PredictionAnalyticsStore(connection: Connection) {
    private val calibration = SignalCalibrationStore(connection)
    private val predictiveModels = PredictiveModelStore(connection)
    private val walkForward = WalkForwardResearchEvaluator(connection)

    fun enrich(result: ScanResult): ScanResult = predictiveModels.enrich(calibration.enrich(result))

    fun train(): List<PredictiveTrainingResult> = predictiveModels.trainAll()

    fun report(horizonMinutes: Int, frictionPercent: Double): WalkForwardResearchReport =
        walkForward.evaluate(horizonMinutes, frictionPercent)
}
