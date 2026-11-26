package com.nuvio.tv.data.iptv

import android.content.Context
import android.util.Log
import com.nuvio.iptv.IptvEngine
import com.nuvio.iptv.epg.EpgIndex
import com.nuvio.iptv.model.ChannelGroup
import com.nuvio.iptv.model.LiveChannel
import com.nuvio.iptv.model.LoadedPlaylist
import com.nuvio.iptv.model.PlaylistSource
import com.nuvio.iptv.model.Programme
import com.nuvio.iptv.net.IptvHttp
import com.nuvio.iptv.xtream.XtreamAuthException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

data class IptvSourceStatus(
    val loadingPlaylist: Boolean = false,
    val loadingEpg: Boolean = false,
    val channelCount: Int = 0,
    val playlistUpdatedAt: Long? = null,
    val epgUpdatedAt: Long? = null,
    val epgChannelCount: Int = 0,
    val error: String? = null,
    val epgErrors: Map<String, String> = emptyMap(),
    val expiresAtMs: Long? = null,
    val maxConnections: Int? = null,
)

data class IptvState(
    val profileId: Int = 1,
    val sources: List<PlaylistSource> = emptyList(),
    val status: Map<String, IptvSourceStatus> = emptyMap(),
    val channels: List<LiveChannel> = emptyList(),
    val groups: List<ChannelGroup> = emptyList(),
    val favorites: List<String> = emptyList(),
    val recentChannels: List<String> = emptyList(),
    val lastChannelId: String? = null,
    val hiddenGroups: Set<String> = emptySet(),
    /** sourceId -> guide. */
    val epg: Map<String, EpgIndex> = emptyMap(),
    /** sourceId -> panel clock offset, for catch-up URLs. */
    val serverOffsets: Map<String, Int> = emptyMap(),
    val revision: Int = 0,
) {
    private val byId: Map<String, LiveChannel> by lazy { channels.associateBy { it.id } }

    fun channel(id: String): LiveChannel? = byId[id]

    fun nowAndNext(channel: LiveChannel, nowMs: Long): Pair<Programme?, Programme?> =
        epg[channel.sourceId]?.nowAndNext(channel.id, nowMs) ?: (null to null)

    fun programmes(channel: LiveChannel, fromMs: Long, toMs: Long): List<Programme> =
        epg[channel.sourceId]?.programmes(channel.id, fromMs, toMs).orEmpty()

    val hasSources: Boolean get() = sources.isNotEmpty()
}

/**
 * Owns the user's Live TV playlists: persists them per profile, caches downloaded playlists and
 * guides on disk, and refreshes them in the background (playlist every 24 h, guide every 12 h).
 */
class IptvRepository(
    private val context: Context,
    private val store: IptvSourceStore,
    private val engine: IptvEngine,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; classDiscriminator = "kind" }
    private val mutableState = MutableStateFlow(IptvState())
    val state: StateFlow<IptvState> = mutableState.asStateFlow()

    private val mutex = Mutex()
    private var stored = StoredIptv()
    private var loadedProfile: Int? = null
    private val playlists = LinkedHashMap<String, LoadedPlaylist>()
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    fun selectProfile(profileId: Int) {
        if (loadedProfile == profileId) return
        loadedProfile = profileId
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        scope.launch {
            mutex.withLock {
                stored = store.read(profileId)
                playlists.clear()
                for (source in stored.sources) readCachedPlaylist(profileId, source)?.let { playlists[source.id] = it }
                publish(profileId) { it.copy(epg = emptyMap()) }
            }
            stored.sources.forEach { refreshIfStale(it) }
        }
    }

    // ---- Sources -------------------------------------------------------------------------

    /** Validates the source by loading it, then saves it. Returns an error message on failure. */
    suspend fun addSource(source: PlaylistSource): Result<PlaylistSource> {
        val profileId = loadedProfile ?: 1
        val withId = when (source) {
            is PlaylistSource.M3u -> if (source.id.isBlank()) source.copy(id = newId()) else source
            is PlaylistSource.Xtream -> if (source.id.isBlank()) source.copy(id = newId()) else source
        }
        val loaded = try {
            engine.loadPlaylist(withId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(IllegalStateException(describe(e), e))
        }
        if (loaded.channels.isEmpty()) return Result.failure(IllegalStateException("No live channels were found in this playlist"))
        mutex.withLock {
            stored = stored.copy(
                sources = stored.sources.filterNot { it.id == withId.id } + withId,
                playlistUpdatedAt = stored.playlistUpdatedAt + (withId.id to System.currentTimeMillis()),
            )
            store.write(profileId, stored)
            playlists[withId.id] = loaded
            writeCachedPlaylist(profileId, loaded)
            publish(profileId)
        }
        refreshEpg(withId, force = true)
        return Result.success(withId)
    }

    fun removeSource(sourceId: String) {
        val profileId = loadedProfile ?: return
        jobs.remove(sourceId)?.cancel()
        scope.launch {
            mutex.withLock {
                stored = stored.copy(
                    sources = stored.sources.filterNot { it.id == sourceId },
                    favorites = stored.favorites.filterNot { it.startsWith("$sourceId:") },
                    recentChannels = stored.recentChannels.filterNot { it.startsWith("$sourceId:") },
                    playlistUpdatedAt = stored.playlistUpdatedAt - sourceId,
                    epgUpdatedAt = stored.epgUpdatedAt - sourceId,
                )
                store.write(profileId, stored)
                playlists.remove(sourceId)
                sourceDir(profileId, sourceId).deleteRecursively()
                publish(profileId) { it.copy(epg = it.epg - sourceId, status = it.status - sourceId) }
            }
        }
    }

    fun refresh(sourceId: String) {
        val source = stored.sources.firstOrNull { it.id == sourceId } ?: return
        launchFor(source) {
            refreshPlaylist(source)
            refreshEpg(source, force = true)
        }
    }

    fun refreshAll() = stored.sources.forEach { refresh(it.id) }

    // ---- Favourites, recents and groups ---------------------------------------------------

    fun toggleFavorite(channelId: String) = mutate {
        val favs = if (channelId in it.favorites) it.favorites - channelId else it.favorites + channelId
        it.copy(favorites = favs)
    }

    fun moveFavorite(channelId: String, toIndex: Int) = mutate {
        val list = it.favorites.toMutableList()
        if (list.remove(channelId)) list.add(toIndex.coerceIn(0, list.size), channelId)
        it.copy(favorites = list)
    }

    fun markWatched(channelId: String) = mutate {
        it.copy(
            lastChannelId = channelId,
            recentChannels = (listOf(channelId) + it.recentChannels.filterNot { id -> id == channelId }).take(RECENTS),
        )
    }

    fun setGroupHidden(group: String, hidden: Boolean) = mutate {
        it.copy(hiddenGroups = if (hidden) (it.hiddenGroups + group).distinct() else it.hiddenGroups - group)
    }

    fun catchupUrl(channel: LiveChannel, programme: Programme, nowMs: Long = System.currentTimeMillis()): String? =
        engine.catchupUrl(channel, programme, nowMs, state.value.serverOffsets[channel.sourceId] ?: 0)

    private fun mutate(block: (StoredIptv) -> StoredIptv) {
        val profileId = loadedProfile ?: return
        scope.launch {
            mutex.withLock {
                stored = block(stored)
                store.write(profileId, stored)
                publish(profileId)
            }
        }
    }

    // ---- Loading --------------------------------------------------------------------------

    private fun refreshIfStale(source: PlaylistSource) = launchFor(source) {
        val now = System.currentTimeMillis()
        val playlistAge = now - (stored.playlistUpdatedAt[source.id] ?: 0L)
        if (playlists[source.id] == null || playlistAge > PLAYLIST_TTL_MS) refreshPlaylist(source)
        val epgAge = now - (stored.epgUpdatedAt[source.id] ?: 0L)
        if (epgAge > EPG_TTL_MS || !epgFiles(loadedProfile ?: 1, source.id).any { it.isFile }) refreshEpg(source, force = true)
        else refreshEpg(source, force = false)
    }

    private fun launchFor(source: PlaylistSource, block: suspend () -> Unit) {
        jobs[source.id]?.cancel()
        jobs[source.id] = scope.launch { block() }
    }

    private suspend fun refreshPlaylist(source: PlaylistSource) {
        val profileId = loadedProfile ?: return
        setStatus(source.id) { it.copy(loadingPlaylist = true, error = null) }
        try {
            val loaded = engine.loadPlaylist(source)
            mutex.withLock {
                playlists[source.id] = loaded
                stored = stored.copy(playlistUpdatedAt = stored.playlistUpdatedAt + (source.id to System.currentTimeMillis()))
                store.write(profileId, stored)
                writeCachedPlaylist(profileId, loaded)
                publish(profileId)
            }
            setStatus(source.id) { it.copy(loadingPlaylist = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Playlist refresh failed for ${source.name}: ${e.javaClass.simpleName}")
            setStatus(source.id) { it.copy(loadingPlaylist = false, error = describe(e)) }
        }
    }

    /** Downloads the guides when [force] (or none cached), then parses them from disk. */
    private suspend fun refreshEpg(source: PlaylistSource, force: Boolean) {
        val profileId = loadedProfile ?: return
        val playlist = playlists[source.id] ?: return
        setStatus(source.id) { it.copy(loadingEpg = true) }
        val failures = LinkedHashMap<String, String>()
        val files = playlist.epgUrls.mapIndexed { i, _ -> File(sourceDir(profileId, source.id), "epg_$i.xml") }
        if (force || files.none { it.isFile }) {
            var anyOk = false
            playlist.epgUrls.forEachIndexed { i, url ->
                try {
                    engine.download(url, files[i], source.userAgentHeaders())
                    anyOk = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures[IptvHttp.redact(url)] = describe(e)
                }
            }
            if (anyOk) mutex.withLock {
                stored = stored.copy(epgUpdatedAt = stored.epgUpdatedAt + (source.id to System.currentTimeMillis()))
                store.write(profileId, stored)
            }
        }
        val index = try {
            engine.epgFromFiles(playlist.channels, files)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures["guide"] = describe(e)
            null
        }
        mutex.withLock {
            publish(profileId) { s -> if (index != null) s.copy(epg = s.epg + (source.id to index)) else s }
        }
        setStatus(source.id) {
            it.copy(loadingEpg = false, epgErrors = failures, epgChannelCount = index?.channelCount ?: it.epgChannelCount)
        }
    }

    private fun PlaylistSource.userAgentHeaders(): Map<String, String> {
        val ua = when (this) {
            is PlaylistSource.M3u -> userAgent
            is PlaylistSource.Xtream -> userAgent
        }
        return ua?.let { mapOf("User-Agent" to it) } ?: emptyMap()
    }

    // ---- State ----------------------------------------------------------------------------

    private fun publish(profileId: Int, extra: (IptvState) -> IptvState = { it }) {
        val all = stored.sources.mapNotNull { playlists[it.id] }
        val channels = all.flatMap { it.channels }
        val groups = mergeGroups(all)
        mutableState.update { prev ->
            val status = stored.sources.associate { src ->
                val p = playlists[src.id]
                src.id to (prev.status[src.id] ?: IptvSourceStatus()).copy(
                    channelCount = p?.channels?.size ?: 0,
                    playlistUpdatedAt = stored.playlistUpdatedAt[src.id],
                    epgUpdatedAt = stored.epgUpdatedAt[src.id],
                    expiresAtMs = p?.account?.expiresAtMs,
                    maxConnections = p?.account?.maxConnections,
                )
            }
            extra(
                prev.copy(
                    profileId = profileId,
                    sources = stored.sources,
                    status = status,
                    channels = channels,
                    groups = groups,
                    favorites = stored.favorites,
                    recentChannels = stored.recentChannels,
                    lastChannelId = stored.lastChannelId,
                    hiddenGroups = stored.hiddenGroups.toSet(),
                    serverOffsets = all.associate { it.source.id to (it.account?.serverUtcOffsetMinutes ?: 0) },
                    revision = prev.revision + 1,
                )
            )
        }
    }

    private fun setStatus(sourceId: String, block: (IptvSourceStatus) -> IptvSourceStatus) {
        mutableState.update { s ->
            s.copy(status = s.status + (sourceId to block(s.status[sourceId] ?: IptvSourceStatus())), revision = s.revision + 1)
        }
    }

    /** With several playlists, groups are prefixed with the playlist name so they stay distinct. */
    private fun mergeGroups(all: List<LoadedPlaylist>): List<ChannelGroup> {
        if (all.size <= 1) return all.firstOrNull()?.groups.orEmpty()
        return all.flatMap { p -> p.groups.map { g -> g.copy(name = "${p.source.name} · ${g.name}") } }
    }

    // ---- Disk cache -----------------------------------------------------------------------

    private fun sourceDir(profileId: Int, sourceId: String) =
        File(context.filesDir, "iptv/p$profileId/${sourceId.replace(Regex("[^A-Za-z0-9_-]"), "_")}")

    private fun epgFiles(profileId: Int, sourceId: String): List<File> =
        sourceDir(profileId, sourceId).listFiles { f -> f.name.startsWith("epg_") && f.name.endsWith(".xml") }?.toList().orEmpty()

    private fun readCachedPlaylist(profileId: Int, source: PlaylistSource): LoadedPlaylist? {
        val f = File(sourceDir(profileId, source.id), "playlist.json")
        if (!f.isFile) return null
        return runCatching { json.decodeFromString(LoadedPlaylist.serializer(), f.readText()) }
            .getOrNull()
            // The cache never stores credentials: re-attach the current source definition.
            ?.copy(source = source)
    }

    private fun writeCachedPlaylist(profileId: Int, playlist: LoadedPlaylist) {
        val dir = sourceDir(profileId, playlist.source.id).apply { mkdirs() }
        val redacted = when (val s = playlist.source) {
            is PlaylistSource.Xtream -> s.copy(password = "")
            is PlaylistSource.M3u -> s
        }
        runCatching { File(dir, "playlist.json").writeText(json.encodeToString(LoadedPlaylist.serializer(), playlist.copy(source = redacted))) }
            .onFailure { Log.w(TAG, "Could not cache playlist: ${it.javaClass.simpleName}") }
    }

    private fun describe(e: Exception): String = when (e) {
        is XtreamAuthException -> e.message ?: "Login failed"
        is com.nuvio.iptv.net.IptvHttpException -> when (e.code) {
            401, 403 -> "The server refused access (HTTP ${e.code}). Check your login details."
            404 -> "Playlist not found (HTTP 404). Check the address."
            else -> "The server returned an error (HTTP ${e.code})."
        }
        is java.net.UnknownHostException -> "Server not found. Check the address."
        is java.net.SocketTimeoutException -> "The server took too long to respond."
        is javax.net.ssl.SSLException -> "Secure connection failed. Try http:// instead of https://."
        is kotlinx.serialization.SerializationException -> "The server sent an unexpected response."
        else -> e.message ?: "Something went wrong"
    }

    private fun newId() = UUID.randomUUID().toString().take(8)

    private companion object {
        const val TAG = "IptvRepository"
        const val RECENTS = 30
        const val PLAYLIST_TTL_MS = 24 * 60 * 60 * 1000L
        const val EPG_TTL_MS = 12 * 60 * 60 * 1000L
    }
}
