// Port of github.com/tidwall/gjson v1.18.0 (MIT License, Copyright (c) 2016 Josh Baker)
package io.jtile38.json

/**
 * Fast JSON value access without building a tree, a port of github.com/tidwall/gjson.
 *
 * The full path syntax is supported: dot separated keys with `\` escapes, `*`/`?` wildcards,
 * array indexes, `#` for the array length and `#.path` to map over elements, queries
 * (`friends.#(last=="Murphy").first`, `#(...)#` for all matches, `%`/`!%` pattern operators and
 * `~` truthiness), the `|` pipe, multipaths (`[a,b]`, `{a,b:c}`), literals (`!true`), JSON lines
 * (`..`) and the built-in modifiers (`@reverse`, `@ugly`, `@pretty`, `@this`, `@valid`,
 * `@flatten`, `@join`, `@keys`, `@values`, `@tostr`, `@fromstr`, `@group`, `@dig`).
 *
 * Strings are byte strings (ISO-8859-1, one char per byte) like Go strings: unescaped `\u`
 * sequences become UTF-8 bytes.
 *
 * Differences from Go: [JsonResult.index] is tracked explicitly instead of by pointer
 * arithmetic, so a result that Go would locate through a pipe or modifier inside a query gets
 * index 0; wildcard matching has no complexity limit because [Match] does not backtrack
 * exponentially; the `Path`/`Paths` result methods and custom modifiers are not ported.
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

    /** Searches [json] for the specified [path] (gjson.Get). */
    fun get(
        json: String,
        path0: String,
    ): JsonResult {
        var path = path0
        if (path.length > 1) {
            if (path[0] == '@' || path[0] == '!') {
                // possible modifier
                val r = if (path[0] == '@') execModifier(json, path) else execStatic(json, path)
                if (r != null) {
                    path = r.first
                    if (path.isNotEmpty() && (path[0] == '|' || path[0] == '.')) {
                        return get(r.second, path.substring(1)).with(0, null)
                    }
                    return parse(r.second)
                }
            }
            if (path[0] == '[' || path[0] == '{') {
                // using a subselector path
                val kind = path[0]
                val subs = parseSubSelectors(path)
                if (subs != null) {
                    path = subs.second
                    if (path.isEmpty() || path[0] == '|' || path[0] == '.') {
                        val b = StringBuilder()
                        b.append(kind)
                        var i = 0
                        for (sub in subs.first) {
                            val res = get(json, sub.path)
                            if (res.exists()) {
                                if (i > 0) b.append(',')
                                if (kind == '{') {
                                    if (sub.name.isNotEmpty()) {
                                        if (sub.name[0] == '"' && valid(sub.name)) {
                                            b.append(sub.name)
                                        } else {
                                            JsonString.append(b, sub.name)
                                        }
                                    } else {
                                        val last = nameOfLast(sub.path)
                                        JsonString.append(b, if (isSimpleName(last)) last else "_")
                                    }
                                    b.append(':')
                                }
                                val raw =
                                    if (res.raw.isEmpty()) {
                                        res.string().ifEmpty { "null" }
                                    } else {
                                        res.raw
                                    }
                                b.append(raw)
                                i++
                            }
                        }
                        b.append(kind + 2)
                        var res = JsonResult(JsonType.JSON, b.toString())
                        if (path.isNotEmpty()) res = res.get(path.substring(1))
                        return res.with(0, res.indexes)
                    }
                }
            }
        }
        val c = Ctx(json)
        if (path.length >= 2 && path[0] == '.' && path[1] == '.') {
            c.lines = true
            parseArray(c, 0, path.substring(2))
        } else {
            for (i in json.indices) {
                if (json[i] == '{') {
                    parseObject(c, i + 1, path)
                    break
                }
                if (json[i] == '[') {
                    parseArray(c, i + 1, path)
                    break
                }
            }
        }
        if (c.piped) {
            val res = c.value().get(c.pipe)
            return res.with(0, res.indexes)
        }
        c.fillIndex()
        return c.value()
    }

    /** Searches [json] for each of the paths (gjson.GetMany). */
    fun getMany(
        json: String,
        vararg paths: String,
    ): List<JsonResult> = paths.map { get(json, it) }

    /** Iterates through JSON lines (gjson.ForEachLine). */
    fun forEachLine(
        json: String,
        iterator: (JsonResult) -> Boolean,
    ) {
        var i = 0
        while (true) {
            val (ni, res) = parseAny(json, i) ?: (json.length to JsonResult())
            i = ni
            if (!res.exists()) break
            if (!iterator(res)) return
        }
    }

    /** Returns an escaped path component (gjson.Escape). */
    fun escape(comp: String): String {
        for (i in comp.indices) {
            if (!isSafePathKeyChar(comp[i])) {
                val sb = StringBuilder(comp.length + 1)
                sb.append(comp, 0, i)
                for (j in i until comp.length) {
                    if (!isSafePathKeyChar(comp[j])) sb.append('\\')
                    sb.append(comp[j])
                }
                return sb.toString()
            }
        }
        return comp
    }

    private fun isSafePathKeyChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c <= ' ' || c > '~' || c == '_' || c == '-' || c == ':'

    // ---- parse context ----

    /** Mutable result being built during a search (gjson parseContext). */
    private class Ctx(
        val json: String,
    ) {
        var type = JsonType.NULL
        var raw = ""
        var str = ""
        var num = 0.0
        var index = 0
        var indexes: IntArray? = null

        /** Position of [raw] in [json] when it is a slice of it, -1 otherwise (replaces Go's pointer math). */
        var rawPos = -1
        var pipe = ""
        var piped = false
        var calcd = false
        var lines = false

        fun exists(): Boolean = type != JsonType.NULL || raw.isNotEmpty()

        fun setSlice(
            t: JsonType,
            start: Int,
            end: Int,
            s: String = "",
            n: Double = 0.0,
        ) {
            type = t
            raw = json.substring(start, minOf(end, json.length))
            str = s
            num = n
            rawPos = start
        }

        fun setResult(
            r: JsonResult,
            pos: Int,
        ) {
            type = r.type
            raw = r.raw
            str = r.str
            num = r.num
            index = r.index
            indexes = r.indexes
            rawPos = pos
        }

        fun fillIndex() {
            if (raw.isNotEmpty() && !calcd) {
                index = if (rawPos >= 0 && rawPos < json.length) rawPos else 0
            }
        }

        fun value(): JsonResult = JsonResult(type, raw, str, num, index, indexes)
    }

    // packed (index, hit) return values
    private fun ret(
        i: Int,
        hit: Boolean,
    ): Long = (i.toLong() shl 1) or (if (hit) 1L else 0L)

    private fun idxOf(r: Long): Int = (r shr 1).toInt()

    private fun hitOf(r: Long): Boolean = (r and 1L) == 1L

    // ---- low level scanning ----

    private fun squash(json: String): String {
        // expects that the lead character is a '[' or '{' or '(' or '"'
        var i = 0
        var depth = 0
        if (json[0] != '"') {
            i = 1
            depth = 1
        }
        while (i < json.length) {
            val c = json[i]
            if (c >= '"' && c <= '}') {
                when (c) {
                    '"' -> {
                        i++
                        val s2 = i
                        while (i < json.length) {
                            if (json[i] > '\\') {
                                i++
                                continue
                            }
                            if (json[i] == '"') {
                                // look for an escaped slash
                                if (json[i - 1] == '\\') {
                                    var n = 0
                                    var j = i - 2
                                    while (j > s2 - 1) {
                                        if (json[j] != '\\') break
                                        n++
                                        j--
                                    }
                                    if (n % 2 == 0) {
                                        i++
                                        continue
                                    }
                                }
                                break
                            }
                            i++
                        }
                        if (depth == 0) {
                            if (i >= json.length) return json
                            return json.substring(0, i + 1)
                        }
                    }

                    '{', '[', '(' -> {
                        depth++
                    }

                    '}', ']', ')' -> {
                        depth--
                        if (depth == 0) return json.substring(0, i + 1)
                    }
                }
            }
            i++
        }
        return json
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

    internal fun toStr(json: String): kotlin.Pair<String, String> {
        // expects that the lead character is a '"'
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
                        // look for an escaped slash
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

    internal class StrScan(
        /** Index just past the closing quote (or the end of the input when not terminated). */
        val end: Int,
        val escaped: Boolean,
        val ok: Boolean,
    )

    /**
     * Scans a string whose opening quote is at [start] - 1 (gjson parseString). The raw string
     * is `json.substring(start - 1, end)`.
     */
    internal fun scanString(
        json: String,
        start: Int,
    ): StrScan {
        var i = start
        while (i < json.length) {
            val c = json[i]
            if (c > '\\') {
                i++
                continue
            }
            if (c == '"') return StrScan(i + 1, false, true)
            if (c == '\\') {
                i++
                while (i < json.length) {
                    val d = json[i]
                    if (d > '\\') {
                        i++
                        continue
                    }
                    if (d == '"') {
                        // look for an escaped slash
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
                        return StrScan(i + 1, true, true)
                    }
                    i++
                }
                break
            }
            i++
        }
        return StrScan(i, false, false)
    }

    /** Old helper kept for callers: returns null when the string is not terminated. */
    internal fun parseString(
        json: String,
        start: Int,
    ): StrScan? {
        val s = scanString(json, start)
        return if (s.ok) s else null
    }

    private fun parseNumberEnd(
        json: String,
        start: Int,
    ): Int {
        var i = start + 1
        while (i < json.length) {
            val c = json[i]
            if (c <= ' ' || c == ',' || c == ']' || c == '}') return i
            i++
        }
        return i
    }

    private fun parseLiteralEnd(
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

    /**
     * Returns the end of the object, array or parenthesized group whose opening character is
     * at [start] (gjson parseSquash).
     */
    internal fun parseSquash(
        json: String,
        start: Int,
    ): Int {
        var i = start + 1
        var depth = 1
        while (i < json.length) {
            val c = json[i]
            if (c == '"') {
                i++
                val s2 = i
                while (i < json.length) {
                    if (json[i] > '\\' || json[i] != '"') {
                        i++
                        continue
                    }
                    // look for an escaped slash
                    if (json[i - 1] == '\\') {
                        var n = 0
                        var j = i - 2
                        while (j > s2 - 1) {
                            if (json[j] != '\\') break
                            n++
                            j--
                        }
                        if (n % 2 == 0) {
                            i++
                            continue
                        }
                    }
                    break
                }
            } else if (c == '{' || c == '[' || c == '(') {
                depth++
            } else if (c == '}' || c == ']' || c == ')') {
                depth--
                if (depth == 0) return i + 1
            }
            i++
        }
        return i
    }

    /** Kept for callers of the previous API. */
    internal fun squashEnd(
        json: String,
        start: Int,
    ): Int = parseSquash(json, start)

    /**
     * gjson parseAny with hit=true. Returns (nextIndex, value) or null when not ok. Objects and
     * arrays get their position in [json] as index, other values index 0.
     */
    internal fun parseAny(
        json: String,
        start: Int,
    ): kotlin.Pair<Int, JsonResult>? {
        var i = start
        while (i < json.length) {
            val c = json[i]
            if (c == '{' || c == '[') {
                val end = parseSquash(json, i)
                return end to JsonResult(JsonType.JSON, json.substring(i, minOf(end, json.length)), index = i)
            }
            if (c <= ' ') {
                i++
                continue
            }
            var num = false
            when (c) {
                '"' -> {
                    val s = i
                    val ps = scanString(json, i + 1)
                    if (!ps.ok) return null
                    val raw = json.substring(s, ps.end)
                    val inner = raw.substring(1, raw.length - 1)
                    val str = if (ps.escaped) unescape(inner) else inner
                    return ps.end to JsonResult(JsonType.STRING, raw, str)
                }

                'n' -> {
                    if (i + 1 < json.length && json[i + 1] != 'u') {
                        num = true
                    } else {
                        val e = parseLiteralEnd(json, i)
                        return e to JsonResult(JsonType.NULL, json.substring(i, e))
                    }
                }

                't', 'f' -> {
                    val e = parseLiteralEnd(json, i)
                    val t = if (c == 't') JsonType.TRUE else JsonType.FALSE
                    return e to JsonResult(t, json.substring(i, e))
                }

                '+', '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'i', 'I', 'N' -> {
                    num = true
                }
            }
            if (num) {
                val e = parseNumberEnd(json, i)
                val raw = json.substring(i, e)
                return e to JsonResult(JsonType.NUMBER, raw, "", parseFloat(raw) ?: 0.0)
            }
            // gjson keeps scanning on unknown characters
            i++
        }
        return null
    }

    // ---- path parsing ----

    private class ArrayPath {
        var part = ""
        var path = ""
        var pipe = ""
        var piped = false
        var more = false
        var alogok = false
        var arrch = false
        var alogkey = ""
        var queryOn = false
        var queryAll = false
        var queryPath = ""
        var queryOp = ""
        var queryValue = ""
    }

    private fun parseArrayPath(path: String): ArrayPath {
        val r = ArrayPath()
        var i = 0
        while (i < path.length) {
            if (path[i] == '|') {
                r.part = path.substring(0, i)
                r.pipe = path.substring(i + 1)
                r.piped = true
                return r
            }
            if (path[i] == '.') {
                r.part = path.substring(0, i)
                if (!r.arrch && i < path.length - 1 && isDotPiperChar(path, i + 1)) {
                    r.pipe = path.substring(i + 1)
                    r.piped = true
                } else {
                    r.path = path.substring(i + 1)
                    r.more = true
                }
                return r
            }
            if (path[i] == '#') {
                r.arrch = true
                if (i == 0 && path.length > 1) {
                    if (path[1] == '.') {
                        r.alogok = true
                        r.alogkey = path.substring(2)
                        r.path = path.substring(0, 1)
                    } else if (path[1] == '[' || path[1] == '(') {
                        // query
                        r.queryOn = true
                        val q = parseQuery(path.substring(i))
                        if (q == null) {
                            // bad query, end now
                            break
                        }
                        var value = q.value
                        if (value.length >= 2 && value[0] == '"' && value[value.length - 1] == '"') {
                            value = value.substring(1, value.length - 1)
                            if (q.vesc) value = unescape(value)
                        }
                        r.queryPath = q.path
                        r.queryOp = q.op
                        r.queryValue = value
                        i = q.i - 1
                        if (i + 1 < path.length && path[i + 1] == '#') r.queryAll = true
                    }
                }
                i++
                continue
            }
            i++
        }
        r.part = path
        r.path = ""
        return r
    }

    private class Query(
        val path: String,
        val op: String,
        val value: String,
        val remain: String,
        val i: Int,
        val vesc: Boolean,
    )

    /** Splits a `#(path op value)` query (gjson parseQuery). */
    private fun parseQuery(query: String): Query? {
        if (query.length < 2 || query[0] != '#' || (query[1] != '(' && query[1] != '[')) return null
        var i = 2
        var j = 0 // start of value part
        var depth = 1
        var vesc = false
        while (i < query.length) {
            if (depth == 1 && j == 0) {
                when (query[i]) {
                    '!', '=', '<', '>', '%' -> {
                        // start of the value part
                        j = i
                        i++
                        continue
                    }
                }
            }
            val c = query[i]
            if (c == '\\') {
                i++
            } else if (c == '[' || c == '(') {
                depth++
            } else if (c == ']' || c == ')') {
                depth--
                if (depth == 0) break
            } else if (c == '"') {
                // inside selector string, balance quotes
                i++
                while (i < query.length) {
                    if (query[i] == '\\') {
                        vesc = true
                        i++
                    } else if (query[i] == '"') {
                        break
                    }
                    i++
                }
            }
            i++
        }
        if (depth > 0) return null
        val path: String
        var op = ""
        var value = ""
        val remain: String
        if (j > 0) {
            path = trim(query.substring(2, j))
            value = trim(query.substring(j, i))
            remain = query.substring(minOf(i + 1, query.length))
            // parse the compare op from the value
            var opsz = 0
            when {
                value.length == 1 -> {
                    opsz = 1
                }

                value[0] == '!' && value[1] == '=' -> {
                    opsz = 2
                }

                value[0] == '!' && value[1] == '%' -> {
                    opsz = 2
                }

                value[0] == '<' && value[1] == '=' -> {
                    opsz = 2
                }

                value[0] == '>' && value[1] == '=' -> {
                    opsz = 2
                }

                value[0] == '=' && value[1] == '=' -> {
                    value = value.substring(1)
                    opsz = 1
                }

                value[0] == '<' -> {
                    opsz = 1
                }

                value[0] == '>' -> {
                    opsz = 1
                }

                value[0] == '=' -> {
                    opsz = 1
                }

                value[0] == '%' -> {
                    opsz = 1
                }
            }
            op = value.substring(0, opsz)
            value = trim(value.substring(opsz))
        } else {
            path = trim(query.substring(2, i))
            remain = query.substring(minOf(i + 1, query.length))
        }
        return Query(path, op, value, remain, i + 1, vesc)
    }

    private fun trim(s: String): String {
        var a = 0
        var b = s.length
        while (a < b && s[a] <= ' ') a++
        while (b > a && s[b - 1] <= ' ') b--
        return s.substring(a, b)
    }

    /** Peeks at s[at] and reports whether it starts a modifier, multipath or literal component. */
    private fun isDotPiperChar(
        s: String,
        at: Int,
    ): Boolean {
        val c = s[at]
        if (c == '@') {
            // check that the next component is *not* a modifier.
            var i = at + 1
            while (i < s.length) {
                if (s[i] == '.' || s[i] == '|' || s[i] == ':') break
                i++
            }
            return modifiers.containsKey(s.substring(at + 1, i))
        }
        return c == '[' || c == '{'
    }

    private class ObjectPath {
        var part = ""
        var path = ""
        var pipe = ""
        var piped = false
        var wild = false
        var more = false
    }

    private fun parseObjectPath(path: String): ObjectPath {
        val r = ObjectPath()
        var i = 0
        while (i < path.length) {
            val c = path[i]
            if (c == '|') {
                r.part = path.substring(0, i)
                r.pipe = path.substring(i + 1)
                r.piped = true
                return r
            }
            if (c == '.') {
                r.part = path.substring(0, i)
                if (i < path.length - 1 && isDotPiperChar(path, i + 1)) {
                    r.pipe = path.substring(i + 1)
                    r.piped = true
                } else {
                    r.path = path.substring(i + 1)
                    r.more = true
                }
                return r
            }
            if (c == '*' || c == '?') {
                r.wild = true
                i++
                continue
            }
            if (c == '\\') {
                // go into escape mode. this is a slower path that
                // strips off the escape character from the part.
                val epart = StringBuilder(path.substring(0, i))
                i++
                if (i < path.length) {
                    epart.append(path[i])
                    i++
                    while (i < path.length) {
                        val d = path[i]
                        if (d == '\\') {
                            i++
                            if (i < path.length) epart.append(path[i])
                            i++
                            continue
                        } else if (d == '.') {
                            r.part = epart.toString()
                            if (i < path.length - 1 && isDotPiperChar(path, i + 1)) {
                                r.pipe = path.substring(i + 1)
                                r.piped = true
                            } else {
                                r.path = path.substring(i + 1)
                                r.more = true
                            }
                            return r
                        } else if (d == '|') {
                            r.part = epart.toString()
                            r.pipe = path.substring(i + 1)
                            r.piped = true
                            return r
                        } else if (d == '*' || d == '?') {
                            r.wild = true
                        }
                        epart.append(d)
                        i++
                    }
                }
                // append the last part
                r.part = epart.toString()
                return r
            }
            i++
        }
        r.part = path
        return r
    }

    // ---- object and array search ----

    private fun parseObject(
        c: Ctx,
        start: Int,
        path: String,
    ): Long {
        val json = c.json
        var i = start
        var kesc = false
        var ok = false
        var key = ""
        val rp = parseObjectPath(path)
        if (!rp.more && rp.piped) {
            c.pipe = rp.pipe
            c.piped = true
        }
        while (i < json.length) {
            keyScan@ while (i < json.length) {
                if (json[i] == '"') {
                    // parse_key_string
                    i++
                    val s = i
                    var done = false
                    while (i < json.length) {
                        if (json[i] > '\\') {
                            i++
                            continue
                        }
                        if (json[i] == '"') {
                            key = json.substring(s, i)
                            i++
                            kesc = false
                            ok = true
                            done = true
                            break
                        }
                        if (json[i] == '\\') {
                            i++
                            while (i < json.length) {
                                if (json[i] > '\\') {
                                    i++
                                    continue
                                }
                                if (json[i] == '"') {
                                    // look for an escaped slash
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
                                    key = json.substring(s, i)
                                    i++
                                    kesc = true
                                    ok = true
                                    done = true
                                    break
                                }
                                i++
                            }
                            break
                        }
                        i++
                    }
                    if (!done) {
                        key = json.substring(s)
                        kesc = false
                        ok = false
                    }
                    break@keyScan
                }
                if (json[i] == '}') return ret(i + 1, false)
                i++
            }
            if (!ok) return ret(i, false)
            val k = if (kesc) unescape(key) else key
            val pmatch = if (rp.wild) matchLimit(k, rp.part) else rp.part == k
            var hit = pmatch && !rp.more
            valueScan@ while (i < json.length) {
                var num = false
                when (json[i]) {
                    '"' -> {
                        val s = i
                        i++
                        val ps = scanString(json, i)
                        i = ps.end
                        if (!ps.ok) return ret(i, false)
                        if (hit) {
                            val inner = json.substring(s + 1, i - 1)
                            c.setSlice(JsonType.STRING, s, i, if (ps.escaped) unescape(inner) else inner)
                            return ret(i, true)
                        }
                    }

                    '{' -> {
                        if (pmatch && !hit) {
                            val r = parseObject(c, i + 1, rp.path)
                            i = idxOf(r)
                            hit = hitOf(r)
                            if (hit) return ret(i, true)
                        } else {
                            val s = i
                            i = parseSquash(json, i)
                            if (hit) {
                                c.setSlice(JsonType.JSON, s, i)
                                return ret(i, true)
                            }
                        }
                    }

                    '[' -> {
                        if (pmatch && !hit) {
                            val r = parseArray(c, i + 1, rp.path)
                            i = idxOf(r)
                            hit = hitOf(r)
                            if (hit) return ret(i, true)
                        } else {
                            val s = i
                            i = parseSquash(json, i)
                            if (hit) {
                                c.setSlice(JsonType.JSON, s, i)
                                return ret(i, true)
                            }
                        }
                    }

                    'n', 't', 'f' -> {
                        val vc = json[i]
                        if (vc == 'n' && i + 1 < json.length && json[i + 1] != 'u') {
                            num = true
                        } else {
                            val s = i
                            i = parseLiteralEnd(json, i)
                            if (hit) {
                                val t =
                                    when (vc) {
                                        't' -> JsonType.TRUE
                                        'f' -> JsonType.FALSE
                                        else -> c.type
                                    }
                                c.setSlice(t, s, i)
                                return ret(i, true)
                            }
                        }
                    }

                    '+', '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'i', 'I', 'N' -> {
                        num = true
                    }

                    else -> {
                        i++
                        continue@valueScan
                    }
                }
                if (num) {
                    val s = i
                    i = parseNumberEnd(json, i)
                    if (hit) {
                        val raw = json.substring(s, i)
                        c.setSlice(JsonType.NUMBER, s, i, "", parseFloat(raw) ?: 0.0)
                        return ret(i, true)
                    }
                }
                break
            }
        }
        return ret(i, false)
    }

    /**
     * Wildcard match used for object keys and `%` queries. Go limits the match complexity to
     * avoid ReDoS; [Match] backtracks at most linearly per star, so no limit is needed.
     */
    private fun matchLimit(
        str: String,
        pattern: String,
    ): Boolean = Match.match(str, pattern)

    private fun parseGoBool(s: String): Boolean? =
        when (s) {
            "1", "t", "true" -> true
            "0", "f", "false" -> false
            else -> null
        }

    private fun falseish(t: JsonResult): Boolean =
        when (t.type) {
            JsonType.NULL -> true
            JsonType.FALSE -> true
            JsonType.STRING -> parseGoBool(t.str.lowercase())?.let { !it } ?: false
            JsonType.NUMBER -> t.num == 0.0
            else -> false
        }

    private fun trueish(t: JsonResult): Boolean =
        when (t.type) {
            JsonType.TRUE -> true
            JsonType.STRING -> parseGoBool(t.str.lowercase()) ?: false
            JsonType.NUMBER -> t.num != 0.0
            else -> false
        }

    private fun queryMatches(
        rp: ArrayPath,
        value0: JsonResult,
    ): Boolean {
        var value = value0
        var rpv = rp.queryValue
        if (rpv.isNotEmpty() && rpv[0] == '~') {
            // convert to bool
            rpv = rpv.substring(1)
            val ish: Boolean? =
                when (rpv) {
                    "*" -> value.exists()
                    "null" -> value.type == JsonType.NULL
                    "true" -> trueish(value)
                    "false" -> falseish(value)
                    else -> null
                }
            if (ish != null) {
                rpv = "true"
                value = JsonResult(if (ish) JsonType.TRUE else JsonType.FALSE)
            } else {
                rpv = ""
                value = JsonResult()
            }
        }
        if (!value.exists()) return false
        if (rp.queryOp == "") {
            // the query is only looking for existence, such as: friends.#(name)
            return true
        }
        when (value.type) {
            JsonType.STRING -> {
                return when (rp.queryOp) {
                    "=" -> value.str == rpv
                    "!=" -> value.str != rpv
                    "<" -> value.str < rpv
                    "<=" -> value.str <= rpv
                    ">" -> value.str > rpv
                    ">=" -> value.str >= rpv
                    "%" -> matchLimit(value.str, rpv)
                    "!%" -> !matchLimit(value.str, rpv)
                    else -> false
                }
            }

            JsonType.NUMBER -> {
                val rpvn = parseFloat(rpv) ?: 0.0
                return when (rp.queryOp) {
                    "=" -> value.num == rpvn
                    "!=" -> value.num != rpvn
                    "<" -> value.num < rpvn
                    "<=" -> value.num <= rpvn
                    ">" -> value.num > rpvn
                    ">=" -> value.num >= rpvn
                    else -> false
                }
            }

            JsonType.TRUE -> {
                return when (rp.queryOp) {
                    "=" -> rpv == "true"
                    "!=" -> rpv != "true"
                    ">" -> rpv == "false"
                    ">=" -> true
                    else -> false
                }
            }

            JsonType.FALSE -> {
                return when (rp.queryOp) {
                    "=" -> rpv == "false"
                    "!=" -> rpv != "false"
                    "<" -> rpv == "true"
                    "<=" -> true
                    else -> false
                }
            }

            else -> {
                return false
            }
        }
    }

    private fun parseArray(
        c: Ctx,
        start: Int,
        path: String,
    ): Long {
        val json = c.json
        var i = start
        var pmatch = false
        var hit = false
        var h = 0
        val alog = ArrayList<Int>()
        var partidx = 0
        var multires: StringBuilder? = null
        val queryIndexes = ArrayList<Int>()
        val rp = parseArrayPath(path)
        if (!rp.arrch) {
            val n = parseUint(rp.part)
            partidx = if (n == null || n > Int.MAX_VALUE) -1 else n.toInt()
        }
        if (!rp.more && rp.piped) {
            c.pipe = rp.pipe
            c.piped = true
        }

        fun procQuery(
            qval: JsonResult,
            qpos: Int,
        ): Boolean {
            if (rp.queryAll && multires == null) multires = StringBuilder("[")
            val parentIndex = qpos
            var res: JsonResult
            if (qval.type == JsonType.JSON) {
                res = qval.get(rp.queryPath)
            } else {
                if (rp.queryPath != "") return false
                res = qval
            }
            if (queryMatches(rp, res)) {
                val resPos: Int
                if (rp.more) {
                    val sp = splitPossiblePipe(rp.path)
                    if (sp != null) {
                        rp.path = sp.first
                        c.pipe = sp.second
                        c.piped = true
                    }
                    res = qval.get(rp.path)
                    resPos = if (res.index > 0) qpos + res.index else -1
                } else {
                    res = qval
                    resPos = qpos
                }
                if (rp.queryAll) {
                    val raw = res.raw.ifEmpty { res.string() }
                    if (raw != "") {
                        val m = multires!!
                        if (m.length > 1) m.append(',')
                        m.append(raw)
                        queryIndexes.add(res.index + parentIndex)
                    }
                } else {
                    c.setResult(res, resPos)
                    return true
                }
            }
            return false
        }

        while (i < json.length + 1) {
            if (!rp.arrch) {
                pmatch = partidx == h
                hit = pmatch && !rp.more
            }
            h++
            if (rp.alogok) alog.add(i)
            while (true) {
                val ch: Char =
                    if (i > json.length) {
                        break
                    } else if (i == json.length) {
                        ']'
                    } else {
                        json[i]
                    }
                var num = false
                when (ch) {
                    '"' -> {
                        val s = i
                        i++
                        val ps = scanString(json, i)
                        i = ps.end
                        if (!ps.ok) return ret(i, false)
                        val inner = json.substring(s + 1, i - 1)
                        if (rp.queryOn) {
                            val qval = JsonResult(JsonType.STRING, json.substring(s, i), if (ps.escaped) unescape(inner) else inner)
                            if (procQuery(qval, s)) return ret(i, true)
                        } else if (hit) {
                            if (!rp.alogok) {
                                c.setSlice(JsonType.STRING, s, i, if (ps.escaped) unescape(inner) else inner)
                                return ret(i, true)
                            }
                        }
                    }

                    '{', '[' -> {
                        if (pmatch && !hit) {
                            val r = if (ch == '{') parseObject(c, i + 1, rp.path) else parseArray(c, i + 1, rp.path)
                            i = idxOf(r)
                            hit = hitOf(r)
                            if (hit && !rp.alogok) return ret(i, true)
                        } else {
                            val s = i
                            i = parseSquash(json, i)
                            if (rp.queryOn) {
                                if (procQuery(JsonResult(JsonType.JSON, json.substring(s, minOf(i, json.length))), s)) return ret(i, true)
                            } else if (hit) {
                                if (!rp.alogok) {
                                    c.setSlice(JsonType.JSON, s, i)
                                    return ret(i, true)
                                }
                            }
                        }
                    }

                    'n', 't', 'f' -> {
                        if (ch == 'n' && i + 1 < json.length && json[i + 1] != 'u') {
                            num = true
                        } else {
                            val s = i
                            i = parseLiteralEnd(json, i)
                            val t =
                                when (ch) {
                                    't' -> JsonType.TRUE
                                    'f' -> JsonType.FALSE
                                    else -> JsonType.NULL
                                }
                            if (rp.queryOn) {
                                if (procQuery(JsonResult(t, json.substring(s, i)), s)) return ret(i, true)
                            } else if (hit) {
                                if (!rp.alogok) {
                                    c.setSlice(if (ch == 'n') c.type else t, s, i)
                                    return ret(i, true)
                                }
                            }
                        }
                    }

                    '+', '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'i', 'I', 'N' -> {
                        num = true
                    }

                    ']' -> {
                        if (rp.arrch && rp.part == "#") {
                            if (rp.alogok) {
                                val sp = splitPossiblePipe(rp.alogkey)
                                if (sp != null) {
                                    rp.alogkey = sp.first
                                    c.pipe = sp.second
                                    c.piped = true
                                }
                                val indexes = ArrayList<Int>()
                                val jsons = StringBuilder("[")
                                var k = 0
                                for (j in alog.indices) {
                                    var idx = alog[j]
                                    while (idx < json.length) {
                                        val d = json[idx]
                                        if (d == ' ' || d == '\t' || d == '\r' || d == '\n') {
                                            idx++
                                            continue
                                        }
                                        break
                                    }
                                    if (idx < json.length && json[idx] != ']') {
                                        val pa = parseAny(json, idx)
                                        if (pa != null) {
                                            val res = pa.second.get(rp.alogkey)
                                            if (res.exists()) {
                                                if (k > 0) jsons.append(',')
                                                jsons.append(res.raw.ifEmpty { res.string() })
                                                indexes.add(res.index)
                                                k++
                                            }
                                        }
                                    }
                                }
                                jsons.append(']')
                                c.type = JsonType.JSON
                                c.raw = jsons.toString()
                                c.rawPos = -1
                                c.indexes = indexes.toIntArray()
                                return ret(i + 1, true)
                            }
                            c.type = JsonType.NUMBER
                            c.num = (h - 1).toDouble()
                            c.raw = (h - 1).toString()
                            c.rawPos = -1
                            c.calcd = true
                            return ret(i + 1, true)
                        }
                        if (!c.exists()) {
                            val m = multires
                            if (m != null && m.isNotEmpty()) {
                                c.setResult(
                                    JsonResult(
                                        JsonType.JSON,
                                        m.append(']').toString(),
                                        indexes = if (queryIndexes.isEmpty()) null else queryIndexes.toIntArray(),
                                    ),
                                    -1,
                                )
                            } else if (rp.queryAll) {
                                c.setResult(JsonResult(JsonType.JSON, "[]"), -1)
                            }
                        }
                        return ret(i + 1, false)
                    }

                    else -> {
                        i++
                        continue
                    }
                }
                if (num) {
                    val s = i
                    i = parseNumberEnd(json, i)
                    val raw = json.substring(s, i)
                    if (rp.queryOn) {
                        if (procQuery(JsonResult(JsonType.NUMBER, raw, "", parseFloat(raw) ?: 0.0), s)) return ret(i, true)
                    } else if (hit) {
                        if (!rp.alogok) {
                            c.setSlice(JsonType.NUMBER, s, i, "", parseFloat(raw) ?: 0.0)
                            return ret(i, true)
                        }
                    }
                }
                break
            }
        }
        return ret(i, false)
    }

    private fun splitPossiblePipe(path: String): kotlin.Pair<String, String>? {
        // take a quick peek for the pipe character. If found we'll split the piped
        // part of the path into the c.pipe field and shorten the rp.
        if (path.indexOf('|') < 0) return null
        if (path.isNotEmpty() && path[0] == '{') {
            val squashed0 = squash(path.substring(1))
            if (squashed0.length < path.length - 1) {
                val squashed = path.substring(0, squashed0.length + 1)
                val remain = path.substring(squashed.length)
                if (remain[0] == '|') return squashed to remain.substring(1)
            }
            return null
        }
        // split the left and right side of the path with the pipe character as
        // the delimiter. This is a little tricky because we'll need to basically
        // parse the entire path.
        var i = 0
        while (i < path.length) {
            if (path[i] == '\\') {
                i++
            } else if (path[i] == '.') {
                if (i == path.length - 1) return null
                if (path[i + 1] == '#') {
                    i += 2
                    if (i == path.length) return null
                    if (path[i] == '[' || path[i] == '(') {
                        val open = path[i]
                        val close = if (open == '[') ']' else ')'
                        // inside selector, balance brackets
                        i++
                        var depth = 1
                        while (i < path.length) {
                            if (path[i] == '\\') {
                                i++
                            } else if (path[i] == open) {
                                depth++
                            } else if (path[i] == close) {
                                depth--
                                if (depth == 0) break
                            } else if (path[i] == '"') {
                                // inside selector string, balance quotes
                                i++
                                while (i < path.length) {
                                    if (path[i] == '\\') {
                                        i++
                                    } else if (path[i] == '"') {
                                        break
                                    }
                                    i++
                                }
                            }
                            i++
                        }
                    }
                }
            } else if (path[i] == '|') {
                return path.substring(0, i) to path.substring(i + 1)
            }
            i++
        }
        return null
    }

    private class SubSelector(
        val name: String,
        val path: String,
    )

    /** Parses a `[path1,path2]` or `{"a":path1,path2}` multipath (gjson parseSubSelectors). */
    private fun parseSubSelectors(path: String): kotlin.Pair<List<SubSelector>, String>? {
        val sels = ArrayList<SubSelector>()
        var modifier = 0
        var depth = 1
        var colon = 0
        var start = 1
        var i = 1

        fun pushSel() {
            if (colon == 0) {
                sels.add(SubSelector("", path.substring(start, i)))
            } else {
                sels.add(SubSelector(path.substring(start, colon), path.substring(colon + 1, i)))
            }
            colon = 0
            modifier = 0
            start = i + 1
        }
        while (i < path.length) {
            when (path[i]) {
                '\\' -> {
                    i++
                }

                '@' -> {
                    if (modifier == 0 && i > 0 && (path[i - 1] == '.' || path[i - 1] == '|')) modifier = i
                }

                ':' -> {
                    if (modifier == 0 && colon == 0 && depth == 1) colon = i
                }

                ',' -> {
                    if (depth == 1) pushSel()
                }

                '"' -> {
                    i++
                    while (i < path.length) {
                        if (path[i] == '\\') {
                            i++
                        } else if (path[i] == '"') {
                            break
                        }
                        i++
                    }
                }

                '[', '(', '{' -> {
                    depth++
                }

                ']', ')', '}' -> {
                    depth--
                    if (depth == 0) {
                        pushSel()
                        return sels to path.substring(i + 1)
                    }
                }
            }
            i++
        }
        return null
    }

    /** Returns the name of the last path component. */
    private fun nameOfLast(path: String): String {
        var i = path.length - 1
        while (i >= 0) {
            if (path[i] == '|' || path[i] == '.') {
                if (i > 0 && path[i - 1] == '\\') {
                    i--
                    continue
                }
                return path.substring(i + 1)
            }
            i--
        }
        return path
    }

    private fun isSimpleName(component: String): Boolean {
        for (c in component) {
            if (c < ' ') return false
            when (c) {
                '[', ']', '{', '}', '(', ')', '#', '|', '!' -> return false
            }
        }
        return true
    }

    // ---- strings and numbers ----

    /** Unescapes the inside of a JSON string (gjson unescape). `\u` escapes become UTF-8 bytes. */
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
                                // need another code
                                if (json.length - i >= 6 && json[i] == '\\' && json[i + 1] == 'u') {
                                    // we expect it to be correct so just consume it
                                    val r2 = hex4(json, i + 2)
                                    r =
                                        if (r in 0xD800..0xDBFF && r2 in 0xDC00..0xDFFF) {
                                            0x10000 + ((r - 0xD800) shl 10) + (r2 - 0xDC00)
                                        } else {
                                            0xFFFD
                                        }
                                    i += 6
                                }
                            }
                            JsonUtf8.appendRune(sb, r)
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

    internal fun parseUint(s: String): Long? {
        if (s.isEmpty()) return null
        var n = 0L
        for (c in s) {
            if (c in '0'..'9') n = n * 10 + (c - '0') else return null
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

    // ---- modifiers and literals ----

    /** Parses a `!literal` path component (gjson execStatic). Returns (pathOut, json) or null. */
    private fun execStatic(
        json: String,
        path: String,
    ): kotlin.Pair<String, String>? {
        var name = path.substring(1)
        if (name.isNotEmpty()) {
            when (name[0]) {
                '{', '[', '"', '+', '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
                    val res = name.substring(0, staticEnd(name))
                    return name.substring(res.length) to res
                }
            }
        }
        var pathOut = ""
        for (i in 1 until path.length) {
            if (path[i] == '|' || path[i] == '.') {
                pathOut = path.substring(i)
                name = path.substring(1, i)
                break
            }
        }
        return when (name.lowercase()) {
            "true", "false", "null", "nan", "inf" -> pathOut to name
            else -> null
        }
    }

    /** End of a static value: parseSquash(name, 0), which also handles strings and numbers. */
    private fun staticEnd(name: String): Int {
        // gjson calls parseSquash on the value regardless of its kind; for a string or number
        // the scan stops at the first unbalanced closing bracket or consumes everything.
        return minOf(parseSquash(name, 0), name.length)
    }

    /** Runs a `@modifier[:args]` path component (gjson execModifier). Returns (pathOut, json) or null. */
    private fun execModifier(
        json: String,
        path: String,
    ): kotlin.Pair<String, String>? {
        var name = path.substring(1)
        var pathOut = ""
        var hasArgs = false
        for (i in 1 until path.length) {
            if (path[i] == ':') {
                pathOut = path.substring(i + 1)
                name = path.substring(1, i)
                hasArgs = pathOut.isNotEmpty()
                break
            }
            if (path[i] == '|' || path[i] == '.') {
                pathOut = path.substring(i)
                name = path.substring(1, i)
                break
            }
        }
        val fn = modifiers[name] ?: return null
        var args = ""
        if (hasArgs) {
            var parsedArgs = false
            when (pathOut[0]) {
                '{', '[', '"' -> {
                    // json arg
                    val res = parse(pathOut)
                    if (res.exists()) {
                        args = squash(pathOut)
                        pathOut = pathOut.substring(args.length)
                        parsedArgs = true
                    }
                }
            }
            if (!parsedArgs) {
                // simple arg
                var i = 0
                while (i < pathOut.length) {
                    if (pathOut[i] == '|') break
                    when (pathOut[i]) {
                        '{', '[', '"', '(' -> {
                            val s = squash(pathOut.substring(i))
                            i += s.length - 1
                        }
                    }
                    i++
                }
                args = pathOut.substring(0, minOf(i, pathOut.length))
                pathOut = pathOut.substring(minOf(i, pathOut.length))
            }
        }
        return pathOut to fn(json, args)
    }

    /** Removes the '[]' or '{}' characters around json. */
    private fun unwrap(json0: String): String {
        var json = trim(json0)
        if (json.length >= 2 && (json[0] == '[' || json[0] == '{')) json = json.substring(1, json.length - 1)
        return json
    }

    private val modifiers: Map<String, (String, String) -> String> =
        mapOf(
            "pretty" to ::modPretty,
            "ugly" to { json, _ -> Pretty.ugly(json) },
            "reverse" to { json, _ -> modReverse(json) },
            "this" to { json, _ -> json },
            "flatten" to ::modFlatten,
            "join" to ::modJoin,
            "valid" to { json, _ -> if (valid(json)) json else "" },
            "keys" to { json, _ -> modKeys(json) },
            "values" to { json, _ -> modValues(json) },
            "tostr" to { json, _ -> JsonString.quote(json) },
            "fromstr" to { json, _ -> if (!valid(json)) "" else parse(json).string() },
            "group" to { json, _ -> modGroup(json) },
            "dig" to ::modDig,
        )

    /** Removes everything but whitespace from [s] (gjson cleanWS). */
    private fun cleanWS(s: String): String {
        for (c in s) {
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return s.filter { it == ' ' || it == '\t' || it == '\n' || it == '\r' }
            }
        }
        return s
    }

    private fun modPretty(
        json: String,
        arg: String,
    ): String {
        if (arg.isNotEmpty()) {
            var opts = Pretty.DEFAULT_OPTIONS
            parse(arg).forEach { key, value ->
                when (key.string()) {
                    "sortKeys" -> opts = opts.copy(sortKeys = value.bool())
                    "indent" -> opts = opts.copy(indent = cleanWS(value.string()))
                    "prefix" -> opts = opts.copy(prefix = cleanWS(value.string()))
                    "width" -> opts = opts.copy(width = value.int().toInt())
                }
                true
            }
            return Pretty.pretty(json, opts)
        }
        return Pretty.pretty(json)
    }

    private fun modReverse(json: String): String {
        val res = parse(json)
        if (res.isArray()) {
            val values = ArrayList<JsonResult>()
            res.forEach { _, value ->
                values.add(value)
                true
            }
            val out = StringBuilder(json.length)
            out.append('[')
            for ((j, i) in (values.size - 1 downTo 0).withIndex()) {
                if (j > 0) out.append(',')
                out.append(values[i].raw)
            }
            out.append(']')
            return out.toString()
        }
        if (res.isObject()) {
            val keyValues = ArrayList<JsonResult>()
            res.forEach { key, value ->
                keyValues.add(key)
                keyValues.add(value)
                true
            }
            val out = StringBuilder(json.length)
            out.append('{')
            var i = keyValues.size - 2
            var j = 0
            while (i >= 0) {
                if (j > 0) out.append(',')
                out.append(keyValues[i].raw).append(':').append(keyValues[i + 1].raw)
                i -= 2
                j++
            }
            out.append('}')
            return out.toString()
        }
        return json
    }

    private fun modFlatten(
        json: String,
        arg: String,
    ): String {
        val res = parse(json)
        if (!res.isArray()) return json
        var deep = false
        if (arg != "") {
            parse(arg).forEach { key, value ->
                if (key.string() == "deep") deep = value.bool()
                true
            }
        }
        val out = StringBuilder("[")
        var idx = 0
        res.forEach { _, value ->
            var raw =
                if (value.isArray()) {
                    if (deep) unwrap(modFlatten(value.raw, arg)) else unwrap(value.raw)
                } else {
                    value.raw
                }
            raw = raw.trim { it == ' ' || it == '\t' || it == '\n' || it == '\r' || it == '\u000b' || it == '\u000c' }
            if (raw.isNotEmpty()) {
                if (idx > 0) out.append(',')
                out.append(raw)
                idx++
            }
            true
        }
        out.append(']')
        return out.toString()
    }

    private fun modKeys(json: String): String {
        val v = parse(json)
        if (!v.exists()) return "[]"
        val obj = v.isObject()
        val out = StringBuilder("[")
        var i = 0
        v.forEach { key, _ ->
            if (i > 0) out.append(',')
            out.append(if (obj) key.raw else "null")
            i++
            true
        }
        out.append(']')
        return out.toString()
    }

    private fun modValues(json: String): String {
        val v = parse(json)
        if (!v.exists()) return "[]"
        if (v.isArray()) return json
        val out = StringBuilder("[")
        var i = 0
        v.forEach { _, value ->
            if (i > 0) out.append(',')
            out.append(value.raw)
            i++
            true
        }
        out.append(']')
        return out.toString()
    }

    private fun modJoin(
        json: String,
        arg: String,
    ): String {
        val res = parse(json)
        if (!res.isArray()) return json
        var preserve = false
        if (arg != "") {
            parse(arg).forEach { key, value ->
                if (key.string() == "preserve") preserve = value.bool()
                true
            }
        }
        val out = StringBuilder("{")
        if (preserve) {
            // Preserve duplicate keys.
            var idx = 0
            res.forEach { _, value ->
                if (value.isObject()) {
                    if (idx > 0) out.append(',')
                    out.append(unwrap(value.raw))
                    idx++
                }
                true
            }
        } else {
            // Deduplicate keys and generate an object with stable ordering.
            val keys = ArrayList<JsonResult>()
            val kvals = HashMap<String, JsonResult>()
            res.forEach { _, value ->
                if (value.isObject()) {
                    value.forEach { key, v ->
                        val k = key.string()
                        if (!kvals.containsKey(k)) keys.add(key)
                        kvals[k] = v
                        true
                    }
                }
                true
            }
            for (i in keys.indices) {
                if (i > 0) out.append(',')
                out.append(keys[i].raw).append(':').append(kvals[keys[i].string()]!!.raw)
            }
        }
        out.append('}')
        return out.toString()
    }

    private fun modGroup(json: String): String {
        val res = parse(json)
        if (!res.isObject()) return ""
        val all = ArrayList<StringBuilder>()
        res.forEach { key, value ->
            if (value.isArray()) {
                var idx = 0
                value.forEach { _, v ->
                    if (idx == all.size) all.add(StringBuilder())
                    all[idx]
                        .append(',')
                        .append(key.raw)
                        .append(':')
                        .append(v.raw)
                    idx++
                    true
                }
            }
            true
        }
        val data = StringBuilder("[")
        for ((i, item) in all.withIndex()) {
            if (i > 0) data.append(',')
            data.append('{').append(item, 1, item.length).append('}')
        }
        data.append(']')
        return data.toString()
    }

    private fun parseRecursiveDescent(
        all: MutableList<JsonResult>,
        parent: JsonResult,
        path: String,
    ) {
        val res = parent.get(path)
        if (res.exists()) all.add(res)
        if (parent.isArray() || parent.isObject()) {
            parent.forEach { _, v ->
                parseRecursiveDescent(all, v, path)
                true
            }
        }
    }

    private fun modDig(
        json: String,
        arg: String,
    ): String {
        val all = ArrayList<JsonResult>()
        parseRecursiveDescent(all, parse(json), arg)
        val out = StringBuilder("[")
        for ((i, res) in all.withIndex()) {
            if (i > 0) out.append(',')
            out.append(res.raw)
        }
        out.append(']')
        return out.toString()
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
