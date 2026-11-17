package com.nuvio.tv.livetv.data

import javax.inject.Inject
import javax.inject.Singleton
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
                    logoUrl = item.string("logo") ?: item.string("logo_url"),
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
