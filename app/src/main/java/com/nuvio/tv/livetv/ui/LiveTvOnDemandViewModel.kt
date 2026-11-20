package com.nuvio.tv.livetv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.livetv.data.LiveTvVodCatalog
import com.nuvio.tv.livetv.data.LiveTvVodEpisode
import com.nuvio.tv.livetv.data.LiveTvVodItem
import com.nuvio.tv.livetv.data.LiveTvVodKind
import com.nuvio.tv.livetv.data.LiveTvVodPlayback
import com.nuvio.tv.livetv.data.LiveTvVodRepository
import com.nuvio.tv.data.local.EpisodeListOrderDataStore
import com.nuvio.tv.domain.model.EpisodeListOrder
import com.nuvio.tv.livetv.data.local.LiveTvSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LiveTvVodCatalogState(
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val errorMessage: String? = null,
    val catalog: LiveTvVodCatalog = LiveTvVodCatalog(),
)

data class LiveTvVodEpisodesState(
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val errorMessage: String? = null,
    val episodes: List<LiveTvVodEpisode> = emptyList(),
)

data class LiveTvOnDemandUiState(
    val kind: LiveTvVodKind = LiveTvVodKind.Movies,
    val catalogs: Map<LiveTvVodKind, LiveTvVodCatalogState> = emptyMap(),
    val hasSource: Boolean = true,
    val favoriteIds: Set<String> = emptySet(),
    val episodeListOrder: EpisodeListOrder = EpisodeListOrder.OldestFirst,
    val episodes: Map<String, LiveTvVodEpisodesState> = emptyMap(),
) {
    fun catalog(kind: LiveTvVodKind): LiveTvVodCatalogState =
        catalogs[kind] ?: LiveTvVodCatalogState()
}

@HiltViewModel
class LiveTvOnDemandViewModel @Inject constructor(
    private val repository: LiveTvVodRepository,
    private val store: LiveTvSettingsDataStore,
    private val episodeListOrderStore: EpisodeListOrderDataStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LiveTvOnDemandUiState())
    val uiState: StateFlow<LiveTvOnDemandUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            store.settings.collect { settings ->
                _uiState.update { it.copy(favoriteIds = settings.favoriteVodIds.toSet()) }
            }
        }
        viewModelScope.launch {
            episodeListOrderStore.order.collect { order ->
                _uiState.update { it.copy(episodeListOrder = order) }
            }
        }
        load(LiveTvVodKind.Movies)
    }

    fun setEpisodeListOrder(order: EpisodeListOrder) {
        _uiState.update { it.copy(episodeListOrder = order) }
        viewModelScope.launch { episodeListOrderStore.set(order) }
    }

    fun selectKind(kind: LiveTvVodKind) {
        _uiState.update { it.copy(kind = kind) }
        load(kind)
    }

    fun refresh() = load(_uiState.value.kind, force = true)

    fun loadEpisodes(series: LiveTvVodItem) {
        val existing = _uiState.value.episodes[series.id]
        if (existing != null && (existing.isLoading || existing.hasLoaded)) return
        _uiState.update { it.copy(episodes = it.episodes + (series.id to LiveTvVodEpisodesState(isLoading = true))) }
        viewModelScope.launch {
            val state = try {
                LiveTvVodEpisodesState(isLoading = false, hasLoaded = true, episodes = repository.episodes(series))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                LiveTvVodEpisodesState(
                    hasLoaded = true,
                    errorMessage = error.message ?: "Could not load episodes.",
                )
            }
            _uiState.update { it.copy(episodes = it.episodes + (series.id to state)) }
        }
    }

    fun retryEpisodes(series: LiveTvVodItem) {
        _uiState.update { it.copy(episodes = it.episodes - series.id) }
        loadEpisodes(series)
    }

    fun toggleFavorite(id: String) {
        viewModelScope.launch {
            val current = store.settings.first().favoriteVodIds
            store.setFavoriteVodIds(if (id in current) current - id else current + id)
        }
    }

    suspend fun playback(item: LiveTvVodItem): LiveTvVodPlayback? =
        playbackOrNull { repository.playback(item) }

    suspend fun playback(series: LiveTvVodItem, episode: LiveTvVodEpisode): LiveTvVodPlayback? =
        playbackOrNull { repository.playback(series, episode) }

    private suspend fun playbackOrNull(block: suspend () -> LiveTvVodPlayback?): LiveTvVodPlayback? =
        try {
            block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }

    private fun load(kind: LiveTvVodKind, force: Boolean = false) {
        val current = _uiState.value.catalogs[kind]
        if (!force && current != null && (current.isLoading || current.hasLoaded)) return
        _uiState.update {
            it.copy(
                catalogs = it.catalogs + (kind to (current ?: LiveTvVodCatalogState()).copy(
                    isLoading = true,
                    errorMessage = null,
                )),
            )
        }
        viewModelScope.launch {
            val state = try {
                val hasSource = repository.hasSource()
                val catalog = repository.catalog(kind)
                _uiState.update { it.copy(hasSource = hasSource) }
                LiveTvVodCatalogState(hasLoaded = true, catalog = catalog)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                LiveTvVodCatalogState(
                    hasLoaded = true,
                    errorMessage = error.message ?: "Could not load On Demand.",
                )
            }
            _uiState.update { it.copy(catalogs = it.catalogs + (kind to state)) }
        }
    }
}
