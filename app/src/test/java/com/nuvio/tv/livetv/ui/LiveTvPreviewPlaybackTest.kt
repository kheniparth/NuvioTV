package com.nuvio.tv.livetv.ui

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.livetv.data.LiveTvRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LiveTvPreviewPlaybackTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @Test
    fun `preview stops before full-screen playback starts`() {
        val calls = mutableListOf<String>()
        val player = mockk<LiveTvPreviewPlayer>(relaxed = true)
        every { player.stop() } answers { calls += "stop" }
        val viewModel = LiveTvPreviewViewModel(mockk<LiveTvRepository>(relaxed = true), player)

        viewModel.stopForPlayback()
        calls += "play"

        assertEquals(listOf("stop", "play"), calls)
    }
}
