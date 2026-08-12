package org.senatov.mimitrends

import javafx.application.Platform
import org.senatov.mimitrends.model.ScanResult
import java.util.concurrent.CompletableFuture

internal class ShortMoveSelectionController(
    private val evaluate: (String) -> ScanResult?,
    private val isCurrent: (String) -> Boolean,
    private val onStart: (String) -> Unit,
    private val onComplete: (String, ScanResult?, Throwable?) -> Unit
) {
    fun open(symbol: String) {
        onStart(symbol)
        CompletableFuture.supplyAsync { evaluate(symbol) }.whenComplete { result, error ->
            Platform.runLater {
                if (isCurrent(symbol)) onComplete(symbol, result, error)
            }
        }
    }
}
