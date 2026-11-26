package com.nuvio.iptv.xmltv

import java.io.Reader

/**
 * Tiny streaming XML pull tokenizer, enough for XMLTV guides (which can be 100+ MB, so DOM
 * parsing is not an option). Works on plain JVM and Android without platform XML APIs.
 */
internal class XmlTokenizer(private val reader: Reader) {

    sealed interface Event {
        data class Start(val name: String, val attrs: Map<String, String>, val selfClosing: Boolean) : Event
        data class End(val name: String) : Event
        data class Text(val text: String) : Event
        data object Eof : Event
    }

    private val buf = CharArray(64 * 1024)
    private var pos = 0
    private var len = 0

    private fun fill(): Boolean {
        if (pos < len) return true
        len = reader.read(buf, 0, buf.size)
        pos = 0
        return len > 0
    }

    private fun peek(): Int = if (fill()) buf[pos].code else -1
    private fun read(): Int = if (fill()) buf[pos++].code else -1

    private val sb = StringBuilder()

    fun next(): Event {
        while (true) {
            val c = peek()
            if (c < 0) return Event.Eof
            if (c != '<'.code) {
                val text = readText()
                if (text.isNotBlank()) return Event.Text(text)
                continue
            }
            read() // '<'
            when (peek()) {
                '/'.code -> {
                    read()
                    val name = readName()
                    skipUntil('>')
                    return Event.End(name)
                }
                '?'.code -> { skipUntilSeq("?>"); continue }
                '!'.code -> {
                    read()
                    if (peek() == '-'.code) { skipUntilSeq("-->"); continue }
                    if (peek() == '['.code) { // <![CDATA[ ... ]]>
                        skipUntilSeq("CDATA[")
                        val text = readUntilSeq("]]>")
                        if (text.isNotEmpty()) return Event.Text(text)
                        continue
                    }
                    skipDoctype(); continue
                }
                else -> return readStartTag()
            }
        }
    }

    private fun readText(): String {
        sb.setLength(0)
        while (true) {
            val c = peek()
            if (c < 0 || c == '<'.code) break
            sb.append(read().toChar())
        }
        return decodeEntities(sb.toString())
    }

    private fun readName(): String {
        val name = StringBuilder()
        while (true) {
            val c = peek()
            if (c < 0 || c.toChar().isWhitespace() || c == '>'.code || c == '/'.code || c == '='.code) break
            name.append(read().toChar())
        }
        return name.toString()
    }

    private fun skipWs() {
        while (true) {
            val c = peek()
            if (c < 0 || !c.toChar().isWhitespace()) return
            read()
        }
    }

    private fun readStartTag(): Event {
        val name = readName()
        val attrs = LinkedHashMap<String, String>(4)
        while (true) {
            skipWs()
            val c = peek()
            if (c < 0) return Event.Start(name, attrs, true)
            if (c == '>'.code) { read(); return Event.Start(name, attrs, false) }
            if (c == '/'.code) { read(); skipUntil('>'); return Event.Start(name, attrs, true) }
            val key = readName()
            if (key.isEmpty()) { read(); continue }
            skipWs()
            if (peek() != '='.code) { attrs[key] = ""; continue }
            read()
            skipWs()
            val q = peek()
            val value = if (q == '"'.code || q == '\''.code) {
                read()
                val v = StringBuilder()
                while (true) {
                    val ch = read()
                    if (ch < 0 || ch == q) break
                    v.append(ch.toChar())
                }
                v.toString()
            } else readName()
            attrs[key] = decodeEntities(value)
        }
    }

    private fun skipUntil(ch: Char) {
        while (true) {
            val c = read()
            if (c < 0 || c == ch.code) return
        }
    }

    private fun skipUntilSeq(seq: String) { readUntilSeq(seq, keep = false) }

    private fun readUntilSeq(seq: String, keep: Boolean = true): String {
        val out = StringBuilder()
        var matched = 0
        while (true) {
            val c = read()
            if (c < 0) break
            val ch = c.toChar()
            if (ch == seq[matched]) {
                matched++
                if (matched == seq.length) break
            } else {
                if (keep && matched > 0) out.append(seq, 0, matched)
                matched = if (ch == seq[0]) 1 else 0
                if (keep && matched == 0) out.append(ch)
            }
        }
        return out.toString()
    }

    private fun skipDoctype() {
        var depth = 0
        while (true) {
            val c = read()
            if (c < 0) return
            when (c) {
                '['.code -> depth++
                ']'.code -> depth--
                '>'.code -> if (depth <= 0) return
            }
        }
    }

    companion object {
        fun decodeEntities(s: String): String {
            if (s.indexOf('&') < 0) return s
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '&') {
                    val semi = s.indexOf(';', i)
                    if (semi in (i + 2)..(i + 10)) {
                        val ent = s.substring(i + 1, semi)
                        val decoded: String? = when {
                            ent == "amp" -> "&"
                            ent == "lt" -> "<"
                            ent == "gt" -> ">"
                            ent == "quot" -> "\""
                            ent == "apos" -> "'"
                            ent.startsWith("#x") || ent.startsWith("#X") ->
                                ent.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                            ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                            else -> null
                        }
                        if (decoded != null) {
                            out.append(decoded)
                            i = semi + 1
                            continue
                        }
                    }
                }
                out.append(c)
                i++
            }
            return out.toString()
        }
    }
}
