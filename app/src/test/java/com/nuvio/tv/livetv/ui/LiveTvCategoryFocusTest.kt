package com.nuvio.tv.livetv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTvCategoryFocusTest {
    @Test
    fun `right restores the last focused item in the selected category when it is visible`() {
        val last = mapOf("news" to "c50", "sport" to "c2")
        assertEquals("c50", liveTvListRestoreKey("news", last, listOf("c40", "c50", "c60")))
    }

    @Test
    fun `right uses the first visible item when the remembered one is off screen`() {
        assertEquals("c40", liveTvListRestoreKey("news", mapOf("news" to "c3"), listOf("c40", "c50")))
        assertEquals("c40", liveTvListRestoreKey("news", emptyMap(), listOf("c40", "c50")))
        assertNull(liveTvListRestoreKey("news", mapOf("news" to "c3"), emptyList()))
        assertEquals("c1", liveTvListRestoreKey("sport", mapOf("news" to "c50"), listOf("c1")))
    }
}
