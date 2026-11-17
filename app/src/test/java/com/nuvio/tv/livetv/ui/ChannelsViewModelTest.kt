package com.nuvio.tv.livetv.ui

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.livetv.data.LiveTvCatalog
import com.nuvio.tv.livetv.data.LiveTvChannel
import com.nuvio.tv.livetv.data.LiveTvEpgProgram
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.data.XmltvGuide
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelsViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @Test
    fun `category filter and search narrow the list`() = runTest {
        val viewModel = viewModel(catalog())
        runCurrent()

        viewModel.selectCategory(ChannelCategory.Group("News"))
        runCurrent()
        assertEquals(listOf("News One", "Other"), names(viewModel))

        viewModel.setQuery("one")
        runCurrent()
        assertEquals(listOf("News One"), names(viewModel))

        viewModel.selectCategory(ChannelCategory.All)
        viewModel.setQuery("TWO")
        runCurrent()
        assertEquals(listOf("Sport Two"), names(viewModel))
    }

    @Test
    fun `now and next come from the guide`() {
        val rows = buildChannelRows(
            channels = catalog().channels,
            guide = catalog().guide,
            favoriteIds = emptySet(),
            category = ChannelCategory.All,
            query = "",
            nowMillis = 1_000L,
        )
        val news = rows.first { it.channel.id == "c1" }
        assertEquals("On now", news.nowTitle)
        assertEquals("Up next", news.nextTitle)
        assertNull(rows.first { it.channel.id == "c2" }.nowTitle)
    }

    @Test
    fun `favourite toggle updates the list and the favourites category`() = runTest {
        val repository = repository(catalog())
        val viewModel = ChannelsViewModel(repository)
        runCurrent()

        viewModel.toggleFavorite("c1")
        runCurrent()
        assertTrue(viewModel.uiState.value.rows.first { it.channel.id == "c1" }.favorite)

        viewModel.selectCategory(ChannelCategory.Favourites)
        runCurrent()
        assertEquals(listOf("News One", "Sport Two"), names(viewModel))

        viewModel.toggleFavorite("c2")
        runCurrent()
        assertEquals(listOf("News One"), names(viewModel))
        coVerify { repository.toggleFavorite("c1") }
        coVerify { repository.toggleFavorite("c2") }
    }

    @Test
    fun `play records the channel in recents after the url resolves`() = runTest {
        val repository = repository(catalog())
        coEvery { repository.resolvePlayableUrl(any()) } coAnswers {
            firstArg<LiveTvChannel>().copy(streamUrl = "http://play", headers = mapOf("User-Agent" to "VLC"))
        }
        val viewModel = ChannelsViewModel(repository)
        val plays = mutableListOf<LiveTvPlayRequest>()
        val collect = launch { viewModel.playRequests.collect { plays += it } }
        runCurrent()

        viewModel.play(catalog().channels.first())
        runCurrent()

        assertEquals("http://play", plays.single().streamUrl)
        assertEquals(mapOf("User-Agent" to "VLC"), plays.single().headers)
        coVerifyOrder {
            repository.resolvePlayableUrl(any())
            repository.recordRecent("c1")
        }
        collect.cancel()
    }

    @Test
    fun `a failed play does not record a recent`() = runTest {
        val repository = repository(catalog())
        coEvery { repository.resolvePlayableUrl(any()) } throws IllegalStateException("no link")
        val viewModel = ChannelsViewModel(repository)
        runCurrent()

        viewModel.play(catalog().channels.first())
        runCurrent()

        assertEquals("no link", viewModel.uiState.value.error)
        coVerify(exactly = 0) { repository.recordRecent(any()) }
    }

    private fun viewModel(catalog: LiveTvCatalog) = ChannelsViewModel(repository(catalog))

    private fun repository(catalog: LiveTvCatalog): LiveTvRepository = mockk {
        coEvery { loadCatalog() } returns catalog
        coEvery { toggleFavorite(any()) } returns Unit
        coEvery { recordRecent(any()) } returns Unit
        coEvery { resolvePlayableUrl(any()) } answers { firstArg() }
    }

    private fun names(viewModel: ChannelsViewModel) = viewModel.uiState.value.rows.map { it.channel.name }

    private fun catalog(): LiveTvCatalog {
        val channels = listOf(
            channel("c1", "News One", "News", "bbc1"),
            channel("c2", "Sport Two", "Sport"),
            channel("c3", "Other", "News"),
        )
        return LiveTvCatalog(
            channels = channels,
            guide = XmltvGuide(
                programsByTvgId = mapOf(
                    "bbc1" to listOf(
                        LiveTvEpgProgram("On now", 0L, 2_000L),
                        LiveTvEpgProgram("Up next", 2_000L, 4_000L),
                    )
                )
            ),
            favoriteIds = setOf("c2"),
        )
    }

    private fun channel(id: String, name: String, group: String, epgId: String? = null) = LiveTvChannel(
        id = id,
        name = name,
        streamUrl = "http://$id",
        group = group,
        epgId = epgId,
    )
}
