package com.nuvio.tv.domain.model

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeListOrderTest {
    private data class Ep(val season: Int?, val episode: Int?, val released: String? = null)

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
    fun latestFirstPutsAiredEpisodesBeforeUnairedAndTba() {
        val episodes = listOf(
            Ep(2, 1, "2026-11-01"),
            Ep(2, 2, "2026-11-15"),
            Ep(2, 3, "2026-12-01"),
            Ep(2, 4, null),
            Ep(0, 1, "2026-11-10"),
        )
        assertEquals(
            listOf(2 to 2, 2 to 1, 2 to 3, 2 to 4, 0 to 1),
            episodes.ordered(EpisodeListOrder.LatestFirst, airDateNow()),
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

    private fun airDateNow(): Clock =
        Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC)

    private fun List<Ep>.ordered(order: EpisodeListOrder, clock: Clock = airDateNow()) =
        orderedEpisodes(order, { it.season }, { it.episode }, { it.released }, clock)
            .map { (it.season ?: 0) to it.episode }
}
