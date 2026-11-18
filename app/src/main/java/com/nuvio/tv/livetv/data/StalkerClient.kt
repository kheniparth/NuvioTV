package com.nuvio.tv.livetv.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

@Singleton
class StalkerClient @Inject constructor(private val http: LiveTvHttp) {
    private var session: Session? = null

    suspend fun channels(settings: LiveTvStalkerSettings): List<LiveTvChannel> {
        val normalized = settings.normalized()
        var auth = token(normalized)
        val genres = request(normalized, auth, "itv", "get_genres").js().array("data").mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.string("id") ?: item.string("alias") ?: return@mapNotNull null
            id to (item.string("title") ?: item.string("name") ?: id)
        }.toMap()
        auth = session?.token ?: auth
        val channels = mutableListOf<LiveTvChannel>()
        for (page in 1..20) {
            val data = request(normalized, auth, "itv", "get_ordered_list", mapOf("p" to page.toString())).js().array("data")
            auth = session?.token ?: auth
            if (data.isEmpty()) break
            data.forEachIndexed { index, element ->
                val item = element as? JsonObject ?: return@forEachIndexed
                val command = item.string("cmd") ?: item.string("mc_cmd") ?: item.string("url") ?: return@forEachIndexed
                val url = command.playableUrl()
                if (url.isBlank()) return@forEachIndexed
                channels += LiveTvChannel(
                    id = "stalker:${item.string("id") ?: "$page:$index"}",
                    name = item.string("name") ?: item.string("title") ?: return@forEachIndexed,
                    streamUrl = url,
                    logoUrl = resolveLogoUrl(item.string("logo") ?: item.string("logo_url"), normalized.portalUrl),
                    group = (item.string("tv_genre_id") ?: item.string("genre_id"))?.let(genres::get),
                    playlistId = STALKER_PLAYLIST_ID,
                    playlistName = "Stalker Portal",
                    headers = headers(normalized, auth),
                    stalkerCommand = command,
                    epgId = item.string("xmltv_id"),
                )
            }
        }
        return channels.distinctBy { it.id }
    }

    /** Portal guide for the next 48 hours, keyed `stalker:<channel id>`. */
    suspend fun epg(settings: LiveTvStalkerSettings): Map<String, List<LiveTvEpgProgram>> {
        val normalized = settings.normalized()
        val body = request(normalized, token(normalized), "itv", "get_epg_info", mapOf("period" to "48"))
        return parseStalkerEpg(body)
    }

    suspend fun vodCatalog(settings: LiveTvStalkerSettings, kind: LiveTvVodKind): LiveTvVodCatalog {
        val normalized = settings.normalized()
        val type = kind.stalkerType()
        val categoryPrefix = "stalker:$type:"
        var auth = token(normalized)
        val categories = request(normalized, auth, type, "get_categories").js().array("data").mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.string("id") ?: item.string("category_id") ?: return@mapNotNull null
            LiveTvVodCategory(
                id = "$categoryPrefix$id",
                name = item.string("title") ?: item.string("name") ?: id,
                remoteId = id,
            )
        }
        auth = session?.token ?: auth
        val items = mutableListOf<LiveTvVodItem>()
        for (page in 1..20) {
            val data = request(normalized, auth, type, "get_ordered_list", mapOf("p" to page.toString())).js().array("data")
            auth = session?.token ?: auth
            if (data.isEmpty()) break
            data.forEach { element ->
                val item = element as? JsonObject ?: return@forEach
                val remoteId = item.string("id") ?: return@forEach
                val name = item.string("name") ?: item.string("title") ?: return@forEach
                items += LiveTvVodItem(
                    id = "stalker-$type:$remoteId",
                    remoteId = remoteId,
                    kind = kind,
                    provider = LiveTvVodProvider.Stalker,
                    name = name,
                    posterUrl = item.string("screenshot_uri") ?: item.string("cover") ?: item.string("logo"),
                    categoryId = item.string("category_id")?.let { "$categoryPrefix$it" },
                    rating = (item.string("rating_imdb") ?: item.string("rating"))
                        ?.toDoubleOrNull()
                        ?.takeIf { it > 0.0 },
                    plot = item.string("description"),
                    year = item.string("year")?.take(4)?.takeIf { year -> year.all(Char::isDigit) },
                    command = item.string("cmd") ?: item.string("url"),
                )
            }
        }
        return LiveTvVodCatalog(categories, items.distinctBy { it.id })
    }

    suspend fun vodEpisodes(settings: LiveTvStalkerSettings, seriesId: String): List<LiveTvVodEpisode> {
        val normalized = settings.normalized()
        var auth = token(normalized)
        val episodes = mutableListOf<LiveTvVodEpisode>()
        for (page in 1..20) {
            val data = request(
                normalized,
                auth,
                "series",
                "get_ordered_list",
                mapOf("movie_id" to seriesId, "season_id" to "0", "episode_id" to "0", "p" to page.toString()),
            ).js().array("data")
            auth = session?.token ?: auth
            if (data.isEmpty()) break
            data.forEachIndexed { index, element ->
                val item = element as? JsonObject ?: return@forEachIndexed
                val id = item.string("id") ?: return@forEachIndexed
                val number = (item.string("episode_number") ?: item.string("episode") ?: item.string("episode_num"))
                    ?.toIntOrNull() ?: (index + 1)
                val season = (item.string("season") ?: item.string("season_number") ?: item.string("series_number"))
                    ?.toIntOrNull() ?: 1
                episodes += LiveTvVodEpisode(
                    id = id,
                    season = season,
                    episodeNumber = number,
                    title = item.string("name")?.takeIf(String::isNotBlank) ?: "Episode $number",
                    plot = item.string("description"),
                    imageUrl = item.string("screenshot_uri"),
                    durationText = item.string("time"),
                    command = item.string("cmd") ?: item.string("url"),
                )
            }
        }
        return episodes.distinctBy { it.id }.sortedWith(compareBy({ it.season }, { it.episodeNumber }))
    }

    suspend fun createVodLink(
        settings: LiveTvStalkerSettings,
        kind: LiveTvVodKind,
        command: String,
    ): LiveTvPlayable {
        val normalized = settings.normalized()
        val direct = command.playableUrl()
        return try {
            val result = request(normalized, token(normalized), kind.stalkerType(), "create_link", mapOf("cmd" to command)).js()
            val url = (result.string("cmd") ?: result.string("url") ?: result.string("stream_url"))?.playableUrl()
            require(!url.isNullOrBlank()) { "The portal returned no stream URL." }
            LiveTvPlayable(url, headers(normalized, session?.token.orEmpty()))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // shortcut: some portals put a ready URL in cmd; play it when create_link fails.
            require(direct.startsWith("http://") || direct.startsWith("https://")) {
                "The portal could not resolve this item."
            }
            LiveTvPlayable(direct, headers(normalized, session?.token.orEmpty()))
        }
    }

    suspend fun createLink(channel: LiveTvChannel, settings: LiveTvStalkerSettings): LiveTvChannel {
        val command = channel.stalkerCommand?.takeIf(String::isNotBlank) ?: return channel
        val normalized = settings.normalized()
        val result = request(normalized, token(normalized), "itv", "create_link", mapOf("cmd" to command)).js()
        val url = (result.string("cmd") ?: result.string("url") ?: result.string("stream_url"))?.playableUrl()
        return channel.copy(
            streamUrl = url?.takeIf(String::isNotBlank) ?: channel.streamUrl,
            headers = channel.headers + headers(normalized, session?.token.orEmpty()),
        )
    }

    private suspend fun token(settings: LiveTvStalkerSettings): String {
        // shortcut: session cache is not locked, call one portal load at a time
        session?.takeIf { it.settings == settings && it.token.isNotBlank() }?.let { return it.token }
        val value = request(settings, null, "stb", "handshake").js().string("token").orEmpty()
        require(value.isNotBlank()) { "Stalker Portal did not return an authorization token." }
        session = Session(settings, value)
        return value
    }

    private suspend fun request(
        settings: LiveTvStalkerSettings,
        token: String?,
        type: String,
        action: String,
        extra: Map<String, String> = emptyMap(),
        allowRefresh: Boolean = true,
    ): JsonObject {
        val parameters = buildMap {
            put("type", type)
            put("action", action)
            put("JsHttpRequest", "1-xml")
            if (!token.isNullOrBlank()) put("token", token)
            if (settings.username.isNotBlank()) put("login", settings.username)
            if (settings.password.isNotBlank()) put("password", settings.password)
            putAll(extra)
        }
        val endpoint = settings.portalEndpoint()
        val query = parameters.entries.joinToString("&", prefix = if ('?' in endpoint) "&" else "?") { (key, value) ->
            "${liveTvEncode(key)}=${liveTvEncode(value)}"
        }
        val response = http.requestRaw("GET", endpoint + query, headers(settings, token))
        if (allowRefresh && !token.isNullOrBlank() && response.authFailed()) {
            if (session?.token == token) session = null
            return request(settings, this.token(settings), type, action, extra, allowRefresh = false)
        }
        if (response.code !in 200..299) error("HTTP ${response.code}")
        return json.parseToJsonElement(response.body).jsonObject
    }

    private fun headers(settings: LiveTvStalkerSettings, token: String?): Map<String, String> = buildMap {
        put("User-Agent", MAG_USER_AGENT)
        put("X-User-Agent", "Model: MAG254; Link: Ethernet")
        put("Referer", settings.portalBaseUrl())
        put("Cookie", "mac=${settings.macAddress}; stb_lang=en; timezone=Europe%2FIstanbul")
        if (!token.isNullOrBlank()) put("Authorization", "Bearer $token")
    }

    private fun LiveTvRawResponse.authFailed(): Boolean {
        if (code == 401) return true
        val text = body.lowercase()
        return "authorization failed" in text || "token expired" in text
    }

    private fun LiveTvStalkerSettings.normalized() = copy(
        portalUrl = portalUrl.trim().trimEnd('/'),
        macAddress = macAddress.trim().uppercase(),
        username = username.trim(),
        password = password.trim(),
    )

    private fun LiveTvStalkerSettings.portalEndpoint(): String {
        val normalized = portalUrl.trim().trimEnd('/')
        return when {
            normalized.contains("portal.php", ignoreCase = true) -> normalized
            normalized.contains("server/load.php", ignoreCase = true) -> normalized
            normalized.endsWith("/c", ignoreCase = true) -> normalized.dropLast(2) + "/portal.php"
            else -> "$normalized/portal.php"
        }
    }

    private fun LiveTvStalkerSettings.portalBaseUrl(): String {
        val base = portalUrl.trim()
            .substringBefore("/portal.php")
            .substringBefore("/server/load.php")
            .trimEnd('/')
            .removeSuffix("/c")
        return "$base/c/"
    }

    private fun String.playableUrl() = trim().removePrefix("ffmpeg ").removePrefix("auto ").substringBefore(' ').trim()

    private fun LiveTvVodKind.stalkerType() = if (this == LiveTvVodKind.Movies) "vod" else "series"

    private fun JsonObject.js() = (this["js"] as? JsonObject) ?: this

    private fun JsonObject.string(key: String) =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

    private fun JsonObject.array(key: String) = (this[key] as? JsonArray)?.toList().orEmpty()

    private data class Session(val settings: LiveTvStalkerSettings, val token: String)

    private companion object {
        const val STALKER_PLAYLIST_ID = "provider:stalker"
        const val MAG_USER_AGENT =
            "Mozilla/5.0 (QtEmbedded; U; Linux; MAG254; en) AppleWebKit/533.3 MAG200 stbapp ver: 4 rev: 2721 Mobile Safari/533.3"
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

internal fun parseStalkerEpg(root: JsonObject): Map<String, List<LiveTvEpgProgram>> {
    val data = ((root["js"] as? JsonObject) ?: root)["data"] as? JsonObject ?: return emptyMap()
    val programs = HashMap<String, MutableList<LiveTvEpgProgram>>()
    data.forEach { (channelId, element) ->
        val items = element as? JsonArray ?: return@forEach
        val id = channelId.trim()
        if (id.isEmpty()) return@forEach
        val key = "stalker:$id".lowercase()
        items.forEach { entry ->
            val item = entry as? JsonObject ?: return@forEach
            val title = item.epgString("name") ?: return@forEach
            val start = item.epgString("start_timestamp")?.toLongOrNull()?.times(1000L) ?: return@forEach
            val stop = item.epgString("stop_timestamp")?.toLongOrNull()?.times(1000L) ?: return@forEach
            if (stop <= start) return@forEach
            programs.getOrPut(key) { mutableListOf() } += LiveTvEpgProgram(
                title = title,
                startMillis = start,
                endMillis = stop,
                description = item.epgString("descr") ?: item.epgString("description"),
                category = item.epgString("category"),
            )
        }
    }
    return programs.mapValues { (_, list) -> list.sortedBy { it.startMillis }.distinctBy { it.startMillis } }
}

private fun JsonObject.epgString(key: String) =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
