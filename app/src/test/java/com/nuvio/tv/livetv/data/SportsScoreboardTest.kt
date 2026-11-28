package com.nuvio.tv.livetv.data

import java.time.Instant
import java.util.Collections
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsScoreboardTest {
    private val now = 1_700_000_000_000L

    @Test
    fun `scoreboard dates cover today and tomorrow only`() {
        val days = espnScoreboardDates(now)
        assertEquals(2, days.size)
        assertTrue(days[0] < days[1])
    }

    @Test
    fun `per day scores load when a date range is rejected`() = runTest {
        val days = espnScoreboardDates(now)
        val seen = Collections.synchronizedList(mutableListOf<String>())
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val dates = request.requestUrl?.queryParameter("dates").orEmpty()
                    seen += dates
                    if ('-' in dates || dates == days[1]) {
                        return MockResponse().setResponseCode(400).setBody("Failed to get events endpoint")
                    }
                    return MockResponse().setBody(scoreboard(now + 3_600_000))
                }
            }
            val games = SportsScoreboard(LiveTvHttp(OkHttpClient())).games(source(server), now)

            assertTrue(seen.none { '-' in it })
            assertEquals(days.toSet(), seen.toSet())
            assertEquals(listOf("9"), games.map { it.id })
            assertEquals("NBA", games.single().leagueLabel)
        }
    }

    @Test
    fun `every failed scoreboard request shows an error`() = runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    MockResponse().setResponseCode(400).setBody("Failed to get events endpoint")
            }
            val error = runCatching {
                SportsScoreboard(LiveTvHttp(OkHttpClient())).games(source(server), now)
            }.exceptionOrNull()

            assertEquals("Couldn't load scores", error?.message)
        }
    }

    private fun source(server: MockWebServer) = SportsScoreSource(
        name = "ESPN",
        scoreboardBase = server.url("/").toString().trimEnd('/'),
        leagues = listOf(SportsLeague("nba", "NBA", "basketball/nba")),
        cricketHeaderUrl = null,
    )

    private fun scoreboard(startMillis: Long): String {
        val start = Instant.ofEpochMilli(startMillis)
        return """
            {"events":[{"id":"9","date":"$start","name":"Lakers at Celtics","status":{"type":{"state":"pre","shortDetail":"7:00 PM"}},
            "competitions":[{"competitors":[
            {"homeAway":"away","team":{"displayName":"Los Angeles Lakers","shortDisplayName":"Lakers","abbreviation":"LAL"}},
            {"homeAway":"home","team":{"displayName":"Boston Celtics","shortDisplayName":"Celtics","abbreviation":"BOS"}}
            ]}]}]}
        """.trimIndent()
    }
}
