package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.data.local.LiveTvEpgSource as StoredEpg
import com.nuvio.tv.livetv.data.local.LiveTvPlaylist as StoredPlaylist
import com.nuvio.tv.livetv.data.local.LiveTvPlaylistType as StoredType
import com.nuvio.tv.livetv.data.local.LiveTvSettings
import com.nuvio.tv.livetv.data.local.LiveTvStalkerSettings as StoredStalker
import com.nuvio.tv.livetv.data.local.LiveTvXtreamSettings as StoredXtream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTvSourceProviderTest {
    @Test
    fun `enabled configured sources are kept`() {
        val sources = LiveTvSettings(
            playlists = listOf(
                StoredPlaylist("on", "A", StoredType.Url, "https://a"),
                StoredPlaylist("off", "B", StoredType.LocalFile, "/tv.m3u", isEnabled = false),
            ),
            xtream = StoredXtream("http://x", "u", "p", isEnabled = false),
            stalker = StoredStalker("http://s", "00:1A:79:00:00:01"),
            epgSources = listOf(StoredEpg("e", "Guide", "https://epg"), StoredEpg("x", "Off", "https://off", isEnabled = false)),
            favoriteChannelIds = listOf("fav"),
            recentChannelIds = listOf("recent"),
        ).toSources()

        assertEquals(listOf("on"), sources.playlists.map { it.id })
        assertEquals(LiveTvPlaylistType.Url, sources.playlists.single().type)
        assertNull(sources.xtream)
        assertEquals("http://s", sources.stalker?.portalUrl)
        assertEquals(listOf("e"), sources.epgSources.map { it.id })
    }
}
