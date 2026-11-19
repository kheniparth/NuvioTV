package com.nuvio.tv.livetv.ui

import android.view.KeyEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.livetv.data.LiveTvCatalog
import com.nuvio.tv.livetv.data.LiveTvChannel
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.data.XmltvGuide
import com.nuvio.tv.livetv.data.local.capLiveTvRecentChannelIds
import com.nuvio.tv.livetv.data.nowNextFor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ChannelCategory {
    data object All : ChannelCategory
    data object Favourites : ChannelCategory
    data object Recents : ChannelCategory
    data class Group(val name: String) : ChannelCategory
}

data class ChannelRow(
    val channel: LiveTvChannel,
    val nowTitle: String?,
    val nextTitle: String?,
    val favorite: Boolean,
)

data class LiveTvPlayRequest(
    val streamUrl: String,
    val title: String,
    val headers: Map<String, String>,
    val logoUrl: String?,
)

data class ChannelsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val categories: List<ChannelCategory> = listOf(
        ChannelCategory.All,
        ChannelCategory.Favourites,
        ChannelCategory.Recents,
    ),
    val selectedCategory: ChannelCategory = ChannelCategory.All,
    val query: String = "",
    val rows: List<ChannelRow> = emptyList(),
)

@HiltViewModel
class ChannelsViewModel @Inject constructor(
    private val repository: LiveTvRepository,
    private val playback: LiveTvPlaybackSession,
) : ViewModel() {
    private val catalog = MutableStateFlow(LiveTvCatalog())
    private val favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    private val selectedCategory = MutableStateFlow<ChannelCategory>(ChannelCategory.All)
    private val query = MutableStateFlow("")
    private val status = MutableStateFlow(Status())
    private val play = MutableSharedFlow<LiveTvPlayRequest>(extraBufferCapacity = 1)
    private var playing = false

    val playRequests: SharedFlow<LiveTvPlayRequest> = play.asSharedFlow()

    val uiState: StateFlow<ChannelsUiState> = combine(
        catalog,
        favoriteIds,
        selectedCategory,
        query,
        status,
    ) { loaded, favorites, category, search, state ->
        ChannelsUiState(
            loading = state.loading,
            error = state.error,
            categories = categoriesOf(loaded.channels),
            selectedCategory = category,
            query = search,
            rows = buildChannelRows(
                loaded.channels,
                loaded.guide,
                favorites,
                category,
                search,
                System.currentTimeMillis(),
                loaded.recentIds,
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChannelsUiState())

    init {
        viewModelScope.launch {
            try {
                val loaded = repository.loadCatalog(System.currentTimeMillis())
                catalog.value = loaded
                favoriteIds.value = loaded.favoriteIds
                status.value = Status(loading = false)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                status.value = Status(loading = false, error = error.message ?: error.javaClass.simpleName)
            }
        }
    }

    fun selectCategory(category: ChannelCategory) {
        selectedCategory.value = category
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun toggleFavorite(channelId: String) {
        val current = favoriteIds.value
        val next = if (channelId in current) current - channelId else current + channelId
        favoriteIds.value = next
        viewModelScope.launch {
            try {
                repository.toggleFavorite(channelId)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                favoriteIds.value = current
                status.update { it.copy(error = error.message ?: error.javaClass.simpleName) }
            }
        }
    }

    fun play(channel: LiveTvChannel) {
        if (playing) return
        playback.clearPending()
        viewModelScope.launch {
            playing = true
            try {
                val resolved = repository.resolvePlayableUrl(channel)
                if (resolved.url.isBlank()) error("No stream URL")
                repository.recordRecent(channel.id)
                val recents = capLiveTvRecentChannelIds(listOf(channel.id) + catalog.value.recentIds)
                catalog.value = catalog.value.copy(recentIds = recents)
                playback.arm(
                    channels = catalog.value.channels,
                    favoriteIds = favoriteIds.value,
                    category = selectedCategory.value,
                    currentId = channel.id,
                    recentIds = recents,
                )
                play.emit(
                    LiveTvPlayRequest(
                        streamUrl = resolved.url,
                        title = channel.name,
                        headers = resolved.headers,
                        logoUrl = channel.logoUrl,
                    )
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                status.update { it.copy(error = error.message ?: error.javaClass.simpleName) }
            } finally {
                playing = false
            }
        }
    }

    private data class Status(val loading: Boolean = true, val error: String? = null)
}

internal fun categoriesOf(channels: List<LiveTvChannel>): List<ChannelCategory> {
    val groups = channels.mapNotNull { it.group?.trim()?.takeIf(String::isNotEmpty) }.distinct().sorted()
    return listOf(ChannelCategory.All, ChannelCategory.Favourites, ChannelCategory.Recents) +
        groups.map { ChannelCategory.Group(it) }
}

internal fun buildChannelRows(
    channels: List<LiveTvChannel>,
    guide: XmltvGuide,
    favoriteIds: Set<String>,
    category: ChannelCategory,
    query: String,
    nowMillis: Long,
    recentIds: List<String> = emptyList(),
): List<ChannelRow> {
    val needle = query.trim()
    return channelsInCategory(channels, favoriteIds, category, recentIds).mapNotNull { channel ->
        if (needle.isNotEmpty() && !channel.name.contains(needle, ignoreCase = true)) return@mapNotNull null
        val epg = guide.nowNextFor(channel.id, channel.epgId, nowMillis)
        ChannelRow(
            channel = channel,
            nowTitle = epg.now?.title,
            nextTitle = epg.next?.title,
            favorite = channel.id in favoriteIds,
        )
    }
}

internal fun LiveTvChannel.matchesCategory(
    category: ChannelCategory,
    favoriteIds: Set<String>,
    recentIds: List<String> = emptyList(),
): Boolean = when (category) {
    ChannelCategory.All -> true
    ChannelCategory.Favourites -> id in favoriteIds
    ChannelCategory.Recents -> id in recentIds
    is ChannelCategory.Group -> group?.trim() == category.name
}

internal fun channelsInCategory(
    channels: List<LiveTvChannel>,
    favoriteIds: Set<String>,
    category: ChannelCategory,
    recentIds: List<String> = emptyList(),
): List<LiveTvChannel> {
    val matched = channels.filter { it.matchesCategory(category, favoriteIds, recentIds) }
    if (category != ChannelCategory.Recents) return matched
    val order = recentIds.withIndex().associate { it.value to it.index }
    return matched.sortedBy { order[it.id] ?: Int.MAX_VALUE }
}

/** Next wraps to the first channel in [category]; previous wraps to the last. */
internal fun adjacentChannel(
    channels: List<LiveTvChannel>,
    favoriteIds: Set<String>,
    category: ChannelCategory,
    currentId: String,
    next: Boolean,
    recentIds: List<String> = emptyList(),
): LiveTvChannel? {
    val list = channelsInCategory(channels, favoriteIds, category, recentIds)
    if (list.isEmpty()) return null
    val index = list.indexOfFirst { it.id == currentId }
    val start = if (index < 0) if (next) -1 else 0 else index
    val nextIndex = if (next) (start + 1) % list.size else (start - 1 + list.size) % list.size
    return list[nextIndex]
}

/** Down the on-screen list is next: D-pad Down, channel-down, and media-next. */
internal fun liveZapMovesNext(keyCode: Int): Boolean =
    keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
        keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN ||
        keyCode == KeyEvent.KEYCODE_MEDIA_NEXT
