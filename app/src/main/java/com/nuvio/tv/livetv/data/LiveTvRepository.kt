package com.nuvio.tv.livetv.data

import android.util.Log
import com.nuvio.tv.livetv.data.local.LiveTvSettingsDataStore
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class LiveTvPlayable(val url: String, val headers: Map<String, String>)

/** Channels, guide and favourites for the Channels screen. */
data class LiveTvCatalog(
    val channels: List<LiveTvChannel> = emptyList(),
    val guide: XmltvGuide = XmltvGuide(),
    val favoriteIds: Set<String> = emptySet(),
)

@Singleton
class LiveTvRepository @Inject constructor(
    private val sources: LiveTvSourceProvider,
    private val http: LiveTvHttp,
    private val xtream: XtreamClient,
    private val stalker: StalkerClient,
    private val cache: LiveTvCache,
    private val settingsStore: LiveTvSettingsDataStore,
) {
    val hasConfiguredSource: Flow<Boolean> = settingsStore.settings.map { it.hasConfiguredSource() }

    /** shortcut: no refresh flag, the list updates when the source signature changes. */
    suspend fun channels(): List<LiveTvChannel> {
        val current = sources.current()
        val signature = sourceSignature(current)
        cache.channels(signature)?.let { return it.channels }
        val loaded = coroutineScope {
            val jobs = buildList {
                current.playlists.forEach { playlist ->
                    add(async { playlist.name to runCatching { loadPlaylist(playlist) } })
                }
                current.xtream?.let { settings ->
                    add(async { "Xtream" to runCatching { LoadedChannels(xtream.liveChannels(settings)) } })
                }
                current.stalker?.let { settings ->
                    add(async { "Stalker" to runCatching { LoadedChannels(stalker.channels(settings)) } })
                }
            }
            val channels = mutableListOf<LiveTvChannel>()
            val epgUrls = mutableListOf<String>()
            var failed = false
            jobs.awaitAll().forEach { (name, result) ->
                result.fold(
                    onSuccess = { loadedChannels ->
                        channels += loadedChannels.channels
                        epgUrls += loadedChannels.epgUrls
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        failed = true
                        Log.w(TAG, "Failed to load $name", error)
                    },
                )
            }
            if (channels.isEmpty() && failed) return@coroutineScope null
            LoadedChannels(channels.distinctBy { it.streamUrl }, epgUrls.distinct())
        } ?: return cache.channels(signature)?.channels.orEmpty()
        if (loaded.channels.isNotEmpty()) cache.saveChannels(signature, loaded.channels, loaded.epgUrls)
        return loaded.channels
    }

    suspend fun loadCatalog(nowMillis: Long): LiveTvCatalog = LiveTvCatalog(
        channels = channels(),
        guide = XmltvGuide(programsByTvgId = guide(nowMillis)),
        favoriteIds = settingsStore.settings.first().favoriteChannelIds.toSet(),
    )

    fun categories(channels: List<LiveTvChannel>): List<String> =
        channels.mapNotNull { it.group?.trim()?.takeIf(String::isNotEmpty) }.distinct().sorted()

    suspend fun epgFor(channel: LiveTvChannel, nowMillis: Long = System.currentTimeMillis()): List<LiveTvEpgProgram> =
        XmltvGuide(programsByTvgId = guide(nowMillis)).programsFor(channel.id, channel.epgId)

    /** Loads the whole guide once and maps it to [channels] by channel id. */
    suspend fun epgForChannels(
        channels: List<LiveTvChannel>,
        nowMillis: Long = System.currentTimeMillis(),
    ): Map<String, List<LiveTvEpgProgram>> {
        val loaded = XmltvGuide(programsByTvgId = guide(nowMillis))
        return channels.mapNotNull { channel ->
            val programs = loaded.programsFor(channel.id, channel.epgId)
            if (programs.isEmpty()) null else channel.id to programs
        }.toMap()
    }

    suspend fun resolvePlayableUrl(channel: LiveTvChannel): LiveTvPlayable {
        val settings = if (channel.stalkerCommand.isNullOrBlank()) null else sources.current().stalker
        val resolved = if (settings == null) channel else stalker.createLink(channel, settings)
        return LiveTvPlayable(resolved.streamUrl, resolved.headers)
    }

    suspend fun toggleFavorite(channelId: String) {
        val current = settingsStore.settings.first().favoriteChannelIds.toMutableSet()
        if (!current.add(channelId)) current.remove(channelId)
        settingsStore.setFavoriteChannelIds(current)
    }

    suspend fun recordRecent(channelId: String) {
        val current = settingsStore.settings.first().recentChannelIds
        settingsStore.setRecentChannelIds(listOf(channelId) + current)
    }

    private suspend fun guide(nowMillis: Long): Map<String, List<LiveTvEpgProgram>> {
        val current = sources.current()
        val selection = selectEpgSources(
            saved = current.epgSources,
            playlistEpgUrls = playlistEpgUrls(current),
            xtream = current.xtream,
            stalker = current.stalker,
        )
        if (selection.xmltvUrls.isEmpty() && !selection.stalker) return emptyMap()
        val signature = buildString {
            selection.xmltvUrls.forEach { append(it).append('|') }
            if (selection.stalker) {
                current.stalker?.let { append("stalker|").append(it.portalUrl).append('|').append(it.macAddress) }
            }
        }
        cache.epg(signature, nowMillis)?.let { return it }
        val merged = HashMap<String, MutableList<LiveTvEpgProgram>>()
        fun merge(programs: Map<String, List<LiveTvEpgProgram>>) {
            programs.forEach { (id, list) -> merged.getOrPut(id) { mutableListOf() }.addAll(list) }
        }
        coroutineScope {
            val jobs = buildList {
                selection.xmltvUrls.forEach { url ->
                    add(async { runCatching { parseEpgPayload(http.getBytes(url, EPG_HEADERS)).programsByTvgId } })
                }
                if (selection.stalker) {
                    current.stalker?.let { settings -> add(async { runCatching { stalker.epg(settings) } }) }
                }
            }
            jobs.awaitAll().forEach { result ->
                result.fold(
                    onSuccess = ::merge,
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

    private suspend fun playlistEpgUrls(current: LiveTvSources): List<String> {
        cache.channels(sourceSignature(current))?.epgUrls?.let { return it }
        if (current.playlists.isEmpty()) return emptyList()
        val urls = mutableListOf<String>()
        for (playlist in current.playlists) {
            val payload = try {
                readPlaylist(playlist)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w(TAG, "Failed to read ${playlist.name} for its guide url", error)
                continue
            }
            urls += parseM3uEpgUrls(payload)
        }
        return urls.distinct()
    }

    private suspend fun readPlaylist(playlist: LiveTvPlaylist): String = when (playlist.type) {
        LiveTvPlaylistType.Url -> http.getText(playlist.source)
        LiveTvPlaylistType.LocalFile -> withContext(Dispatchers.IO) { File(playlist.source).readText() }
    }

    private suspend fun loadPlaylist(playlist: LiveTvPlaylist): LoadedChannels {
        val payload = readPlaylist(playlist)
        return LoadedChannels(parseM3uPlaylist(payload, playlist), parseM3uEpgUrls(payload))
    }

    private data class LoadedChannels(
        val channels: List<LiveTvChannel>,
        val epgUrls: List<String> = emptyList(),
    )

    private fun sourceSignature(sources: LiveTvSources): String = buildString {
        append("v4;")
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
