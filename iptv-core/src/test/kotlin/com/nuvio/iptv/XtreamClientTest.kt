package com.nuvio.iptv

import com.nuvio.iptv.model.CatchupType
import com.nuvio.iptv.model.PlaylistSource
import com.nuvio.iptv.xtream.XtreamAuthException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XtreamClientTest {
    private lateinit var server: MockWebServer

    @BeforeTest fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if ("password=bad" in path) return MockResponse().setBody("""{"user_info":{"auth":0}}""")
                val body = when {
                    "get_live_categories" in path -> """[{"category_id":"2","category_name":"Sports"},{"category_id":1,"category_name":"News"}]"""
                    "get_live_streams" in path -> """[
                        {"num":"1","name":"Sky Sports ","stream_id":"55","stream_icon":"","epg_channel_id":"sky.uk","category_id":"2","tv_archive":1,"tv_archive_duration":"7"},
                        {"num":2,"name":"BBC News","stream_id":9,"stream_icon":"http://i/b.png","epg_channel_id":null,"category_id":"1","tv_archive":"0"},
                        {"name":"Broken"}
                    ]"""
                    "/player_api.php" in path -> """{"user_info":{"auth":1,"status":"Active","exp_date":"1798761600","max_connections":"2","active_cons":"0","is_trial":"0"},
                        "server_info":{"timestamp_now":1795716000,"time_now":"2026-11-26 19:00:00","timezone":"Europe/Paris"}}"""
                    else -> null
                }
                return body?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @AfterTest fun tearDown() = server.shutdown()

    private fun source(password: String = "p@ss") = PlaylistSource.Xtream(
        id = "x", name = "Provider", serverUrl = server.url("/").toString() + "player_api.php", username = "user", password = password,
    )

    @Test fun loadsAccountCategoriesAndChannels() = runTest {
        val engine = IptvEngine()
        val playlist = engine.loadPlaylist(source())
        val account = playlist.account!!
        assertEquals(60, account.serverUtcOffsetMinutes)
        assertEquals(2, account.maxConnections)
        assertEquals(1798761600_000L, account.expiresAtMs)

        assertEquals(listOf("Sky Sports", "BBC News"), playlist.channels.map { it.name })
        assertEquals(listOf("Sports", "News"), playlist.groups.map { it.name })
        val sky = playlist.channels[0]
        assertEquals("x:55", sky.id)
        assertNull(sky.logoUrl)
        assertEquals(CatchupType.XTREAM, sky.catchup?.type)
        assertEquals(7, sky.catchup?.days)
        assertTrue(sky.streamUrl.endsWith("/live/user/p%40ss/55.ts"), sky.streamUrl)
        assertNull(playlist.channels[1].catchup)
        assertTrue(playlist.epgUrls.single().contains("/xmltv.php?username=user&password=p%40ss"))
    }

    @Test fun badLoginFails() = runTest {
        assertFailsWith<XtreamAuthException> { IptvEngine().loadPlaylist(source(password = "bad")) }
    }
}
