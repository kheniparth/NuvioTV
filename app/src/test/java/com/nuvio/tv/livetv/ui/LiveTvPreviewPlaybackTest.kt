package com.nuvio.tv.livetv.ui

import androidx.media3.common.MimeTypes
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.livetv.data.LiveTvChannel
import com.nuvio.tv.livetv.data.LiveTvPlayable
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.data.StalkerStage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun `preview plays the resolved url with the source headers and mime`() = runTest {
        val channel = LiveTvChannel(
            id = "s1",
            name = "News",
            streamUrl = "http://portal/cmd",
            headers = mapOf("User-Agent" to "MAG254"),
            stalkerCommand = "ffrt http://portal/cmd",
        )
        val headers = mapOf("User-Agent" to "MAG254", "Authorization" to "Bearer t")
        val repository = mockk<LiveTvRepository>()
        every { repository.stalkerPreviewBlocked() } returns false
        coEvery { repository.openChannel(channel, StalkerStage.Preview, any()) } returns LiveTvPlayable("http://cdn/live.m3u8", headers)
        val player = mockk<LiveTvPreviewPlayer>(relaxed = true)
        val viewModel = LiveTvPreviewViewModel(repository, player)

        viewModel.onFocus(channel)
        advanceTimeBy(PREVIEW_FOCUS_DELAY_MILLIS)
        runCurrent()

        coVerify { repository.openChannel(channel, StalkerStage.Preview, any()) }
        verify { player.play("http://cdn/live.m3u8", headers, MimeTypes.APPLICATION_M3U8) }
    }

    @Test
    fun `preview source kind matches the full player for hls ts and progressive`() = runTest {
        assertEquals(PreviewSourceKind.Hls, previewSourceKind(MimeTypes.APPLICATION_M3U8))
        assertEquals(PreviewSourceKind.Ts, previewSourceKind(MimeTypes.VIDEO_MP2T))
        assertEquals(PreviewSourceKind.Progressive, previewSourceKind(MimeTypes.VIDEO_MP4))
        assertEquals(MimeTypes.APPLICATION_M3U8, previewMimeType("http://cdn/live.m3u8", emptyMap()))
        assertEquals(MimeTypes.VIDEO_MP2T, previewMimeType("http://cdn/a.ts", emptyMap()))
        assertEquals(MimeTypes.VIDEO_MP4, previewMimeType("http://cdn/a.mp4", emptyMap()))
    }
}
