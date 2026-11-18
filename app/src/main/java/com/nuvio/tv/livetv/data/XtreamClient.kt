package com.nuvio.tv.livetv.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class XtreamCategory(val id: String, val name: String)

@Singleton
class XtreamClient @Inject constructor(private val http: LiveTvHttp) {
    suspend fun categories(settings: LiveTvXtreamSettings): List<XtreamCategory> =
        request(settings.normalized(), "get_live_categories").arrayOrEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.string("category_id") ?: item.string("id") ?: return@mapNotNull null
            XtreamCategory(id, item.string("category_name") ?: item.string("name") ?: id)
        }

    suspend fun liveChannels(settings: LiveTvXtreamSettings): List<LiveTvChannel> {
        val normalized = settings.normalized()
        val categories = categories(normalized).associate { it.id to it.name }
        val extension = liveExtension(normalized)
        return request(normalized, "get_live_streams").arrayOrEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val name = item.string("name") ?: return@mapNotNull null
            val streamId = item.string("stream_id") ?: item.string("id") ?: return@mapNotNull null
            LiveTvChannel(
                id = "xtream:$streamId",
                name = name,
                streamUrl = liveStreamUrl(normalized, streamId, extension),
                logoUrl = resolveLogoUrl(item.string("stream_icon") ?: item.string("logo"), normalized.serverUrl),
                group = item.string("category_id")?.let(categories::get),
                playlistId = XTREAM_PLAYLIST_ID,
                playlistName = "Xtream",
                headers = mapOf("User-Agent" to LIVE_TV_STREAM_USER_AGENT, "Accept" to "*/*"),
                streamType = extension,
                epgId = item.string("epg_channel_id"),
            )
        }.distinctBy { it.streamUrl }
    }

    suspend fun vodCatalog(settings: LiveTvXtreamSettings, kind: LiveTvVodKind): LiveTvVodCatalog {
        val normalized = settings.normalized()
        val (categoryAction, listAction) = when (kind) {
            LiveTvVodKind.Movies -> "get_vod_categories" to "get_vod_streams"
            LiveTvVodKind.Series -> "get_series_categories" to "get_series"
        }
        val categoryPrefix = vodCategoryPrefix(kind)
        val categories = request(normalized, categoryAction).arrayOrEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.string("category_id") ?: return@mapNotNull null
            LiveTvVodCategory("$categoryPrefix$id", item.string("category_name") ?: id, id)
        }
        val items = request(normalized, listAction).arrayOrEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val name = item.string("name") ?: item.string("title") ?: return@mapNotNull null
            val remoteId = when (kind) {
                LiveTvVodKind.Movies -> item.string("stream_id")
                LiveTvVodKind.Series -> item.string("series_id")
            } ?: return@mapNotNull null
            LiveTvVodItem(
                id = "${vodItemPrefix(kind)}:$remoteId",
                remoteId = remoteId,
                kind = kind,
                provider = LiveTvVodProvider.Xtream,
                name = name,
                posterUrl = item.string("stream_icon") ?: item.string("cover"),
                categoryId = item.string("category_id")?.let { "$categoryPrefix$it" },
                rating = item.string("rating")?.toDoubleOrNull()?.takeIf { it > 0.0 },
                plot = item.string("plot"),
                year = (item.string("year") ?: item.string("releaseDate") ?: item.string("release_date"))
                    ?.take(4)
                    ?.takeIf { year -> year.all(Char::isDigit) },
                extension = item.string("container_extension")?.trim()?.trimStart('.')?.ifBlank { null },
            )
        }.distinctBy { it.id }
        val usedCategoryIds = items.mapNotNull { it.categoryId }.toSet()
        return LiveTvVodCatalog(categories.filter { it.id in usedCategoryIds }, items)
    }

    suspend fun seriesEpisodes(settings: LiveTvXtreamSettings, seriesId: String): List<LiveTvVodEpisode> {
        val normalized = settings.normalized()
        val root = request(normalized, "get_series_info", mapOf("series_id" to seriesId)) as? JsonObject
            ?: return emptyList()
        val episodes = root["episodes"]
        val groups: List<Pair<String?, JsonElement>> = when (episodes) {
            is JsonObject -> episodes.entries.map { (season, value) -> season to value }
            is JsonArray -> episodes.map { null to it }
            else -> emptyList()
        }
        return groups.flatMap { (seasonKey, value) ->
            val list = (value as? JsonArray)?.toList() ?: listOf(value)
            list.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val id = item.string("id") ?: return@mapNotNull null
                val info = item["info"] as? JsonObject
                val season = item.string("season")?.toIntOrNull() ?: seasonKey?.toIntOrNull() ?: 1
                val number = item.string("episode_num")?.toIntOrNull() ?: 0
                val extension = item.string("container_extension")?.trim()?.trimStart('.')?.ifBlank { null } ?: "mp4"
                LiveTvVodEpisode(
                    id = id,
                    season = season,
                    episodeNumber = number,
                    title = item.string("title")?.takeIf(String::isNotBlank) ?: "Episode $number",
                    plot = info?.string("plot"),
                    imageUrl = info?.string("movie_image"),
                    durationText = info?.string("duration"),
                    streamUrl = item.string("direct_source")
                        ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                        ?: normalized.seriesStreamUrl(id, extension),
                    extension = extension,
                )
            }
        }.sortedWith(compareBy({ it.season }, { it.episodeNumber }))
    }

    fun movieStreamUrl(settings: LiveTvXtreamSettings, item: LiveTvVodItem): String {
        val normalized = settings.normalized()
        val extension = item.extension ?: "mp4"
        return "${normalized.serverUrl}/movie/${liveTvEncode(normalized.username)}/${liveTvEncode(normalized.password)}/${liveTvEncode(item.remoteId)}.${liveTvEncode(extension)}"
    }

    val vodHeaders: Map<String, String> get() = playlistHeaders

    fun liveStreamUrl(settings: LiveTvXtreamSettings, streamId: String, extension: String): String {
        val normalized = settings.normalized()
        return "${normalized.serverUrl}/live/${liveTvEncode(normalized.username)}/${liveTvEncode(normalized.password)}/${liveTvEncode(streamId)}.${liveTvEncode(extension)}"
    }

    private suspend fun liveExtension(settings: LiveTvXtreamSettings): String {
        val formats = try {
            val url = "${settings.serverUrl}/player_api.php?username=${liveTvEncode(settings.username)}&password=${liveTvEncode(settings.password)}"
            val login = json.parseToJsonElement(http.getText(url, playlistHeaders)) as? JsonObject
            ((login?.get("user_info") as? JsonObject)?.get("allowed_output_formats") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() }
                .orEmpty()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            emptyList()
        }
        return if (formats.isEmpty() || "ts" in formats || "m3u8" !in formats) "ts" else "m3u8"
    }

    private suspend fun request(
        settings: LiveTvXtreamSettings,
        action: String,
        extra: Map<String, String> = emptyMap(),
    ): JsonElement {
        val parameters = buildString {
            append("username=").append(liveTvEncode(settings.username))
            append("&password=").append(liveTvEncode(settings.password))
            append("&action=").append(liveTvEncode(action))
            extra.forEach { (key, value) -> append('&').append(liveTvEncode(key)).append('=').append(liveTvEncode(value)) }
        }
        val url = "${settings.serverUrl}/player_api.php?$parameters"
        return json.parseToJsonElement(http.getText(url, playlistHeaders))
    }

    private fun LiveTvXtreamSettings.seriesStreamUrl(id: String, extension: String) =
        "$serverUrl/series/${liveTvEncode(username)}/${liveTvEncode(password)}/${liveTvEncode(id)}.${liveTvEncode(extension)}"

    private fun LiveTvXtreamSettings.normalized() = copy(
        serverUrl = serverUrl.trim().trimEnd('/').substringBefore("/player_api.php").trimEnd('/'),
        username = username.trim(),
        password = password.trim(),
    )

    private fun JsonObject.string(key: String) =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

    private fun JsonElement.arrayOrEmpty() =
        (this as? JsonArray)?.toList() ?: ((this as? JsonObject)?.get("data") as? JsonArray)?.toList().orEmpty()

    private companion object {
        const val XTREAM_PLAYLIST_ID = "provider:xtream"

        fun vodCategoryPrefix(kind: LiveTvVodKind) =
            "xtream:${if (kind == LiveTvVodKind.Movies) "vod" else "series"}:"

        fun vodItemPrefix(kind: LiveTvVodKind) =
            "xtream-${if (kind == LiveTvVodKind.Movies) "vod" else "series"}"

        val playlistHeaders = mapOf("Accept" to "application/json, text/plain, */*", "User-Agent" to "Nuvio/1.0")
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
