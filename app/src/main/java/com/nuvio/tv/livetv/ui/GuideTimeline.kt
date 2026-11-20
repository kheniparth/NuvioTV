package com.nuvio.tv.livetv.ui

/** Guide timeline maths: one shared 12-hour window, aligned to the last 30-minute slot. */
internal const val GUIDE_SLOT_MILLIS = 30L * 60L * 1000L
internal const val GUIDE_WINDOW_MILLIS = 12L * 60L * 60L * 1000L
internal const val GUIDE_DP_PER_MINUTE = 4f

/** Shares of the grid beside the preview. Names stay near a quarter; the timeline keeps at least 60%. */
internal const val GUIDE_CATEGORY_SHARE = 0.16f
internal const val GUIDE_NAME_SHARE = 0.24f
internal const val GUIDE_TIMELINE_SHARE = 0.60f

internal data class GuideProgrammeSpan(val xDp: Float, val widthDp: Float)

/** The window starts one slot before [nowMillis] so the now-line is never at the very edge. */
internal fun guideWindowStart(nowMillis: Long): Long =
    nowMillis / GUIDE_SLOT_MILLIS * GUIDE_SLOT_MILLIS - GUIDE_SLOT_MILLIS

/** Position and width of a programme on the shared timeline, clipped at both window edges. */
internal fun guideProgrammeSpan(
    startMillis: Long,
    endMillis: Long,
    windowStartMillis: Long,
    windowEndMillis: Long = windowStartMillis + GUIDE_WINDOW_MILLIS,
    dpPerMinute: Float = GUIDE_DP_PER_MINUTE,
): GuideProgrammeSpan? {
    val start = maxOf(startMillis, windowStartMillis)
    val end = minOf(endMillis, windowEndMillis)
    if (end <= start) return null
    return GuideProgrammeSpan(
        xDp = (start - windowStartMillis) / 60_000f * dpPerMinute,
        widthDp = (end - start) / 60_000f * dpPerMinute,
    )
}
