package com.nuvio.tv.livetv.ui

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.data.SportsScoreboard
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @Test
    fun `a source that never answers stops loading at the timeout`() = runTest {
        val scores = mockk<SportsScoreboard>()
        coEvery { scores.games(any()) } coAnswers { awaitCancellation() }
        val viewModel = SportsViewModel(scores, mockk(), StandardTestDispatcher(testScheduler))
        runCurrent()

        assertTrue(viewModel.uiState.value.loading)
        advanceTimeBy(SPORTS_LOAD_TIMEOUT_MILLIS + 1)
        runCurrent()

        assertFalse(viewModel.uiState.value.loading)
        assertEquals("Couldn't load scores", viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.rows.isEmpty())
    }

    @Test
    fun `a failing source shows its error and stops loading`() = runTest {
        val scores = mockk<SportsScoreboard>()
        coEvery { scores.games(any()) } throws IllegalStateException("ESPN unreachable")
        val viewModel = SportsViewModel(scores, mockk(), StandardTestDispatcher(testScheduler))
        runCurrent()

        assertFalse(viewModel.uiState.value.loading)
        assertEquals("ESPN unreachable", viewModel.uiState.value.error)
    }
}
