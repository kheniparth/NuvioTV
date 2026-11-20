package com.nuvio.tv.livetv.ui

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTvHeroFormatTest {
    @Test
    fun `progress is the fraction of the airing and stays inside 0 to 1`() {
        assertEquals(0.25f, liveTvAiringProgress(0L, 1_000L, 250L)!!, 0.001f)
        assertEquals(0f, liveTvAiringProgress(1_000L, 2_000L, 0L)!!, 0.001f)
        assertEquals(1f, liveTvAiringProgress(0L, 1_000L, 5_000L)!!, 0.001f)
        assertNull(liveTvAiringProgress(1_000L, 1_000L, 1_000L))
    }

    @Test
    fun `clock range uses the zone and a 24-hour clock`() {
        val start = 20 * 3_600_000L
        val end = 22 * 3_600_000L
        assertEquals("20:00 - 22:00", formatLiveTvClockRange(start, end, ZoneOffset.UTC))
    }
}
