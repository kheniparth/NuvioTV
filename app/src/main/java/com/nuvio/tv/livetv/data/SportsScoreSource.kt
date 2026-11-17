package com.nuvio.tv.livetv.data

import android.util.Log
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Public scoreboard the Sports tab reads. Cricket series ids change, so they come from [cricketHeaderUrl]. */
data class SportsScoreSource(
    val name: String,
    val scoreboardBase: String,
    val leagues: List<SportsLeague>,
    val cricketHeaderUrl: String?,
)

data class SportsLeague(val id: String, val label: String, val path: String)

enum class SportsState { Upcoming, Live, Finished }

data class SportsSide(
    val name: String,
    val shortName: String,
    val abbreviation: String,
    val score: String?,
    val isHome: Boolean,
)

data class SportsGame(
    val id: String,
    val leagueId: String,
    val leagueLabel: String,
    val name: String,
    val startMillis: Long,
    val state: SportsState,
    val statusDetail: String,
    val sides: List<SportsSide>,
    val broadcasts: List<String>,
)

internal data class EspnScoreboardDates(val range: String, val days: List<String>)

/** ESPN site API. Soccer slugs and the big US leagues are fixed; cricket/eng.1 and cricket/ipl 404. */
internal val EspnSportsScoreSource = SportsScoreSource(
    name = "ESPN",
    scoreboardBase = "https://site.api.espn.com/apis/site/v2/sports",
    cricketHeaderUrl = "https://site.web.api.espn.com/apis/personalized/v2/scoreboard/header?sport=cricket",
    leagues = listOf(
        SportsLeague("nba", "NBA", "basketball/nba"),
        SportsLeague("nfl", "NFL", "football/nfl"),
        SportsLeague("nhl", "NHL", "hockey/nhl"),
        SportsLeague("mlb", "MLB", "baseball/mlb"),
        SportsLeague("eng.1", "Premier League", "soccer/eng.1"),
        SportsLeague("esp.1", "LaLiga", "soccer/esp.1"),
        SportsLeague("ita.1", "Serie A", "soccer/ita.1"),
        SportsLeague("ger.1", "Bundesliga", "soccer/ger.1"),
        SportsLeague("fra.1", "Ligue 1", "soccer/fra.1"),
        SportsLeague("uefa.champions", "Champions League", "soccer/uefa.champions"),
        SportsLeague("uefa.europa", "Europa League", "soccer/uefa.europa"),
        SportsLeague("idn.1", "Liga 1 Indonesia", "soccer/idn.1"),
    ),
)

class SportsScoreboard @Inject constructor(private val http: LiveTvHttp) {
    suspend fun games(nowMillis: Long = System.currentTimeMillis()): List<SportsGame> {
        val source = EspnSportsScoreSource
        val dates = espnScoreboardDates(nowMillis)
        val leagues = source.leagues + cricketLeagues(source)
        val results = coroutineScope {
            leagues.map { league ->
                async {
                    runCatching { fetchLeague(source, league, dates) }.onFailure { error ->
                        if (error is CancellationException) throw error
                        Log.w(TAG, "ESPN ${league.id} failed", error)
                    }
                }
            }.awaitAll()
        }
        if (results.isNotEmpty() && results.all { it.isFailure }) {
            throw results.first().exceptionOrNull() ?: IllegalStateException("No scores")
        }
        return visibleSportsGames(results.flatMap { it.getOrNull().orEmpty() }, nowMillis)
    }

    private suspend fun cricketLeagues(source: SportsScoreSource): List<SportsLeague> {
        val url = source.cricketHeaderUrl ?: return emptyList()
        return runCatching { parseCricketHeader(http.getText(url, ESPN_HEADERS)) }
            .onFailure { error ->
                if (error is CancellationException) throw error
                Log.w(TAG, "ESPN cricket header failed", error)
            }
            .getOrDefault(emptyList())
    }

    private suspend fun fetchLeague(
        source: SportsScoreSource,
        league: SportsLeague,
        dates: EspnScoreboardDates,
    ): List<SportsGame> {
        val base = "${source.scoreboardBase}/${league.path}/scoreboard"
        suspend fun attempt(url: String): List<SportsGame>? = try {
            parseEspnScoreboard(http.getText(url, ESPN_HEADERS), league)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "ESPN ${league.id} $url", error)
            null
        }

        attempt("$base?dates=${dates.range}&limit=1000")?.takeIf { it.isNotEmpty() }?.let { return it }
        val perDay = mutableListOf<SportsGame>()
        var anyDaySucceeded = false
        for (day in dates.days) {
            attempt("$base?dates=$day")?.let { events ->
                anyDaySucceeded = true
                perDay += events
            }
        }
        if (perDay.isNotEmpty()) return perDay
        attempt(base)?.let { return it }
        if (anyDaySucceeded) return emptyList()
        error("No scoreboard for ${league.label}")
    }

    private companion object {
        const val TAG = "SportsScoreboard"
    }
}

internal fun espnScoreboardDates(nowMillis: Long): EspnScoreboardDates {
    val today = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.of("America/New_York")).toLocalDate()
    val days = (-1..2).map { offset ->
        val date = today.plusDays(offset.toLong())
        "%04d%02d%02d".format(date.year, date.monthValue, date.dayOfMonth)
    }
    return EspnScoreboardDates(range = "${days.first()}-${days.last()}", days = days)
}

/** Live games, plus upcoming ones that start within 48 hours. */
internal fun visibleSportsGames(games: List<SportsGame>, nowMillis: Long): List<SportsGame> {
    return games
        .filter { game ->
            when (game.state) {
                SportsState.Live -> true
                SportsState.Upcoming -> game.startMillis - nowMillis <= UPCOMING_WINDOW_MILLIS
                SportsState.Finished -> false
            }
        }
        .distinctBy { "${it.leagueId}:${it.id}" }
        .sortedWith(compareBy<SportsGame> { if (it.state == SportsState.Live) 0 else 1 }.thenBy { it.startMillis })
}

internal fun parseEspnScoreboard(payload: String, league: SportsLeague): List<SportsGame> {
    val root = sportsJson.parseToJsonElement(payload) as? JsonObject ?: return emptyList()
    val raw = root.array("events")
    var firstFailure: String? = null
    val parsed = raw.mapNotNull { event ->
        runCatching { parseEvent(event, league) }
            .onFailure { if (firstFailure == null) firstFailure = it.message ?: it::class.simpleName }
            .getOrNull()
    }
    if (raw.isNotEmpty() && parsed.isEmpty()) {
        error("0 of ${raw.size} events readable (${firstFailure ?: "unknown"})")
    }
    return parsed
}

internal fun parseCricketHeader(payload: String): List<SportsLeague> {
    val root = sportsJson.parseToJsonElement(payload) as? JsonObject ?: return emptyList()
    return root.array("sports").flatMap { it.array("leagues") }.mapNotNull { league ->
        val id = league.string("id") ?: return@mapNotNull null
        val label = league.string("shortName") ?: league.string("name") ?: return@mapNotNull null
        SportsLeague(id = id, label = label, path = "cricket/$id")
    }.distinctBy { it.id }
}

internal fun normalizeSportsName(value: String): String =
    value.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .split(' ')
        .filter { it.isNotBlank() && it !in channelNoiseTokens }
        .joinToString(" ")

/**
 * Channels named for both teams (full name, short name, or abbreviation) rank first,
 * then channels whose name is one of the broadcaster names.
 */
internal fun matchSportsChannels(
    game: SportsGame,
    channels: List<LiveTvChannel>,
    limit: Int = 8,
): List<LiveTvChannel> {
    val teamKeys = game.sides.map(::sportsTeamKeys).filter { it.isNotEmpty() }
    val broadcastKeys = game.broadcasts.map(::normalizeSportsName).filter { it.length >= 3 }
    if (teamKeys.size < 2 && broadcastKeys.isEmpty()) return emptyList()
    return channels.map { it to normalizeSportsName(it.name) }
        .mapNotNull { (channel, normalized) ->
            if (normalized.isEmpty()) return@mapNotNull null
            val padded = " $normalized "
            val bothTeams = teamKeys.size >= 2 && teamKeys.all { keys ->
                keys.any { padded.contains(" $it ") }
            }
            val score = when {
                bothTeams -> 3
                broadcastKeys.any { it == normalized } -> 2
                broadcastKeys.any { padded.contains(" $it ") } -> 1
                else -> 0
            }
            if (score == 0) null else channel to score
        }
        .sortedWith(compareByDescending<Pair<LiveTvChannel, Int>> { it.second }.thenBy { it.first.name.length })
        .take(limit)
        .map { it.first }
}

private fun sportsTeamKeys(side: SportsSide): List<String> = listOf(
    side.name to 3,
    side.shortName to 3,
    side.abbreviation to 2,
).mapNotNull { (raw, min) -> normalizeSportsName(raw).takeIf { it.length >= min } }.distinct()

private fun parseEvent(event: JsonObject, league: SportsLeague): SportsGame? {
    val id = event.string("id") ?: return null
    val rawDate = event.string("date")
    val startMillis = rawDate?.let(::parseEspnDate) ?: error("unreadable date: $rawDate")
    val statusType = (event.obj("status") ?: event.array("competitions").firstOrNull()?.obj("status"))?.obj("type")
    val state = when (statusType?.string("state")) {
        "in" -> SportsState.Live
        "post" -> SportsState.Finished
        else -> SportsState.Upcoming
    }
    val competitions = event.array("competitions")
    val sides = competitions.firstOrNull()?.array("competitors").orEmpty().mapNotNull { item ->
        val team = item.obj("team")
        val name = team?.string("displayName") ?: return@mapNotNull null
        SportsSide(
            name = name,
            shortName = team.string("shortDisplayName") ?: name,
            abbreviation = team.string("abbreviation") ?: team.string("shortDisplayName").orEmpty(),
            score = item.string("score"),
            isHome = item.string("homeAway") == "home",
        )
    }.sortedBy { if (it.isHome) 1 else 0 }
    val broadcasts = competitions.flatMap { competition ->
        val named = competition.array("broadcasts").flatMap { broadcast ->
            (broadcast["names"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        }
        val geo = competition.array("geoBroadcasts").mapNotNull { it.obj("media")?.string("shortName") }
        named + geo
    }.map(String::trim).filter(String::isNotBlank).distinctBy(String::lowercase)
    return SportsGame(
        id = id,
        leagueId = league.id,
        leagueLabel = league.label,
        name = event.string("name") ?: event.string("shortName") ?: sides.joinToString(" vs ") { it.name },
        startMillis = startMillis,
        state = state,
        statusDetail = statusType?.string("shortDetail") ?: statusType?.string("detail").orEmpty(),
        sides = sides,
        broadcasts = broadcasts,
    )
}

private fun parseEspnDate(value: String): Long? {
    val trimmed = value.trim()
    runCatching { Instant.parse(trimmed).toEpochMilli() }.getOrNull()?.let { return it }
    val match = ESPN_DATE.find(trimmed) ?: return null
    val group = match.groupValues
    return LocalDateTime.of(
        group[1].toInt(),
        group[2].toInt(),
        group[3].toInt(),
        group[4].toInt(),
        group[5].toInt(),
        group.getOrNull(6)?.toIntOrNull() ?: 0,
    ).toInstant(ZoneOffset.UTC).toEpochMilli()
}

private fun JsonObject.string(key: String) =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

private fun JsonObject.obj(key: String) = this[key] as? JsonObject

private fun JsonObject.array(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

private val sportsJson = Json { ignoreUnknownKeys = true; isLenient = true }

private val ESPN_DATE = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?""")

private val channelNoiseTokens = setOf(
    "hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "h265", "h264", "1080p", "720p", "50fps", "60fps",
    "raw", "vip", "backup", "live", "tv",
)

private val ESPN_HEADERS = mapOf(
    "Accept" to "application/json",
    "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36",
)

private const val UPCOMING_WINDOW_MILLIS = 48L * 60L * 60L * 1000L
