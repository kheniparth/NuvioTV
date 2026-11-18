package com.nuvio.tv.livetv.data

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StalkerClientTest {
    @Test
    fun `handshake lists channels with the mac cookie and create_link strips the player prefix`() = runTest {
        MockWebServer().use { server ->
            val client = stalker()
            val settings = settings(server)
            server.enqueue(MockResponse().setBody("""{"js":{"token":"tok"}}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[{"id":"1","title":"News"}]}}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[{"id":"9","name":"BBC","cmd":"ffmpeg http://cdn/bbc.ts","tv_genre_id":"1","logo":"http://logo","xmltv_id":"bbc"}]}}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[]}}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"cmd":"ffmpeg http://cdn/play.ts"}}"""))

            val channel = client.channels(settings).single()
            val playable = client.createLink(channel, settings)
            val handshake = server.takeRequest()
            val genres = server.takeRequest()
            val page = server.takeRequest()
            server.takeRequest()
            val link = server.takeRequest()

            assertEquals("handshake", handshake.requestUrl?.queryParameter("action"))
            assertEquals("mac=00:1A:79:11:22:33; stb_lang=en; timezone=Europe%2FIstanbul", handshake.getHeader("Cookie"))
            assertTrue(handshake.getHeader("User-Agent").orEmpty().contains("MAG254"))
            assertEquals("get_genres", genres.requestUrl?.queryParameter("action"))
            assertEquals("Bearer tok", page.getHeader("Authorization"))
            assertEquals("stalker:9", channel.id)
            assertEquals("News", channel.group)
            assertEquals("bbc", channel.epgId)
            assertEquals("ffmpeg http://cdn/bbc.ts", channel.stalkerCommand)
            assertEquals("create_link", link.requestUrl?.queryParameter("action"))
            assertEquals("ffmpeg http://cdn/bbc.ts", link.requestUrl?.queryParameter("cmd"))
            assertEquals("http://cdn/play.ts", playable.streamUrl)
            assertEquals("Bearer tok", playable.headers["Authorization"])
        }
    }

    @Test
    fun `relative portal logo resolves and get_epg_info uses period 48`() = runTest {
        MockWebServer().use { server ->
            val settings = settings(server)
            server.enqueue(MockResponse().setBody("""{"js":{"token":"tok"}}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[]}}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[{"id":"9","name":"BBC","cmd":"http://cdn/bbc.ts","logo":"/misc/logos/9.png"}]}}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[]}}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":{"9":[{"name":"News","start_timestamp":"100","stop_timestamp":"200","descr":"Now"}]}}}"""))
            val client = stalker()

            val channel = client.channels(settings).single()
            val epg = client.epg(settings)
            val requests = List(5) { server.takeRequest() }

            assertEquals(server.url("/misc/logos/9.png").toString(), channel.logoUrl)
            assertEquals("get_epg_info", requests.last().requestUrl?.queryParameter("action"))
            assertEquals("48", requests.last().requestUrl?.queryParameter("period"))
            assertEquals("News", epg["stalker:9"]?.single()?.title)
            assertEquals(100_000L, epg["stalker:9"]?.single()?.startMillis)
            assertEquals("Now", epg["stalker:9"]?.single()?.description)
        }
    }

    @Test
    fun `401 and an expired token each handshake once and retry create_link`() = runTest {
        MockWebServer().use { server ->
            val settings = settings(server)
            server.enqueue(MockResponse().setBody("""{"js":{"token":"old"}}"""))
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("""{"js":{"token":"new"}}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"cmd":"http://cdn/after-401.ts"}}"""))
            val denied = stalker().createLink(channel(), settings)

            server.enqueue(MockResponse().setBody("""{"js":{"token":"old"}}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"error":"Authorization failed."}}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"token":"fresh"}}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"cmd":"http://cdn/after-expire.ts"}}"""))
            val expired = stalker().createLink(channel(), settings)

            assertEquals("handshake", server.takeRequest().requestUrl?.queryParameter("action"))
            assertEquals("old", server.takeRequest().requestUrl?.queryParameter("token"))
            assertEquals("handshake", server.takeRequest().requestUrl?.queryParameter("action"))
            assertEquals("new", server.takeRequest().requestUrl?.queryParameter("token"))
            assertEquals("http://cdn/after-401.ts", denied.streamUrl)
            assertEquals("Bearer new", denied.headers["Authorization"])

            assertEquals("handshake", server.takeRequest().requestUrl?.queryParameter("action"))
            assertEquals("old", server.takeRequest().requestUrl?.queryParameter("token"))
            assertEquals("handshake", server.takeRequest().requestUrl?.queryParameter("action"))
            assertEquals("fresh", server.takeRequest().requestUrl?.queryParameter("token"))
            assertEquals("http://cdn/after-expire.ts", expired.streamUrl)
            assertEquals("Bearer fresh", expired.headers["Authorization"])
        }
    }

    private fun stalker() = StalkerClient(LiveTvHttp(OkHttpClient()))

    private fun settings(server: MockWebServer) = LiveTvStalkerSettings(
        portalUrl = server.url("/").toString().trimEnd('/'),
        macAddress = "00:1a:79:11:22:33",
    )

    private fun channel() = LiveTvChannel(
        id = "stalker:1",
        name = "One",
        streamUrl = "http://cdn/a.ts",
        stalkerCommand = "ffmpeg http://cdn/a.ts",
    )
}
