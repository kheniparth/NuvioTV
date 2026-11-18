package com.nuvio.tv.livetv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.livetv.data.LiveTvChannel
import com.nuvio.tv.livetv.data.LiveTvRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/** Channels the player can switch among. Armed when Live TV starts playback. */
@Singleton
class LiveTvPlaybackSession @Inject constructor(
    private val repository: LiveTvRepository,
) {
    var channels: List<LiveTvChannel> = emptyList()
        private set
    var favoriteIds: Set<String> = emptySet()
        private set
    var category: ChannelCategory = ChannelCategory.All
        private set
    var currentId: String? = null
        private set

    /** Resolved switch request. The player route can mangle this URL, so the player reads it back. */
    private var pending: LiveTvPlayRequest? = null

    fun arm(
        channels: List<LiveTvChannel>,
        favoriteIds: Set<String>,
        category: ChannelCategory,
        currentId: String,
    ) {
        this.channels = channels
        this.favoriteIds = favoriteIds
        this.category = category
        this.currentId = currentId
    }

    fun inCategory(): List<LiveTvChannel> = channelsInCategory(channels, favoriteIds, category)

    fun adjacent(next: Boolean): LiveTvChannel? {
        val id = currentId ?: return null
        return adjacentChannel(channels, favoriteIds, category, id, next)
    }

    fun markCurrent(id: String) {
        currentId = id
    }

    fun clearPending() {
        pending = null
    }

    fun consumePending(): LiveTvPlayRequest? = pending.also { pending = null }

    suspend fun playable(channel: LiveTvChannel): LiveTvPlayRequest {
        pending = null
        val resolved = repository.resolvePlayableUrl(channel)
        if (resolved.url.isBlank()) error("No stream URL")
        repository.recordRecent(channel.id)
        return LiveTvPlayRequest(
            streamUrl = resolved.url,
            title = channel.name,
            headers = resolved.headers,
            logoUrl = channel.logoUrl,
        ).also { pending = it }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LiveTvPlayerEntryPoint {
    fun session(): LiveTvPlaybackSession
}

@Composable
fun rememberLiveTvPlayback(): LiveTvPlaybackSession {
    val appContext = LocalContext.current.applicationContext
    return remember(appContext) {
        EntryPointAccessors.fromApplication(appContext, LiveTvPlayerEntryPoint::class.java).session()
    }
}

/** Writes the resolved switch over the route arguments. The player decodes [headers] once. */
internal fun SavedStateHandle.applyResolvedLiveChannel(session: LiveTvPlaybackSession) {
    val pending = session.consumePending() ?: return
    val contentType = get<String>("contentType")
    if (contentType != null && contentType != "channel") return
    set("streamUrl", pending.streamUrl)
    set("title", pending.title)
    set(
        "headers",
        URLEncoder.encode(JSONObject(pending.headers).toString(), "UTF-8").replace("+", "%20"),
    )
    if (contentType.isNullOrEmpty()) set("contentType", "channel")
}
