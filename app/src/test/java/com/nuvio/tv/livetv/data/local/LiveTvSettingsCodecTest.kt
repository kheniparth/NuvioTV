package com.nuvio.tv.livetv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvSettingsCodecTest {
    @Test
    fun playlistsRoundTripUrlAndLocalFile() {
        val playlists = listOf(
            LiveTvPlaylist("p1", "Mine", LiveTvPlaylistType.Url, "https://x/list.m3u?a=1&b=2", true),
            LiveTvPlaylist("p2", "Disk", LiveTvPlaylistType.LocalFile, "/sdcard/tv.m3u", false),
        )

        val decoded = decodeLiveTvPlaylists(encodeLiveTvPlaylists(playlists))

        assertEquals(playlists, decoded)
    }

    @Test
    fun playlistDecodeDropsBrokenRecordsAndDefaultsEnabled() {
        val json = """
            [
              {"id":"ok","name":"","type":"Url","source":"https://a"},
              {"id":"","type":"Url","source":"https://nope"},
              {"id":"bad","type":"Ftp","source":"ftp://x"},
              "nope"
            ]
        """.trimIndent()

        val decoded = decodeLiveTvPlaylists(json)

        assertEquals(
            listOf(LiveTvPlaylist("ok", "M3U playlist", LiveTvPlaylistType.Url, "https://a", true)),
            decoded
        )
    }

    @Test
    fun xtreamAndStalkerRoundTripAndOptionalPortalLogin() {
        val xtream = LiveTvXtreamSettings("https://x/t", "user", "secret", false)
        assertEquals(xtream, decodeLiveTvXtream(encodeLiveTvXtream(xtream)))

        val stalker = decodeLiveTvStalker(
            """{"portalUrl":"http://h/c/","macAddress":"00:1A:79:AA:BB:CC"}"""
        )
        assertEquals(
            LiveTvStalkerSettings("http://h/c/", "00:1A:79:AA:BB:CC", "", "", true),
            stalker
        )
        assertEquals(LiveTvXtreamSettings(), decodeLiveTvXtream("{"))
        assertEquals(LiveTvStalkerSettings(), decodeLiveTvStalker(null))
    }

    @Test
    fun epgSourcesRoundTrip() {
        val sources = listOf(
            LiveTvEpgSource("e1", "Guide", "https://epg/xmltv.xml.gz", true),
            LiveTvEpgSource("e2", "Other", "https://epg/other.xml", false),
        )
        assertEquals(sources, decodeLiveTvEpgSources(encodeLiveTvEpgSources(sources)))
        assertTrue(decodeLiveTvEpgSources("not-json").isEmpty())
    }

    @Test
    fun recentsStayNewestFirstAndCapAt50() {
        val newestFirst = (1..55).map { "ch$it" } + listOf("  ", "ch1", "ch2")

        val capped = capLiveTvRecentChannelIds(newestFirst)

        assertEquals(50, capped.size)
        assertEquals("ch1", capped.first())
        assertEquals("ch50", capped.last())
        assertEquals(capped, decodeLiveTvStringList(encodeLiveTvStringList(capped)))
    }

    @Test
    fun favoritesDropBlanksAndSort() {
        assertEquals(
            listOf("a", "b"),
            normalizeLiveTvFavoriteChannelIds(listOf(" b ", "", "a", "b"))
        )
    }
}
