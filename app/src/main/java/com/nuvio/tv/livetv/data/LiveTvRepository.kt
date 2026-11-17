package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.data.local.LiveTvPlaylist as SettingsPlaylist
import com.nuvio.tv.livetv.data.local.LiveTvPlaylistType as SettingsPlaylistType
import com.nuvio.tv.livetv.data.local.LiveTvSettings
import com.nuvio.tv.livetv.data.local.LiveTvSettingsDataStore
import com.nuvio.tv.livetv.data.local.LiveTvStalkerSettings as SettingsStalkerSettings
import com.nuvio.tv.livetv.data.local.LiveTvXtreamSettings as SettingsXtreamSettings
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class LiveTvCatalog(
    val channels: List<LiveTvChannel> = emptyList(),
    val guide: XmltvGuide = XmltvGuide(),
    val favoriteIds: Set<String> = emptySet(),
    val loadError: String? = null,
)

@Singleton
class LiveTvRepository @Inject constructor(
    private val settingsStore: LiveTvSettingsDataStore,
    private val http: LiveTvHttp,
    private val xtream: XtreamClient,
    private val stalker: StalkerClient,
) {
    val hasConfiguredSource: Flow<Boolean> = settingsStore.settings.map { it.hasConfiguredSource() }

    suspend fun loadCatalog(): LiveTvCatalog {
        val settings = settingsStore.settings.first()
        val channels = mutableListOf<LiveTvChannel>()
        val guides = mutableListOf<XmltvGuide>()
        val errors = mutableListOf<String>()
        settings.playlists.filter { it.isEnabled && it.source.isNotBlank() }.forEach { playlist ->
            load("M3U ${playlist.name}", errors) {
                channels += parseM3uPlaylist(readPlaylist(playlist), playlist.toWire())
            }
        }
        val xtreamSettings = settings.xtream
        if (xtreamSettings.isEnabled && xtreamSettings.isFilled()) {
            load("Xtream", errors) { channels += xtream.liveChannels(xtreamSettings.toWire()) }
        }
        val stalkerSettings = settings.stalker
        if (stalkerSettings.isEnabled && stalkerSettings.isFilled()) {
            load("Stalker", errors) { channels += stalker.channels(stalkerSettings.toWire()) }
        }
        settings.epgSources.filter { it.isEnabled && it.url.isNotBlank() }.forEach { source ->
            load("EPG ${source.name}", errors) {
                guides += parseEpgPayload(http.getBytes(source.url, epgHeaders))
            }
        }
        return LiveTvCatalog(
            channels = channels.distinctBy { it.id },
            guide = mergeGuides(guides),
            favoriteIds = settings.favoriteChannelIds.toSet(),
            loadError = errors.takeIf { channels.isEmpty() }?.joinToString("\n")?.takeIf { it.isNotBlank() },
        )
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

    suspend fun resolvePlayableUrl(channel: LiveTvChannel): LiveTvChannel {
        if (channel.stalkerCommand.isNullOrBlank()) return channel
        val settings = settingsStore.settings.first().stalker
        return stalker.createLink(channel, settings.toWire())
    }

    private suspend fun readPlaylist(playlist: SettingsPlaylist): String =
        if (playlist.type == SettingsPlaylistType.LocalFile) {
            withContext(Dispatchers.IO) { File(playlist.source).readText() }
        } else {
            http.getText(playlist.source, mapOf("User-Agent" to LIVE_TV_STREAM_USER_AGENT))
        }

    private suspend fun load(label: String, errors: MutableList<String>, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            errors += "$label: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun SettingsPlaylist.toWire() = LiveTvPlaylist(
        id = id,
        name = name,
        type = if (type == SettingsPlaylistType.LocalFile) LiveTvPlaylistType.LocalFile else LiveTvPlaylistType.Url,
        source = source,
        isEnabled = isEnabled,
    )

    private fun SettingsXtreamSettings.toWire() = LiveTvXtreamSettings(
        serverUrl = serverUrl,
        username = username,
        password = password,
        isEnabled = isEnabled,
    )

    private fun SettingsXtreamSettings.isFilled() =
        serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    private fun SettingsStalkerSettings.toWire() = LiveTvStalkerSettings(
        portalUrl = portalUrl,
        macAddress = macAddress,
        username = username,
        password = password,
        isEnabled = isEnabled,
    )

    private fun SettingsStalkerSettings.isFilled() =
        portalUrl.isNotBlank() && macAddress.isNotBlank()

    private companion object {
        val epgHeaders = mapOf("User-Agent" to LIVE_TV_STREAM_USER_AGENT, "Accept" to "*/*")
    }
}

internal fun mergeGuides(guides: List<XmltvGuide>): XmltvGuide {
    if (guides.size <= 1) return guides.firstOrNull() ?: XmltvGuide()
    val programs = HashMap<String, MutableList<LiveTvEpgProgram>>()
    guides.forEach { guide ->
        guide.programsByTvgId.forEach { (id, list) ->
            programs.getOrPut(id) { mutableListOf() }.addAll(list)
        }
    }
    return XmltvGuide(
        channelIds = guides.flatMap { it.channelIds }.toSet(),
        programsByTvgId = programs.mapValues { (_, list) ->
            list.sortedBy { it.startMillis }.distinctBy { it.startMillis }
        },
    )
}
