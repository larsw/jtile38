// Port of parts of github.com/tidwall/gjson (MIT License, Copyright (c) 2016 Josh Baker)
package io.jtile38.json

/**
 * Fast JSON value access without building a tree, following github.com/tidwall/gjson.
 *
 * Supported path syntax for [get]: dot separated keys (`name.last`), `\` escapes for
 * `.`, `*`, `?` and `|`, `*`/`?` wildcards in object keys, numeric array indexes
 * (`children.1`), `#` for the array length (`children.#`), `#` followed by a sub path to
 * map over array elements (`friends.#.first`), and `|` as a component separator.
 * Queries (`#(...)`), modifiers (`@reverse`) and multipaths are not supported yet.
 */
object Gjson {
    /** Returns true if [json] is a valid JSON document (gjson.Valid). */
    fun valid(json: String): Boolean = Validator(json).validPayload()

    /** Parses the first JSON value in [json] without validating it (gjson.Parse). */
    fun parse(json: String): JsonResult {
        var i = 0
        while (i < json.length) {
            val c = json[i]
            if (c == '{' || c == '[') {
                return JsonResult(JsonType.JSON, json.substring(i), index = i)
            }
            if (c <= ' ') {
                i++
                continue
            }
            val rest = json.substring(i)
            return when (c) {
                '+', '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'i', 'I', 'N' -> {
                    val raw = toNum(rest)
                    JsonResult(JsonType.NUMBER, raw, "", parseFloat(raw) ?: 0.0, i)
                }

                'n' -> {
                    if (i + 1 < json.length && json[i + 1] != 'u') {
                        val raw = toNum(rest)
                        JsonResult(JsonType.NUMBER, raw, "", parseFloat(raw) ?: 0.0, i)
                    } else {
                        JsonResult(JsonType.NULL, toLit(rest), index = i)
                    }
                }

                't' -> {
                    JsonResult(JsonType.TRUE, toLit(rest), index = i)
                }

                'f' -> {
                    JsonResult(JsonType.FALSE, toLit(rest), index = i)
                }

                '"' -> {
                    val (raw, str) = toStr(rest)
                    JsonResult(JsonType.STRING, raw, str, 0.0, i)
                }

                else -> {
                    JsonResult()
                }
            }
        }
        return JsonResult()
    }

    /** Searches [json] for the specified [path] (gjson.Get, subset). */
    fun get(
        json: String,
        path: String,
    ): JsonResult {
        val root = parse(json)
        if (!root.exists()) return JsonResult()
        val comps = splitPath(path)
        val r = getComponents(root, comps, 0)
        return r
    }

    private class PathComp(
        val key: String,
        val wild: Boolean,
    )

    private fun splitPath(path: String): List<PathComp> {
        val out = ArrayList<PathComp>()
        val sb = StringBuilder()
        var wild = false
        var i = 0
        while (i < path.length) {
            val c = path[i]
            when {
                c == '\\' && i + 1 < path.length -> {
                    i++
                    val e = path[i]
                    if (e == '*' || e == '?') {
                        // escaped wildcard: keep the escape so wildcard matching treats it literally
                        sb.append('\\').append(e)
                    } else {
                        sb.append(e)
                    }
                }

                c == '.' || c == '|' -> {
                    out.add(PathComp(sb.toString(), wild))
                    sb.setLength(0)
                    wild = false
                }

                else -> {
                    if (c == '*' || c == '?') wild = true
                    sb.append(c)
                }
            }
            i++
        }
        out.add(PathComp(sb.toString(), wild))
        return out
    }

    private fun literal(comp: PathComp): String =
        if (!comp.key.contains('\\')) {
            comp.key
        } else {
            buildString {
                var i = 0
                val k = comp.key
                while (i < k.length) {
                    if (k[i] == '\\' && i + 1 < k.length) i++
                    append(k[i])
                    i++
                }
            }
        }

    private fun getComponents(
        cur: JsonResult,
        comps: List<PathComp>,
        ci: Int,
    ): JsonResult {
        if (ci == comps.size) return cur
        val comp = comps[ci]
        if (cur.isObject()) {
            var found: JsonResult? = null
            val lit = literal(comp)
            cur.forEach { k, v ->
                val hit = if (comp.wild) Match.match(k.str, comp.key) else k.str == lit
                if (hit) {
                    found = v
                    false
                } else {
                    true
                }
            }
            val f = found ?: return JsonResult()
            return getComponents(f, comps, ci + 1)
        }
        if (cur.isArray()) {
            if (comp.key == "#" && !comp.wild) {
                val elems = cur.array()
                if (ci == comps.size - 1) {
                    return JsonResult(JsonType.NUMBER, elems.size.toString(), "", elems.size.toDouble())
                }
                val sb = StringBuilder("[")
                var n = 0
                for (e in elems) {
                    val r = getComponents(e, comps, ci + 1)
                    if (r.exists()) {
                        if (n > 0) sb.append(',')
                        sb.append(if (r.raw.isEmpty()) r.string() else r.raw)
                        n++
                    }
                }
                sb.append(']')
                return JsonResult(JsonType.JSON, sb.toString())
            }
            val idx = comp.key.toIntOrNull() ?: return JsonResult()
            if (idx < 0) return JsonResult()
            var found: JsonResult? = null
            var n = 0
            cur.forEach { _, v ->
                if (n == idx) {
                    found = v
                    false
                } else {
                    n++
                    true
                }
            }
            val f = found ?: return JsonResult()
            return getComponents(f, comps, ci + 1)
        }
        return JsonResult()
    }

    // ---- low level scanning (shared with JsonResult) ----

    internal class StrScan(
        val end: Int,
        val escaped: Boolean,
    )

    /**
     * Scans a string whose opening quote is at i-1. Returns the index just past the closing
     * quote, or null if the string is not terminated.
     */
    internal fun parseString(
        json: String,
        start: Int,
    ): StrScan? {
        var i = start
        while (i < json.length) {
            val c = json[i]
            if (c > '\\') {
                i++
                continue
            }
            if (c == '"') return StrScan(i + 1, false)
            if (c == '\\') {
                i++
                while (i < json.length) {
                    val d = json[i]
                    if (d > '\\') {
                        i++
                        continue
                    }
                    if (d == '"') {
                        if (json[i - 1] == '\\') {
                            var n = 0
                            var j = i - 2
                            while (j > 0) {
                                if (json[j] != '\\') break
                                n++
                                j--
                            }
                            if (n % 2 == 0) {
                                i++
                                continue
                            }
                        }
                        return StrScan(i + 1, true)
                    }
                    i++
                }
                return null
            }
            i++
        }
        return null
    }

    /** Returns the end index of the object/array starting at [start] (which holds '{' or '['). */
    internal fun squashEnd(
        json: String,
        start: Int,
    ): Int {
        var i = start + 1
        var depth = 1
        while (i < json.length) {
            when (json[i]) {
                '"' -> {
                    i++
                    while (i < json.length) {
                        val c = json[i]
                        if (c == '\\') {
                            i += 2
                            continue
                        }
                        if (c == '"') break
                        i++
                    }
                }

                '{', '[' -> {
                    depth++
                }

                '}', ']' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
            i++
        }
        return json.length
    }

    /** gjson parseAny with hit=true. Returns (nextIndex, value) or null when not ok. */
    internal fun parseAny(
        json: String,
        start: Int,
    ): Pair<Int, JsonResult>? {
        var i = start
        while (i < json.length) {
            val c = json[i]
            if (c == '{' || c == '[') {
                val end = squashEnd(json, i)
                return end to JsonResult(JsonType.JSON, json.substring(i, end))
            }
            if (c <= ' ') {
                i++
                continue
            }
            var num = false
            when (c) {
                '"' -> {
                    val s = i
                    val ps = parseString(json, i + 1) ?: return null
                    val raw = json.substring(s, ps.end)
                    val inner = raw.substring(1, raw.length - 1)
                    val str = if (ps.escaped) unescape(inner) else inner
                    return ps.end to JsonResult(JsonType.STRING, raw, str)
                }

                'n' -> {
                    if (i + 1 < json.length && json[i + 1] != 'u') {
                        num = true
                    } else {
                        val e = litEnd(json, i)
                        return e to JsonResult(JsonType.NULL, json.substring(i, e))
                    }
                }

                't', 'f' -> {
                    val e = litEnd(json, i)
                    val t = if (c == 't') JsonType.TRUE else JsonType.FALSE
                    return e to JsonResult(t, json.substring(i, e))
                }

                '+', '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'i', 'I', 'N' -> {
                    num = true
                }
            }
            if (num) {
                var e = i + 1
                while (e < json.length) {
                    val d = json[e]
                    if (d <= ' ' || d == ',' || d == ']' || d == '}') break
                    e++
                }
                val raw = json.substring(i, e)
                return e to JsonResult(JsonType.NUMBER, raw, "", parseFloat(raw) ?: 0.0)
            }
            // gjson keeps scanning on unknown characters
            i++
        }
        return null
    }

    private fun litEnd(
        json: String,
        start: Int,
    ): Int {
        var i = start + 1
        while (i < json.length) {
            if (json[i] < 'a' || json[i] > 'z') return i
            i++
        }
        return i
    }

    private fun toNum(json: String): String {
        for (i in 1 until json.length) {
            val c = json[i]
            if (c <= '-') {
                if (c <= ' ' || c == ',') return json.substring(0, i)
            } else if (c == ']' || c == '}') {
                return json.substring(0, i)
            }
        }
        return json
    }

    private fun toLit(json: String): String {
        for (i in 1 until json.length) {
            if (json[i] < 'a' || json[i] > 'z') return json.substring(0, i)
        }
        return json
    }

    private fun toStr(json: String): Pair<String, String> {
        var i = 1
        while (i < json.length) {
            val c = json[i]
            if (c > '\\') {
                i++
                continue
            }
            if (c == '"') return json.substring(0, i + 1) to json.substring(1, i)
            if (c == '\\') {
                i++
                while (i < json.length) {
                    val d = json[i]
                    if (d > '\\') {
                        i++
                        continue
                    }
                    if (d == '"') {
                        if (json[i - 1] == '\\') {
                            var n = 0
                            var j = i - 2
                            while (j > 0) {
                                if (json[j] != '\\') break
                                n++
                                j--
                            }
                            if (n % 2 == 0) {
                                i++
                                continue
                            }
                        }
                        return json.substring(0, i + 1) to unescape(json.substring(1, i))
                    }
                    i++
                }
                val raw = if (i + 1 < json.length) json.substring(0, i + 1) else json.substring(0, i)
                return raw to unescape(json.substring(1, minOf(i, json.length)))
            }
            i++
        }
        return json to json.substring(1)
    }

    /** Unescapes the inside of a JSON string (gjson unescape). */
    fun unescape(json: String): String {
        val sb = StringBuilder(json.length)
        var i = 0
        while (i < json.length) {
            val c = json[i]
            when {
                c < ' ' -> {
                    return sb.toString()
                }

                c == '\\' -> {
                    i++
                    if (i >= json.length) return sb.toString()
                    when (json[i]) {
                        '\\' -> {
                            sb.append('\\')
                        }

                        '/' -> {
                            sb.append('/')
                        }

                        'b' -> {
                            sb.append('\b')
                        }

                        'f' -> {
                            sb.append('\u000c')
                        }

                        'n' -> {
                            sb.append('\n')
                        }

                        'r' -> {
                            sb.append('\r')
                        }

                        't' -> {
                            sb.append('\t')
                        }

                        '"' -> {
                            sb.append('"')
                        }

                        'u' -> {
                            if (i + 5 > json.length) return sb.toString()
                            var r = hex4(json, i + 1)
                            i += 5
                            if (r in 0xD800..0xDFFF) {
                                if (json.length - i >= 6 && json[i] == '\\' && json[i + 1] == 'u') {
                                    val r2 = hex4(json, i + 2)
                                    r =
                                        if (r in 0xD800..0xDBFF && r2 in 0xDC00..0xDFFF) {
                                            0x10000 + ((r - 0xD800) shl 10) + (r2 - 0xDC00)
                                        } else {
                                            0xFFFD
                                        }
                                    i += 6
                                } else {
                                    r = 0xFFFD
                                }
                            }
                            sb.appendCodePoint(r)
                            i-- // backtrack index by one
                        }

                        else -> {
                            return sb.toString()
                        }
                    }
                }

                else -> {
                    sb.append(c)
                }
            }
            i++
        }
        return sb.toString()
    }

    private fun hex4(
        s: String,
        at: Int,
    ): Int {
        var n = 0
        for (k in 0 until 4) {
            val d = Character.digit(s[at + k], 16)
            if (d < 0) return 0
            n = n * 16 + d
        }
        return n
    }

    /**
     * Parses a float the way Go's strconv.ParseFloat does for the inputs gjson sees.
     * Returns null when the text is not a number. Out of range values become +/-Infinity.
     */
    fun parseFloat(s: String): Double? {
        if (s.isEmpty()) return null
        var body = s
        var neg = false
        if (body[0] == '+' || body[0] == '-') {
            neg = body[0] == '-'
            body = body.substring(1)
        }
        when (body.lowercase()) {
            "inf", "infinity" -> return if (neg) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
            "nan" -> return if (s[0] == '+' || s[0] == '-') null else Double.NaN
        }
        if (body.isEmpty()) return null
        for (c in body) {
            if (!(c in '0'..'9' || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-' || c == '_')) {
                return null
            }
        }
        if (body.contains('_')) return null
        return try {
            val v = body.toDouble()
            if (neg) -v else v
        } catch (_: NumberFormatException) {
            null
        }
    }

    // ---- validation (port of gjson valid*) ----

    private class Validator(
        val data: String,
    ) {
        fun validPayload(): Boolean {
            var i = 0
            while (i < data.length) {
                when (data[i]) {
                    ' ', '\t', '\n', '\r' -> {
                        i++
                    }

                    else -> {
                        i = validAny(i)
                        if (i < 0) return false
                        while (i < data.length) {
                            when (data[i]) {
                                ' ', '\t', '\n', '\r' -> i++
                                else -> return false
                            }
                        }
                        return true
                    }
                }
            }
            return false
        }

        // each returns the next index, or -1 when invalid
        fun validAny(start: Int): Int {
            var i = start
            while (i < data.length) {
                when (data[i]) {
                    ' ', '\t', '\n', '\r' -> i++
                    '{' -> return validObject(i + 1)
                    '[' -> return validArray(i + 1)
                    '"' -> return validString(i + 1)
                    '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> return validNumber(i + 1)
                    't' -> return validLit(i + 1, "rue")
                    'f' -> return validLit(i + 1, "alse")
                    'n' -> return validLit(i + 1, "ull")
                    else -> return -1
                }
            }
            return -1
        }

        fun validObject(start: Int): Int {
            var i = start
            while (i < data.length) {
                when (data[i]) {
                    ' ', '\t', '\n', '\r' -> {
                        i++
                    }

                    '}' -> {
                        return i + 1
                    }

                    '"' -> {
                        while (true) {
                            i = validString(i + 1)
                            if (i < 0) return -1
                            i = validColon(i)
                            if (i < 0) return -1
                            i = validAny(i)
                            if (i < 0) return -1
                            i = validComma(i, '}')
                            if (i < 0) return -1
                            if (data[i] == '}') return i + 1
                            i++
                            var foundKey = false
                            while (i < data.length) {
                                when (data[i]) {
                                    ' ', '\t', '\n', '\r' -> {
                                        i++
                                    }

                                    '"' -> {
                                        foundKey = true
                                        break
                                    }

                                    else -> {
                                        return -1
                                    }
                                }
                                if (foundKey) break
                            }
                            if (!foundKey) return -1
                        }
                    }

                    else -> {
                        return -1
                    }
                }
            }
            return -1
        }

        fun validColon(start: Int): Int {
            var i = start
            while (i < data.length) {
                when (data[i]) {
                    ' ', '\t', '\n', '\r' -> i++
                    ':' -> return i + 1
                    else -> return -1
                }
            }
            return -1
        }

        fun validComma(
            start: Int,
            end: Char,
        ): Int {
            var i = start
            while (i < data.length) {
                val c = data[i]
                when {
                    c == ' ' || c == '\t' || c == '\n' || c == '\r' -> i++
                    c == ',' || c == end -> return i
                    else -> return -1
                }
            }
            return -1
        }

        fun validArray(start: Int): Int {
            var i = start
            while (i < data.length) {
                when (data[i]) {
                    ' ', '\t', '\n', '\r' -> {
                        i++
                    }

                    ']' -> {
                        return i + 1
                    }

                    else -> {
                        while (i < data.length) {
                            i = validAny(i)
                            if (i < 0) return -1
                            i = validComma(i, ']')
                            if (i < 0) return -1
                            if (data[i] == ']') return i + 1
                            i++
                        }
                        return -1
                    }
                }
            }
            return -1
        }

        fun validString(start: Int): Int {
            var i = start
            while (i < data.length) {
                val c = data[i]
                if (c < ' ') {
                    return -1
                } else if (c == '\\') {
                    i++
                    if (i == data.length) return -1
                    when (data[i]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> {}

                        'u' -> {
                            for (j in 0 until 4) {
                                i++
                                if (i >= data.length) return -1
                                val h = data[i]
                                if (!((h in '0'..'9') || (h in 'a'..'f') || (h in 'A'..'F'))) return -1
                            }
                        }

                        else -> {
                            return -1
                        }
                    }
                } else if (c == '"') {
                    return i + 1
                }
                i++
            }
            return -1
        }

        fun validNumber(start: Int): Int {
            var i = start - 1
            if (data[i] == '-') {
                i++
                if (i == data.length) return -1
                if (data[i] < '0' || data[i] > '9') return -1
            }
            if (i == data.length) return -1
            if (data[i] == '0') {
                i++
            } else {
                while (i < data.length && data[i] in '0'..'9') i++
            }
            if (i == data.length) return i
            if (data[i] == '.') {
                i++
                if (i == data.length) return -1
                if (data[i] < '0' || data[i] > '9') return -1
                i++
                while (i < data.length && data[i] in '0'..'9') i++
            }
            if (i == data.length) return i
            if (data[i] == 'e' || data[i] == 'E') {
                i++
                if (i == data.length) return -1
                if (data[i] == '+' || data[i] == '-') i++
                if (i == data.length) return -1
                if (data[i] < '0' || data[i] > '9') return -1
                i++
                while (i < data.length && data[i] in '0'..'9') i++
            }
            return i
        }

        fun validLit(
            start: Int,
            rest: String,
        ): Int {
            if (data.startsWith(rest, start)) return start + rest.length
            return -1
        }
    }
}
