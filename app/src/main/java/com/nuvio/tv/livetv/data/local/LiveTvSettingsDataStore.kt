package com.nuvio.tv.livetv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Profile-scoped Live TV provider settings. File `live_tv_settings` for profile 1,
 * `live_tv_settings_p{id}` otherwise (see [ProfileDataStoreFactory]).
 *
 * String preference keys and JSON. Names match Enhanced `LiveTvStorage`; a missing
 * `isEnabled` means true. Enhanced still stores unit-separator blobs, so a shared
 * bootstrap must write this JSON when the target is NuvioTV.
 *
 * - `playlists_blob`: `[{"id","name","type":"Url"|"LocalFile","source","isEnabled"}]`
 * - `xtream_settings`: `{"serverUrl","username","password","isEnabled"}`
 * - `stalker_settings`: `{"portalUrl","macAddress","username","password","isEnabled"}`
 * - `epg_sources_blob`: `[{"id","name","url","isEnabled"}]`
 * - `favorite_channel_ids_blob`: `["channel-id", ...]` sorted
 * - `favorite_vod_ids_blob`: `["vod-item-id", ...]` sorted
 * - `recent_channel_ids`: `["newest-id", ...]` newest first, at most 50
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class LiveTvSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
) {
    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    val settings: Flow<LiveTvSettings> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, FEATURE).data.map { prefs ->
            LiveTvSettings(
                playlists = decodeLiveTvPlaylists(prefs[playlistsKey]),
                xtream = decodeLiveTvXtream(prefs[xtreamKey]),
                stalker = decodeLiveTvStalker(prefs[stalkerKey]),
                epgSources = decodeLiveTvEpgSources(prefs[epgSourcesKey]),
                favoriteChannelIds = normalizeLiveTvFavoriteChannelIds(
                    decodeLiveTvStringList(prefs[favoritesKey])
                ),
                favoriteVodIds = normalizeLiveTvFavoriteChannelIds(
                    decodeLiveTvStringList(prefs[vodFavoritesKey])
                ),
                recentChannelIds = capLiveTvRecentChannelIds(
                    decodeLiveTvStringList(prefs[recentsKey])
                ),
            )
        }
    }

    suspend fun setPlaylists(playlists: List<LiveTvPlaylist>) {
        store().edit { it[playlistsKey] = encodeLiveTvPlaylists(playlists) }
    }

    suspend fun setXtream(settings: LiveTvXtreamSettings) {
        store().edit { it[xtreamKey] = encodeLiveTvXtream(settings) }
    }

    suspend fun setStalker(settings: LiveTvStalkerSettings) {
        store().edit { it[stalkerKey] = encodeLiveTvStalker(settings) }
    }

    suspend fun setEpgSources(sources: List<LiveTvEpgSource>) {
        store().edit { it[epgSourcesKey] = encodeLiveTvEpgSources(sources) }
    }

    suspend fun setFavoriteChannelIds(ids: Collection<String>) {
        store().edit {
            it[favoritesKey] = encodeLiveTvStringList(normalizeLiveTvFavoriteChannelIds(ids))
        }
    }

    suspend fun setFavoriteVodIds(ids: Collection<String>) {
        store().edit {
            it[vodFavoritesKey] = encodeLiveTvStringList(normalizeLiveTvFavoriteChannelIds(ids))
        }
    }

    suspend fun setRecentChannelIds(ids: List<String>) {
        store().edit { it[recentsKey] = encodeLiveTvStringList(capLiveTvRecentChannelIds(ids)) }
    }

    private companion object {
        const val FEATURE = "live_tv_settings"
        val playlistsKey = stringPreferencesKey(LIVE_TV_PLAYLISTS_KEY)
        val xtreamKey = stringPreferencesKey(LIVE_TV_XTREAM_KEY)
        val stalkerKey = stringPreferencesKey(LIVE_TV_STALKER_KEY)
        val epgSourcesKey = stringPreferencesKey(LIVE_TV_EPG_SOURCES_KEY)
        val favoritesKey = stringPreferencesKey(LIVE_TV_FAVORITES_KEY)
        val vodFavoritesKey = stringPreferencesKey(LIVE_TV_VOD_FAVORITES_KEY)
        val recentsKey = stringPreferencesKey(LIVE_TV_RECENTS_KEY)
    }
}
