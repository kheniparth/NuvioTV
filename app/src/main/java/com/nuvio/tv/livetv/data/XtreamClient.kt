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
                logoUrl = item.string("stream_icon") ?: item.string("logo"),
                group = item.string("category_id")?.let(categories::get),
                playlistId = XTREAM_PLAYLIST_ID,
                playlistName = "Xtream",
                headers = mapOf("User-Agent" to LIVE_TV_STREAM_USER_AGENT, "Accept" to "*/*"),
                streamType = extension,
                epgId = item.string("epg_channel_id"),
            )
        }.distinctBy { it.streamUrl }
    }

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

    private suspend fun request(settings: LiveTvXtreamSettings, action: String): JsonElement {
        val url = "${settings.serverUrl}/player_api.php?username=${liveTvEncode(settings.username)}&password=${liveTvEncode(settings.password)}&action=${liveTvEncode(action)}"
        return json.parseToJsonElement(http.getText(url, playlistHeaders))
    }

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
        val playlistHeaders = mapOf("Accept" to "application/json, text/plain, */*", "User-Agent" to "Nuvio/1.0")
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
