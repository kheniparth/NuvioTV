package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.EpisodeListOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

@Singleton
class EpisodeListOrderDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
) {
    private val key = stringPreferencesKey("episode_list_order")

    val order: Flow<EpisodeListOrder> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, FEATURE).data.map { prefs ->
            if (prefs[key] == EpisodeListOrder.LatestFirst.name) {
                EpisodeListOrder.LatestFirst
            } else {
                EpisodeListOrder.OldestFirst
            }
        }
    }

    suspend fun set(order: EpisodeListOrder) {
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { it[key] = order.name }
    }

    private companion object {
        const val FEATURE = "episode_list_order"
    }
}
