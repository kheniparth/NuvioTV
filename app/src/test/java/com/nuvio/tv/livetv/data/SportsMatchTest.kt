package com.nuvio.tv.livetv.data

import java.time.Instant
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsMatchTest {
    @Test
    fun `nba matches full names short names and abbreviations`() {
        val game = game(
            "nba",
            "NBA",
            side("Los Angeles Lakers", "Lakers", "LAL"),
            side("Boston Celtics", "Celtics", "BOS"),
        )
        assertEquals(
            listOf("LAL @ BOS", "Lakers vs Celtics HD", "Los Angeles Lakers vs Boston Celtics"),
            names(game, listOf("Lakers vs Celtics HD", "LAL @ BOS", "Los Angeles Lakers vs Boston Celtics", "Lakers only", "News")),
        )
    }

    @Test
    fun `nfl matches team names and two letter abbreviations`() {
        val game = game(
            "nfl",
            "NFL",
            side("Kansas City Chiefs", "Chiefs", "KC"),
            side("Buffalo Bills", "Bills", "BUF"),
        )
        assertEquals(
            listOf("KC at BUF", "Chiefs at Bills"),
            names(game, listOf("Chiefs at Bills", "KC at BUF", "Chiefs only", "PACKERS")),
        )
    }

    @Test
    fun `nhl matches team names and abbreviations`() {
        val game = game(
            "nhl",
            "NHL",
            side("New York Rangers", "Rangers", "NYR"),
            side("Boston Bruins", "Bruins", "BOS"),
        )
        assertEquals(
            listOf("NYR BOS", "Rangers vs Bruins"),
            names(game, listOf("Rangers vs Bruins", "NYR BOS", "Rangers replay")),
        )
    }

    @Test
    fun `mlb matches team names and abbreviations`() {
        val game = game(
            "mlb",
            "MLB",
            side("New York Yankees", "Yankees", "NYY"),
            side("Boston Red Sox", "Red Sox", "BOS"),
        )
        assertEquals(
            listOf("NYY BOS", "Yankees vs Red Sox"),
            names(game, listOf("Yankees vs Red Sox", "NYY BOS", "Yankees classic")),
        )
    }

    @Test
    fun `soccer matches full names short names and abbreviations`() {
        val game = game(
            "eng.1",
            "Premier League",
            side("Arsenal", "Arsenal", "ARS"),
            side("Manchester City", "Man City", "MCI"),
        )
        assertEquals(
            listOf("ARS vs MCI", "Man City vs Arsenal", "Arsenal vs Manchester City"),
            names(game, listOf("Arsenal vs Manchester City", "Man City vs Arsenal", "ARS vs MCI", "Arsenal highlights")),
        )
    }

    @Test
    fun `cricket matches team names and abbreviations`() {
        val game = game(
            "24707",
            "Sri Lanka in Pakistan",
            side("India", "India", "IND"),
            side("Australia", "Australia", "AUS"),
        )
        assertEquals(
            listOf("IND vs AUS", "India vs Australia"),
            names(game, listOf("India vs Australia", "IND vs AUS", "India tour")),
        )
    }

    @Test
    fun `broadcaster name matches when the teams do not`() {
        val game = game(
            "nba",
            "NBA",
            side("Los Angeles Lakers", "Lakers", "LAL"),
            side("Boston Celtics", "Celtics", "BOS"),
        ).copy(broadcasts = listOf("ESPN"))
        assertEquals(
            listOf("LAL @ BOS", "ESPN HD"),
            names(game, listOf("ESPN HD", "LAL @ BOS", "Weather")),
        )
    }

    @Test
    fun `visible games keep live and the next 48 hours`() {
        val now = 1_700_000_000_000L
        val live = game("nba", "NBA", side("A", "Aaa", "AAA"), side("B", "Bbb", "BBB")).copy(id = "live", state = SportsState.Live, startMillis = now)
        val soon = live.copy(id = "soon", state = SportsState.Upcoming, startMillis = now + 3_600_000)
        val later = live.copy(id = "later", state = SportsState.Upcoming, startMillis = now + 72 * 3_600_000)
        val done = live.copy(id = "done", state = SportsState.Finished, startMillis = now - 1_000)
        assertEquals(listOf("live", "soon"), visibleSportsGames(listOf(done, later, soon, live), now).map { it.id })
    }

    @Test
    fun `parses an espn event and the cricket series header`() {
        val payload = """
            {"events":[{"id":"9","date":"2026-11-17T01:30:00Z","name":"Lakers at Celtics","status":{"type":{"state":"in","shortDetail":"Q2"}},
            "competitions":[{"competitors":[
            {"homeAway":"away","score":"40","team":{"displayName":"Los Angeles Lakers","shortDisplayName":"Lakers","abbreviation":"LAL"}},
            {"homeAway":"home","score":"38","team":{"displayName":"Boston Celtics","shortDisplayName":"Celtics","abbreviation":"BOS"}}
            ],"broadcasts":[{"names":["ESPN"]}]}]}]}
        """.trimIndent()
        val games = parseEspnScoreboard(payload, SportsLeague("nba", "NBA", "basketball/nba"))
        assertEquals(1, games.size)
        assertEquals(SportsState.Live, games[0].state)
        assertEquals("LAL", games[0].sides[0].abbreviation)
        assertEquals(listOf("ESPN"), games[0].broadcasts)
        assertEquals(Instant.parse("2026-11-17T01:30:00Z").toEpochMilli(), games[0].startMillis)

        val header = """{"sports":[{"leagues":[{"id":"24707","name":"Sri Lanka in Pakistan","shortName":"SL in PAK"},{"name":"skip"}]}]}"""
        assertEquals(
            listOf(SportsLeague("24707", "SL in PAK", "cricket/24707")),
            parseCricketHeader(header),
        )
    }

    @Test
    fun `five hundred games against six thousand channels finish quickly and stop when cancelled`() = runBlocking {
        val channels = List(6_000) { index ->
            LiveTvChannel(id = "c$index", name = "Guide $index HD", streamUrl = "http://x/$index")
        }
        val games = List(500) { index ->
            game("nba", "NBA", side("Zebras $index", "Zebras", "ZEB"), side("Yaks $index", "Yaks", "YAK"))
                .copy(id = "g$index")
        }
        val elapsed = measureTimeMillis { matchSportsChannelsOnce(games, channels) }
        assertTrue("matching took ${elapsed}ms", elapsed < 2_000)

        var cancelled = false
        val job = launch(Dispatchers.Default) {
            coroutineContext[Job]?.cancel()
            try {
                matchSportsChannelsOnce(games, channels)
            } catch (expected: CancellationException) {
                cancelled = true
            }
        }
        job.join()
        assertTrue(cancelled)
    }

    private fun names(game: SportsGame, channelNames: List<String>) =
        matchSportsChannels(game, channelNames.map { LiveTvChannel(id = it, name = it, streamUrl = "http://x/$it") }).map { it.name }

    private fun side(name: String, shortName: String, abbreviation: String) =
        SportsSide(name, shortName, abbreviation, score = null, isHome = false)

    private fun game(leagueId: String, label: String, away: SportsSide, home: SportsSide) = SportsGame(
        id = "1",
        leagueId = leagueId,
        leagueLabel = label,
        name = "${away.name} at ${home.name}",
        startMillis = 0L,
        state = SportsState.Live,
        statusDetail = "",
        sides = listOf(away, home.copy(isHome = true)),
        broadcasts = emptyList(),
    )
}
