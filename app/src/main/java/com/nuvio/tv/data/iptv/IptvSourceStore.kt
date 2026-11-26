package com.nuvio.tv.data.iptv

import android.content.Context
import com.nuvio.iptv.model.PlaylistSource
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Everything Live TV keeps per profile. Credentials live here, encrypted with the Android Keystore. */
@Serializable
data class StoredIptv(
    val sources: List<PlaylistSource> = emptyList(),
    val favorites: List<String> = emptyList(),
    val recentChannels: List<String> = emptyList(),
    val lastChannelId: String? = null,
    /** sourceId -> epoch ms of the last successful playlist refresh. */
    val playlistUpdatedAt: Map<String, Long> = emptyMap(),
    /** sourceId -> epoch ms of the last successful guide download. */
    val epgUpdatedAt: Map<String, Long> = emptyMap(),
    val hiddenGroups: List<String> = emptyList(),
) {
    override fun toString(): String = "StoredIptv(sources=${sources.size}, favorites=${favorites.size})"
}

@Singleton
class IptvSourceStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = ProtectedProfilePreferences(context, "iptv_sources", "com.nuvio.tv.iptv.v1")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "kind" }

    fun read(profileId: Int): StoredIptv =
        preferences.read(profileId)?.let { runCatching { json.decodeFromString(StoredIptv.serializer(), it) }.getOrNull() }
            ?: StoredIptv()

    fun write(profileId: Int, value: StoredIptv) {
        preferences.write(profileId, json.encodeToString(StoredIptv.serializer(), value))
    }
}
