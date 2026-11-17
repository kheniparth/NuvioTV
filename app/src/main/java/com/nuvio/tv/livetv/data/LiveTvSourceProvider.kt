package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.data.local.LiveTvSettings
import com.nuvio.tv.livetv.data.local.LiveTvSettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import com.nuvio.tv.livetv.data.local.LiveTvPlaylistType as StoredPlaylistType

/** Enabled sources only. Favourites and recents stay in [LiveTvSettingsDataStore]. */
data class LiveTvSources(
    val playlists: List<LiveTvPlaylist> = emptyList(),
    val xtream: LiveTvXtreamSettings? = null,
    val stalker: LiveTvStalkerSettings? = null,
    val epgSources: List<LiveTvEpgSource> = emptyList(),
)

interface LiveTvSourceProvider {
    suspend fun current(): LiveTvSources
}

@Singleton
class SettingsLiveTvSourceProvider @Inject constructor(
    private val store: LiveTvSettingsDataStore,
) : LiveTvSourceProvider {
    override suspend fun current(): LiveTvSources = store.settings.first().toSources()
}

internal fun LiveTvSettings.toSources(): LiveTvSources = LiveTvSources(
    playlists = playlists.filter { it.isEnabled && it.source.isNotBlank() }.map { playlist ->
        LiveTvPlaylist(
            id = playlist.id,
            name = playlist.name,
            type = when (playlist.type) {
                StoredPlaylistType.Url -> LiveTvPlaylistType.Url
                StoredPlaylistType.LocalFile -> LiveTvPlaylistType.LocalFile
            },
            source = playlist.source,
        )
    },
    xtream = xtream.takeIf {
        it.isEnabled && it.serverUrl.isNotBlank() && it.username.isNotBlank() && it.password.isNotBlank()
    }?.let { LiveTvXtreamSettings(it.serverUrl, it.username, it.password) },
    stalker = stalker.takeIf { it.isEnabled && it.portalUrl.isNotBlank() && it.macAddress.isNotBlank() }
        ?.let { LiveTvStalkerSettings(it.portalUrl, it.macAddress, it.username, it.password) },
    epgSources = epgSources.filter { it.isEnabled && it.url.isNotBlank() }.map {
        LiveTvEpgSource(it.id, it.name, it.url)
    },
)
