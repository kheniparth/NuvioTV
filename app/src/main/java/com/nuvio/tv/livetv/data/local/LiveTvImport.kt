package com.nuvio.tv.livetv.data.local

import com.google.gson.JsonParser
import java.util.Base64

/**
 * Payload for a shell/root Live TV source import. M3U + Xtream proxy only —
 * stalker portal/MAC fields are never applied (see TEC-426).
 */
data class LiveTvImport(
    val profileId: Int,
    val m3uUrl: String? = null,
    val xtreamUrl: String? = null,
    val xtreamUser: String? = null,
    val xtreamPassword: String? = null,
)

/** Null on bad base64 or JSON. Unknown keys (including stalker fields) are ignored. */
fun parseLiveTvImport(base64Json: String): LiveTvImport? {
    val json = runCatching {
        String(Base64.getDecoder().decode(base64Json.trim()))
    }.getOrNull() ?: return null
    val obj = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
    val profileId = obj.get("profileId")
        ?.takeIf { it.isJsonPrimitive }
        ?.let { runCatching { it.asInt }.getOrNull() }
        ?: return null

    fun opt(key: String): String? =
        obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    return LiveTvImport(
        profileId = profileId,
        m3uUrl = opt("m3uUrl"),
        xtreamUrl = opt("xtreamUrl"),
        xtreamUser = opt("xtreamUser"),
        xtreamPassword = opt("xtreamPassword"),
    )
}

/**
 * Merge proxy sources into [current]. Stalker portal/MAC/isEnabled are never changed.
 * Null or blank import fields leave that value alone. EPG, favourites and recents
 * are untouched. Playlist id `stalker-m3u` keeps name/isEnabled; other playlists stay.
 */
fun mergeLiveTvImport(current: LiveTvSettings, import: LiveTvImport): LiveTvSettings {
    val xtream = current.xtream.copy(
        serverUrl = import.xtreamUrl?.takeIf { it.isNotBlank() } ?: current.xtream.serverUrl,
        username = import.xtreamUser?.takeIf { it.isNotBlank() } ?: current.xtream.username,
        password = import.xtreamPassword?.takeIf { it.isNotBlank() } ?: current.xtream.password,
    )

    val playlists = if (import.m3uUrl.isNullOrBlank()) {
        current.playlists
    } else {
        val m3u = import.m3uUrl
        val index = current.playlists.indexOfFirst { it.id == STALKER_M3U_PLAYLIST_ID }
        if (index >= 0) {
            current.playlists.toMutableList().also { list ->
                list[index] = list[index].copy(source = m3u)
            }
        } else {
            current.playlists + LiveTvPlaylist(
                id = STALKER_M3U_PLAYLIST_ID,
                name = "Stalker M3U",
                type = LiveTvPlaylistType.Url,
                source = m3u,
                isEnabled = true,
            )
        }
    }

    return current.copy(xtream = xtream, playlists = playlists)
}

/** Root (0) or shell (2000). Process.SHELL_UID is hidden; use the literal. */
fun isTrustedImportCaller(uid: Int): Boolean = uid == 0 || uid == 2000

/**
 * Gate first: untrusted callers never reach parse or [apply].
 * Returns `updated`, `unchanged`, or `error:<reason>`.
 */
fun handleLiveTvImport(
    uid: Int,
    method: String,
    arg: String?,
    apply: (LiveTvImport) -> Boolean,
): String {
    if (!isTrustedImportCaller(uid)) throw SecurityException("refused")
    if (method != "import") return "error:method"
    val import = parseLiveTvImport(arg ?: return "error:payload") ?: return "error:payload"
    return if (apply(import)) "updated" else "unchanged"
}

internal const val STALKER_M3U_PLAYLIST_ID = "stalker-m3u"
