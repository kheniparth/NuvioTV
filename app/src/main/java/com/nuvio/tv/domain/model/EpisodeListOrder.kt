package com.nuvio.tv.domain.model

import com.nuvio.tv.core.util.isEpisodeReleaseAired
import com.nuvio.tv.core.util.parseEpisodeReleaseInstant
import java.time.Clock

enum class EpisodeListOrder {
    OldestFirst,
    LatestFirst,
}

/**
 * Season, then episode. Missing numbers stay last in both orders.
 * Season tabs keep specials (0) last so Oldest first matches today's strip.
 * Latest first puts aired episodes newest first, then unaired/TBA by season and episode.
 */
fun <T> List<T>.orderedEpisodes(
    order: EpisodeListOrder,
    season: (T) -> Int?,
    episode: (T) -> Int?,
    released: (T) -> String? = { null },
    clock: Clock = Clock.systemDefaultZone(),
): List<T> {
    if (order == EpisodeListOrder.OldestFirst) {
        return sortedWith(
            compareBy<T>(
                { seasonRank(season(it) ?: 0, false) },
                { episodeRank(episode(it), false) },
            )
        )
    }
    val (specials, regular) = partition { (season(it) ?: 0) == 0 }
    val (aired, unaired) = regular.partition { isEpisodeReleaseAired(released(it), clock) == true }
    val airedSorted = aired.sortedByDescending { parseEpisodeReleaseInstant(released(it), clock.zone) }
    val unairedSorted = if (aired.isEmpty()) {
        // No air dates at all (Live TV VOD, say): keep the old highest-number-first order.
        unaired.sortedWith(
            compareBy<T>(
                { seasonRank(season(it) ?: 0, true) },
                { episodeRank(episode(it), true) },
            )
        )
    } else {
        unaired.sortedWith(
            compareBy<T>(
                { season(it) ?: 0 },
                { episodeRank(episode(it), false) },
            )
        )
    }
    val specialsSorted = specials.sortedWith(compareBy { episodeRank(episode(it), true) })
    return airedSorted + unairedSorted + specialsSorted
}

fun List<Int>.orderedSeasonTabs(order: EpisodeListOrder): List<Int> {
    val regular = filter { it > 0 }
    val specials = filter { it == 0 }
    val ordered = if (order == EpisodeListOrder.LatestFirst) regular.sortedDescending() else regular.sorted()
    return ordered + specials
}

private fun seasonRank(season: Int, latestFirst: Boolean): Int =
    if (latestFirst) -season else season

private fun episodeRank(episode: Int?, latestFirst: Boolean): Int {
    if (episode == null) return Int.MAX_VALUE
    return if (latestFirst) -episode else episode
}
