package com.nuvio.tv.livetv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class LiveTvImportTest {

    @Test
    fun isTrustedImportCaller_rootAndShellOnly() {
        assertTrue(isTrustedImportCaller(0))
        assertTrue(isTrustedImportCaller(2000))
        assertFalse(isTrustedImportCaller(1000))
        assertFalse(isTrustedImportCaller(10123))
        assertFalse(isTrustedImportCaller(-1))
    }

    @Test
    fun handleLiveTvImport_untrustedNeverCallsApply() {
        val calls = AtomicInteger(0)
        try {
            handleLiveTvImport(10123, "import", encode(goodPayload())) {
                calls.incrementAndGet()
                true
            }
            fail("expected SecurityException")
        } catch (e: SecurityException) {
            assertEquals("refused", e.message)
        }
        assertEquals(0, calls.get())
    }

    @Test
    fun handleLiveTvImport_badArgAndUnknownMethod() {
        assertEquals(
            "error:payload",
            handleLiveTvImport(2000, "import", "%%%not-base64%%%") { true },
        )
        assertEquals(
            "error:payload",
            handleLiveTvImport(2000, "import", null) { true },
        )
        assertEquals(
            "error:method",
            handleLiveTvImport(2000, "export", encode(goodPayload())) { true },
        )
    }

    @Test
    fun handleLiveTvImport_trustedGoodArgCallsApplyOnce() {
        val calls = AtomicInteger(0)
        val result = handleLiveTvImport(2000, "import", encode(goodPayload())) {
            calls.incrementAndGet()
            true
        }
        assertEquals("updated", result)
        assertEquals(1, calls.get())
    }

    @Test
    fun merge_keepsDisabledFlagsOtherPlaylistEpgAndStalkerUntouched() {
        val current = LiveTvSettings(
            playlists = listOf(
                LiveTvPlaylist("other", "Other", LiveTvPlaylistType.Url, "https://x/other.m3u", true),
                LiveTvPlaylist(STALKER_M3U_PLAYLIST_ID, "Stalker M3U", LiveTvPlaylistType.Url, "https://x/old.m3u", false),
            ),
            xtream = LiveTvXtreamSettings("https://x/old", "u", "p", isEnabled = false),
            stalker = LiveTvStalkerSettings(
                portalUrl = "https://x/portal",
                macAddress = "00:11:22:33:44:55",
                username = "keep",
                password = "keep",
                isEnabled = false,
            ),
            epgSources = listOf(LiveTvEpgSource("e1", "Guide", "https://x/epg.xml", true)),
            favoriteChannelIds = listOf("fav1"),
            recentChannelIds = listOf("r1"),
        )
        val import = LiveTvImport(
            profileId = 1,
            m3uUrl = "https://x/t",
            xtreamUrl = "https://x/new",
            xtreamUser = "nu",
            xtreamPassword = "np",
        )

        val merged = mergeLiveTvImport(current, import)

        assertEquals(false, merged.xtream.isEnabled)
        assertEquals("https://x/new", merged.xtream.serverUrl)
        assertEquals("nu", merged.xtream.username)
        assertEquals("np", merged.xtream.password)

        val stalkerM3u = merged.playlists.first { it.id == STALKER_M3U_PLAYLIST_ID }
        assertEquals(false, stalkerM3u.isEnabled)
        assertEquals("Stalker M3U", stalkerM3u.name)
        assertEquals("https://x/t", stalkerM3u.source)
        assertEquals(
            LiveTvPlaylist("other", "Other", LiveTvPlaylistType.Url, "https://x/other.m3u", true),
            merged.playlists.first { it.id == "other" },
        )
        assertEquals(current.epgSources, merged.epgSources)
        assertEquals(current.favoriteChannelIds, merged.favoriteChannelIds)
        assertEquals(current.recentChannelIds, merged.recentChannelIds)
        assertEquals(current.stalker, merged.stalker)
    }

    @Test
    fun merge_appendsStalkerM3uWhenMissing() {
        val current = LiveTvSettings(
            playlists = listOf(
                LiveTvPlaylist("other", "Other", LiveTvPlaylistType.Url, "https://x/other.m3u", true),
            ),
        )
        val merged = mergeLiveTvImport(
            current,
            LiveTvImport(profileId = 1, m3uUrl = "https://x/t"),
        )
        assertEquals(2, merged.playlists.size)
        assertEquals(
            LiveTvPlaylist(STALKER_M3U_PLAYLIST_ID, "Stalker M3U", LiveTvPlaylistType.Url, "https://x/t", true),
            merged.playlists.last(),
        )
    }

    @Test
    fun merge_ignoresStalkerFieldsIfPresentInJson() {
        val current = LiveTvSettings(
            stalker = LiveTvStalkerSettings("https://x/keep", "AA:BB", "", "", false),
        )
        val b64 = encode(
            """{"profileId":1,"stalkerPortal":"https://x/evil","stalkerMac":"00:1a:79:aa:bb:cc","m3uUrl":"https://x/t"}""",
        )
        val import = parseLiveTvImport(b64)!!
        assertEquals("https://x/t", import.m3uUrl)
        val merged = mergeLiveTvImport(current, import)
        assertEquals(current.stalker, merged.stalker)
        assertNotEquals("https://x/evil", merged.stalker.portalUrl)
    }

    @Test
    fun merge_alreadyPresentReturnsEqual() {
        val current = LiveTvSettings(
            playlists = listOf(
                LiveTvPlaylist(STALKER_M3U_PLAYLIST_ID, "Stalker M3U", LiveTvPlaylistType.Url, "https://x/t", false),
            ),
            xtream = LiveTvXtreamSettings("https://x/t", "u", "p", false),
            stalker = LiveTvStalkerSettings("https://x/portal", "00:11", "", "", false),
        )
        val import = LiveTvImport(
            profileId = 1,
            m3uUrl = "https://x/t",
            xtreamUrl = "https://x/t",
            xtreamUser = "u",
            xtreamPassword = "p",
        )
        assertEquals(current, mergeLiveTvImport(current, import))
    }

    @Test
    fun merge_nullFieldsLeaveSourcesUnchanged() {
        val current = LiveTvSettings(
            playlists = listOf(
                LiveTvPlaylist(STALKER_M3U_PLAYLIST_ID, "Stalker M3U", LiveTvPlaylistType.Url, "https://x/old.m3u", false),
            ),
            xtream = LiveTvXtreamSettings("https://x/old", "ou", "op", false),
            stalker = LiveTvStalkerSettings("https://x/portal", "00:11", "su", "sp", false),
        )
        val merged = mergeLiveTvImport(current, LiveTvImport(profileId = 1))
        assertEquals(current, merged)
    }

    @Test
    fun parse_rejectsGarbage() {
        assertNull(parseLiveTvImport("not-base64!!"))
        assertNull(parseLiveTvImport(encode("{not json")))
        assertNull(parseLiveTvImport(encode("""{"m3uUrl":"https://x/t"}""")))
    }

    private fun goodPayload(): String =
        """{"profileId":1,"m3uUrl":"https://x/t","xtreamUrl":"https://x/t","xtreamUser":"u","xtreamPassword":"p"}"""

    private fun encode(json: String): String =
        Base64.getEncoder().encodeToString(json.toByteArray())
}
