package org.senatov.mimitrends.log

import org.slf4j.Marker
import org.slf4j.MarkerFactory

object LogTag {
    val APP: Marker = MarkerFactory.getMarker("APP")
    val UI: Marker = MarkerFactory.getMarker("UI")
    val API: Marker = MarkerFactory.getMarker("API")
    val IO: Marker = MarkerFactory.getMarker("IO")
    val STATE: Marker = MarkerFactory.getMarker("STATE")
    val DB: Marker = MarkerFactory.getMarker("DB")
}
