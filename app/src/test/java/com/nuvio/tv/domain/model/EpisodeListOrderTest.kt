package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeListOrderTest {
    private data class Ep(val season: Int?, val episode: Int?)

    @Test
    fun oldestFirstSortsBySeasonThenEpisode() {
        val episodes = listOf(Ep(2, 1), Ep(1, 10), Ep(1, 2), Ep(2, 3))
        assertEquals(
            listOf(1 to 2, 1 to 10, 2 to 1, 2 to 3),
            episodes.ordered(EpisodeListOrder.OldestFirst),
        )
    }

    @Test
    fun latestFirstPutsHighestSeasonAndEpisodeFirst() {
        val episodes = listOf(Ep(1, 2), Ep(2, 1), Ep(1, 10), Ep(2, 3))
        assertEquals(
            listOf(2 to 3, 2 to 1, 1 to 10, 1 to 2),
            episodes.ordered(EpisodeListOrder.LatestFirst),
        )
    }

    @Test
    fun missingEpisodeNumbersStayLast() {
        val episodes = listOf(Ep(1, null), Ep(1, 2), Ep(1, 1))
        assertEquals(listOf(1 to 1, 1 to 2, 1 to null), episodes.ordered(EpisodeListOrder.OldestFirst))
        assertEquals(listOf(1 to 2, 1 to 1, 1 to null), episodes.ordered(EpisodeListOrder.LatestFirst))
    }

    @Test
    fun seasonTabsKeepSpecialsLast() {
        val seasons = listOf(0, 2, 1)
        assertEquals(listOf(1, 2, 0), seasons.orderedSeasonTabs(EpisodeListOrder.OldestFirst))
        assertEquals(listOf(2, 1, 0), seasons.orderedSeasonTabs(EpisodeListOrder.LatestFirst))
    }

    private fun List<Ep>.ordered(order: EpisodeListOrder) =
        orderedEpisodes(order, { it.season }, { it.episode }).map { (it.season ?: 0) to it.episode }
}
