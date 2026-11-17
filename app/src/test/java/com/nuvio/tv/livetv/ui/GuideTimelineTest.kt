package com.nuvio.tv.livetv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuideTimelineTest {
    private val windowStart = 12L * 60L * 60L * 1000L
    private val windowEnd = windowStart + GUIDE_WINDOW_MILLIS

    @Test
    fun `programme inside the window keeps its start and duration`() {
        val span = guideProgrammeSpan(
            startMillis = windowStart + 30L * 60_000L,
            endMillis = windowStart + 90L * 60_000L,
            windowStartMillis = windowStart,
        )

        assertEquals(120f, span!!.xDp, 0.001f)
        assertEquals(240f, span.widthDp, 0.001f)
    }

    @Test
    fun `programme starting before the window is clipped at the left edge`() {
        val span = guideProgrammeSpan(
            startMillis = windowStart - 30L * 60_000L,
            endMillis = windowStart + 60L * 60_000L,
            windowStartMillis = windowStart,
        )

        assertEquals(0f, span!!.xDp, 0.001f)
        assertEquals(240f, span.widthDp, 0.001f)
    }

    @Test
    fun `programme ending after the window is clipped at the right edge`() {
        val span = guideProgrammeSpan(
            startMillis = windowEnd - 15L * 60_000L,
            endMillis = windowEnd + 60L * 60_000L,
            windowStartMillis = windowStart,
        )

        assertEquals(2820f, span!!.xDp, 0.001f)
        assertEquals(60f, span.widthDp, 0.001f)
    }

    @Test
    fun `programme outside the window has no span`() {
        assertNull(
            guideProgrammeSpan(
                startMillis = windowEnd,
                endMillis = windowEnd + 60L * 60_000L,
                windowStartMillis = windowStart,
            )
        )
        assertNull(
            guideProgrammeSpan(
                startMillis = windowStart - 60L * 60_000L,
                endMillis = windowStart,
                windowStartMillis = windowStart,
            )
        )
        assertNull(
            guideProgrammeSpan(
                startMillis = windowStart + 60_000L,
                endMillis = windowStart + 60_000L,
                windowStartMillis = windowStart,
            )
        )
    }

    @Test
    fun `window starts one slot before the current slot`() {
        val now = 12L * 60L * 60_000L + 10L * 60_000L

        assertEquals(11L * 60L * 60_000L + 30L * 60_000L, guideWindowStart(now))
        assertEquals(guideWindowStart(now), guideWindowStart(now + 10L * 60_000L))
    }
}
