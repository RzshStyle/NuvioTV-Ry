package com.nuvio.tv.core.tmdb

import java.time.LocalDate

/**
 * Placeholders allowed in TMDB filter text: {today}, {d-N} / {d+N} (days from today) and
 * {region}. Plain string handling on purpose: Android's ICU regex rejects an unescaped "}".
 */
object TmdbPlaceholders {
    fun fill(value: String, today: LocalDate, region: String): String {
        if (!value.contains('{')) return value
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val open = value.indexOf('{', i)
            if (open < 0) { out.append(value, i, value.length); break }
            val close = value.indexOf('}', open + 1)
            if (close < 0) { out.append(value, i, value.length); break }
            out.append(value, i, open)
            val token = value.substring(open + 1, close).trim().lowercase()
            out.append(resolve(token, today, region) ?: value.substring(open, close + 1))
            i = close + 1
        }
        return out.toString()
    }

    private fun resolve(token: String, today: LocalDate, region: String): String? = when {
        token == "today" -> today.toString()
        token == "region" -> region
        token.startsWith("d-") -> token.drop(2).toLongOrNull()?.let { today.minusDays(it).toString() }
        token.startsWith("d+") -> token.drop(2).toLongOrNull()?.let { today.plusDays(it).toString() }
        else -> null
    }
}
