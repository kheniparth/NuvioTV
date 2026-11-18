package com.nuvio.tv.livetv.ui

import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.livetv.data.LiveTvChannel
import com.nuvio.tv.livetv.data.LiveTvPlayable
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.ui.screens.player.PlayerNavigationArgs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTvPlaybackSessionTest {
    @Test
    fun `switching to a stalker channel calls the resolver`() = runTest {
        val next = LiveTvChannel(
            id = "stalker:2",
            name = "Next",
            streamUrl = "http://raw",
            headers = mapOf("Authorization" to "Bearer old"),
            stalkerCommand = "ffmpeg http://raw",
        )
        val cnn = next.copy(id = "stalker:1", name = "CNN", streamUrl = "http://cnn", stalkerCommand = null)
        val repository = mockk<LiveTvRepository> {
            coEvery { resolvePlayableUrl(next) } returns LiveTvPlayable(
                "http://play?token=a%2Fb",
                mapOf("Authorization" to "Bearer new", "Cookie" to "timezone=Europe%2FIstanbul"),
            )
            coEvery { recordRecent(any()) } returns Unit
        }
        val session = LiveTvPlaybackSession(repository)
        session.arm(listOf(cnn, next), emptySet(), ChannelCategory.All, cnn.id)

        val request = session.playable(session.adjacent(next = true)!!)
        val handle = SavedStateHandle(
            mapOf(
                "streamUrl" to next.streamUrl,
                "title" to "raw",
                "headers" to "",
                "contentType" to "channel",
            )
        )
        handle.applyResolvedLiveChannel(session)
        val args = PlayerNavigationArgs.from(handle)
        val headers = JSONObject(requireNotNull(args.headersJson))

        assertEquals("http://play?token=a%2Fb", request.streamUrl)
        assertEquals(mapOf("Authorization" to "Bearer new", "Cookie" to "timezone=Europe%2FIstanbul"), request.headers)
        assertEquals("http://play?token=a%2Fb", args.streamUrl)
        assertEquals("Next", args.title)
        assertEquals("Bearer new", headers.getString("Authorization"))
        assertEquals("timezone=Europe%2FIstanbul", headers.getString("Cookie"))
        coVerify(exactly = 1) { repository.resolvePlayableUrl(next) }
        coVerify(exactly = 1) { repository.recordRecent("stalker:2") }
        assertNull(session.consumePending())
    }
}
