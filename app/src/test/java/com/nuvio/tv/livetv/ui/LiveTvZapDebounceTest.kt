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
class LiveTvZapDebounceTest {

    @Test
    fun `a burst of presses opens only the last channel`() = runTest {
        val opened = mutableListOf<String>()
        val debounce = debounce(opened)

        listOf("b", "c", "d").forEach { debounce.request(channel(it)) }
        advanceTimeBy(LIVE_TV_ZAP_DEBOUNCE_MILLIS - 1)
        runCurrent()
        assertTrue(opened.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("d"), opened)
    }

    @Test
    fun `cancelling the pending switch opens nothing`() = runTest {
        val opened = mutableListOf<String>()
        val debounce = debounce(opened)

        debounce.request(channel("b"))
        advanceTimeBy(200)
        debounce.cancel()
        advanceTimeBy(LIVE_TV_ZAP_DEBOUNCE_MILLIS)
        runCurrent()

        assertTrue(opened.isEmpty())
    }

    private fun TestScope.debounce(opened: MutableList<String>) =
        LiveTvZapDebounce(scope = this, onOpen = { opened += it.id })

    private fun channel(id: String) = LiveTvChannel(id = id, name = id, streamUrl = "http://$id")
}
