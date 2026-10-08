package org.senatov.mimitrends.shortmove

import javafx.application.Platform
import org.senatov.mimitrends.db.CurveCandidateStore
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.CurveCandidate
import org.slf4j.Logger
import java.util.concurrent.CompletableFuture

internal class CurveCandidateReviewCoordinator(
    private val store: CurveCandidateStore,
    private val model: CurveCandidateModel,
    private val log: Logger,
    private val refresh: () -> Unit
) {
    fun review(candidate: CurveCandidate, approved: Boolean, done: (Throwable?) -> Unit) {
        CompletableFuture.runAsync {
            check(store.review(candidate, approved)) { "Curve candidate has already been reviewed" }
            model.record(candidate, approved)
        }.whenComplete { _, error ->
            Platform.runLater {
                if (error != null) log.warn(
                    LogTag.DB, "curve candidate review failed symbol={} anchor={}",
                    candidate.symbol, candidate.anchorEpochSeconds, error
                )
                done(error)
                if (error == null) refresh()
            }
        }
    }
}