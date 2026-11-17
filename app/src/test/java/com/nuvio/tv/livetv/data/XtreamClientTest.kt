package com.nuvio.tv.livetv.data

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamClientTest {
    private val http = LiveTvHttp(OkHttpClient())
    private val client = XtreamClient(http)

    @Test
    fun `live url encodes credentials and strips player_api from the server`() {
        val settings = LiveTvXtreamSettings(
            serverUrl = "http://panel.example/player_api.php/",
            username = "user name",
            password = "p@ss",
        )
        assertEquals(
            "http://panel.example/live/user%20name/p%40ss/12.ts",
            client.liveStreamUrl(settings, "12", "ts"),
        )
    }

    @Test
    fun `categories and live streams use the panel link and hls when ts is not allowed`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"category_id":"2","category_name":"Sport"}]"""))
            val settings = LiveTvXtreamSettings(server.url("/").toString().trimEnd('/'), "u", "p")
            assertEquals(listOf(XtreamCategory("2", "Sport")), client.categories(settings))
            assertEquals("get_live_categories", server.takeRequest().requestUrl?.queryParameter("action"))

            server.enqueue(MockResponse().setBody("""[{"category_id":"2","category_name":"Sport"}]"""))
            server.enqueue(MockResponse().setBody("""{"user_info":{"allowed_output_formats":["m3u8"]}}"""))
            server.enqueue(MockResponse().setBody("""[{"name":"Match","stream_id":"5","category_id":"2","stream_icon":"http://logo","epg_channel_id":"m1"}]"""))
            val channel = client.liveChannels(settings).single()

            assertEquals("xtream:5", channel.id)
            assertEquals("Match", channel.name)
            assertEquals("Sport", channel.group)
            assertEquals("m1", channel.epgId)
            assertEquals("http://logo", channel.logoUrl)
            assertTrue(channel.streamUrl.endsWith("/live/u/p/5.m3u8"))
            assertEquals("get_live_categories", server.takeRequest().requestUrl?.queryParameter("action"))
            assertEquals(null, server.takeRequest().requestUrl?.queryParameter("action"))
            assertEquals("get_live_streams", server.takeRequest().requestUrl?.queryParameter("action"))
        }
    }
}
