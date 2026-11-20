package com.nuvio.tv.livetv.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Fraction of the airing that has elapsed. Null when the programme has no duration. */
internal fun liveTvAiringProgress(startMillis: Long, endMillis: Long, nowMillis: Long): Float? {
    val duration = endMillis - startMillis
    if (duration <= 0L) return null
    return ((nowMillis - startMillis).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
}

/** Clock span for the hero, in the device zone. Same 24-hour clock as the guide ruler. */
internal fun formatLiveTvClockRange(startMillis: Long, endMillis: Long, zone: ZoneId): String {
    val clock = DateTimeFormatter.ofPattern("HH:mm")
    fun format(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).format(clock)
    return "${format(startMillis)} - ${format(endMillis)}"
}
