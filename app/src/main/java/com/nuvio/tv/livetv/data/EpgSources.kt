package com.nuvio.tv.livetv.data

data class EpgSelection(
    val xmltvUrls: List<String> = emptyList(),
    val stalker: Boolean = false,
)

/**
 * Saved guide URLs, then playlist `url-tvg` headers, then the Xtream `xmltv.php` URL.
 * Stalker has no XMLTV URL: [EpgSelection.stalker] means call `get_epg_info`.
 */
fun selectEpgSources(
    saved: List<LiveTvEpgSource>,
    playlistEpgUrls: List<String>,
    xtream: LiveTvXtreamSettings?,
    stalker: LiveTvStalkerSettings?,
): EpgSelection {
    val urls = LinkedHashSet<String>()
    saved.filter { it.isEnabled && it.url.isNotBlank() }.map { it.url.trim() }.forEach(urls::add)
    playlistEpgUrls.forEach { url ->
        val trimmed = url.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            urls.add(trimmed)
        }
    }
    if (xtream != null && xtream.isEnabled && xtream.isConfigured) urls.add(xtreamEpgUrl(xtream))
    return EpgSelection(
        xmltvUrls = urls.toList(),
        stalker = stalker != null && stalker.isEnabled && stalker.isConfigured,
    )
}

fun xtreamEpgUrl(settings: LiveTvXtreamSettings): String {
    val server = settings.serverUrl.trim().trimEnd('/').substringBefore("/player_api.php").trimEnd('/')
    return "$server/xmltv.php?username=${liveTvEncode(settings.username.trim())}&password=${liveTvEncode(settings.password.trim())}"
}
