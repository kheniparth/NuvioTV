package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.data.local.LiveTvEpgSource as SettingsEpgSource
import com.nuvio.tv.livetv.data.local.LiveTvPlaylist as SettingsPlaylist
import com.nuvio.tv.livetv.data.local.LiveTvPlaylistType as SettingsPlaylistType
import com.nuvio.tv.livetv.data.local.LiveTvStalkerSettings as SettingsStalkerSettings
import com.nuvio.tv.livetv.data.local.LiveTvXtreamSettings as SettingsXtreamSettings
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A configured Live TV source, as the settings screen sees it. */
sealed interface LiveTvConnectionTarget {
    val key: String

    data class Playlist(val playlist: SettingsPlaylist) : LiveTvConnectionTarget {
        override val key: String get() = playlist.id
    }

    data class Xtream(val settings: SettingsXtreamSettings) : LiveTvConnectionTarget {
        override val key: String get() = "xtream"
    }

    data class Stalker(val settings: SettingsStalkerSettings) : LiveTvConnectionTarget {
        override val key: String get() = "stalker"
    }

    data class Epg(val source: SettingsEpgSource) : LiveTvConnectionTarget {
        override val key: String get() = source.id
    }
}

/**
 * Loads one source and returns how many channels it has. An EPG source has no channels, so it
 * returns null after a successful fetch. Throws with the reason when the source does not answer.
 */
@Singleton
class LiveTvConnectionTester @Inject constructor(
    private val http: LiveTvHttp,
    private val xtream: XtreamClient,
    private val stalker: StalkerClient,
) {
    suspend fun channelCount(target: LiveTvConnectionTarget): Int? = when (target) {
        is LiveTvConnectionTarget.Playlist -> {
            val playlist = target.playlist
            val payload = if (playlist.type == SettingsPlaylistType.LocalFile) {
                withContext(Dispatchers.IO) { File(playlist.source).readText() }
            } else {
                http.getText(playlist.source, mapOf("User-Agent" to LIVE_TV_STREAM_USER_AGENT))
            }
            parseM3uPlaylist(payload).size
        }
        is LiveTvConnectionTarget.Xtream -> xtream.liveChannels(target.settings.toWire()).size
        is LiveTvConnectionTarget.Stalker -> stalker.channels(target.settings.toWire()).size
        is LiveTvConnectionTarget.Epg -> {
            http.getText(target.source.url)
            null
        }
    }

    private fun SettingsXtreamSettings.toWire() = LiveTvXtreamSettings(
        serverUrl = serverUrl,
        username = username,
        password = password,
        isEnabled = isEnabled,
    )

    private fun SettingsStalkerSettings.toWire() = LiveTvStalkerSettings(
        portalUrl = portalUrl,
        macAddress = macAddress,
        username = username,
        password = password,
        isEnabled = isEnabled,
    )
}
