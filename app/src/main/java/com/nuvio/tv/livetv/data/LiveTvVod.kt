package com.nuvio.tv.livetv.data

enum class LiveTvVodKind { Movies, Series }

enum class LiveTvVodProvider { Xtream, Stalker }

/** [id] is namespaced by provider; [remoteId] is what the provider API expects. */
data class LiveTvVodCategory(
    val id: String,
    val name: String,
    val remoteId: String = id,
)

data class LiveTvVodItem(
    val id: String,
    val remoteId: String,
    val kind: LiveTvVodKind,
    val provider: LiveTvVodProvider,
    val name: String,
    val posterUrl: String? = null,
    val categoryId: String? = null,
    val rating: Double? = null,
    val plot: String? = null,
    val year: String? = null,
    val extension: String? = null,
    /** Stalker only: the portal command resolved with `create_link` when the item is played. */
    val command: String? = null,
)

data class LiveTvVodEpisode(
    val id: String,
    val season: Int,
    val episodeNumber: Int,
    val title: String,
    val plot: String? = null,
    val imageUrl: String? = null,
    val durationText: String? = null,
    val streamUrl: String = "",
    val extension: String = "mp4",
    val command: String? = null,
)

data class LiveTvVodCatalog(
    val categories: List<LiveTvVodCategory> = emptyList(),
    val items: List<LiveTvVodItem> = emptyList(),
)

data class LiveTvVodPlayback(
    val title: String,
    val streamUrl: String,
    val headers: Map<String, String>,
    val contentType: String,
    val posterUrl: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
)

internal fun filterLiveTvVodItems(
    items: List<LiveTvVodItem>,
    categoryId: String?,
    searchQuery: String,
): List<LiveTvVodItem> {
    val query = searchQuery.trim()
    return items.filter { item ->
        (categoryId == null || item.categoryId == categoryId) &&
            (query.isEmpty() || item.name.contains(query, ignoreCase = true))
    }
}
