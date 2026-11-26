package com.nuvio.iptv

import com.nuvio.iptv.catchup.CatchupUrlBuilder
import com.nuvio.iptv.epg.EpgIndex
import com.nuvio.iptv.m3u.M3uParser
import com.nuvio.iptv.model.ChannelGroup
import com.nuvio.iptv.model.LiveChannel
import com.nuvio.iptv.model.LoadedPlaylist
import com.nuvio.iptv.model.PlaylistSource
import com.nuvio.iptv.model.Programme
import com.nuvio.iptv.net.IptvHttp
import com.nuvio.iptv.net.IptvHttpException
import com.nuvio.iptv.xtream.XtreamClient
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.InputStreamReader

/**
 * Entry point used by the apps: loads user-supplied playlists and guides, and builds play /
 * catch-up URLs. Holds no state; the app layer caches results.
 */
class IptvEngine(private val http: IptvHttp = IptvHttp()) {

    val xtream = XtreamClient(http)

    /**
     * Loads [source]. When the user gave no user agent and the server rejects the request, retries
     * with the agents of common IPTV players (many panels only allow known players) and, for an
     * https:// address, with http://. The returned [LoadedPlaylist.source] carries what worked, so
     * callers should store that.
     */
    suspend fun loadPlaylist(source: PlaylistSource): LoadedPlaylist {
        var firstError: Exception? = null
        for (candidate in candidatesFor(source)) {
            try {
                return when (candidate) {
                    is PlaylistSource.M3u -> loadM3u(candidate)
                    is PlaylistSource.Xtream -> loadXtream(candidate)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstError == null) firstError = e
                if (!isRetryable(e)) break
            }
        }
        throw firstError ?: IllegalStateException("Could not load playlist")
    }

    private fun isRetryable(e: Exception): Boolean = when (e) {
        is IptvHttpException -> e.code != 401 && e.code != 404
        is javax.net.ssl.SSLException -> true
        is java.io.IOException -> true
        else -> false
    }

    private fun candidatesFor(source: PlaylistSource): List<PlaylistSource> {
        val agents: List<String?> = if (userAgentOf(source) != null) listOf(userAgentOf(source)) else listOf(null) + FALLBACK_USER_AGENTS
        val out = ArrayList<PlaylistSource>()
        for (ua in agents) {
            when (source) {
                is PlaylistSource.M3u -> out += source.copy(userAgent = ua)
                is PlaylistSource.Xtream -> {
                    out += source.copy(userAgent = ua)
                    val trimmed = source.serverUrl.trim()
                    if (trimmed.startsWith("https://", ignoreCase = true)) {
                        out += source.copy(serverUrl = "http://" + trimmed.substring(8), userAgent = ua)
                    }
                }
            }
        }
        return out
    }

    private suspend fun loadM3u(source: PlaylistSource.M3u): LoadedPlaylist {
        val headers = source.userAgent?.let { mapOf("User-Agent" to it) } ?: emptyMap()
        val result = http.get(source.url, headers) { stream ->
            M3uParser().parse(source.id, InputStreamReader(stream, Charsets.UTF_8), source.userAgent)
        }
        return LoadedPlaylist(
            source = source,
            channels = result.channels,
            groups = groupsOf(result.channels),
            epgUrls = (source.epgUrls + result.epgUrls).distinct(),
        )
    }

    private suspend fun loadXtream(source: PlaylistSource.Xtream): LoadedPlaylist {
        val account = xtream.account(source)
        val categories = xtream.liveCategories(source)
        val channels = xtream.liveChannels(source, categories)
        return LoadedPlaylist(
            source = source,
            channels = channels,
            groups = groupsOf(channels),
            epgUrls = (source.epgUrls + xtream.xmltvUrl(source)).distinct(),
            account = account,
        )
    }

    /**
     * Downloads and merges every guide for [playlist]. A failing guide is skipped and reported in
     * [EpgLoadResult.failures] so one broken URL does not empty the whole guide.
     */
    suspend fun loadEpg(
        playlist: LoadedPlaylist,
        nowMs: Long = System.currentTimeMillis(),
        pastDays: Int = maxCatchupDays(playlist.channels).coerceIn(1, 14),
        futureDays: Int = 3,
    ): EpgLoadResult {
        val builder = EpgIndex.Builder(playlist.channels)
        val failures = LinkedHashMap<String, String>()
        val from = nowMs - pastDays * DAY_MS
        val to = nowMs + futureDays * DAY_MS
        for (url in playlist.epgUrls) {
            try {
                http.get(url, uaHeaders(playlist.source)) { builder.addXmltv(it, from, to) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures[IptvHttp.redact(url)] = e.message ?: e::class.simpleName.orEmpty()
            }
        }
        return EpgLoadResult(builder.build(), failures)
    }

    /** Downloads [url] to [dest] atomically (temp file + rename). */
    suspend fun download(url: String, dest: File, headers: Map<String, String> = emptyMap()) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        http.get(url, headers) { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw java.io.IOException("Could not save ${dest.name}")
    }

    /** Builds a guide from previously downloaded XMLTV files (plain or gzipped). */
    fun epgFromFiles(
        channels: List<LiveChannel>,
        files: List<File>,
        nowMs: Long = System.currentTimeMillis(),
        pastDays: Int = maxCatchupDays(channels).coerceIn(1, 14),
        futureDays: Int = 3,
    ): EpgIndex {
        val builder = EpgIndex.Builder(channels)
        val from = nowMs - pastDays * DAY_MS
        val to = nowMs + futureDays * DAY_MS
        for (f in files) if (f.isFile) f.inputStream().use { builder.addXmltv(it, from, to) }
        return builder.build()
    }

    fun catchupUrl(channel: LiveChannel, programme: Programme, nowMs: Long, serverUtcOffsetMinutes: Int = 0): String? =
        CatchupUrlBuilder.build(channel, programme.startMs, programme.endMs, nowMs, serverUtcOffsetMinutes)

    companion object {
        const val DAY_MS = 86_400_000L

        /** Tried in order when a server rejects the default agent. Mirrors common IPTV players. */
        val FALLBACK_USER_AGENTS: List<String> = listOf(
            "TiviMate/5.1.6 (Android 12)",
            "IPTVSmartersPlayer",
            "VLC/3.0.21 LibVLC/3.0.21",
            "okhttp/4.12.0",
            "Dalvik/2.1.0 (Linux; U; Android 12)",
        )

        fun userAgentOf(source: PlaylistSource): String? = when (source) {
            is PlaylistSource.M3u -> source.userAgent
            is PlaylistSource.Xtream -> source.userAgent
        }

        fun uaHeaders(source: PlaylistSource): Map<String, String> =
            userAgentOf(source)?.let { mapOf("User-Agent" to it) } ?: emptyMap()

        fun groupsOf(channels: List<LiveChannel>): List<ChannelGroup> {
            val map = LinkedHashMap<String, MutableList<String>>()
            channels.forEach { map.getOrPut(it.group) { ArrayList() } += it.id }
            return map.map { (name, ids) -> ChannelGroup(name, ids) }
        }

        fun maxCatchupDays(channels: List<LiveChannel>): Int = channels.maxOfOrNull { it.catchup?.days ?: 0 } ?: 0
    }
}

data class EpgLoadResult(val index: EpgIndex, val failures: Map<String, String>)
