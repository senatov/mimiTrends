package org.senatov.mimitrends.scanner

import org.senatov.mimitrends.model.ScanResult

internal object ScannerResultOrder {
    val newestFirst: Comparator<ScanResult> = compareBy<ScanResult>(ScanResult::isRetained)
        .thenByDescending(ScanResult::signalEpochMillis)
        .thenByDescending(ScanResult::updatedAtMillis)
        .thenBy(ScanResult::symbol)
}
