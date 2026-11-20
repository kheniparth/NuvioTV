package com.nuvio.tv.livetv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SportsStatusTest {
    @Test
    fun `live badge is shown once`() {
        assertEquals("LIVE", liveSportsStatus("LIVE", "Live"))
        assertEquals("LIVE", liveSportsStatus("LIVE", " "))
        assertEquals("LIVE Q2", liveSportsStatus("LIVE", "Q2"))
    }
}
