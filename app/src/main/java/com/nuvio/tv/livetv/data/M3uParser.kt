package com.nuvio.tv.livetv.data

import java.net.URLDecoder

/** User agent for http(s) channels that do not set their own. IPTV panels often refuse a browser UA. */
internal const val LIVE_TV_STREAM_USER_AGENT = "VLC/3.0.0 LibVLC/3.0.0"

fun parseM3uPlaylist(
    payload: String,
    playlist: LiveTvPlaylist? = null,
): List<LiveTvChannel> {
    val channels = mutableListOf<LiveTvChannel>()
    var pendingInfo: M3uInfo? = null
    val pendingHeaders = LinkedHashMap<String, String>()
    var pendingStreamType: String? = null

    payload.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .forEach { line ->
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val info = parseExtInf(line)
                    pendingInfo = info
                    info.headers.forEach { (key, value) -> pendingHeaders.putM3uHeader(key, value) }
                }
                line.startsWith("#EXTVLCOPT", ignoreCase = true) -> {
                    parseVlcOption(line.substringAfter(':', missingDelimiterValue = ""))
                        ?.let { (key, value) -> pendingHeaders.putM3uHeader(key, value) }
                }
                line.startsWith("#KODIPROP", ignoreCase = true) -> {
                    parseKodiProp(line.substringAfter(':', missingDelimiterValue = ""), pendingHeaders)
                        ?.let { pendingStreamType = it }
                }
                // shortcut: #EXTHTTP JSON headers are ignored, parse them when a playlist needs them
                line.startsWith("#") -> Unit
                else -> {
                    val (streamUrl, urlHeaders) = splitM3uStreamUrl(line)
                    if (streamUrl.isBlank()) return@forEach
                    urlHeaders.forEach { (key, value) -> pendingHeaders.putM3uHeader(key, value) }
                    val info = pendingInfo
                    val name = info?.name?.takeIf(String::isNotBlank)
                        ?: streamUrl.substringAfterLast('/').substringBefore('?').ifBlank { "Channel" }
                    channels += LiveTvChannel(
                        id = stableChannelId(streamUrl, channels.size),
                        name = name,
                        streamUrl = streamUrl,
                        logoUrl = info?.logoUrl?.takeIf(String::isNotBlank),
                        group = info?.group?.takeIf(String::isNotBlank),
                        playlistId = playlist?.id,
                        playlistName = playlist?.name,
                        headers = defaultLiveTvStreamHeaders(streamUrl) + pendingHeaders,
                        streamType = pendingStreamType,
                        epgId = info?.epgId?.takeIf(String::isNotBlank),
                    )
                    pendingInfo = null
                    pendingHeaders.clear()
                    pendingStreamType = null
                }
            }
        }

    return channels.distinctBy { it.streamUrl }
}

private data class M3uInfo(
    val name: String,
    val logoUrl: String?,
    val group: String?,
    val epgId: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

private fun parseExtInf(line: String): M3uInfo {
    val name = line.substring(extInfNameDelimiterIndex(line) + 1)
        .trim()
        .ifBlank { readM3uAttribute(line, "tvg-name").orEmpty() }
    val headers = buildMap {
        (readM3uAttribute(line, "http-user-agent") ?: readM3uAttribute(line, "user-agent"))
            ?.let { put("User-Agent", it) }
        (
            readM3uAttribute(line, "http-referrer")
                ?: readM3uAttribute(line, "http-referer")
                ?: readM3uAttribute(line, "referrer")
                ?: readM3uAttribute(line, "referer")
            )?.let { put("Referer", it) }
    }
    return M3uInfo(
        name = name,
        logoUrl = readM3uAttribute(line, "tvg-logo"),
        group = readM3uAttribute(line, "group-title"),
        epgId = readM3uAttribute(line, "tvg-id"),
        headers = headers,
    )
}

private fun extInfNameDelimiterIndex(line: String): Int {
    var inQuotes = false
    line.forEachIndexed { index, char ->
        when {
            char == '"' -> inQuotes = !inQuotes
            char == ',' && !inQuotes -> return index
        }
    }
    return line.lastIndex
}

private fun parseVlcOption(option: String): Pair<String, String>? {
    val key = option.substringBefore('=', missingDelimiterValue = "").trim().lowercase()
    val value = option.substringAfter('=', missingDelimiterValue = "").trim().trim('"')
    if (value.isBlank()) return null
    return when (key) {
        "http-user-agent" -> "User-Agent" to value
        "http-referrer", "http-referer" -> "Referer" to value
        "http-origin" -> "Origin" to value
        "http-cookie" -> "Cookie" to value
        else -> null
    }
}

private fun parseKodiProp(property: String, headers: MutableMap<String, String>): String? {
    val key = property.substringBefore('=', missingDelimiterValue = "").trim().lowercase()
    val value = property.substringAfter('=', missingDelimiterValue = "").trim().trim('"')
    if (value.isBlank()) return null
    return when (key) {
        "http-user-agent", "user-agent" -> {
            headers.putM3uHeader("User-Agent", value)
            null
        }
        "http-referrer", "http-referer", "referrer", "referer" -> {
            headers.putM3uHeader("Referer", value)
            null
        }
        "inputstream.adaptive.manifest_type" -> when (value.lowercase()) {
            "mpd", "dash" -> "dash"
            "hls", "m3u8" -> "hls"
            "ism", "isml", "smoothstreaming" -> "ss"
            else -> null
        }
        "mimetype" -> when {
            value.contains("mpegurl", ignoreCase = true) -> "hls"
            value.contains("dash", ignoreCase = true) -> "dash"
            else -> null
        }
        "inputstream.adaptive.stream_headers",
        "inputstream.adaptive.common_headers",
        "inputstream.adaptive.manifest_headers" -> {
            parseM3uHeaderQuery(value).forEach { (headerKey, headerValue) ->
                headers.putM3uHeader(headerKey, headerValue)
            }
            null
        }
        else -> null
    }
}

private fun splitM3uStreamUrl(line: String): Pair<String, Map<String, String>> {
    val separator = line.indexOf('|')
    if (separator < 0) return line.trim() to emptyMap()
    return line.substring(0, separator).trim() to parseM3uHeaderQuery(line.substring(separator + 1))
}

private fun parseM3uHeaderQuery(query: String): Map<String, String> =
    query.split('&')
        .mapNotNull { parameter ->
            val key = parameter.substringBefore('=', missingDelimiterValue = "").trim().decodeM3uComponent()
            val value = parameter.substringAfter('=', missingDelimiterValue = "").trim().trim('"').decodeM3uComponent()
            if (key.isBlank() || value.isBlank()) null else key to value
        }
        .toMap()

private fun String.decodeM3uComponent(): String =
    if ('%' !in this) this
    else runCatching { URLDecoder.decode(replace("+", "%2B"), "UTF-8") }.getOrDefault(this)

private fun MutableMap<String, String>.putM3uHeader(rawKey: String, value: String) {
    val trimmedValue = value.trim()
    if (trimmedValue.isBlank()) return
    val key = when (rawKey.trim().lowercase()) {
        "user-agent", "useragent", "http-user-agent" -> "User-Agent"
        "referer", "referrer", "http-referer", "http-referrer" -> "Referer"
        "origin", "http-origin" -> "Origin"
        "cookie", "http-cookie" -> "Cookie"
        else -> rawKey.trim()
    }
    if (key.isBlank() || key.any { it.isWhitespace() || it == ':' }) return
    keys.firstOrNull { it.equals(key, ignoreCase = true) }?.let(::remove)
    put(key, trimmedValue)
}

private fun defaultLiveTvStreamHeaders(url: String): Map<String, String> =
    if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
        mapOf("User-Agent" to LIVE_TV_STREAM_USER_AGENT)
    } else {
        emptyMap()
    }

private fun readM3uAttribute(line: String, key: String): String? {
    val marker = "$key=\""
    val start = line.indexOf(marker, ignoreCase = true)
    if (start < 0) return null
    val valueStart = start + marker.length
    val valueEnd = line.indexOf('"', startIndex = valueStart).takeIf { it >= 0 } ?: return null
    return line.substring(valueStart, valueEnd).trim()
}

private fun stableChannelId(streamUrl: String, index: Int): String =
    "live:${streamUrl.hashCode().toUInt().toString(16)}:$index"
