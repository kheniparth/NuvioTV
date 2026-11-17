package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.livetv.data.LiveTvConnectionTarget
import com.nuvio.tv.livetv.data.LiveTvConnectionTester
import com.nuvio.tv.livetv.data.local.LiveTvEpgSource
import com.nuvio.tv.livetv.data.local.LiveTvPlaylist
import com.nuvio.tv.livetv.data.local.LiveTvSettingsDataStore
import com.nuvio.tv.livetv.data.local.LiveTvStalkerSettings
import com.nuvio.tv.livetv.data.local.LiveTvXtreamSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LiveTvTestState(
    val isTesting: Boolean = false,
    val success: Boolean = false,
    val channelCount: Int? = null,
    val error: String? = null,
)

data class LiveTvSettingsUiState(
    val playlists: List<LiveTvPlaylist> = emptyList(),
    val xtream: LiveTvXtreamSettings = LiveTvXtreamSettings(),
    val stalker: LiveTvStalkerSettings = LiveTvStalkerSettings(),
    val epgSources: List<LiveTvEpgSource> = emptyList(),
    val testStates: Map<String, LiveTvTestState> = emptyMap(),
)

@HiltViewModel
class LiveTvSettingsViewModel @Inject constructor(
    private val dataStore: LiveTvSettingsDataStore,
    private val tester: LiveTvConnectionTester,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LiveTvSettingsUiState())
    val uiState: StateFlow<LiveTvSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            dataStore.settings.collectLatest { settings ->
                _uiState.update {
                    it.copy(
                        playlists = settings.playlists,
                        xtream = settings.xtream,
                        stalker = settings.stalker,
                        epgSources = settings.epgSources,
                    )
                }
            }
        }
    }

    fun savePlaylist(playlist: LiveTvPlaylist) {
        val updated = _uiState.value.playlists.filterNot { it.id == playlist.id } + playlist
        viewModelScope.launch { dataStore.setPlaylists(updated) }
    }

    fun removePlaylist(id: String) {
        val updated = _uiState.value.playlists.filterNot { it.id == id }
        viewModelScope.launch { dataStore.setPlaylists(updated) }
    }

    fun saveEpgSource(source: LiveTvEpgSource) {
        val updated = _uiState.value.epgSources.filterNot { it.id == source.id } + source
        viewModelScope.launch { dataStore.setEpgSources(updated) }
    }

    fun removeEpgSource(id: String) {
        val updated = _uiState.value.epgSources.filterNot { it.id == id }
        viewModelScope.launch { dataStore.setEpgSources(updated) }
    }

    fun saveXtream(settings: LiveTvXtreamSettings) {
        viewModelScope.launch { dataStore.setXtream(settings) }
    }

    fun saveStalker(settings: LiveTvStalkerSettings) {
        viewModelScope.launch { dataStore.setStalker(settings) }
    }

    fun testConnection(target: LiveTvConnectionTarget) {
        val key = target.key
        _uiState.update { it.copy(testStates = it.testStates + (key to LiveTvTestState(isTesting = true))) }
        viewModelScope.launch {
            val result = try {
                LiveTvTestState(success = true, channelCount = tester.channelCount(target))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                LiveTvTestState(error = error.message ?: error.javaClass.simpleName)
            }
            _uiState.update { it.copy(testStates = it.testStates + (key to result)) }
        }
    }
}
