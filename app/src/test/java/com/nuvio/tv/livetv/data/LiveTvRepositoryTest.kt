package com.nuvio.tv.livetv.data

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTvRepositoryTest {
    private val http = mockk<LiveTvHttp>()
    private val xtream = mockk<XtreamClient>()
    private val stalker = mockk<StalkerClient>()
    private val xtreamSettings = LiveTvXtreamSettings("http://panel", "u", "p")
    private val stalkerSettings = LiveTvStalkerSettings("http://portal", "00:1A:79:00:00:01")

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

        val disk = LiveTvRepository(provider, http, xtream, stalker, LiveTvCache(dir)).channels()
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

    private fun repo(sources: LiveTvSourceProvider, cache: LiveTvCache) =
        LiveTvRepository(sources, http, xtream, stalker, cache)

    private fun tempDir() = File(System.getProperty("java.io.tmpdir"), "livetv-cache-${System.nanoTime()}").apply { mkdirs() }

    private class FakeSources(var sources: LiveTvSources) : LiveTvSourceProvider {
        override suspend fun current(): LiveTvSources = sources
    }
}
