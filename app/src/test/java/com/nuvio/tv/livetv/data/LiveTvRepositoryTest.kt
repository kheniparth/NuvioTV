package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.data.local.LiveTvSettingsDataStore
import com.nuvio.tv.livetv.data.local.LiveTvPlaylist as StoredPlaylist
import com.nuvio.tv.livetv.data.local.LiveTvPlaylistType as StoredPlaylistType
import com.nuvio.tv.livetv.data.local.LiveTvSettings as StoredSettings
import com.nuvio.tv.livetv.data.local.LiveTvStalkerSettings as StoredStalker
import com.nuvio.tv.livetv.data.local.LiveTvXtreamSettings as StoredXtream
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvRepositoryTest {
    private val http = mockk<LiveTvHttp>()
    private val xtream = mockk<XtreamClient>()
    private val stalker = mockk<StalkerClient>()
    private val xtreamSettings = LiveTvXtreamSettings("http://panel", "u", "p")
    private val stalkerSettings = LiveTvStalkerSettings("http://portal", "00:1A:79:00:00:01")

    @Test
    fun `a source counts when it is saved, including when disabled`() {
        assertFalse(StoredSettings().hasConfiguredSource())
        assertFalse(StoredSettings(playlists = listOf(playlist("p", ""))).hasConfiguredSource())
        assertTrue(
            StoredSettings(playlists = listOf(playlist("p", "http://list").copy(isEnabled = false))).hasConfiguredSource()
        )
        assertFalse(StoredSettings(xtream = StoredXtream(serverUrl = "http://x")).hasConfiguredSource())
        assertTrue(
            StoredSettings(stalker = StoredStalker("http://portal", "00:1A:79:00:00:01", isEnabled = false))
                .hasConfiguredSource()
        )
    }

    @Test
    fun `channels merge two sources and a failing source keeps the rest`() = runTest {
        val dir = tempDir()
        val provider = FakeSources(
            LiveTvSources(
                playlists = listOf(LiveTvPlaylist("m3u", "Mine", LiveTvPlaylistType.Url, "https://a.m3u")),
                xtream = xtreamSettings,
            )
        )
        coEvery { http.getText("https://a.m3u") } returns "#EXTINF:-1 group-title=\"News\",One\nhttp://one\n"
        coEvery { xtream.liveChannels(xtreamSettings) } throws IllegalStateException("panel down")
        val repo = repo(provider, LiveTvCache(dir))

        val channels = repo.channels()

        assertEquals(listOf("One"), channels.map { it.name })
        assertEquals(listOf("News"), repo.categories(channels))
        coVerify(exactly = 0) { stalker.channels(any()) }

        coEvery { xtream.liveChannels(xtreamSettings) } returns listOf(
            LiveTvChannel(id = "xtream:2", name = "Two", streamUrl = "http://two", group = "Sport", playlistId = "provider:xtream"),
        )
        provider.sources = provider.sources.copy(playlists = listOf(
            LiveTvPlaylist("m3u", "Mine", LiveTvPlaylistType.Url, "https://b.m3u"),
        ))
        coEvery { http.getText("https://b.m3u") } returns "#EXTINF:-1 group-title=\"News\",One\nhttp://one\n"
        val merged = repo.channels()
        assertEquals(listOf("One", "Two"), merged.map { it.name })

        val disk = LiveTvRepository(provider, http, xtream, stalker, LiveTvCache(dir), mockk(relaxed = true)).channels()
        assertEquals(listOf("One", "Two"), disk.map { it.name })
        coVerify(exactly = 1) { http.getText("https://b.m3u") }
        coVerify(exactly = 2) { xtream.liveChannels(xtreamSettings) }
    }

    @Test
    fun `resolvePlayableUrl returns each source url and headers`() = runTest {
        val provider = FakeSources(LiveTvSources(xtream = xtreamSettings, stalker = stalkerSettings))
        val repo = repo(provider, LiveTvCache(tempDir()))
        val m3u = LiveTvChannel(id = "live:1", name = "One", streamUrl = "http://one", headers = mapOf("User-Agent" to "VLC"))
        val xtreamChannel = LiveTvChannel(
            id = "xtream:2",
            name = "Two",
            streamUrl = "http://panel/live/u/p/2.ts",
            headers = mapOf("User-Agent" to "VLC/3.0.0 LibVLC/3.0.0"),
            playlistId = "provider:xtream",
        )
        val portal = LiveTvChannel(
            id = "stalker:3",
            name = "Three",
            streamUrl = "http://cmd",
            headers = mapOf("Authorization" to "Bearer old"),
            stalkerCommand = "ffmpeg http://cmd",
        )
        coEvery { stalker.createLink(portal, stalkerSettings) } returns portal.copy(
            streamUrl = "http://play",
            headers = mapOf("Authorization" to "Bearer new"),
        )

        assertEquals(LiveTvPlayable("http://one", m3u.headers), repo.resolvePlayableUrl(m3u))
        assertEquals(LiveTvPlayable(xtreamChannel.streamUrl, xtreamChannel.headers), repo.resolvePlayableUrl(xtreamChannel))
        assertEquals(LiveTvPlayable("http://play", mapOf("Authorization" to "Bearer new")), repo.resolvePlayableUrl(portal))
        coVerify(exactly = 1) { stalker.createLink(any(), any()) }
    }

    @Test
    fun `epgFor reads a gzip guide for the channel id`() = runTest {
        val xml = """
            <tv><programme start="20261117120000 +0000" stop="20261117130000 +0000" channel="BBC1"><title>News</title></programme></tv>
        """.trimIndent()
        val gzip = ByteArrayOutputStream().use { out ->
            GZIPOutputStream(out).use { it.write(xml.toByteArray()) }
            out.toByteArray()
        }
        val provider = FakeSources(LiveTvSources(epgSources = listOf(LiveTvEpgSource("e", "Guide", "https://epg.gz"))))
        coEvery { http.getBytes("https://epg.gz", any()) } returns gzip
        val repo = repo(provider, LiveTvCache(tempDir()))

        val channel = LiveTvChannel(id = "1", name = "BBC", streamUrl = "http://bbc", epgId = "bbc1")
        val programs = repo.epgFor(channel)

        assertEquals(listOf("News"), programs.map { it.title })
        assertEquals(programs, repo.epgFor(channel))
        assertEquals(emptyList<LiveTvEpgProgram>(), repo.epgFor(channel.copy(epgId = null)))
        coVerify(exactly = 1) { http.getBytes(any(), any()) }
    }

    @Test
    fun `catalog returns channels, guide and favourites`() = runTest {
        val provider = FakeSources(
            LiveTvSources(
                playlists = listOf(LiveTvPlaylist("m3u", "Mine", LiveTvPlaylistType.Url, "https://a.m3u")),
                epgSources = listOf(LiveTvEpgSource("e", "Guide", "https://epg.xml")),
            )
        )
        coEvery { http.getText("https://a.m3u") } returns M3U
        coEvery { http.getBytes("https://epg.xml", any()) } returns EPG.toByteArray()
        val store = mockk<LiveTvSettingsDataStore> {
            every { settings } returns MutableStateFlow(StoredSettings(favoriteChannelIds = listOf("keep")))
        }
        val catalog = LiveTvRepository(provider, http, xtream, stalker, LiveTvCache(tempDir()), store)
            .loadCatalog(1_794_916_800_000L)

        assertEquals(listOf("BBC"), catalog.channels.map { it.name })
        assertEquals("News", catalog.channels.single().group)
        assertEquals(setOf("keep"), catalog.favoriteIds)
        assertEquals("On now", catalog.guide.nowNext("bbc1", 1_794_916_800_000L).now?.title)
    }

    @Test
    fun `favourite toggle is stored and recents go newest first`() = runTest {
        val stored = MutableStateFlow(StoredSettings(favoriteChannelIds = listOf("one")))
        val store = mockk<LiveTvSettingsDataStore> {
            every { settings } returns stored
            coEvery { setFavoriteChannelIds(any()) } answers {
                stored.value = stored.value.copy(favoriteChannelIds = firstArg<Collection<String>>().toList())
            }
            coEvery { setRecentChannelIds(any()) } answers {
                stored.value = stored.value.copy(recentChannelIds = firstArg())
            }
        }
        val repo = LiveTvRepository(FakeSources(LiveTvSources()), http, xtream, stalker, LiveTvCache(tempDir()), store)

        repo.toggleFavorite("one")
        assertEquals(emptySet<String>(), stored.value.favoriteChannelIds.toSet())
        repo.toggleFavorite("two")
        assertEquals(setOf("two"), stored.value.favoriteChannelIds.toSet())

        repo.recordRecent("two")
        assertEquals(listOf("two"), stored.value.recentChannelIds)
    }

    private fun repo(
        sources: LiveTvSourceProvider,
        cache: LiveTvCache,
    ) = LiveTvRepository(sources, http, xtream, stalker, cache, mockk(relaxed = true))

    private fun tempDir() = File(System.getProperty("java.io.tmpdir"), "livetv-cache-${System.nanoTime()}").apply { mkdirs() }

    private fun playlist(id: String, source: String, enabled: Boolean = true) = StoredPlaylist(
        id = id,
        name = id,
        type = StoredPlaylistType.Url,
        source = source,
        isEnabled = enabled,
    )

    private class FakeSources(var sources: LiveTvSources) : LiveTvSourceProvider {
        override suspend fun current(): LiveTvSources = sources
    }

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
