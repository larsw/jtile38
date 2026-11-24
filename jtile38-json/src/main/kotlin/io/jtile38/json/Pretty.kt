// Port of github.com/tidwall/pretty v1.2.1 (MIT License, Copyright (c) 2017 Josh Baker)
package io.jtile38.json

/** JSON formatting (pretty.Pretty, pretty.PrettyOptions and pretty.Ugly). Colors are not ported. */
object Pretty {
    /** Formatting options (pretty.Options). */
    data class Options(
        /** Max column width for single line arrays. */
        val width: Int = 80,
        /** Prefix for all lines. */
        val prefix: String = "",
        /** Nested indentation. */
        val indent: String = "  ",
        /** Sort object keys alphabetically. */
        val sortKeys: Boolean = false,
    )

    val DEFAULT_OPTIONS = Options()

    /** Formats [json] with every element on its own line (pretty.Pretty). */
    fun pretty(json: String): String = pretty(json, DEFAULT_OPTIONS)

    /** Like [pretty] with custom options (pretty.PrettyOptions). */
    fun pretty(
        json: String,
        opts: Options,
    ): String {
        val buf = StringBuilder(json.length)
        if (opts.prefix.isNotEmpty()) buf.append(opts.prefix)
        Printer(json, opts.width, opts.prefix, opts.indent).appendAny(buf, 0, true, opts.sortKeys, 0, 0, -1)
        if (buf.isNotEmpty()) buf.append('\n')
        return buf.toString()
    }

    /** Removes insignificant whitespace from [json] (pretty.Ugly). */
    fun ugly(json: String): String {
        val dst = StringBuilder(json.length)
        var i = 0
        val n = json.length
        while (i < n) {
            val c = json[i]
            if (c > ' ') {
                dst.append(c)
                if (c == '"') {
                    i++
                    while (i < n) {
                        val d = json[i]
                        dst.append(d)
                        if (d == '"') {
                            var j = i - 1
                            while (json[j] == '\\') j--
                            if ((j - i) % 2 != 0) break
                        }
                        i++
                    }
                }
            }
            i++
        }
        return dst.toString()
    }

    /** Result of an append step: next input index, last newline position and ok flag. */
    private class Step(
        val i: Int,
        val nl: Int,
        val ok: Boolean,
    )

    private class Pair(
        var kstart: Int = 0,
        var kend: Int = 0,
        var vstart: Int = 0,
        var vend: Int = 0,
    )

    private class Printer(
        val json: String,
        val width: Int,
        val prefix: String,
        val indent: String,
    ) {
        fun isNaNOrInf(i: Int): Boolean {
            val c = json[i]
            return c == 'i' || c == 'I' || c == '+' || c == 'N' || (c == 'n' && i + 1 < json.length && json[i + 1] != 'u')
        }

        fun appendAny(
            buf: StringBuilder,
            start: Int,
            pretty: Boolean,
            sortkeys: Boolean,
            tabs: Int,
            nl: Int,
            max: Int,
        ): Step {
            var i = start
            while (i < json.length) {
                val c = json[i]
                if (c <= ' ') {
                    i++
                    continue
                }
                if (c == '"') return appendString(buf, i, nl)
                if (c in '0'..'9' || c == '-' || isNaNOrInf(i)) return appendNumber(buf, i, nl)
                if (c == '{') return appendObject(buf, i, '{', '}', pretty, sortkeys, tabs, nl, max)
                if (c == '[') return appendObject(buf, i, '[', ']', pretty, sortkeys, tabs, nl, max)
                when (c) {
                    't' -> {
                        buf.append("true")
                        return Step(i + 4, nl, true)
                    }

                    'f' -> {
                        buf.append("false")
                        return Step(i + 5, nl, true)
                    }

                    'n' -> {
                        buf.append("null")
                        return Step(i + 4, nl, true)
                    }
                }
                i++
            }
            return Step(i, nl, true)
        }

        fun appendObject(
            buf: StringBuilder,
            start: Int,
            open: Char,
            close: Char,
            pretty: Boolean,
            sortkeys0: Boolean,
            tabs: Int,
            nl0: Int,
            max: Int,
        ): Step {
            var i = start
            var nl = nl0
            var sortkeys = sortkeys0
            if (width > 0) {
                if (pretty && open == '[' && max == -1) {
                    // here we try to create a single line array
                    val m = width - (buf.length - nl)
                    if (m > 3) {
                        val s1 = buf.length
                        val s2 = i
                        val r = Printer(json, width, prefix, "").appendObject(buf, i, '[', ']', false, sortkeys, 0, 0, m)
                        if (r.ok && buf.length - s1 <= m) return Step(r.i, nl, true)
                        buf.setLength(s1)
                        i = s2
                    }
                } else if (max != -1 && open == '{') {
                    return Step(i, nl, false)
                }
            }
            buf.append(open)
            i++
            val pairs = if (open == '{' && sortkeys) ArrayList<Pair>(8) else null
            var n = 0
            while (i < json.length) {
                if (json[i] <= ' ') {
                    i++
                    continue
                }
                if (json[i] == close) {
                    if (pretty) {
                        if (open == '{' && sortkeys) sortPairs(buf, pairs!!)
                        if (n > 0) {
                            nl = buf.length
                            if (buf[nl - 1] == ' ') buf.setCharAt(nl - 1, '\n') else buf.append('\n')
                        }
                        if (buf[buf.length - 1] != open) appendTabs(buf, tabs)
                    }
                    buf.append(close)
                    return Step(i + 1, nl, open != '{')
                }
                if (open == '[' || json[i] == '"') {
                    if (n > 0) {
                        buf.append(',')
                        if (width != -1 && open == '[') buf.append(' ')
                    }
                    val p = Pair()
                    if (pretty) {
                        nl = buf.length
                        if (buf[nl - 1] == ' ') buf.setCharAt(nl - 1, '\n') else buf.append('\n')
                        if (open == '{' && sortkeys) {
                            p.kstart = i
                            p.vstart = buf.length
                        }
                        appendTabs(buf, tabs + 1)
                    }
                    if (open == '{') {
                        val r = appendString(buf, i, nl)
                        i = r.i
                        nl = r.nl
                        if (sortkeys) p.kend = i
                        buf.append(':')
                        if (pretty) buf.append(' ')
                    }
                    val r = appendAny(buf, i, pretty, sortkeys, tabs + 1, nl, max)
                    i = r.i
                    nl = r.nl
                    if (max != -1 && !r.ok) return Step(i, nl, false)
                    if (pretty && open == '{' && sortkeys) {
                        p.vend = buf.length
                        if (p.kstart > p.kend || p.vstart > p.vend) {
                            // bad data. disable sorting
                            sortkeys = false
                        } else {
                            pairs!!.add(p)
                        }
                    }
                    i--
                    n++
                }
                i++
            }
            return Step(i, nl, open != '{')
        }

        fun sortPairs(
            buf: StringBuilder,
            pairs: MutableList<Pair>,
        ) {
            if (pairs.isEmpty()) return
            val vstart = pairs[0].vstart
            val vend = pairs[pairs.size - 1].vend
            val snapshot = buf.toString()
            val original = pairs.toList()
            // stable sort, like sort.Stable
            val sorted =
                pairs.sortedWith { a, b ->
                    if (less(snapshot, a, b)) {
                        -1
                    } else if (less(snapshot, b, a)) {
                        1
                    } else {
                        0
                    }
                }
            if (sorted.indices.all { sorted[it] === original[it] }) return
            val nbuf = StringBuilder(vend - vstart)
            for ((idx, p) in sorted.withIndex()) {
                nbuf.append(snapshot, p.vstart, p.vend)
                if (idx < sorted.size - 1) nbuf.append(",\n")
            }
            buf.setLength(vstart)
            buf.append(nbuf)
        }

        fun less(
            buf: String,
            a: Pair,
            b: Pair,
        ): Boolean {
            if (isLess(buf, a, b, true)) return true
            if (isLess(buf, b, a, true)) return false
            return isLess(buf, a, b, false)
        }

        fun isLess(
            buf: String,
            a: Pair,
            b: Pair,
            byKey: Boolean,
        ): Boolean {
            val k1 = json.substring(a.kstart, a.kend)
            val k2 = json.substring(b.kstart, b.kend)
            var v1: String
            var v2: String
            if (byKey) {
                v1 = k1
                v2 = k2
            } else {
                v1 = buf.substring(a.vstart, a.vend).trim()
                v2 = buf.substring(b.vstart, b.vend).trim()
                if (v1.length >= k1.length + 1) v1 = v1.substring(k1.length + 1).trim()
                if (v2.length >= k2.length + 1) v2 = v2.substring(k2.length + 1).trim()
            }
            val t1 = jtype(v1)
            val t2 = jtype(v2)
            if (t1 < t2) return true
            if (t1 > t2) return false
            if (t1 == JSTRING) return parsestr(v1) < parsestr(v2)
            if (t1 == JNUMBER) {
                val n1 = Gjson.parseFloat(v1) ?: 0.0
                val n2 = Gjson.parseFloat(v2) ?: 0.0
                return n1 < n2
            }
            return v1 < v2
        }

        fun jtype(v: String): Int {
            if (v.isEmpty()) return JNULL
            return when (v[0]) {
                '"' -> JSTRING
                'f' -> JFALSE
                't' -> JTRUE
                'n' -> JNULL
                '[', '{' -> JJSON
                else -> JNUMBER
            }
        }

        fun parsestr(s: String): String {
            for (i in 1 until s.length) {
                if (s[i] == '\\') return Gjson.unescape(s.substring(1, s.length - 1))
                if (s[i] == '"') return s.substring(1, i)
            }
            return ""
        }

        fun appendString(
            buf: StringBuilder,
            s: Int,
            nl: Int,
        ): Step {
            var i = s + 1
            while (i < json.length) {
                if (json[i] == '"') {
                    var sc = 0
                    var j = i - 1
                    while (j > s) {
                        if (json[j] == '\\') sc++ else break
                        j--
                    }
                    if (sc % 2 == 1) {
                        i++
                        continue
                    }
                    i++
                    break
                }
                i++
            }
            buf.append(json, s, minOf(i, json.length))
            return Step(i, nl, true)
        }

        fun appendNumber(
            buf: StringBuilder,
            s: Int,
            nl: Int,
        ): Step {
            var i = s + 1
            while (i < json.length) {
                val c = json[i]
                if (c <= ' ' || c == ',' || c == ':' || c == ']' || c == '}') break
                i++
            }
            buf.append(json, s, i)
            return Step(i, nl, true)
        }

        fun appendTabs(
            buf: StringBuilder,
            tabs: Int,
        ) {
            if (prefix.isNotEmpty()) buf.append(prefix)
            repeat(tabs) { buf.append(indent) }
        }
    }

    private const val JNULL = 0
    private const val JFALSE = 1
    private const val JNUMBER = 2
    private const val JSTRING = 3
    private const val JTRUE = 4
    private const val JJSON = 5
}
