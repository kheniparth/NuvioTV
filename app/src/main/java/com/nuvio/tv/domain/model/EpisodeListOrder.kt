package com.nuvio.tv.domain.model

enum class EpisodeListOrder {
    OldestFirst,
    LatestFirst,
}

/**
 * Season, then episode. Missing numbers stay last in both orders.
 * Season tabs keep specials (0) last so Oldest first matches today's strip.
 */
fun <T> List<T>.orderedEpisodes(
    order: EpisodeListOrder,
    season: (T) -> Int?,
    episode: (T) -> Int?,
): List<T> {
    val latest = order == EpisodeListOrder.LatestFirst
    return sortedWith(
        compareBy<T>(
            { seasonRank(season(it) ?: 0, latest) },
            { episodeRank(episode(it), latest) },
        )
    )
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
