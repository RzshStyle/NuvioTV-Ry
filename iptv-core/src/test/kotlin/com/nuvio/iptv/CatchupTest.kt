package com.nuvio.iptv

import com.nuvio.iptv.catchup.CatchupUrlBuilder
import com.nuvio.iptv.model.Catchup
import com.nuvio.iptv.model.CatchupType
import com.nuvio.iptv.model.CivilTime
import com.nuvio.iptv.model.LiveChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatchupTest {
    private val start = CivilTime(2026, 11, 26, 18, 0, 0).toEpochMillis()
    private val end = start + 3_600_000
    private val now = start + 5 * 3_600_000
    private val s = start / 1000

    private fun ch(url: String, type: CatchupType, source: String? = null, days: Int = 3) =
        LiveChannel(id = "x", sourceId = "s", name = "n", streamUrl = url, catchup = Catchup(type, days, source))

    @Test fun shift() = assertEquals(
        "http://h/c.ts?utc=$s&lutc=${now / 1000}",
        CatchupUrlBuilder.build(ch("http://h/c.ts", CatchupType.SHIFT), start, end, now),
    )

    @Test fun appendAndTemplates() = assertEquals(
        "http://h/c.m3u8?start=$s&dur=60&d=2026-11-26T18:00",
        CatchupUrlBuilder.build(ch("http://h/c.m3u8", CatchupType.APPEND, "?start={utc}&dur={duration:60}&d={(b)yyyy-MM-dd}T{H}:{M}"), start, end, now),
    )

    @Test fun defaultFullUrl() = assertEquals(
        "http://arch/c?from=$s&to=${end / 1000}",
        CatchupUrlBuilder.build(ch("http://h/c.ts", CatchupType.DEFAULT, "http://arch/c?from=\${start}&to=\${end}"), start, end, now),
    )

    @Test fun flussonic() {
        assertEquals("http://f/ch/index-$s-3600.m3u8", CatchupUrlBuilder.build(ch("http://f/ch/index.m3u8", CatchupType.FLUSSONIC), start, end, now))
        assertEquals("http://f/ch/timeshift_abs-$s.ts", CatchupUrlBuilder.build(ch("http://f/ch/mpegts", CatchupType.FLUSSONIC), start, end, now))
    }

    @Test fun xtreamUsesServerOffset() = assertEquals(
        "http://p.example:8080/timeshift/user/pass/60/2026-11-26:19-00/123.ts",
        CatchupUrlBuilder.build(ch("http://p.example:8080/live/user/pass/123.ts", CatchupType.XTREAM), start, end, now, serverUtcOffsetMinutes = 60),
    )

    @Test fun outsideWindowOrFutureIsNull() {
        assertNull(CatchupUrlBuilder.build(ch("http://h/c.ts", CatchupType.SHIFT, days = 1), start - 2 * 86_400_000L, start, now))
        assertNull(CatchupUrlBuilder.build(ch("http://h/c.ts", CatchupType.SHIFT), now + 1, now + 2, now))
    }
}
