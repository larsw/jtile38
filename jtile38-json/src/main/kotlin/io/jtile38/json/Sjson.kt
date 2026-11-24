// Port of github.com/tidwall/sjson v1.2.5 (MIT License, Copyright (c) 2016 Josh Baker)
package io.jtile38.json

/** Error returned by [Sjson] operations (sjson errorType). */
class SjsonException(
    message: String,
) : Exception(message)

/**
 * Sets and deletes JSON values by path without parsing the whole document, a port of
 * github.com/tidwall/sjson. Paths use the gjson syntax; a simple path (keys and indexes, `-1`
 * to append to an array, `:` prefix to force an object key) can create missing containers,
 * while complex paths (with `|`, `#`, `@`, `*` or `?`) can only replace existing values.
 *
 * The input is expected to be well-formed and is not validated. Strings are byte strings.
 * The ReplaceInPlace option is not ported (it is a Go memory optimization).
 */
object Sjson {
    /** Sets a string value (sjson.Set with a string value). */
    fun set(
        json: String,
        path: String,
        value: String,
        optimistic: Boolean = false,
    ): String = run(json) { set(json, path, value, stringify = true, del = false, optimistic = optimistic) }

    /** Sets a raw JSON value (sjson.SetRaw). */
    fun setRaw(
        json: String,
        path: String,
        value: String,
        optimistic: Boolean = false,
    ): String = run(json) { set(json, path, value, stringify = false, del = false, optimistic = optimistic) }

    /** Sets a boolean value (sjson.Set with a bool). */
    fun setBool(
        json: String,
        path: String,
        value: Boolean,
    ): String = setRaw(json, path, if (value) "true" else "false")

    /** Sets an integer value (sjson.Set with an int64). */
    fun setInt(
        json: String,
        path: String,
        value: Long,
    ): String = setRaw(json, path, value.toString())

    /** Sets a float value (sjson.Set with a float64, formatted with strconv 'f', -1). */
    fun setFloat(
        json: String,
        path: String,
        value: Double,
    ): String = setRaw(json, path, JsonFloat.format(value))

    /** Deletes the value at [path] (sjson.Delete). Returns [json] unchanged when it does not exist. */
    fun delete(
        json: String,
        path: String,
    ): String = run(json) { set(json, path, "", stringify = false, del = true, optimistic = false) }

    /** Maps the internal "no change" signal to returning the input, like sjson's public functions. */
    private inline fun run(
        json: String,
        f: () -> String,
    ): String =
        try {
            f()
        } catch (_: NoChange) {
            json
        }

    private object NoChange : RuntimeException() {
        private fun readResolve(): Any = NoChange

        override fun fillInStackTrace(): Throwable = this
    }

    private class PathResult(
        /** Current key part. */
        var part: String = "",
        /** gjson get part. */
        var gpart: String = "",
        /** Remaining path. */
        var path: String = "",
        /** Force a string key. */
        var force: Boolean = false,
        /** There is more path to parse. */
        var more: Boolean = false,
    )

    private fun isSimpleChar(ch: Char): Boolean =
        when (ch) {
            '|', '#', '@', '*', '?' -> false
            else -> true
        }

    /** Returns the parsed component, or null when the path is not simple. */
    private fun parsePath(path0: String): PathResult? {
        var path = path0
        val r = PathResult()
        if (path.isNotEmpty() && path[0] == ':') {
            r.force = true
            path = path.substring(1)
        }
        var i = 0
        while (i < path.length) {
            if (path[i] == '.') {
                r.part = path.substring(0, i)
                r.gpart = path.substring(0, i)
                r.path = path.substring(i + 1)
                r.more = true
                return r
            }
            if (!isSimpleChar(path[i])) return null
            if (path[i] == '\\') {
                // go into escape mode. this is a slower path that
                // strips off the escape character from the part.
                val epart = StringBuilder(path.substring(0, i))
                val gpart = StringBuilder(path.substring(0, i + 1))
                i++
                if (i < path.length) {
                    epart.append(path[i])
                    gpart.append(path[i])
                    i++
                    while (i < path.length) {
                        if (path[i] == '\\') {
                            gpart.append('\\')
                            i++
                            if (i < path.length) {
                                epart.append(path[i])
                                gpart.append(path[i])
                            }
                            i++
                            continue
                        } else if (path[i] == '.') {
                            r.part = epart.toString()
                            r.gpart = gpart.toString()
                            r.path = path.substring(i + 1)
                            r.more = true
                            return r
                        } else if (!isSimpleChar(path[i])) {
                            return null
                        }
                        epart.append(path[i])
                        gpart.append(path[i])
                        i++
                    }
                }
                // append the last part
                r.part = epart.toString()
                r.gpart = gpart.toString()
                return r
            }
            i++
        }
        r.part = path
        r.gpart = path
        return r
    }

    private fun mustMarshalString(s: String): Boolean {
        for (c in s) {
            if (c < ' ' || c.code > 0x7f || c == '"' || c == '\\') return true
        }
        return false
    }

    /** Makes a json string and appends it to [buf] (appendStringify). */
    private fun appendStringify(
        buf: StringBuilder,
        s: String,
    ) {
        if (mustMarshalString(s)) {
            // json.Marshal of a string produces the same bytes as gjson.AppendJSONString
            JsonString.append(buf, s)
            return
        }
        buf.append('"').append(s).append('"')
    }

    /** Builds a json block from a json path (appendBuild). */
    private fun appendBuild(
        buf: StringBuilder,
        array: Boolean,
        paths: List<PathResult>,
        from: Int,
        raw: String,
        stringify: Boolean,
    ) {
        if (!array) {
            appendStringify(buf, paths[from].part)
            buf.append(':')
        }
        if (paths.size - from > 1) {
            val n = atoui(paths[from + 1])
            if (n != null || (!paths[from + 1].force && paths[from + 1].part == "-1")) {
                buf.append('[')
                repeat(n ?: 0) { buf.append("null,") }
                appendBuild(buf, true, paths, from + 1, raw, stringify)
                buf.append(']')
            } else {
                buf.append('{')
                appendBuild(buf, false, paths, from + 1, raw, stringify)
                buf.append('}')
            }
        } else {
            if (stringify) appendStringify(buf, raw) else buf.append(raw)
        }
    }

    /** Converts a numeric key to an unsigned int, null when not numeric (atoui). */
    private fun atoui(r: PathResult): Int? {
        if (r.force) return null
        var n = 0
        for (c in r.part) {
            if (c < '0' || c > '9') return null
            n = n * 10 + (c - '0')
        }
        return n
    }

    private fun trim(s: String): String {
        var a = 0
        var b = s.length
        while (a < b && s[a] <= ' ') a++
        while (b > a && s[b - 1] <= ' ') b--
        return s.substring(a, b)
    }

    /** Deletes the previous key or comma (deleteTailItem). Returns true when the next comma should go. */
    private fun deleteTailItem(buf: StringBuilder): Boolean {
        var i = buf.length - 1
        while (i >= 0) {
            // look for either a ',',':','['
            when (buf[i]) {
                '[' -> {
                    return true
                }

                ',' -> {
                    buf.setLength(i)
                    return false
                }

                ':' -> {
                    // delete tail string
                    i--
                    while (i >= 0) {
                        if (buf[i] == '"') {
                            i--
                            while (i >= 0) {
                                if (buf[i] == '"') {
                                    i--
                                    if (i >= 0 && buf[i] == '\\') {
                                        // Go decrements once here and once more in the loop post statement
                                        i -= 2
                                        continue
                                    }
                                    while (i >= 0) {
                                        // look for either a ',','{'
                                        when (buf[i]) {
                                            '{' -> {
                                                buf.setLength(i + 1)
                                                return true
                                            }

                                            ',' -> {
                                                buf.setLength(i)
                                                return false
                                            }
                                        }
                                        i--
                                    }
                                }
                                i--
                            }
                            break
                        }
                        i--
                    }
                    return false
                }
            }
            i--
        }
        return false
    }

    private fun appendRawPaths(
        buf: StringBuilder,
        jstr0: String,
        paths: List<PathResult>,
        from: Int,
        raw: String,
        stringify: Boolean,
        del: Boolean,
    ) {
        var jstr = jstr0
        var res = JsonResult()
        var found = false
        val p0 = paths[from]
        if (del) {
            if (p0.part == "-1" && !p0.force) {
                res = Gjson.get(jstr, "#")
                if (res.int() > 0) {
                    res = Gjson.get(jstr, (res.int() - 1).toString())
                    found = true
                }
            }
        }
        if (!found) res = Gjson.get(jstr, p0.gpart)
        if (res.index > 0) {
            if (paths.size - from > 1) {
                buf.append(jstr, 0, res.index)
                appendRawPaths(buf, res.raw, paths, from + 1, raw, stringify, del)
                buf.append(jstr, res.index + res.raw.length, jstr.length)
                return
            }
            buf.append(jstr, 0, res.index)
            var exidx = 0 // additional forward stripping
            if (del) {
                val delNextComma = deleteTailItem(buf)
                if (delNextComma) {
                    var i = res.index + res.raw.length
                    var j = 0
                    while (i < jstr.length) {
                        if (jstr[i] <= ' ') {
                            i++
                            j++
                            continue
                        }
                        if (jstr[i] == ',') exidx = j + 1
                        break
                    }
                }
            } else {
                if (stringify) appendStringify(buf, raw) else buf.append(raw)
            }
            buf.append(jstr, minOf(res.index + res.raw.length + exidx, jstr.length), jstr.length)
            return
        }
        if (del) throw NoChange
        val n = atoui(p0)
        val numeric = n != null
        var isempty = true
        for (c in jstr) {
            if (c > ' ') {
                isempty = false
                break
            }
        }
        if (isempty) jstr = if (numeric) "[]" else "{}"
        var jsres = Gjson.parse(jstr)
        if (jsres.type != JsonType.JSON) {
            jstr = if (numeric) "[]" else "{}"
            jsres = Gjson.parse(jstr)
        }
        var comma = false
        for (i in 1 until jsres.raw.length) {
            if (jsres.raw[i] <= ' ') continue
            if (jsres.raw[i] == '}' || jsres.raw[i] == ']') break
            comma = true
            break
        }
        when (jsres.raw[0]) {
            '{' -> {
                var end = jsres.raw.length - 1
                while (end > 0) {
                    if (jsres.raw[end] == '}') break
                    end--
                }
                buf.append(jsres.raw, 0, end)
                if (comma) buf.append(',')
                appendBuild(buf, false, paths, from, raw, stringify)
                buf.append('}')
            }

            '[' -> {
                var appendit = false
                if (!numeric) {
                    if (p0.part == "-1" && !p0.force) {
                        appendit = true
                    } else {
                        throw SjsonException("cannot set array element for non-numeric key '" + p0.part + "'")
                    }
                }
                if (appendit) {
                    var njson = trim(jsres.raw)
                    if (njson[njson.length - 1] == ']') njson = njson.substring(0, njson.length - 1)
                    buf.append(njson)
                    if (comma) buf.append(',')
                    appendBuild(buf, true, paths, from, raw, stringify)
                    buf.append(']')
                    return
                }
                buf.append('[')
                val ress = jsres.array()
                for (i in ress.indices) {
                    if (i > 0) buf.append(',')
                    buf.append(ress[i].raw)
                }
                if (ress.isEmpty()) {
                    repeat(n!! - ress.size) { buf.append("null,") }
                } else {
                    repeat(n!! - ress.size) { buf.append(",null") }
                    if (comma) buf.append(',')
                }
                appendBuild(buf, true, paths, from, raw, stringify)
                buf.append(']')
            }

            else -> {
                throw SjsonException("json must be an object or array")
            }
        }
    }

    private fun isOptimisticPath(path: String): Boolean {
        for (c in path) {
            if (c < '.' || c > 'z') return false
            if (c > '9' && c < 'A') return false
        }
        return true
    }

    private fun set(
        jstr: String,
        path: String,
        raw: String,
        stringify: Boolean,
        del: Boolean,
        optimistic: Boolean,
    ): String {
        if (path == "") throw SjsonException("path cannot be empty")
        if (!del && optimistic && isOptimisticPath(path)) {
            val res = Gjson.get(jstr, path)
            if (res.exists() && res.index > 0) {
                val buf = StringBuilder(jstr.length - res.raw.length + raw.length + 2)
                buf.append(jstr, 0, res.index)
                if (stringify) appendStringify(buf, raw) else buf.append(raw)
                buf.append(jstr, res.index + res.raw.length, jstr.length)
                return buf.toString()
            }
        }
        val paths = ArrayList<PathResult>()
        var r = parsePath(path)
        var simple = r != null
        if (r != null) {
            paths.add(r)
            while (r!!.more) {
                r = parsePath(r.path)
                if (r == null) {
                    simple = false
                    break
                }
                paths.add(r)
            }
        }
        if (!simple) {
            if (del) throw SjsonException("cannot delete value from a complex path")
            return setComplexPath(jstr, path, raw, stringify)
        }
        val buf = StringBuilder(jstr.length + raw.length + 16)
        appendRawPaths(buf, jstr, paths, 0, raw, stringify, del)
        return buf.toString()
    }

    private fun setComplexPath(
        jstr0: String,
        path: String,
        raw: String,
        stringify: Boolean,
    ): String {
        var jstr = jstr0
        val res = Gjson.get(jstr, path)
        val ix = res.indexes
        if (!res.exists() || !(res.index != 0 || (ix != null && ix.isNotEmpty()))) throw NoChange
        if (res.index != 0) {
            val njson = StringBuilder(jstr.substring(0, res.index))
            if (stringify) appendStringify(njson, raw) else njson.append(raw)
            njson.append(jstr, res.index + res.raw.length, jstr.length)
            jstr = njson.toString()
        }
        if (ix != null && ix.isNotEmpty()) {
            val vals = ArrayList<JsonResult>(ix.size)
            res.forEach { _, vres ->
                vals.add(vres)
                true
            }
            if (ix.size != vals.size) throw NoChange
            // replace from the back so earlier indexes stay valid (sort.SliceStable by index desc)
            val order = vals.indices.sortedByDescending { ix[it] }
            for (k in order) {
                val vres = vals[k]
                val index = ix[k]
                val njson = StringBuilder(jstr.substring(0, index))
                if (stringify) appendStringify(njson, raw) else njson.append(raw)
                njson.append(jstr, index + vres.raw.length, jstr.length)
                jstr = njson.toString()
            }
        }
        return jstr
    }
}
