package com.nuvio.iptv.catchup

import com.nuvio.iptv.model.CatchupType
import com.nuvio.iptv.model.CivilTime
import com.nuvio.iptv.model.LiveChannel
import com.nuvio.iptv.model.pad2
import com.nuvio.iptv.model.pad4

/**
 * Builds the URL that plays a past programme (catch-up / archive), following the conventions
 * used by TiviMate and Kodi's IPTV Simple client.
 */
object CatchupUrlBuilder {

    /**
     * @param serverUtcOffsetMinutes the provider's clock offset; Xtream panels expect local panel time.
     * @return null when the channel has no catch-up or the programme is outside the archive window.
     */
    fun build(
        channel: LiveChannel,
        startMs: Long,
        endMs: Long,
        nowMs: Long,
        serverUtcOffsetMinutes: Int = 0,
    ): String? {
        val catchup = channel.catchup ?: return null
        if (startMs >= nowMs) return null
        val oldestAllowed = nowMs - catchup.days * 86_400_000L
        if (startMs < oldestAllowed) return null
        val stream = channel.streamUrl
        val ctx = Ctx(startMs, endMs, nowMs, serverUtcOffsetMinutes)

        return when (catchup.type) {
            CatchupType.DEFAULT -> {
                val src = catchup.source ?: return shiftUrl(stream, ctx)
                if (src.startsWith("http://") || src.startsWith("https://")) fill(src, ctx)
                else fill(stream + src, ctx)
            }
            CatchupType.APPEND -> fill(stream + (catchup.source ?: return shiftUrl(stream, ctx)), ctx)
            CatchupType.SHIFT -> shiftUrl(stream, ctx)
            CatchupType.FLUSSONIC -> flussonicUrl(stream, ctx)
            CatchupType.XTREAM -> xtreamUrl(stream, ctx)
        }
    }

    private data class Ctx(val start: Long, val end: Long, val now: Long, val offset: Int) {
        val startSec get() = start / 1000
        val endSec get() = end / 1000
        val nowSec get() = now / 1000
        val durationSec get() = ((end - start) / 1000).coerceAtLeast(60)
    }

    private fun shiftUrl(stream: String, ctx: Ctx): String {
        val sep = if ('?' in stream) '&' else '?'
        return "$stream${sep}utc=${ctx.startSec}&lutc=${ctx.nowSec}"
    }

    // http://host/channel/index.m3u8 -> http://host/channel/index-<start>-<duration>.m3u8
    // http://host/channel/mpegts     -> http://host/channel/timeshift_abs-<start>.ts
    private val FLUSSONIC_HLS = Regex("^(.+/)([^/]*?)(index|mono|video|tracks-[^/]+)\\.m3u8(\\?.*)?$")
    private val FLUSSONIC_TS = Regex("^(.+/)mpegts(\\?.*)?$")

    private fun flussonicUrl(stream: String, ctx: Ctx): String? {
        FLUSSONIC_HLS.matchEntire(stream)?.let { m ->
            val (base, prefix, kind, query) = m.destructured
            return "$base$prefix$kind-${ctx.startSec}-${ctx.durationSec}.m3u8$query"
        }
        FLUSSONIC_TS.matchEntire(stream)?.let { m ->
            val (base, query) = m.destructured
            return "${base}timeshift_abs-${ctx.startSec}.ts$query"
        }
        return null
    }

    // http://host:port/[live/]user/pass/123.ts -> http://host:port/timeshift/user/pass/<minutes>/<Y-m-d:H-M>/123.ts
    private val XTREAM_LIVE = Regex("^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\\d+)(\\.[a-z0-9]+)?(\\?.*)?$", RegexOption.IGNORE_CASE)

    private fun xtreamUrl(stream: String, ctx: Ctx): String? {
        val m = XTREAM_LIVE.matchEntire(stream) ?: return null
        val (host, user, pass, id, ext, _) = m.destructured
        val t = CivilTime.fromEpochMillis(ctx.start, ctx.offset)
        val minutes = (ctx.durationSec + 59) / 60
        val stamp = "${t.year.pad4()}-${t.month.pad2()}-${t.day.pad2()}:${t.hour.pad2()}-${t.minute.pad2()}"
        return "$host/timeshift/$user/$pass/$minutes/$stamp/$id${ext.ifEmpty { ".ts" }}"
    }

    // {utc} {start} {utcend} {end} {lutc} {now} {timestamp} {duration} {duration:N} {offset} {offset:N}
    // {Y} {m} {d} {H} {M} {S}, ${start} style, and {(b)yyyy-MM-dd HH:mm} / ${(e)...} date formats.
    private val TOKEN = Regex("\\$?\\{([^}]+)\\}")

    private fun fill(template: String, ctx: Ctx): String = TOKEN.replace(template) { m ->
        val token = m.groupValues[1]
        val begin by lazy { CivilTime.fromEpochMillis(ctx.start, ctx.offset) }
        val endT by lazy { CivilTime.fromEpochMillis(ctx.end, ctx.offset) }
        when {
            token == "utc" || token == "start" -> ctx.startSec.toString()
            token == "utcend" || token == "end" -> ctx.endSec.toString()
            token == "lutc" || token == "now" || token == "timestamp" -> ctx.nowSec.toString()
            token == "duration" -> ctx.durationSec.toString()
            token.startsWith("duration:") -> (ctx.durationSec / (token.substringAfter(':').toLongOrNull() ?: 1).coerceAtLeast(1)).toString()
            token == "offset" -> (ctx.nowSec - ctx.startSec).toString()
            token.startsWith("offset:") -> ((ctx.nowSec - ctx.startSec) / (token.substringAfter(':').toLongOrNull() ?: 1).coerceAtLeast(1)).toString()
            token == "Y" -> begin.year.pad4()
            token == "m" -> begin.month.pad2()
            token == "d" -> begin.day.pad2()
            token == "H" -> begin.hour.pad2()
            token == "M" -> begin.minute.pad2()
            token == "S" -> begin.second.pad2()
            token.startsWith("(b)") -> formatDate(token.removePrefix("(b)"), begin)
            token.startsWith("(e)") -> formatDate(token.removePrefix("(e)"), endT)
            token.startsWith("(n)") -> formatDate(token.removePrefix("(n)"), CivilTime.fromEpochMillis(ctx.now, ctx.offset))
            else -> m.value
        }
    }

    private fun formatDate(pattern: String, t: CivilTime): String =
        pattern.replace("yyyy", t.year.pad4())
            .replace("MM", t.month.pad2())
            .replace("dd", t.day.pad2())
            .replace("HH", t.hour.pad2())
            .replace("mm", t.minute.pad2())
            .replace("ss", t.second.pad2())
}
