package com.nuvio.tv.livetv.data

data class LiveTvChannel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val group: String? = null,
    val playlistId: String? = null,
    val playlistName: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val streamType: String? = null,
    val stalkerCommand: String? = null,
    val epgId: String? = null,
)

data class LiveTvEpgProgram(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val description: String? = null,
    val category: String? = null,
) {
    fun isAiringAt(timeMillis: Long): Boolean = timeMillis in startMillis until endMillis

    fun progressAt(timeMillis: Long): Float {
        val duration = endMillis - startMillis
        if (duration <= 0L) return 0f
        return ((timeMillis - startMillis).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    }
}

data class LiveTvEpgSource(
    val id: String,
    val name: String,
    val url: String,
    val isEnabled: Boolean = true,
)

data class LiveTvStalkerSettings(
    val portalUrl: String = "",
    val macAddress: String = "",
    val username: String = "",
    val password: String = "",
    val isEnabled: Boolean = true,
) {
    val isConfigured: Boolean get() = portalUrl.isNotBlank() && macAddress.isNotBlank()
}

data class LiveTvXtreamSettings(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val isEnabled: Boolean = true,
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}

enum class LiveTvPlaylistType {
    Url,
    LocalFile,
}

data class LiveTvPlaylist(
    val id: String,
    val name: String,
    val type: LiveTvPlaylistType,
    val source: String,
    val isEnabled: Boolean = true,
)

/** Absolute http(s) logo. A portal path is resolved against [pageUrl]; anything else is dropped. */
internal fun resolveLogoUrl(raw: String?, pageUrl: String): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val resolved = if (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)) {
        value
    } else {
        runCatching { java.net.URI(pageUrl).resolve(value).toString() }.getOrNull() ?: return null
    }
    return resolved.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
}
