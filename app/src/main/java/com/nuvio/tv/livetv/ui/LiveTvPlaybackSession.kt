package com.nuvio.tv.livetv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.livetv.data.LiveTvChannel
import com.nuvio.tv.livetv.data.LiveTvPlayable
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.data.StalkerStage
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
    private val previewPlayer: LiveTvPreviewPlayer,
) {
    var channels: List<LiveTvChannel> = emptyList()
        private set
    var favoriteIds: Set<String> = emptySet()
        private set
    var category: ChannelCategory = ChannelCategory.All
        private set
    var recentIds: List<String> = emptyList()
        private set
    var currentId: String? = null
        private set

    /** Channel to focus when the list comes back from the player. Cleared once focus lands. */
    var returnChannelId: String? = null
        private set

    /** Resolved switch request. The player route can mangle this URL, so the player reads it back. */
    private var pending: LiveTvPlayRequest? = null

    fun arm(
        channels: List<LiveTvChannel>,
        favoriteIds: Set<String>,
        category: ChannelCategory,
        currentId: String,
        recentIds: List<String> = emptyList(),
    ) {
        this.channels = channels
        this.favoriteIds = favoriteIds
        this.category = category
        this.recentIds = recentIds
        this.currentId = currentId
        this.returnChannelId = currentId
    }

    fun noteReturnChannel(id: String) {
        returnChannelId = id
    }

    fun clearReturnChannel() {
        returnChannelId = null
    }

    fun inCategory(): List<LiveTvChannel> = channelsInCategory(channels, favoriteIds, category, recentIds)

    /** [fromId] lets a burst of presses walk from the pending target instead of the playing channel. */
    fun adjacent(next: Boolean, fromId: String? = currentId): LiveTvChannel? {
        val id = fromId ?: return null
        return adjacentChannel(channels, favoriteIds, category, id, next, recentIds)
    }

    fun markCurrent(id: String) {
        currentId = id
        if (returnChannelId != null) returnChannelId = id
    }

    fun clearPending() {
        pending = null
    }

    fun consumePending(): LiveTvPlayRequest? = pending.also { pending = null }

    fun currentIsStalker(): Boolean =
        channels.find { it.id == currentId }?.stalkerCommand?.isNotBlank() == true

    suspend fun playable(
        channel: LiveTvChannel,
        releasePlayer: suspend () -> Unit = {},
    ): LiveTvPlayRequest {
        pending = null
        // Release the full player and the preview, and wait for that, before create_link.
        val resolved = repository.openChannel(channel, StalkerStage.Zap) {
            releasePlayer()
            previewPlayer.stop()
        }
        if (resolved.url.isBlank()) error("No stream URL")
        repository.recordRecent(channel.id)
        return requestFor(channel, resolved).also { pending = it }
    }

    suspend fun refreshAfterForbidden(status: Int, failedAttempt: Int): LiveTvPlayRequest? {
        val channel = channels.find { it.id == currentId } ?: return null
        if (channel.stalkerCommand.isNullOrBlank()) return null
        val resolved = repository.retryStalker(channel, status, failedAttempt) { previewPlayer.stop() }
        if (resolved.url.isBlank()) error("No stream URL")
        return requestFor(channel, resolved).also { pending = it }
    }

    private fun requestFor(channel: LiveTvChannel, resolved: LiveTvPlayable) = LiveTvPlayRequest(
        streamUrl = resolved.url,
        title = channel.name,
        headers = resolved.headers,
        logoUrl = channel.logoUrl,
    )
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
