package com.nuvio.tv.livetv.data

import android.util.Log
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LiveTvPlayable(val url: String, val headers: Map<String, String>)

@Singleton
class LiveTvRepository @Inject constructor(
    private val sources: LiveTvSourceProvider,
    private val http: LiveTvHttp,
    private val xtream: XtreamClient,
    private val stalker: StalkerClient,
    private val cache: LiveTvCache,
) {
    /** shortcut: no refresh flag, the list updates when the source signature changes. */
    suspend fun channels(): List<LiveTvChannel> {
        val current = sources.current()
        val signature = sourceSignature(current)
        cache.channels(signature)?.let { return it }
        val loaded = coroutineScope {
            val jobs = buildList {
                current.playlists.forEach { playlist ->
                    add(async { playlist.name to runCatching { loadPlaylist(playlist) } })
                }
                current.xtream?.let { settings ->
                    add(async { "Xtream" to runCatching { xtream.liveChannels(settings) } })
                }
                current.stalker?.let { settings ->
                    add(async { "Stalker" to runCatching { stalker.channels(settings) } })
                }
            }
            val channels = mutableListOf<LiveTvChannel>()
            var failed = false
            jobs.awaitAll().forEach { (name, result) ->
                result.fold(
                    onSuccess = { channels += it },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        failed = true
                        Log.w(TAG, "Failed to load $name", error)
                    },
                )
            }
            if (channels.isEmpty() && failed) return@coroutineScope null
            channels.distinctBy { it.streamUrl }
        } ?: return cache.channels(signature).orEmpty()
        if (loaded.isNotEmpty()) cache.saveChannels(signature, loaded)
        return loaded
    }

    fun categories(channels: List<LiveTvChannel>): List<String> =
        channels.mapNotNull { it.group?.trim()?.takeIf(String::isNotEmpty) }.distinct().sorted()

    suspend fun epgFor(channel: LiveTvChannel, nowMillis: Long = System.currentTimeMillis()): List<LiveTvEpgProgram> {
        val key = channel.epgId?.trim()?.lowercase().orEmpty()
        if (key.isEmpty()) return emptyList()
        return guide(nowMillis)[key].orEmpty()
    }

    suspend fun resolvePlayableUrl(channel: LiveTvChannel): LiveTvPlayable {
        val settings = if (channel.stalkerCommand.isNullOrBlank()) null else sources.current().stalker
        val resolved = if (settings == null) channel else stalker.createLink(channel, settings)
        return LiveTvPlayable(resolved.streamUrl, resolved.headers)
    }

    private suspend fun guide(nowMillis: Long): Map<String, List<LiveTvEpgProgram>> {
        val urls = sources.current().epgSources.map { it.url }.distinct()
        if (urls.isEmpty()) return emptyMap()
        val signature = urls.joinToString("|")
        cache.epg(signature, nowMillis)?.let { return it }
        val merged = HashMap<String, MutableList<LiveTvEpgProgram>>()
        coroutineScope {
            urls.map { url ->
                async {
                    runCatching {
                        val bytes = http.getBytes(url, EPG_HEADERS)
                        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                            parseXmltvGzip(bytes)
                        } else {
                            parseXmltv(bytes.toString(Charsets.UTF_8))
                        }
                    }
                }
            }.awaitAll().forEach { result ->
                result.fold(
                    onSuccess = { guide ->
                        guide.programsByTvgId.forEach { (id, programs) ->
                            merged.getOrPut(id) { mutableListOf() }.addAll(programs)
                        }
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        Log.w(TAG, "Failed to load a guide", error)
                    },
                )
            }
        }
        val programs = merged.mapValues { (_, list) -> list.sortedBy { it.startMillis }.distinctBy { it.startMillis } }
        if (programs.isNotEmpty()) cache.saveEpg(signature, nowMillis, programs)
        return programs
    }

    private suspend fun loadPlaylist(playlist: LiveTvPlaylist): List<LiveTvChannel> {
        val payload = when (playlist.type) {
            LiveTvPlaylistType.Url -> http.getText(playlist.source)
            LiveTvPlaylistType.LocalFile -> withContext(Dispatchers.IO) { File(playlist.source).readText() }
        }
        return parseM3uPlaylist(payload, playlist)
    }

    private fun sourceSignature(sources: LiveTvSources): String = buildString {
        append("v3;")
        sources.playlists.forEach { playlist ->
            append(playlist.id).append('|').append(playlist.type.name).append('|')
            append(playlist.source.length).append(':').append(playlist.source.hashCode()).append(';')
        }
        sources.xtream?.let { append("x|").append(it.serverUrl).append('|').append(it.username).append('|').append(it.password.hashCode()).append(';') }
        sources.stalker?.let { append("s|").append(it.portalUrl).append('|').append(it.macAddress).append('|').append(it.username).append(';') }
    }.let { raw -> "${raw.length}:${raw.hashCode()}" }

    private companion object {
        const val TAG = "LiveTvRepository"
        val EPG_HEADERS = mapOf(
            "Accept" to "application/xml, text/xml, application/gzip, application/octet-stream, */*",
            "User-Agent" to "Nuvio/1.0",
        )
    }
}
