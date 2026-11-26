package com.nuvio.iptv.xmltv

import com.nuvio.iptv.model.CivilTime
import com.nuvio.iptv.model.EpgChannel
import com.nuvio.iptv.model.Programme
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * Streams an XMLTV guide and hands channels / programmes to callbacks, so huge provider guides
 * never sit in memory as a whole. Gzipped guides (.xml.gz) are detected automatically.
 */
class XmltvParser {

    interface Sink {
        fun onChannel(channel: EpgChannel) {}

        /** Return false to stop parsing early. */
        fun onProgramme(programme: Programme): Boolean = true
    }

    /**
     * @param keepChannel decides whether programmes of a channel id are wanted at all (cheap filter
     *   applied before text is collected).
     * @param windowStartMs programmes ending before this are dropped.
     * @param windowEndMs programmes starting after this are dropped.
     */
    fun parse(
        input: InputStream,
        sink: Sink,
        keepChannel: (String) -> Boolean = { true },
        windowStartMs: Long = Long.MIN_VALUE,
        windowEndMs: Long = Long.MAX_VALUE,
    ) {
        val reader = InputStreamReader(maybeGunzip(input), Charsets.UTF_8)
        val tok = XmlTokenizer(reader)

        // Channel state
        var chId: String? = null
        val chNames = ArrayList<String>(2)
        var chIcon: String? = null

        // Programme state
        var inProgramme = false
        var skipProgramme = false
        var pChannel = ""
        var pStart = 0L
        var pEnd = 0L
        var title: String? = null
        var subTitle: String? = null
        var desc: String? = null
        val categories = ArrayList<String>(2)
        var icon: String? = null
        var episode: String? = null
        var episodeSystem: String? = null
        var episodeIsOnscreen = false

        var currentText: String? = null

        loop@ while (true) {
            when (val ev = tok.next()) {
                XmlTokenizer.Event.Eof -> break@loop
                is XmlTokenizer.Event.Start -> {
                    currentText = null
                    when (ev.name) {
                        "channel" -> {
                            chId = ev.attrs["id"]
                            chNames.clear()
                            chIcon = null
                            if (ev.selfClosing) {
                                chId?.let { sink.onChannel(EpgChannel(it, emptyList(), null)) }
                                chId = null
                            }
                        }
                        "programme" -> {
                            val channel = ev.attrs["channel"].orEmpty()
                            val start = parseXmltvTime(ev.attrs["start"])
                            val stop = parseXmltvTime(ev.attrs["stop"])
                            inProgramme = !ev.selfClosing
                            skipProgramme = start == null || channel.isEmpty() || !keepChannel(channel) ||
                                (stop != null && stop < windowStartMs) || start > windowEndMs
                            pChannel = channel
                            pStart = start ?: 0L
                            // Missing stop: assume 30 min; fixed up by EpgIndex using next programme start.
                            pEnd = stop ?: (pStart + 30 * 60_000L)
                            title = null; subTitle = null; desc = null; icon = null
                            episode = null; episodeSystem = null; episodeIsOnscreen = false
                            categories.clear()
                        }
                        "icon" -> {
                            val src = ev.attrs["src"]
                            if (inProgramme) icon = icon ?: src else if (chId != null) chIcon = chIcon ?: src
                        }
                        "episode-num" -> episodeSystem = ev.attrs["system"]
                    }
                }
                is XmlTokenizer.Event.Text -> currentText = (currentText ?: "") + ev.text
                is XmlTokenizer.Event.End -> {
                    val text = currentText?.trim()
                    currentText = null
                    when (ev.name) {
                        "display-name" -> if (chId != null && !text.isNullOrEmpty()) chNames += text
                        "channel" -> {
                            chId?.let { sink.onChannel(EpgChannel(it, chNames.toList(), chIcon)) }
                            chId = null
                        }
                        "title" -> if (inProgramme && title == null) title = text
                        "sub-title" -> if (inProgramme && subTitle == null) subTitle = text
                        "desc" -> if (inProgramme && desc == null) desc = text
                        "category" -> if (inProgramme && !text.isNullOrEmpty()) categories += text
                        "episode-num" -> if (inProgramme && !text.isNullOrEmpty()) {
                            // Prefer the human readable form; convert xmltv_ns (0-based "s.e.p") otherwise.
                            when (episodeSystem) {
                                "onscreen", "SxxExx" -> { episode = text; episodeIsOnscreen = true }
                                "xmltv_ns" -> if (!episodeIsOnscreen) episode = formatXmltvNs(text) ?: episode
                                else -> if (episode == null) episode = text
                            }
                        }
                        "programme" -> {
                            if (inProgramme && !skipProgramme) {
                                val keepGoing = sink.onProgramme(
                                    Programme(
                                        channelId = pChannel,
                                        startMs = pStart,
                                        endMs = pEnd,
                                        title = title ?: "",
                                        subtitle = subTitle,
                                        description = desc,
                                        categories = categories.toList(),
                                        iconUrl = icon,
                                        episode = episode,
                                    )
                                )
                                if (!keepGoing) break@loop
                            }
                            inProgramme = false
                        }
                    }
                }
            }
        }
        reader.close()
    }

    companion object {
        fun maybeGunzip(input: InputStream): InputStream {
            val bis = if (input is BufferedInputStream) input else BufferedInputStream(input, 64 * 1024)
            bis.mark(2)
            val b1 = bis.read()
            val b2 = bis.read()
            bis.reset()
            return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(bis, 64 * 1024) else bis
        }

        /** "20261126190000 +0100" (offset optional, seconds optional) -> epoch ms. */
        fun parseXmltvTime(value: String?): Long? {
            if (value.isNullOrBlank()) return null
            val v = value.trim()
            val digits = v.takeWhile { it.isDigit() }
            if (digits.length < 12) return null
            val year = digits.substring(0, 4).toInt()
            val month = digits.substring(4, 6).toInt()
            val day = digits.substring(6, 8).toInt()
            val hour = digits.substring(8, 10).toInt()
            val minute = digits.substring(10, 12).toInt()
            val second = if (digits.length >= 14) digits.substring(12, 14).toInt() else 0
            val rest = v.substring(digits.length).trim()
            var offsetMinutes = 0
            if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
                val sign = if (rest[0] == '-') -1 else 1
                val hh = rest.substring(1, 3).toIntOrNull() ?: 0
                val mm = rest.substring(3, 5).toIntOrNull() ?: 0
                offsetMinutes = sign * (hh * 60 + mm)
            }
            return CivilTime(year, month, day, hour, minute, second).toEpochMillis(offsetMinutes)
        }

        /** xmltv_ns "1.4.0/1" (zero based) -> "S02E05". */
        fun formatXmltvNs(text: String): String? {
            val parts = text.split('.')
            if (parts.size < 2) return null
            val season = parts[0].substringBefore('/').trim().toIntOrNull()
            val ep = parts[1].substringBefore('/').trim().toIntOrNull()
            return when {
                season != null && ep != null -> "S%02dE%02d".format(season + 1, ep + 1)
                ep != null -> "E%02d".format(ep + 1)
                else -> null
            }
        }
    }
}
