package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.data.LiveTvChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTvPreviewDebounceTest {

    @Test
    fun `preview starts only after the focus delay`() = runTest {
        val started = mutableListOf<String>()
        val debounce = debounce(started)

        debounce.focus(channel("a"))
        advanceTimeBy(PREVIEW_FOCUS_DELAY_MILLIS - 1)
        runCurrent()
        assertTrue(started.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("a"), started)
    }

    @Test
    fun `focus change cancels the pending preview`() = runTest {
        val started = mutableListOf<String>()
        val debounce = debounce(started)

        debounce.focus(channel("a"))
        advanceTimeBy(400)
        debounce.focus(channel("b"))
        advanceTimeBy(PREVIEW_FOCUS_DELAY_MILLIS - 1)
        runCurrent()
        assertTrue(started.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("b"), started)
    }

    @Test
    fun `clearing focus stops and cancels the pending preview`() = runTest {
        val started = mutableListOf<String>()
        var stops = 0
        val debounce = LiveTvPreviewDebounce(
            scope = this,
            onStart = { started += it.id },
            onStop = { stops += 1 },
        )

        debounce.focus(channel("a"))
        advanceTimeBy(400)
        debounce.focus(null)
        advanceTimeBy(PREVIEW_FOCUS_DELAY_MILLIS)
        runCurrent()

        assertTrue(started.isEmpty())
        assertTrue(stops > 0)
    }

    @Test
    fun `focusing the same channel does not restart the delay`() = runTest {
        val started = mutableListOf<String>()
        val debounce = debounce(started)

        debounce.focus(channel("a"))
        advanceTimeBy(600)
        debounce.focus(channel("a"))
        advanceTimeBy(PREVIEW_FOCUS_DELAY_MILLIS - 601)
        runCurrent()
        assertTrue(started.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("a"), started)
    }

    private fun TestScope.debounce(started: MutableList<String>) =
        LiveTvPreviewDebounce(
            scope = this,
            onStart = { started += it.id },
            onStop = {},
        )

    private fun channel(id: String) = LiveTvChannel(id = id, name = id, streamUrl = "http://$id")
}
