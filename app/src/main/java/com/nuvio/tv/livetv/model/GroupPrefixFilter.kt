package com.nuvio.tv.livetv.model

/**
 * Keeps (or hides) channels by the tag at the start of their group or channel name, such as
 * "UK" in "UK | Sports", "US" in "US: News" or "4K" in "4K| UHD 3840P".
 *
 * Applied while a playlist is read, so channels left out never reach Live TV, the guide,
 * Sports or search, and the guide has fewer channels to match.
 */
object GroupPrefixFilter {
    const val OFF = "off"
    const val KEEP = "keep"
    const val HIDE = "hide"

    /** Characters that end a tag: "UK|", "UK:", "UK -", "[UK]", "UK/". */
    private const val SEPARATORS = "|:-]/)•·»>"

    /** The tags as typed: one per line or separated by commas, without their separator. */
    fun parseTags(text: String): List<String> =
        text.split('\n', ',', ';')
            .map { normalizeTag(it) }
            .filter { it.isNotEmpty() }
            .distinct()

    fun joinTags(tags: Collection<String>): String = tags.map { normalizeTag(it) }.filter { it.isNotEmpty() }.distinct().joinToString(",")

    /** "uk |" -> "UK", " [4k] " -> "4K". */
    fun normalizeTag(raw: String): String =
        raw.trim().trimStart { !it.isLetterOrDigit() }.trimEnd { !it.isLetterOrDigit() }.uppercase()

    /** The name with any leading decoration (★, [, |, #, spaces) taken off, in upper case. */
    private fun body(name: String): String = name.trimStart { !it.isLetterOrDigit() }.uppercase()

    /** True when [name] starts with [tag] as a whole word: "UK| Sports" and "UK Sports" match "UK", "UKRAINE" doesn't. */
    fun startsWithTag(name: String, tag: String): Boolean {
        val b = body(name)
        if (!b.startsWith(tag)) return false
        if (b.length == tag.length) return true
        return !b[tag.length].isLetterOrDigit()
    }

    /**
     * The tag a group's name starts with, if it has one: the first word, when a separator
     * follows it ("4K| UHD" -> "4K", "US: News" -> "US", "UK - Sports" -> "UK").
     * "Sports Football" has none.
     */
    fun detectTag(group: String): String? {
        val b = body(group)
        var i = 0
        while (i < b.length && (b[i].isLetterOrDigit() || b[i] == '+' || b[i] == '&')) i++
        if (i == 0 || i > 12) return null
        var j = i
        while (j < b.length && b[j] == ' ') j++
        if (j >= b.length || b[j] !in SEPARATORS) return null
        return b.substring(0, i)
    }

    /** Short stamp for the saved channel list: changes whenever the filter does. Null when off. */
    fun stamp(mode: String, tags: String): String? {
        val list = parseTags(tags)
        if (mode == OFF || list.isEmpty()) return null
        return mode + "=" + list.sorted().joinToString("+")
    }

    /**
     * A filter ready to use while reading a playlist. Remembers each group's answer, since
     * thousands of channels share a few hundred groups.
     */
    class Active(private val mode: String, private val tags: List<String>) {
        private val byGroup = HashMap<String, Boolean>()

        private fun matches(name: String): Boolean = tags.any { startsWithTag(name, it) }

        /** True when the channel stays. */
        fun keep(group: String, channelName: String): Boolean {
            val groupHit = byGroup.getOrPut(group) { matches(group) }
            val hit = groupHit || matches(channelName)
            return if (mode == KEEP) hit else !hit
        }
    }

    /** Null when the filter is off (or has no tags): every channel stays. */
    fun active(mode: String, tags: String): Active? {
        val list = parseTags(tags)
        if (mode == OFF || list.isEmpty()) return null
        return Active(mode, list)
    }
}
