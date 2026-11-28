package com.nuvio.tv.livetv.data

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** On Demand for the enabled Xtream and Stalker sources. */
@Singleton
class LiveTvVodRepository @Inject constructor(
    private val sources: LiveTvSourceProvider,
    private val xtream: XtreamClient,
    private val stalker: StalkerClient,
) {
    suspend fun hasSource(): Boolean = sources.current().let { it.xtream != null || it.stalker != null }

    suspend fun catalog(kind: LiveTvVodKind): LiveTvVodCatalog {
        val current = sources.current()
        val loads = buildList<suspend () -> LiveTvVodCatalog> {
            current.xtream?.let { settings -> add { xtream.vodCatalog(settings, kind) } }
            current.stalker?.let { settings -> add { stalker.vodCatalog(settings, kind) } }
        }
        if (loads.isEmpty()) return LiveTvVodCatalog()
        val results = coroutineScope { loads.map { load -> async { runCatching { load() } } }.awaitAll() }
        val categories = mutableListOf<LiveTvVodCategory>()
        val items = mutableListOf<LiveTvVodItem>()
        var loaded = 0
        results.forEach { result ->
            result.fold(
                onSuccess = {
                    loaded++
                    categories += it.categories
                    items += it.items
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    Log.w(TAG, "Failed to load an On Demand catalog", error)
                },
            )
        }
        if (loaded == 0) error("Could not load On Demand.")
        return LiveTvVodCatalog(categories, items.distinctBy { it.id })
    }

    suspend fun episodes(series: LiveTvVodItem): List<LiveTvVodEpisode> {
        val current = sources.current()
        return when (series.provider) {
            LiveTvVodProvider.Xtream -> current.xtream?.let { xtream.seriesEpisodes(it, series.remoteId) }
            LiveTvVodProvider.Stalker -> current.stalker?.let { stalker.vodEpisodes(it, series.remoteId) }
        }.orEmpty()
    }

    suspend fun playback(item: LiveTvVodItem): LiveTvVodPlayback? {
        val current = sources.current()
        return when (item.provider) {
            LiveTvVodProvider.Xtream -> {
                val settings = current.xtream ?: return null
                LiveTvVodPlayback(
                    title = item.name,
                    streamUrl = xtream.movieStreamUrl(settings, item),
                    headers = xtream.vodHeaders,
                    contentType = "movie",
                    posterUrl = item.posterUrl,
                )
            }

            LiveTvVodProvider.Stalker -> {
                val settings = current.stalker ?: return null
                val command = item.command ?: return null
                val playable = stalker.createVodLink(settings, item.kind, command)
                LiveTvVodPlayback(
                    title = item.name,
                    streamUrl = playable.url,
                    headers = playable.headers,
                    contentType = "movie",
                    posterUrl = item.posterUrl,
                )
            }
        }
    }

    suspend fun playback(series: LiveTvVodItem, episode: LiveTvVodEpisode): LiveTvVodPlayback? {
        val current = sources.current()
        val playable = when (series.provider) {
            LiveTvVodProvider.Xtream -> {
                current.xtream ?: return null
                LiveTvPlayable(episode.streamUrl, xtream.vodHeaders)
            }

            LiveTvVodProvider.Stalker -> {
                val settings = current.stalker ?: return null
                val command = episode.command ?: return null
                stalker.createVodLink(settings, LiveTvVodKind.Series, command)
            }
        }
        return LiveTvVodPlayback(
            title = series.name,
            streamUrl = playable.url,
            headers = playable.headers,
            contentType = "series",
            posterUrl = series.posterUrl,
            seasonNumber = episode.season,
            episodeNumber = episode.episodeNumber,
            episodeTitle = episode.title,
        )
    }

    private companion object {
        const val TAG = "LiveTvVodRepository"
    }
}
