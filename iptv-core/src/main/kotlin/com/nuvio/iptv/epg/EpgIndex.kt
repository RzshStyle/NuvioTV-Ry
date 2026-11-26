package com.nuvio.iptv.epg

import com.nuvio.iptv.model.EpgChannel
import com.nuvio.iptv.model.LiveChannel
import com.nuvio.iptv.model.Programme
import com.nuvio.iptv.xmltv.XmltvParser
import java.io.InputStream

/**
 * In-memory guide for the channels the user actually has, keyed by playlist channel id.
 * Built from one or more XMLTV streams; the app layer persists it between launches.
 */
class EpgIndex private constructor(
    private val byChannel: Map<String, List<Programme>>,
) {
    val channelCount: Int get() = byChannel.size
    val programmeCount: Int get() = byChannel.values.sumOf { it.size }

    fun programmes(channelId: String): List<Programme> = byChannel[channelId].orEmpty()

    /** Programmes overlapping [fromMs, toMs). */
    fun programmes(channelId: String, fromMs: Long, toMs: Long): List<Programme> {
        val list = byChannel[channelId] ?: return emptyList()
        var lo = firstEndingAfter(list, fromMs)
        val out = ArrayList<Programme>()
        while (lo < list.size && list[lo].startMs < toMs) {
            out += list[lo]
            lo++
        }
        return out
    }

    fun nowAndNext(channelId: String, nowMs: Long): Pair<Programme?, Programme?> {
        val list = byChannel[channelId] ?: return null to null
        val i = firstEndingAfter(list, nowMs)
        val current = list.getOrNull(i)?.takeIf { it.startMs <= nowMs }
        val next = if (current != null) list.getOrNull(i + 1) else list.getOrNull(i)
        return current to next
    }

    fun programmeAt(channelId: String, atMs: Long): Programme? = nowAndNext(channelId, atMs).first

    private fun firstEndingAfter(list: List<Programme>, t: Long): Int {
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].endMs <= t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    class Builder(channels: List<LiveChannel>) {
        private val matcher = EpgMatcher(channels)
        private val collected = HashMap<String, ArrayList<Programme>>()
        private val shifts: Map<String, Long> = channels
            .filter { it.epgShiftHours != 0.0 }
            .associate { it.id to (it.epgShiftHours * 3_600_000).toLong() }

        /** Parses one XMLTV stream. Call for every EPG URL, then [build]. */
        fun addXmltv(input: InputStream, windowStartMs: Long, windowEndMs: Long) {
            XmltvParser().parse(
                input,
                object : XmltvParser.Sink {
                    // XMLTV lists <channel> elements before <programme>s, so names are known in time.
                    override fun onChannel(channel: EpgChannel) = matcher.registerEpgChannel(channel)
                    override fun onProgramme(programme: Programme): Boolean {
                        for (target in matcher.channelIdsFor(programme.channelId)) {
                            val shift = shifts[target] ?: 0L
                            collected.getOrPut(target) { ArrayList() } += programme.copy(
                                channelId = target,
                                startMs = programme.startMs + shift,
                                endMs = programme.endMs + shift,
                            )
                        }
                        return true
                    }
                },
                keepChannel = { matcher.channelIdsFor(it).isNotEmpty() },
                windowStartMs = windowStartMs,
                windowEndMs = windowEndMs,
            )
        }

        fun build(): EpgIndex {
            val result = HashMap<String, List<Programme>>(collected.size)
            for ((id, list) in collected) {
                list.sortBy { it.startMs }
                // De-duplicate (several EPG sources) and repair overlaps / missing stop times.
                val cleaned = ArrayList<Programme>(list.size)
                for (p in list) {
                    val last = cleaned.lastOrNull()
                    if (last != null && p.startMs < last.endMs) {
                        if (p.startMs == last.startMs) continue
                        cleaned[cleaned.size - 1] = last.copy(endMs = p.startMs)
                    }
                    if (p.endMs > p.startMs) cleaned += p
                }
                result[id] = cleaned
            }
            return EpgIndex(result)
        }
    }

    companion object {
        fun of(programmes: Collection<Programme>): EpgIndex =
            EpgIndex(programmes.groupBy { it.channelId }.mapValues { (_, v) -> v.sortedBy { it.startMs } })
    }
}

/**
 * Maps XMLTV channel ids to playlist channels: exact tvg-id first, then case-insensitive id,
 * then a normalised display-name match (so "BBC One HD" finds "bbc one hd" / "BBC ONE").
 */
class EpgMatcher(private val channels: List<LiveChannel>) {
    private val byEpgId = HashMap<String, MutableList<String>>()
    private val byEpgIdLower = HashMap<String, MutableList<String>>()
    private val byName = HashMap<String, MutableList<String>>()
    private val resolved = HashMap<String, List<String>>()
    private val namesForEpgId = HashMap<String, List<String>>()

    init {
        for (c in channels) {
            c.epgChannelId?.let {
                byEpgId.getOrPut(it) { ArrayList() } += c.id
                byEpgIdLower.getOrPut(it.lowercase()) { ArrayList() } += c.id
            }
            if (c.epgChannelId == null) byName.getOrPut(normalise(c.name)) { ArrayList() } += c.id
        }
    }

    fun registerEpgChannel(epg: EpgChannel) {
        namesForEpgId[epg.id] = epg.displayNames
        resolved.remove(epg.id)
    }

    fun channelIdsFor(epgChannelId: String): List<String> = resolved.getOrPut(epgChannelId) {
        byEpgId[epgChannelId]
            ?: byEpgIdLower[epgChannelId.lowercase()]
            ?: run {
                val candidates = namesForEpgId[epgChannelId].orEmpty() + epgChannelId.substringBeforeLast('.')
                candidates.firstNotNullOfOrNull { byName[normalise(it)] }
            }
            ?: emptyList()
    }

    companion object {
        private val QUALITY = Regex("\\b(fhd|uhd|hd|sd|4k|8k|hevc|h265|raw|1080p|720p|backup)\\b")
        private val PREFIX = Regex("^[a-z]{2,3}\\s*[:|\\-]\\s+")

        fun normalise(name: String): String = name.lowercase()
            .replace(PREFIX, "")
            .replace(QUALITY, "")
            .replace(Regex("[^a-z0-9]+"), "")
    }
}
