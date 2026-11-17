package com.nuvio.tv.livetv.data.local

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** M3U playlist. `type` is `Url` or `LocalFile`, matching Enhanced `LiveTvPlaylistType`. */
data class LiveTvPlaylist(
    val id: String,
    val name: String,
    val type: LiveTvPlaylistType,
    val source: String,
    val isEnabled: Boolean = true,
)

enum class LiveTvPlaylistType { Url, LocalFile }

/** Xtream Codes account. `serverUrl` is the server, `username` / `password` the login. */
data class LiveTvXtreamSettings(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val isEnabled: Boolean = true,
)

/** Stalker / Ministra portal. `username` and `password` are optional. */
data class LiveTvStalkerSettings(
    val portalUrl: String = "",
    val macAddress: String = "",
    val username: String = "",
    val password: String = "",
    val isEnabled: Boolean = true,
)

data class LiveTvEpgSource(
    val id: String,
    val name: String,
    val url: String,
    val isEnabled: Boolean = true,
)

data class LiveTvSettings(
    val playlists: List<LiveTvPlaylist> = emptyList(),
    val xtream: LiveTvXtreamSettings = LiveTvXtreamSettings(),
    val stalker: LiveTvStalkerSettings = LiveTvStalkerSettings(),
    val epgSources: List<LiveTvEpgSource> = emptyList(),
    val favoriteChannelIds: List<String> = emptyList(),
    val recentChannelIds: List<String> = emptyList(),
)

internal const val LIVE_TV_MAX_RECENT_CHANNELS = 50

internal const val LIVE_TV_PLAYLISTS_KEY = "playlists_blob"
internal const val LIVE_TV_XTREAM_KEY = "xtream_settings"
internal const val LIVE_TV_STALKER_KEY = "stalker_settings"
internal const val LIVE_TV_EPG_SOURCES_KEY = "epg_sources_blob"
internal const val LIVE_TV_FAVORITES_KEY = "favorite_channel_ids_blob"
internal const val LIVE_TV_RECENTS_KEY = "recent_channel_ids"

internal fun encodeLiveTvPlaylists(playlists: List<LiveTvPlaylist>): String =
    gson.toJson(playlists.filter { it.id.isNotBlank() && it.source.isNotBlank() })

internal fun decodeLiveTvPlaylists(json: String?): List<LiveTvPlaylist> =
    jsonArray(json).mapNotNull { element ->
        val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val typeName = obj.text("type") ?: return@mapNotNull null
        val type = runCatching { LiveTvPlaylistType.valueOf(typeName) }.getOrNull() ?: return@mapNotNull null
        val id = obj.text("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val source = obj.text("source")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        LiveTvPlaylist(
            id = id,
            name = obj.text("name")?.takeIf { it.isNotBlank() } ?: "M3U playlist",
            type = type,
            source = source,
            isEnabled = obj.enabledFlag(),
        )
    }

internal fun encodeLiveTvXtream(settings: LiveTvXtreamSettings): String = gson.toJson(settings)

internal fun decodeLiveTvXtream(json: String?): LiveTvXtreamSettings {
    val obj = jsonObject(json) ?: return LiveTvXtreamSettings()
    return LiveTvXtreamSettings(
        serverUrl = obj.text("serverUrl").orEmpty(),
        username = obj.text("username").orEmpty(),
        password = obj.text("password").orEmpty(),
        isEnabled = obj.enabledFlag(),
    )
}

internal fun encodeLiveTvStalker(settings: LiveTvStalkerSettings): String = gson.toJson(settings)

internal fun decodeLiveTvStalker(json: String?): LiveTvStalkerSettings {
    val obj = jsonObject(json) ?: return LiveTvStalkerSettings()
    return LiveTvStalkerSettings(
        portalUrl = obj.text("portalUrl").orEmpty(),
        macAddress = obj.text("macAddress").orEmpty(),
        username = obj.text("username").orEmpty(),
        password = obj.text("password").orEmpty(),
        isEnabled = obj.enabledFlag(),
    )
}

internal fun encodeLiveTvEpgSources(sources: List<LiveTvEpgSource>): String =
    gson.toJson(sources.filter { it.id.isNotBlank() && it.url.isNotBlank() })

internal fun decodeLiveTvEpgSources(json: String?): List<LiveTvEpgSource> =
    jsonArray(json).mapNotNull { element ->
        val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val id = obj.text("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val url = obj.text("url")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        LiveTvEpgSource(
            id = id,
            name = obj.text("name")?.takeIf { it.isNotBlank() } ?: "EPG",
            url = url,
            isEnabled = obj.enabledFlag(),
        )
    }

internal fun encodeLiveTvStringList(ids: List<String>): String = gson.toJson(ids)

internal fun decodeLiveTvStringList(json: String?): List<String> =
    jsonArray(json).mapNotNull { element ->
        element.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
    }

/** Newest first. First occurrence wins; blanks dropped; capped at [LIVE_TV_MAX_RECENT_CHANNELS]. */
internal fun capLiveTvRecentChannelIds(ids: List<String>): List<String> =
    ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(LIVE_TV_MAX_RECENT_CHANNELS)

internal fun normalizeLiveTvFavoriteChannelIds(ids: Collection<String>): List<String> =
    ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()

private val gson = Gson()

private fun jsonArray(json: String?): List<JsonElement> {
    if (json.isNullOrBlank()) return emptyList()
    val array = runCatching { JsonParser.parseString(json).asJsonArray }.getOrNull() ?: return emptyList()
    return array.toList()
}

private fun jsonObject(json: String?): JsonObject? =
    if (json.isNullOrBlank()) null
    else runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()

private fun JsonObject.text(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString

/** Missing or unreadable `isEnabled` stays enabled, matching Enhanced. */
private fun JsonObject.enabledFlag(): Boolean {
    val value = get("isEnabled") ?: return true
    return runCatching { value.asBoolean }.getOrDefault(true)
}
