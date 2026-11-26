package com.nuvio.iptv.m3u

import com.nuvio.iptv.model.Catchup
import com.nuvio.iptv.model.CatchupType
import com.nuvio.iptv.model.LiveChannel
import java.io.BufferedReader
import java.io.Reader

/**
 * Streaming parser for extended M3U playlists (the format IPTV providers and TiviMate use).
 *
 * Understands: #EXTM3U header attributes (url-tvg / x-tvg-url, catchup defaults, tvg-shift),
 * #EXTINF attributes (tvg-id, tvg-name, tvg-logo, tvg-chno, group-title, catchup*, timeshift,
 * tvg-shift), #EXTGRP, #EXTVLCOPT http-user-agent / http-referrer / http-origin, and Kodi-style
 * "url|User-Agent=x&Referer=y" header suffixes.
 */
class M3uParser {

    data class Result(
        val channels: List<LiveChannel>,
        /** EPG URLs advertised by the playlist header. */
        val epgUrls: List<String>,
    )

    fun parse(sourceId: String, reader: Reader, defaultUserAgent: String? = null): Result {
        val channels = ArrayList<LiveChannel>()
        val epgUrls = LinkedHashSet<String>()
        val seenKeys = HashMap<String, Int>()

        var headerAttrs: Map<String, String> = emptyMap()
        var pendingInfo: ExtInf? = null
        var pendingGroup: String? = null
        val pendingHeaders = LinkedHashMap<String, String>()

        val buffered = reader as? BufferedReader ?: BufferedReader(reader)
        var first = true
        buffered.forEachLine { raw ->
            var line = raw.trim()
            if (first) {
                line = line.removePrefix("﻿")
                first = false
            }
            if (line.isEmpty()) return@forEachLine
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    headerAttrs = parseAttributes(line.substring(7))
                    listOf("url-tvg", "x-tvg-url", "tvg-url").forEach { key ->
                        headerAttrs[key]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                            ?.forEach { epgUrls += it }
                    }
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingInfo = parseExtInf(line)
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    pendingGroup = line.substringAfter(':').trim().ifEmpty { null }
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter(':')
                    val key = opt.substringBefore('=').trim().lowercase()
                    val value = opt.substringAfter('=', "").trim()
                    when (key) {
                        "http-user-agent" -> pendingHeaders["User-Agent"] = value
                        "http-referrer", "http-referer" -> pendingHeaders["Referer"] = value
                        "http-origin" -> pendingHeaders["Origin"] = value
                    }
                }
                line.startsWith("#") -> Unit // other directives (#KODIPROP, #EXT-X-*, comments)
                else -> {
                    val info = pendingInfo
                    if (info != null) {
                        val (url, urlHeaders) = splitKodiHeaders(line)
                        val headers = LinkedHashMap<String, String>()
                        defaultUserAgent?.let { headers["User-Agent"] = it }
                        headers.putAll(pendingHeaders)
                        headers.putAll(urlHeaders)
                        channels += buildChannel(sourceId, info, pendingGroup, url, headers, headerAttrs, seenKeys)
                    }
                    pendingInfo = null
                    pendingGroup = null
                    pendingHeaders.clear()
                }
            }
        }
        return Result(channels, epgUrls.toList())
    }

    private data class ExtInf(val attrs: Map<String, String>, val title: String)

    private fun parseExtInf(line: String): ExtInf {
        // #EXTINF:-1 tvg-id="x" group-title="Sports, UK",Channel Name
        val body = line.substringAfter(':', "")
        var inQuotes = false
        var commaAt = -1
        for (i in body.indices) {
            val c = body[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) { commaAt = i; break }
        }
        val attrPart = if (commaAt >= 0) body.substring(0, commaAt) else body
        val title = if (commaAt >= 0) body.substring(commaAt + 1).trim() else ""
        // Skip the duration token before the attributes.
        val firstSpace = attrPart.indexOfFirst { it == ' ' || it == '\t' }
        val attrs = if (firstSpace >= 0) parseAttributes(attrPart.substring(firstSpace)) else emptyMap()
        return ExtInf(attrs, title)
    }

    private fun buildChannel(
        sourceId: String,
        info: ExtInf,
        extGroup: String?,
        url: String,
        headers: Map<String, String>,
        header: Map<String, String>,
        seenKeys: MutableMap<String, Int>,
    ): LiveChannel {
        val a = info.attrs
        val name = info.title.ifEmpty { a["tvg-name"].orEmpty() }.ifEmpty { url }
        val group = a["group-title"]?.takeIf { it.isNotBlank() }
            ?: extGroup
            ?: LiveChannel.UNGROUPED
        val epgId = a["tvg-id"]?.takeIf { it.isNotBlank() }
        val baseKey = epgId?.let { "$it|$url" } ?: url
        val count = seenKeys.merge(baseKey, 1, Int::plus) ?: 1
        val key = if (count == 1) baseKey else "$baseKey#$count"
        return LiveChannel(
            id = "$sourceId:${stableSuffix(key)}",
            sourceId = sourceId,
            name = name,
            number = (a["tvg-chno"] ?: a["channel-number"] ?: a["tvg-num"])?.trim()?.toIntOrNull(),
            group = group,
            logoUrl = (a["tvg-logo"] ?: a["logo"])?.takeIf { it.isNotBlank() },
            epgChannelId = epgId,
            streamUrl = url,
            headers = headers,
            catchup = parseCatchup(a, header),
            epgShiftHours = (a["tvg-shift"] ?: header["tvg-shift"])?.toDoubleOrNull() ?: 0.0,
        )
    }

    private fun stableSuffix(key: String): String {
        // 64-bit FNV-1a: stable across runs and devices, no digest library needed.
        var h = 0xcbf29ce484222325uL
        for (ch in key) {
            h = h xor ch.code.toULong()
            h *= 0x100000001b3uL
        }
        return h.toString(16)
    }

    private fun parseCatchup(a: Map<String, String>, header: Map<String, String>): Catchup? {
        val typeRaw = (a["catchup"] ?: a["catchup-type"] ?: header["catchup"] ?: header["catchup-type"])
            ?.trim()?.lowercase()
        val days = (a["catchup-days"] ?: a["timeshift"] ?: a["tvg-rec"] ?: header["catchup-days"])
            ?.trim()?.toIntOrNull()
        val source = (a["catchup-source"] ?: header["catchup-source"])?.takeIf { it.isNotBlank() }
        val type = when (typeRaw) {
            null, "" -> if (days != null && days > 0) (if (source != null) CatchupType.DEFAULT else CatchupType.SHIFT) else return null
            "default" -> CatchupType.DEFAULT
            "append" -> CatchupType.APPEND
            "shift", "timeshift" -> CatchupType.SHIFT
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> CatchupType.FLUSSONIC
            "xc", "xtream" -> CatchupType.XTREAM
            "vod" -> CatchupType.DEFAULT
            else -> return null
        }
        return Catchup(type, days?.coerceAtLeast(1) ?: 1, source)
    }

    companion object {
        /** Parses key="value" / key=value pairs. Keys are lower-cased. */
        fun parseAttributes(text: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            var i = 0
            val n = text.length
            while (i < n) {
                while (i < n && text[i].isWhitespace()) i++
                val keyStart = i
                while (i < n && text[i] != '=' && !text[i].isWhitespace()) i++
                if (i >= n || text[i] != '=') { // bare token without value
                    if (i > keyStart) out[text.substring(keyStart, i).lowercase()] = ""
                    continue
                }
                val key = text.substring(keyStart, i).lowercase()
                i++ // '='
                val value: String
                if (i < n && (text[i] == '"' || text[i] == '\'')) {
                    val quote = text[i]
                    val end = text.indexOf(quote, i + 1).let { if (it < 0) n else it }
                    value = text.substring(i + 1, end)
                    i = (end + 1).coerceAtMost(n)
                } else {
                    val start = i
                    while (i < n && !text[i].isWhitespace()) i++
                    value = text.substring(start, i)
                }
                if (key.isNotEmpty()) out[key] = value.trim()
            }
            return out
        }

        /** "http://x/y.ts|User-Agent=abc&Referer=http://z" -> url + headers. */
        fun splitKodiHeaders(line: String): Pair<String, Map<String, String>> {
            val bar = line.indexOf('|')
            if (bar < 0) return line to emptyMap()
            val url = line.substring(0, bar).trim()
            val headers = LinkedHashMap<String, String>()
            line.substring(bar + 1).split('&').forEach { part ->
                val k = part.substringBefore('=').trim()
                val v = part.substringAfter('=', "").trim()
                if (k.isNotEmpty() && v.isNotEmpty()) {
                    val name = when (k.lowercase()) {
                        "user-agent" -> "User-Agent"
                        "referer", "referrer" -> "Referer"
                        "origin" -> "Origin"
                        else -> k
                    }
                    headers[name] = runCatching { java.net.URLDecoder.decode(v.replace("+", "%2B"), "UTF-8") }.getOrDefault(v)
                }
            }
            return url to headers
        }
    }
}
