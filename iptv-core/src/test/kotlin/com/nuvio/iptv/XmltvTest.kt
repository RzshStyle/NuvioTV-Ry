package com.nuvio.iptv

import com.nuvio.iptv.epg.EpgIndex
import com.nuvio.iptv.model.CivilTime
import com.nuvio.iptv.model.LiveChannel
import com.nuvio.iptv.model.Programme
import com.nuvio.iptv.xmltv.XmltvParser
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class XmltvTest {
    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv generator-info-name="test">
          <!-- comment -->
          <channel id="bbc1.uk"><display-name>BBC One</display-name><icon src="http://i/bbc.png"/></channel>
          <channel id="itv.uk"><display-name lang="en">ITV 1 HD</display-name></channel>
          <programme start="20261126180000 +0000" stop="20261126190000 +0000" channel="bbc1.uk">
            <title lang="en">News &amp; Weather</title>
            <desc><![CDATA[Top <b>stories</b>]]></desc>
            <category>News</category><category>Current affairs</category>
            <episode-num system="xmltv_ns">1.4.0/1</episode-num>
          </programme>
          <programme start="20261126200000 +0100" stop="20261126203000 +0100" channel="bbc1.uk">
            <title>The One Show</title>
          </programme>
          <programme start="20261126190000 +0000" channel="itv.uk"><title>Corrie</title></programme>
          <programme start="20261126200000 +0000" stop="20261126210000 +0000" channel="other"><title>Skip</title></programme>
        </tv>
    """.trimIndent()

    private val t18 = CivilTime(2026, 11, 26, 18, 0, 0).toEpochMillis()

    private fun collect(bytes: ByteArray): List<Programme> {
        val out = ArrayList<Programme>()
        XmltvParser().parse(bytes.inputStream(), object : XmltvParser.Sink {
            override fun onProgramme(programme: Programme): Boolean { out += programme; return true }
        })
        return out
    }

    @Test fun parsesProgrammes() {
        val p = collect(xml.toByteArray())
        assertEquals(4, p.size)
        assertEquals("News & Weather", p[0].title)
        assertEquals("Top <b>stories</b>", p[0].description)
        assertEquals(listOf("News", "Current affairs"), p[0].categories)
        assertEquals("S02E05", p[0].episode)
        assertEquals(t18, p[0].startMs)
        assertEquals(t18 + 3_600_000, p[0].endMs)
        assertEquals(t18 + 3_600_000, p[1].startMs) // 20:00 +0100 == 19:00 UTC
    }

    @Test fun gzipIsDetected() {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(xml.toByteArray()) }
        assertEquals(4, collect(bos.toByteArray()).size)
    }

    @Test fun indexMatchesByIdAndNameAndAnswersNowNext() {
        val channels = listOf(
            LiveChannel(id = "a", sourceId = "s", name = "BBC One", epgChannelId = "bbc1.uk", streamUrl = "x"),
            LiveChannel(id = "b", sourceId = "s", name = "UK: ITV 1 FHD", streamUrl = "y"),
        )
        val builder = EpgIndex.Builder(channels)
        builder.addXmltv(xml.byteInputStream(), Long.MIN_VALUE, Long.MAX_VALUE)
        val index = builder.build()
        assertEquals(2, index.channelCount)
        val (now, next) = index.nowAndNext("a", t18 + 10 * 60_000)
        assertEquals("News & Weather", now?.title)
        assertEquals("The One Show", next?.title)
        assertEquals("Corrie", index.programmes("b").single().title)
        val (gapNow, gapNext) = index.nowAndNext("a", t18 - 1)
        assertNull(gapNow)
        assertEquals("News & Weather", gapNext?.title)
        assertEquals(1, index.programmes("a", t18 + 3_600_000, t18 + 7_200_000).size)
    }

    @Test fun windowFilterDropsOldProgrammes() {
        val builder = EpgIndex.Builder(listOf(LiveChannel(id = "a", sourceId = "s", name = "BBC One", epgChannelId = "bbc1.uk", streamUrl = "x")))
        builder.addXmltv(xml.byteInputStream(), t18 + 3_600_001, Long.MAX_VALUE)
        assertEquals(listOf("The One Show"), builder.build().programmes("a").map { it.title })
    }

    @Test fun civilTimeRoundTrips() {
        val ms = CivilTime(2024, 2, 29, 23, 59, 58).toEpochMillis(60)
        assertEquals(CivilTime(2024, 2, 29, 22, 59, 58), CivilTime.fromEpochMillis(ms))
        assertEquals(0L, CivilTime(1970, 1, 1, 0, 0, 0).toEpochMillis())
    }
}
