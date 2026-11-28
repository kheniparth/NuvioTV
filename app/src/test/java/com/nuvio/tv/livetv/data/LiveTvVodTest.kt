package com.nuvio.tv.livetv.data

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvVodTest {
    private val http = LiveTvHttp(OkHttpClient())
    private val xtream = XtreamClient(http)
    private val stalker = StalkerClient(http)

    @Test
    fun `search filters by name and category`() {
        val items = listOf(
            item("1", "Alpha", "c1"),
            item("2", "Beta", "c2"),
            item("3", "ALPHABET", "c2"),
        )

        assertEquals(listOf("1"), filterLiveTvVodItems(items, "c1", "").map { it.id })
        assertEquals(listOf("1", "3"), filterLiveTvVodItems(items, null, " alpha ").map { it.id })
        assertEquals(listOf("3"), filterLiveTvVodItems(items, "c2", "alpha").map { it.id })
    }

    @Test
    fun `xtream catalog maps movies and builds the movie url`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"category_id":"7","category_name":"Drama"},{"category_id":"9","category_name":"Unused"}]"""))
            server.enqueue(
                MockResponse().setBody(
                    """[{"name":"Film","stream_id":"11","category_id":"7","stream_icon":"http://img","rating":"7.5","plot":"Plot","year":"2024","container_extension":".mkv"}]"""
                )
            )
            val settings = xtreamSettings(server)

            val catalog = xtream.vodCatalog(settings, LiveTvVodKind.Movies)

            assertEquals(LiveTvVodCategory("xtream:vod:7", "Drama", "7"), catalog.categories.single())
            val movie = catalog.items.single()
            assertEquals("xtream-vod:11", movie.id)
            assertEquals(LiveTvVodProvider.Xtream, movie.provider)
            assertEquals("http://img", movie.posterUrl)
            assertEquals("xtream:vod:7", movie.categoryId)
            assertEquals(7.5, movie.rating!!, 0.001)
            assertEquals("mkv", movie.extension)
            assertEquals("get_vod_categories", server.takeRequest().requestUrl?.queryParameter("action"))
            assertEquals("get_vod_streams", server.takeRequest().requestUrl?.queryParameter("action"))
            assertEquals("${settings.serverUrl}/movie/u%20name/p%40ss/11.mkv", xtream.movieStreamUrl(settings, movie))
        }
    }

    @Test
    fun `xtream series episodes are season ordered and use the series url`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"episodes":{"2":[{"id":"e2","title":"Two","episode_num":1,"container_extension":"mp4","info":{"plot":"p","duration":"45m","movie_image":"http://e2"}}],""" +
                        """"1":[{"id":"e1","title":"One","episode_num":2,"container_extension":"mkv","direct_source":"http://direct/e1.mkv"}]}}"""
                )
            )

            val settings = xtreamSettings(server)
            val episodes = xtream.seriesEpisodes(settings, "42")

            assertEquals(listOf("e1", "e2"), episodes.map { it.id })
            assertEquals(1, episodes.first().season)
            assertEquals(2, episodes.first().episodeNumber)
            assertEquals("http://direct/e1.mkv", episodes.first().streamUrl)
            assertEquals("${settings.serverUrl}/series/u%20name/p%40ss/e2.mp4", episodes.last().streamUrl)
            assertEquals("http://e2", episodes.last().imageUrl)
        }
    }

    @Test
    fun `stalker catalog lists vod categories and items with their portal command`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"js":{"token":"tok"}}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[{"id":"5","title":"Films"}]}}"""))
            server.enqueue(
                MockResponse().setBody(
                    """{"js":{"data":[{"id":"100","name":"Movie","category_id":"5","screenshot_uri":"http://s","rating_imdb":"8.1","description":"D","year":"2020","cmd":"ffmpeg http://cdn/movie.mp4"}]}}"""
                )
            )
            server.enqueue(MockResponse().setBody("""{"js":{"data":[]}}"""))
            val settings = stalkerSettings(server)

            val catalog = stalker.vodCatalog(settings, LiveTvVodKind.Movies)

            assertEquals(LiveTvVodCategory("stalker:vod:5", "Films", "5"), catalog.categories.single())
            val movie = catalog.items.single()
            assertEquals("stalker-vod:100", movie.id)
            assertEquals(LiveTvVodProvider.Stalker, movie.provider)
            assertEquals("stalker:vod:5", movie.categoryId)
            assertEquals(8.1, movie.rating!!, 0.001)
            assertEquals("ffmpeg http://cdn/movie.mp4", movie.command)
            assertEquals("handshake", server.takeRequest().requestUrl?.queryParameter("action"))
            val categoriesRequest = server.takeRequest()
            assertEquals("vod", categoriesRequest.requestUrl?.queryParameter("type"))
            assertEquals("get_categories", categoriesRequest.requestUrl?.queryParameter("action"))
            val pageRequest = server.takeRequest()
            assertEquals("vod", pageRequest.requestUrl?.queryParameter("type"))
            assertEquals("get_ordered_list", pageRequest.requestUrl?.queryParameter("action"))
        }
    }

    @Test
    fun `stalker series episodes keep season and episode numbers`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"js":{"token":"tok"}}"""))
            server.enqueue(
                MockResponse().setBody(
                    """{"js":{"data":[{"id":"e9","name":"Finale","series_number":"2","episode_number":"3","screenshot_uri":"http://e9","time":"47m","cmd":"ffmpeg http://cdn/e9.mp4"}]}}"""
                )
            )
            server.enqueue(MockResponse().setBody("""{"js":{"data":[]}}"""))

            val episode = stalker.vodEpisodes(stalkerSettings(server), "42").single()

            assertEquals("e9", episode.id)
            assertEquals(2, episode.season)
            assertEquals(3, episode.episodeNumber)
            assertEquals("47m", episode.durationText)
            assertEquals("ffmpeg http://cdn/e9.mp4", episode.command)
            assertEquals("handshake", server.takeRequest().requestUrl?.queryParameter("action"))
            val pageRequest = server.takeRequest()
            assertEquals("series", pageRequest.requestUrl?.queryParameter("type"))
            assertEquals("get_ordered_list", pageRequest.requestUrl?.queryParameter("action"))
            assertEquals("42", pageRequest.requestUrl?.queryParameter("movie_id"))
        }
    }

    @Test
    fun `stalker vod playback resolves create link with the portal headers`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"js":{"token":"tok"}}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"cmd":"http://cdn/play.mp4"}}"""))

            val playable = stalker.createVodLink(
                stalkerSettings(server),
                LiveTvVodKind.Movies,
                "ffmpeg http://cdn/movie.mp4",
            )

            assertEquals("http://cdn/play.mp4", playable.url)
            assertEquals("Bearer tok", playable.headers["Authorization"])
            assertEquals("handshake", server.takeRequest().requestUrl?.queryParameter("action"))
            val link = server.takeRequest()
            assertEquals("vod", link.requestUrl?.queryParameter("type"))
            assertEquals("create_link", link.requestUrl?.queryParameter("action"))
            assertEquals("ffmpeg http://cdn/movie.mp4", link.requestUrl?.queryParameter("cmd"))
            assertTrue(link.getHeader("Cookie").orEmpty().contains("mac=00:1A:79:11:22:33"))
        }
    }

    private fun xtreamSettings(server: MockWebServer) = LiveTvXtreamSettings(
        serverUrl = server.url("/").toString().trimEnd('/'),
        username = "u name",
        password = "p@ss",
    )

    private fun stalkerSettings(server: MockWebServer) = LiveTvStalkerSettings(
        portalUrl = server.url("/").toString().trimEnd('/'),
        macAddress = "00:1a:79:11:22:33",
    )

    private fun item(id: String, name: String, categoryId: String) = LiveTvVodItem(
        id = id,
        remoteId = id,
        kind = LiveTvVodKind.Movies,
        provider = LiveTvVodProvider.Xtream,
        name = name,
        categoryId = categoryId,
    )
}
