package com.nuvio.iptv.xtream

import com.nuvio.iptv.model.Catchup
import com.nuvio.iptv.model.CatchupType
import com.nuvio.iptv.model.CivilTime
import com.nuvio.iptv.model.LiveChannel
import com.nuvio.iptv.model.PlaylistSource
import com.nuvio.iptv.model.XtreamAccountInfo
import com.nuvio.iptv.net.IptvHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.URLEncoder

class XtreamAuthException(message: String) : IOException(message)

/**
 * Xtream Codes player API client (player_api.php). Field types vary wildly between panels
 * (numbers as strings, nulls, booleans as "0"/"1"), so responses are read leniently.
 */
class XtreamClient(private val http: IptvHttp) {

    data class Category(val id: String, val name: String)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun baseUrl(source: PlaylistSource.Xtream): String {
        var s = source.serverUrl.trim().trimEnd('/')
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
        // Users often paste the full player_api.php or get.php link.
        s = s.substringBefore("/player_api.php").substringBefore("/get.php").substringBefore("/xmltv.php")
        return s
    }

    private fun apiUrl(source: PlaylistSource.Xtream, action: String? = null, extra: String = ""): String {
        val u = URLEncoder.encode(source.username, "UTF-8")
        val p = URLEncoder.encode(source.password, "UTF-8")
        val a = action?.let { "&action=$it" }.orEmpty()
        return "${baseUrl(source)}/player_api.php?username=$u&password=$p$a$extra"
    }

    fun xmltvUrl(source: PlaylistSource.Xtream): String {
        val u = URLEncoder.encode(source.username, "UTF-8")
        val p = URLEncoder.encode(source.password, "UTF-8")
        return "${baseUrl(source)}/xmltv.php?username=$u&password=$p"
    }

    fun liveStreamUrl(source: PlaylistSource.Xtream, streamId: Int): String =
        "${baseUrl(source)}/live/${pathPart(source.username)}/${pathPart(source.password)}/$streamId.${source.outputFormat.extension}"

    private fun pathPart(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun headers(source: PlaylistSource.Xtream) =
        source.userAgent?.let { mapOf("User-Agent" to it) } ?: emptyMap()

    suspend fun account(source: PlaylistSource.Xtream): XtreamAccountInfo {
        val root = json.parseToJsonElement(http.getText(apiUrl(source), headers(source)))
        val obj = root as? JsonObject ?: throw XtreamAuthException("Unexpected response from server")
        val user = obj["user_info"] as? JsonObject ?: throw XtreamAuthException("Login failed: check server, username and password")
        if (user.int("auth") == 0) throw XtreamAuthException("Login failed: check username and password")
        val status = user.str("status")
        if (status != null && !status.equals("Active", true)) throw XtreamAuthException("Account status: $status")
        val server = obj["server_info"] as? JsonObject
        return XtreamAccountInfo(
            status = status,
            expiresAtMs = user.long("exp_date")?.takeIf { it > 0 }?.times(1000),
            maxConnections = user.int("max_connections"),
            activeConnections = user.int("active_cons"),
            isTrial = user.int("is_trial") == 1,
            serverUtcOffsetMinutes = server?.let { serverOffsetMinutes(it) } ?: 0,
        )
    }

    /** server_info.time_now is panel wall-clock "YYYY-MM-DD HH:MM:SS"; timestamp_now is UTC epoch. */
    private fun serverOffsetMinutes(server: JsonObject): Int {
        val ts = server.long("timestamp_now") ?: return 0
        val wall = server.str("time_now") ?: return 0
        val m = Regex("(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2}):(\\d{2})").find(wall) ?: return 0
        val (y, mo, d, h, mi, s) = m.destructured
        val wallMs = CivilTime(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt()).toEpochMillis(0)
        val diffMin = (wallMs - ts * 1000) / 60_000.0
        // Round to the nearest 15 minutes to absorb request latency.
        return (Math.round(diffMin / 15.0) * 15).toInt()
    }

    suspend fun liveCategories(source: PlaylistSource.Xtream): List<Category> {
        val arr = json.parseToJsonElement(http.getText(apiUrl(source, "get_live_categories"), headers(source))) as? JsonArray
            ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("category_id") ?: return@mapNotNull null
            Category(id, o.str("category_name") ?: id)
        }
    }

    suspend fun liveChannels(source: PlaylistSource.Xtream, categories: List<Category>): List<LiveChannel> {
        val names = categories.associate { it.id to it.name }
        val order = categories.withIndex().associate { it.value.id to it.index }
        val arr = json.parseToJsonElement(http.getText(apiUrl(source, "get_live_streams"), headers(source))) as? JsonArray
            ?: return emptyList()
        val hdr = headers(source)
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val streamId = o.int("stream_id") ?: return@mapNotNull null
            val archive = o.int("tv_archive") == 1
            val archiveDays = o.int("tv_archive_duration") ?: 0
            val categoryId = o.str("category_id")
            LiveChannel(
                id = "${source.id}:$streamId",
                sourceId = source.id,
                name = o.str("name")?.trim().orEmpty().ifEmpty { "Channel $streamId" },
                number = o.int("num"),
                group = categoryId?.let { names[it] } ?: LiveChannel.UNGROUPED,
                logoUrl = o.str("stream_icon")?.takeIf { it.isNotBlank() },
                epgChannelId = o.str("epg_channel_id")?.takeIf { it.isNotBlank() },
                streamUrl = liveStreamUrl(source, streamId),
                headers = hdr,
                catchup = if (archive && archiveDays > 0) Catchup(CatchupType.XTREAM, archiveDays) else null,
                xtreamStreamId = streamId,
            ) to (categoryId?.let { order[it] } ?: Int.MAX_VALUE)
        }.sortedWith(compareBy({ it.second }, { it.first.number ?: Int.MAX_VALUE })).map { it.first }
    }

    private fun JsonObject.prim(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }
    private fun JsonObject.str(key: String): String? = prim(key)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = str(key)?.trim()?.toDoubleOrNull()?.toLong()
    private fun JsonObject.int(key: String): Int? = str(key)?.trim()?.let { v ->
        when (v.lowercase()) { "true" -> 1; "false" -> 0; else -> v.toDoubleOrNull()?.toInt() }
    }
}
