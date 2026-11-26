package com.nuvio.iptv.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A playlist the user added in settings. Nothing is bundled: every source comes from the user.
 */
@Serializable
sealed interface PlaylistSource {
    val id: String
    val name: String

    /** Extra EPG (XMLTV) URLs entered by the user. Xtream sources also get the panel's own xmltv.php. */
    val epgUrls: List<String>

    @Serializable
    @SerialName("m3u")
    data class M3u(
        override val id: String,
        override val name: String,
        val url: String,
        override val epgUrls: List<String> = emptyList(),
        val userAgent: String? = null,
    ) : PlaylistSource

    @Serializable
    @SerialName("xtream")
    data class Xtream(
        override val id: String,
        override val name: String,
        val serverUrl: String,
        val username: String,
        val password: String,
        override val epgUrls: List<String> = emptyList(),
        val outputFormat: XtreamOutput = XtreamOutput.TS,
        val userAgent: String? = null,
    ) : PlaylistSource {
        override fun toString(): String = "Xtream(id=$id, name=$name, serverUrl=$serverUrl)"
    }
}

@Serializable
enum class XtreamOutput(val extension: String) { TS("ts"), HLS("m3u8") }

@Serializable
data class LiveChannel(
    /** Stable across refreshes: "<sourceId>:<key>" where key is the Xtream stream id or the M3U url/tvg-id. */
    val id: String,
    val sourceId: String,
    val name: String,
    val number: Int? = null,
    val group: String = UNGROUPED,
    val logoUrl: String? = null,
    /** tvg-id / epg_channel_id used to look up programmes. */
    val epgChannelId: String? = null,
    val streamUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val catchup: Catchup? = null,
    val xtreamStreamId: Int? = null,
    /** Shift in hours applied to this channel's EPG times (tvg-shift). */
    val epgShiftHours: Double = 0.0,
) {
    companion object {
        const val UNGROUPED = "Uncategorised"
    }
}

@Serializable
data class ChannelGroup(val name: String, val channelIds: List<String>)

@Serializable
enum class CatchupType { DEFAULT, APPEND, SHIFT, FLUSSONIC, XTREAM }

@Serializable
data class Catchup(
    val type: CatchupType,
    val days: Int,
    /** catchup-source template, may be null for SHIFT / FLUSSONIC / XTREAM. */
    val source: String? = null,
)

@Serializable
data class Programme(
    val channelId: String,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val categories: List<String> = emptyList(),
    val iconUrl: String? = null,
    val episode: String? = null,
) {
    val durationMs: Long get() = endMs - startMs
    fun isLiveAt(nowMs: Long): Boolean = nowMs in startMs until endMs
}

@Serializable
data class EpgChannel(
    val id: String,
    val displayNames: List<String>,
    val iconUrl: String? = null,
)

/** Result of loading one source. */
@Serializable
data class LoadedPlaylist(
    val source: PlaylistSource,
    val channels: List<LiveChannel>,
    val groups: List<ChannelGroup>,
    /** EPG URLs to fetch: user-entered plus any advertised by the playlist (url-tvg) or panel. */
    val epgUrls: List<String>,
    val account: XtreamAccountInfo? = null,
)

@Serializable
data class XtreamAccountInfo(
    val status: String?,
    val expiresAtMs: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val isTrial: Boolean,
    /** Panel clock minus UTC, in minutes. Needed to build catch-up URLs in the panel's time. */
    val serverUtcOffsetMinutes: Int,
)
