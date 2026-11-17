package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.data.local.LiveTvEpgSource
import com.nuvio.tv.livetv.data.local.LiveTvPlaylist
import com.nuvio.tv.livetv.data.local.LiveTvPlaylistType
import com.nuvio.tv.livetv.data.local.LiveTvSettings
import com.nuvio.tv.livetv.data.local.LiveTvSettingsDataStore
import com.nuvio.tv.livetv.data.local.LiveTvStalkerSettings
import com.nuvio.tv.livetv.data.local.LiveTvXtreamSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvRepositoryTest {
    @Test
    fun `a source counts when it is saved, including when disabled`() {
        assertFalse(LiveTvSettings().hasConfiguredSource())
        assertFalse(LiveTvSettings(playlists = listOf(playlist("p", ""))).hasConfiguredSource())
        assertTrue(
            LiveTvSettings(playlists = listOf(playlist("p", "http://list").copy(isEnabled = false))).hasConfiguredSource()
        )
        assertFalse(LiveTvSettings(xtream = LiveTvXtreamSettings(serverUrl = "http://x")).hasConfiguredSource())
        assertTrue(
            LiveTvSettings(stalker = LiveTvStalkerSettings("http://portal", "00:1A:79:00:00:01", isEnabled = false))
                .hasConfiguredSource()
        )
    }

    @Test
    fun `loads an enabled playlist and epg, and skips a disabled playlist`() = runTest {
        val stored = MutableStateFlow(
            LiveTvSettings(
                playlists = listOf(playlist("on", "http://on.m3u"), playlist("off", "http://off.m3u", enabled = false)),
                epgSources = listOf(LiveTvEpgSource("e", "Guide", "http://guide.xml")),
                favoriteChannelIds = listOf("keep"),
            )
        )
        val http = mockk<LiveTvHttp>()
        coEvery { http.getText("http://on.m3u", any()) } returns M3U
        coEvery { http.getBytes("http://guide.xml", any()) } returns EPG.toByteArray()
        val xtream = mockk<XtreamClient>(relaxed = true)
        val stalker = mockk<StalkerClient>(relaxed = true)
        val store = mockk<LiveTvSettingsDataStore> { every { settings } returns stored }
        val catalog = LiveTvRepository(store, http, xtream, stalker).loadCatalog()

        assertEquals(listOf("BBC"), catalog.channels.map { it.name })
        assertEquals("News", catalog.channels.single().group)
        assertEquals(setOf("keep"), catalog.favoriteIds)
        assertEquals("On now", catalog.guide.nowNext("bbc1", 1_794_916_800_000L).now?.title)
        assertNull(catalog.loadError)
        coVerify(exactly = 0) { http.getText("http://off.m3u", any()) }
        coVerify(exactly = 0) { xtream.liveChannels(any()) }
        coVerify(exactly = 0) { stalker.channels(any()) }
    }

    @Test
    fun `resolve asks the portal for a stalker link and leaves other channels alone`() = runTest {
        val stored = MutableStateFlow(
            LiveTvSettings(stalker = LiveTvStalkerSettings("http://portal", "00:1A:79:00:00:01"))
        )
        val stalkerChannel = LiveTvChannel(
            id = "stalker:1",
            name = "One",
            streamUrl = "http://placeholder",
            stalkerCommand = "ffmpeg http://cmd",
        )
        val plain = LiveTvChannel(id = "m3u", name = "Two", streamUrl = "http://plain")
        val stalker = mockk<StalkerClient> {
            coEvery { createLink(stalkerChannel, any()) } returns stalkerChannel.copy(streamUrl = "http://real")
        }
        val store = mockk<LiveTvSettingsDataStore> { every { settings } returns stored }
        val repository = LiveTvRepository(store, mockk(relaxed = true), mockk(relaxed = true), stalker)

        assertEquals("http://real", repository.resolvePlayableUrl(stalkerChannel).streamUrl)
        assertEquals(plain, repository.resolvePlayableUrl(plain))
        coVerify(exactly = 1) { stalker.createLink(any(), any()) }
    }

    private fun playlist(id: String, source: String, enabled: Boolean = true) = LiveTvPlaylist(
        id = id,
        name = id,
        type = LiveTvPlaylistType.Url,
        source = source,
        isEnabled = enabled,
    )

    private companion object {
        val M3U = """
            #EXTM3U
            #EXTINF:-1 tvg-id="bbc1" group-title="News",BBC
            http://stream/bbc.ts
        """.trimIndent()
        val EPG = """
            <tv>
              <programme start="20261117120000 +0000" stop="20261117130000 +0000" channel="bbc1">
                <title>On now</title>
              </programme>
            </tv>
        """.trimIndent()
    }
}
