package com.nuvio.tv.livetv.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgSourceTest {
    @Test
    fun `selects a saved guide, playlist url-tvg, xtream xmltv and stalker`() {
        val header = """
            #EXTM3U url-tvg="https://a/epg.xml, http://b/epg.gz" x-tvg-url="ftp://nope" tvg-url="https://c/epg.xml"
            #EXTINF:-1 tvg-id="bbc1" tvg-logo="http://logo/bbc.png",BBC
            http://stream/bbc.ts
        """.trimIndent()

        val selected = selectEpgSources(
            saved = listOf(
                LiveTvEpgSource("e", "Guide", "https://saved/epg.xml"),
                LiveTvEpgSource("off", "Off", "https://off/epg.xml", isEnabled = false),
            ),
            playlistEpgUrls = parseM3uEpgUrls(header) + listOf("https://saved/epg.xml", "ftp://no"),
            xtream = LiveTvXtreamSettings("http://panel.example/player_api.php/", "user name", "p@ss"),
            stalker = LiveTvStalkerSettings("http://portal", "00:1A:79:00:00:01"),
        )

        assertEquals(
            listOf(
                "https://saved/epg.xml",
                "https://a/epg.xml",
                "http://b/epg.gz",
                "https://c/epg.xml",
                "http://panel.example/xmltv.php?username=user%20name&password=p%40ss",
            ),
            selected.xmltvUrls,
        )
        assertTrue(selected.stalker)
        assertEquals(emptyList<String>(), parseM3uEpgUrls("#EXTINF:-1,BBC\nhttp://bbc\n"))
        val off = selectEpgSources(
            saved = emptyList(),
            playlistEpgUrls = emptyList(),
            xtream = LiveTvXtreamSettings(),
            stalker = LiveTvStalkerSettings("http://portal", "00:1A:79:00:00:01", isEnabled = false),
        )
        assertFalse(off.stalker)
        assertTrue(off.xmltvUrls.isEmpty())
    }

    @Test
    fun `stalker get_epg_info maps to the channel id and ignores a bad slot`() {
        val root = Json.parseToJsonElement(STALKER_EPG).jsonObject
        val guide = XmltvGuide(programsByTvgId = parseStalkerEpg(root))
        val noon = 1_794_916_800_000L

        val during = guide.nowNextFor("Stalker:9", "missing-xmltv", noon + 30 * 60_000L)
        assertEquals("News", during.now?.title)
        assertEquals("Now", during.now?.description)
        assertEquals("Later", during.next?.title)
        assertTrue(guide.programsFor("stalker:9", "missing-xmltv").none { it.title == "Backwards" })
        assertTrue(guide.programsFor("nope", null).isEmpty())
    }

    @Test
    fun `portal and playlist logos stay absolute`() {
        assertEquals(
            "http://host/stalker_portal/misc/logos/9.png",
            resolveLogoUrl("/stalker_portal/misc/logos/9.png", "http://host/stalker_portal/server/load.php"),
        )
        assertEquals("http://logo/bbc.png", resolveLogoUrl("http://logo/bbc.png", "https://x/playlist.m3u"))
        assertNull(resolveLogoUrl("logo.png", "not a url"))
        assertNull(resolveLogoUrl("  ", "http://host"))
    }

    private companion object {
        // Later is first so the guide is wrong unless programmes are sorted by start.
        val STALKER_EPG = """
            {"js":{"data":{
              "9":[
                {"name":"Later","start_timestamp":1794920400,"stop_timestamp":1794924000},
                {"name":"Backwards","start_timestamp":"5","stop_timestamp":"5"},
                {"name":"News","start_timestamp":"1794916800","stop_timestamp":"1794920400","descr":"Now"}
              ],
              "":[{"name":"Nope","start_timestamp":"1","stop_timestamp":"2"}]
            }}}
        """.trimIndent()
    }
}