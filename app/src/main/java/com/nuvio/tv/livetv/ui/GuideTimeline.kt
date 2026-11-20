package com.nuvio.tv.livetv.ui

/** Guide timeline maths: one shared 12-hour window, aligned to the last 30-minute slot. */
internal const val GUIDE_SLOT_MILLIS = 30L * 60L * 1000L
internal const val GUIDE_WINDOW_MILLIS = 12L * 60L * 60L * 1000L
internal const val GUIDE_DP_PER_MINUTE = 4f

internal data class GuideProgrammeSpan(val xDp: Float, val widthDp: Float)

/**
 * Guide category column. 1080p is 960dp and 720p is 1280dp or about 853dp.
 * Page padding is 48dp, gaps 24dp, preview 320dp. Prefer 180dp so a label fits on one line,
 * but never take more than about a third of the space left for the grid.
 */
internal fun guideCategoryWidthDp(screenWidthDp: Int): Int {
    val leftover = (screenWidthDp - 96 - 48 - 320).coerceAtLeast(0)
    val cap = (leftover * 0.36f).toInt()
    return 180.coerceAtMost(cap).coerceAtLeast(0)
}

/** Channel column beside the timeline. Wider when the row has room, so a name is not cut mid-word. */
internal fun guideChannelColumnWidthDp(screenWidthDp: Int): Int {
    val guide = (screenWidthDp - 96 - 48 - 320 - guideCategoryWidthDp(screenWidthDp)).coerceAtLeast(0)
    val room = guide - 96
    return when {
        room >= 200 -> 200
        room > 0 -> room
        else -> 0
    }
}

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
