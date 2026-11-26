package com.nuvio.iptv

import com.nuvio.iptv.m3u.M3uParser
import com.nuvio.iptv.model.CatchupType
import com.nuvio.iptv.model.LiveChannel
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class M3uParserTest {
    private val sample = """
        ﻿#EXTM3U url-tvg="http://epg.example/guide.xml.gz,http://epg.example/2.xml" catchup="shift" catchup-days="3"
        #EXTINF:-1 tvg-id="bbc1.uk" tvg-name="BBC One" tvg-logo="http://logo/bbc1.png" tvg-chno="101" group-title="UK | Entertainment, HD",BBC One HD
        http://srv.example/live/u/p/1.ts
        #EXTINF:-1 tvg-id="sky.sports" catchup="flussonic" catchup-days="7",Sky Sports
        #EXTVLCOPT:http-user-agent=MyAgent/1.0
        #EXTVLCOPT:http-referrer=http://ref.example/
        http://fl.example/skysports/index.m3u8
        #EXTINF:-1,No Group Channel
        #EXTGRP:Music
        http://srv.example/music.m3u8|User-Agent=Kodi+Agent&Referer=http%3A%2F%2Fr.example
        #EXTINF:-1 tvg-id="" catchup="none",Plain
        http://srv.example/plain.ts
        #EXTINF:-1 tvg-id="bbc1.uk",BBC One Backup
        http://srv.example/live/u/p/1.ts
    """.trimIndent()

    private val result = M3uParser().parse("src", StringReader(sample))

    @Test fun headerEpgUrls() {
        assertEquals(listOf("http://epg.example/guide.xml.gz", "http://epg.example/2.xml"), result.epgUrls)
    }

    @Test fun parsesAttributesAndCommasInsideQuotes() {
        val c = result.channels[0]
        assertEquals("BBC One HD", c.name)
        assertEquals("UK | Entertainment, HD", c.group)
        assertEquals("bbc1.uk", c.epgChannelId)
        assertEquals(101, c.number)
        assertEquals("http://logo/bbc1.png", c.logoUrl)
        assertEquals(CatchupType.SHIFT, c.catchup?.type) // inherited from header
        assertEquals(3, c.catchup?.days)
    }

    @Test fun vlcOptionsBecomeHeaders() {
        val c = result.channels[1]
        assertEquals("MyAgent/1.0", c.headers["User-Agent"])
        assertEquals("http://ref.example/", c.headers["Referer"])
        assertEquals(CatchupType.FLUSSONIC, c.catchup?.type)
        assertEquals(7, c.catchup?.days)
    }

    @Test fun extGrpAndKodiHeaders() {
        val c = result.channels[2]
        assertEquals("Music", c.group)
        assertEquals("http://srv.example/music.m3u8", c.streamUrl)
        assertEquals("Kodi+Agent", c.headers["User-Agent"])
        assertEquals("http://r.example", c.headers["Referer"])
    }

    @Test fun unknownCatchupIsIgnoredAndDefaultGroup() {
        val c = result.channels[3]
        assertNull(c.catchup)
        assertEquals(LiveChannel.UNGROUPED, c.group)
        assertNull(c.epgChannelId)
    }

    @Test fun duplicateEntriesGetDistinctStableIds() {
        assertEquals(5, result.channels.size)
        assertNotEquals(result.channels[0].id, result.channels[4].id)
        val again = M3uParser().parse("src", StringReader(sample))
        assertEquals(result.channels.map { it.id }, again.channels.map { it.id })
    }
}
