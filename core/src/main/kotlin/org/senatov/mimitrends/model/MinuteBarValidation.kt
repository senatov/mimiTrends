package org.senatov.mimitrends.model

fun MinuteBar.isValidMinuteBar(): Boolean = minuteEpochSeconds % 60L == 0L &&
    volume.isFinite() && volume >= 0.0 && (volumeStatus != VolumeStatus.REPORTED || volume > 0.0) &&
    open.isFinite() && open > 0.0 &&
    high.isFinite() && low.isFinite() && close.isFinite() && close > 0.0 &&
    high >= maxOf(open, close, low) && low <= minOf(open, close, high)
