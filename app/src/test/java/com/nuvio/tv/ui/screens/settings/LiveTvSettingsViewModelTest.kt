package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.livetv.data.LiveTvConnectionTarget
import com.nuvio.tv.livetv.data.LiveTvConnectionTester
import com.nuvio.tv.livetv.data.local.LiveTvEpgSource
import com.nuvio.tv.livetv.data.local.LiveTvPlaylist
import com.nuvio.tv.livetv.data.local.LiveTvPlaylistType
import com.nuvio.tv.livetv.data.local.LiveTvSettings
import com.nuvio.tv.livetv.data.local.LiveTvSettingsDataStore
import com.nuvio.tv.livetv.data.local.LiveTvStalkerSettings
import com.nuvio.tv.livetv.data.local.LiveTvXtreamSettings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTvSettingsViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @Test
    fun `playlists and EPG sources can be added edited and removed`() = runTest {
        val (store, _) = fakeStore()
        val viewModel = LiveTvSettingsViewModel(store, mockk(relaxed = true))
        runCurrent()

        val playlist = LiveTvPlaylist("p1", "One", LiveTvPlaylistType.Url, "http://one")
        viewModel.savePlaylist(playlist)
        runCurrent()
        assertEquals(listOf(playlist), viewModel.uiState.value.playlists)

        viewModel.savePlaylist(playlist.copy(name = "Edited"))
        runCurrent()
        assertEquals("Edited", viewModel.uiState.value.playlists.single().name)

        val epg = LiveTvEpgSource("e1", "Guide", "http://guide.xml")
        viewModel.saveEpgSource(epg)
        runCurrent()
        assertEquals(listOf(epg), viewModel.uiState.value.epgSources)

        viewModel.removePlaylist("p1")
        viewModel.removeEpgSource("e1")
        runCurrent()
        assertTrue(viewModel.uiState.value.playlists.isEmpty())
        assertTrue(viewModel.uiState.value.epgSources.isEmpty())
    }

    @Test
    fun `xtream and stalker accounts are saved`() = runTest {
        val (store, _) = fakeStore()
        val viewModel = LiveTvSettingsViewModel(store, mockk(relaxed = true))
        runCurrent()

        val xtream = LiveTvXtreamSettings("http://panel", "user", "pass")
        val stalker = LiveTvStalkerSettings("http://portal", "00:1A:79:00:00:01")
        viewModel.saveXtream(xtream)
        viewModel.saveStalker(stalker)
        runCurrent()

        assertEquals(xtream, viewModel.uiState.value.xtream)
        assertEquals(stalker, viewModel.uiState.value.stalker)
    }

    @Test
    fun `test connection publishes the channel count`() = runTest {
        val (store, _) = fakeStore()
        val tester = mockk<LiveTvConnectionTester> {
            coEvery { channelCount(any()) } returns 7
        }
        val viewModel = LiveTvSettingsViewModel(store, tester)
        runCurrent()

        val playlist = LiveTvPlaylist("p1", "One", LiveTvPlaylistType.Url, "http://one")
        viewModel.testConnection(LiveTvConnectionTarget.Playlist(playlist))
        runCurrent()

        val result = viewModel.uiState.value.testStates.getValue("p1")
        assertTrue(result.success)
        assertEquals(7, result.channelCount)
        assertNull(result.error)
    }

    @Test
    fun `test connection publishes the error`() = runTest {
        val (store, _) = fakeStore()
        val tester = mockk<LiveTvConnectionTester> {
            coEvery { channelCount(any()) } throws IllegalStateException("unreachable")
        }
        val viewModel = LiveTvSettingsViewModel(store, tester)
        runCurrent()

        val source = LiveTvEpgSource("e1", "Guide", "http://guide.xml")
        viewModel.testConnection(LiveTvConnectionTarget.Epg(source))
        runCurrent()

        val result = viewModel.uiState.value.testStates.getValue("e1")
        assertEquals("unreachable", result.error)
        assertTrue(!result.success)
    }

    private fun fakeStore(): Pair<LiveTvSettingsDataStore, MutableStateFlow<LiveTvSettings>> {
        val state = MutableStateFlow(LiveTvSettings())
        val store = mockk<LiveTvSettingsDataStore> {
            every { settings } returns state
            coEvery { setPlaylists(any()) } coAnswers {
                state.value = state.value.copy(playlists = firstArg())
            }
            coEvery { setXtream(any()) } coAnswers {
                state.value = state.value.copy(xtream = firstArg())
            }
            coEvery { setStalker(any()) } coAnswers {
                state.value = state.value.copy(stalker = firstArg())
            }
            coEvery { setEpgSources(any()) } coAnswers {
                state.value = state.value.copy(epgSources = firstArg())
            }
        }
        return store to state
    }
}
