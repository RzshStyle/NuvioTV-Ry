package com.nuvio.iptv

import com.nuvio.iptv.model.PlaylistSource
import com.nuvio.iptv.net.IptvHttpException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UserAgentFallbackTest {
    private fun server(allowed: (String) -> Boolean) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val ua = request.getHeader("User-Agent").orEmpty()
                if (!allowed(ua)) return MockResponse().setResponseCode(513)
                val path = request.path.orEmpty()
                val body = when {
                    "get_live_categories" in path -> """[{"category_id":"1","category_name":"News"}]"""
                    "get_live_streams" in path -> """[{"num":1,"name":"News 1","stream_id":7,"category_id":"1"}]"""
                    else -> """{"user_info":{"auth":1,"status":"Active"}}"""
                }
                return MockResponse().setBody(body)
            }
        }
    }

    private fun source(url: String) = PlaylistSource.Xtream(id = "s", name = "P", serverUrl = url, username = "u", password = "p")

    @Test fun retriesWithPlayerAgentAndKeepsIt() = runTest {
        val s = server { it.startsWith("IPTVSmarters") }
        val loaded = IptvEngine().loadPlaylist(source(s.url("/").toString()))
        val saved = loaded.source as PlaylistSource.Xtream
        assertEquals("IPTVSmartersPlayer", saved.userAgent)
        assertEquals("IPTVSmartersPlayer", loaded.channels.single().headers["User-Agent"])
        s.shutdown()
    }

    @Test fun explicitAgentIsNotOverridden() = runTest {
        val s = server { it.startsWith("IPTVSmarters") }
        val src = source(s.url("/").toString()).copy(userAgent = "Custom/1")
        val e = assertFailsWith<IptvHttpException> { IptvEngine().loadPlaylist(src) }
        assertEquals(513, e.code)
        s.shutdown()
    }
}
