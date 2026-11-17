package com.nuvio.tv.livetv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {
    @Test
    fun `parses attributes groups headers and a bare channel from a crlf playlist`() {
        val playlist = LiveTvPlaylist(
            id = "p1",
            name = "Mine",
            type = LiveTvPlaylistType.Url,
            source = "http://example/pl.m3u",
        )
        val payload = """
            #EXTM3U

            #EXTINF:-1 tvg-id="bbc1" tvg-name="BBC One" tvg-logo="http://logo/bbc.png" group-title="News, UK",BBC One HD
            #EXTVLCOPT:http-user-agent=TestAgent/1
            #EXTVLCOPT:http-referrer=http://ref.example/
            http://stream.example/bbc.ts

            #EXTINF:-1,Kodi
            #KODIPROP:http-user-agent=KodiAgent
            #KODIPROP:http-referrer=http://kodi.example/
            http://stream.example/kodi.ts

            #EXTINF:-1,Query
            #KODIPROP:inputstream.adaptive.stream_headers=User-Agent=Query%20Agent&Referer=http%3A%2F%2Fquery.example%2F
            http://stream.example/query.ts

            #EXTINF:-1,Plain
            http://stream.example/plain.ts
        """.trimIndent().replace("\n", "\r\n")

        val channels = parseM3uPlaylist(payload, playlist)

        assertEquals(4, channels.size)
        assertEquals(listOf("BBC One HD", "Kodi", "Query", "Plain"), channels.map { it.name })
        channels.forEach {
            assertEquals("p1", it.playlistId)
            assertEquals("Mine", it.playlistName)
        }

        val news = channels[0]
        assertEquals("bbc1", news.epgId)
        assertEquals("http://logo/bbc.png", news.logoUrl)
        assertEquals("News, UK", news.group)
        assertEquals("http://stream.example/bbc.ts", news.streamUrl)
        assertEquals("TestAgent/1", news.headers["User-Agent"])
        assertEquals("http://ref.example/", news.headers["Referer"])

        val kodi = channels[1]
        assertNull(kodi.group)
        assertNull(kodi.epgId)
        assertNull(kodi.logoUrl)
        assertEquals("KodiAgent", kodi.headers["User-Agent"])
        assertEquals("http://kodi.example/", kodi.headers["Referer"])

        val query = channels[2]
        assertEquals("Query Agent", query.headers["User-Agent"])
        assertEquals("http://query.example/", query.headers["Referer"])

        val plain = channels[3]
        assertNull(plain.group)
        assertNull(plain.epgId)
        assertNull(plain.logoUrl)
        assertEquals(mapOf("User-Agent" to LIVE_TV_STREAM_USER_AGENT), plain.headers)
        assertEquals(4, channels.map { it.id }.distinct().size)
    }
}
