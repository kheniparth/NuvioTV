package com.nuvio.tv.livetv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class EpgTest {
    @Test
    fun `parses offsets now next window and a channel with no programmes`() {
        val noon = 1_794_916_800_000L
        assertEquals(noon, parseXmltvTime("20261117120000 +0000"))
        assertEquals(noon, parseXmltvTime("20261117140000 +0200"))
        assertEquals(noon, parseXmltvTime("20261117070000 -0500"))
        assertEquals(noon, parseXmltvTime("20261117120000"))
        assertNull(parseXmltvTime("20261117"))

        val guide = parseXmltv(FIXTURE)
        assertEquals(setOf("bbc1.uk", "empty.chan"), guide.channelIds)

        val empty = guide.nowNext("empty.chan", noon)
        assertNull(empty.now)
        assertNull(empty.next)
        assertTrue(guide.programsInWindow("empty.chan", noon, noon + 3_600_000L).isEmpty())

        val duringNews = guide.nowNext("BBC1.uk", noon + 30 * 60_000L)
        assertEquals("News & Weather", duringNews.now?.title)
        assertEquals("The midday news", duringNews.now?.description)
        assertEquals(noon, duringNews.now?.startMillis)
        assertEquals("Later", duringNews.next?.title)

        val window = guide.programsInWindow("bbc1.uk", noon + 30 * 60_000L, noon + 3_600_000L)
        assertEquals(listOf("News & Weather"), window.map { it.title })

        val after = guide.nowNext("bbc1.uk", noon + 2 * 3_600_000L)
        assertNull(after.now)
        assertEquals("Tomorrow", after.next?.title)
    }

    @Test
    fun `parses the same guide from gzip`() {
        val gzipped = ByteArrayOutputStream().use { buffer ->
            GZIPOutputStream(buffer).use { gzip -> gzip.write(FIXTURE.toByteArray()) }
            buffer.toByteArray()
        }
        val guide = parseEpgPayload(gzipped)
        assertEquals(listOf("News & Weather", "Later"), guide.programsInWindow(
            "bbc1.uk",
            1_794_916_800_000L,
            1_794_916_800_000L + 2 * 3_600_000L,
        ).map { it.title })
    }

    private companion object {
        // Tomorrow is first so now/next is wrong unless programmes are sorted by start.
        val FIXTURE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="BBC1.uk"><display-name>BBC One</display-name></channel>
              <channel id="empty.chan"><display-name>Empty</display-name></channel>
              <programme start="20261118000000 +0000" stop="20261118010000 +0000" channel="BBC1.uk">
                <title>Tomorrow</title>
              </programme>
              <programme start="20261117150000 +0200" stop="20261117160000 +0200" channel="BBC1.uk">
                <title>Later</title>
                <desc>After</desc>
              </programme>
              <programme start="20261117120000 +0000" stop="20261117130000 +0000" channel="BBC1.uk">
                <title>News &amp; Weather</title>
                <desc><![CDATA[The midday news]]></desc>
              </programme>
            </tv>
        """.trimIndent()
    }
}
