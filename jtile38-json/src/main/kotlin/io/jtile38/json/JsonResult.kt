// Port of github.com/tidwall/gjson v1.18.0 (MIT License, Copyright (c) 2016 Josh Baker)
package io.jtile38.json

/** The kind of a JSON value, mirroring gjson.Type. */
enum class JsonType {
    NULL,
    FALSE,
    NUMBER,
    STRING,
    TRUE,
    JSON,
    ;

    /** Same text as gjson's Type.String(). */
    override fun toString(): String =
        when (this) {
            NULL -> "Null"
            FALSE -> "False"
            NUMBER -> "Number"
            STRING -> "String"
            TRUE -> "True"
            JSON -> "JSON"
        }
}

/**
 * A JSON value located in a document, mirroring gjson.Result.
 *
 * [raw] is the raw JSON text of the value, [str] the unescaped string value for strings,
 * [num] the numeric value for numbers, [index] the position of [raw] in the source document
 * (0 when unknown) and [indexes] the positions of the elements of a result produced by a path
 * containing `#`.
 */
class JsonResult(
    val type: JsonType = JsonType.NULL,
    val raw: String = "",
    val str: String = "",
    val num: Double = 0.0,
    val index: Int = 0,
    val indexes: IntArray? = null,
) {
    /** True if the value exists (gjson Result.Exists). */
    fun exists(): Boolean = type != JsonType.NULL || raw.isNotEmpty()

    fun isObject(): Boolean = type == JsonType.JSON && raw.isNotEmpty() && raw[0] == '{'

    fun isArray(): Boolean = type == JsonType.JSON && raw.isNotEmpty() && raw[0] == '['

    fun isBool(): Boolean = type == JsonType.TRUE || type == JsonType.FALSE

    /** A copy with a different [index] and [indexes]. */
    internal fun with(
        index: Int,
        indexes: IntArray?,
    ): JsonResult = JsonResult(type, raw, str, num, index, indexes)

    /** String representation with gjson Result.String() semantics. */
    fun string(): String =
        when (type) {
            JsonType.FALSE -> {
                "false"
            }

            JsonType.TRUE -> {
                "true"
            }

            JsonType.STRING -> {
                str
            }

            JsonType.JSON -> {
                raw
            }

            JsonType.NUMBER -> {
                if (raw.isEmpty()) {
                    // calculated result
                    JsonFloat.format(num)
                } else {
                    var i = 0
                    if (raw[0] == '-') i++
                    var allDigits = true
                    while (i < raw.length) {
                        if (raw[i] < '0' || raw[i] > '9') {
                            allDigits = false
                            break
                        }
                        i++
                    }
                    if (allDigits) raw else JsonFloat.format(num)
                }
            }

            JsonType.NULL -> {
                ""
            }
        }

    override fun toString(): String = string()

    fun bool(): Boolean =
        when (type) {
            JsonType.TRUE -> true
            JsonType.STRING -> parseGoBool(str.lowercase())
            JsonType.NUMBER -> num != 0.0
            else -> false
        }

    fun int(): Long =
        when (type) {
            JsonType.TRUE -> {
                1L
            }

            JsonType.STRING -> {
                parseIntOrNull(str) ?: 0L
            }

            JsonType.NUMBER -> {
                // try to directly convert the float64 to int64, then parse the raw string
                safeInt(num) ?: parseIntOrNull(raw) ?: goFloatToLong(num)
            }

            else -> {
                0L
            }
        }

    fun float(): Double =
        when (type) {
            JsonType.TRUE -> 1.0
            JsonType.STRING -> Gjson.parseFloat(str) ?: 0.0
            JsonType.NUMBER -> num
            else -> 0.0
        }

    /**
     * Iterates through values (gjson Result.ForEach). For objects the key and value of each
     * member are passed; for arrays the key is a NUMBER result holding the element index.
     * Non-container values are passed once with an empty key.
     */
    fun forEach(iter: (key: JsonResult, value: JsonResult) -> Boolean) {
        if (!exists()) return
        if (type != JsonType.JSON) {
            iter(JsonResult(), this)
            return
        }
        val json = raw
        var obj = false
        var i = 0
        while (i < json.length) {
            val c = json[i]
            if (c == '{') {
                i++
                obj = true
                break
            } else if (c == '[') {
                i++
                break
            }
            if (c > ' ') return
            i++
        }
        var arrIdx = -1
        var idx = 0
        while (i < json.length) {
            var key: JsonResult
            if (obj) {
                if (json[i] != '"') {
                    i++
                    continue
                }
                val s = i
                val ps = Gjson.scanString(json, i + 1)
                if (!ps.ok) return
                i = ps.end
                val kraw = json.substring(s, i)
                val inner = kraw.substring(1, kraw.length - 1)
                val kstr = if (ps.escaped) Gjson.unescape(inner) else inner
                key = JsonResult(JsonType.STRING, kraw, kstr, 0.0, s + index)
            } else {
                arrIdx++
                key = JsonResult(JsonType.NUMBER, "", "", arrIdx.toDouble())
            }
            while (i < json.length) {
                val c = json[i]
                if (c <= ' ' || c == ',' || c == ':') {
                    i++
                    continue
                }
                break
            }
            val s = i
            val pa = Gjson.parseAny(json, i) ?: return
            i = pa.first
            val v = pa.second
            val ix = indexes
            val vIndex =
                if (ix != null) {
                    if (idx < ix.size) ix[idx] else v.index
                } else {
                    s + index
                }
            if (!iter(key, v.with(vIndex, null))) return
            idx++
        }
    }

    /** Returns the elements of an array; a non-array value yields a single element list (gjson Array). */
    fun array(): List<JsonResult> {
        if (type == JsonType.NULL) return emptyList()
        if (!isArray()) return listOf(this)
        @Suppress("UNCHECKED_CAST")
        return arrayOrMap('[', false).a!!
    }

    /** Returns the members of an object, first occurrence of a key wins (gjson Map). */
    fun map(): Map<String, JsonResult> {
        if (type != JsonType.JSON) return LinkedHashMap()
        return arrayOrMap('{', false).o ?: LinkedHashMap()
    }

    /**
     * Returns the value as a plain Kotlin value (gjson Value): Boolean, Double, String, null,
     * Map<String, Any?> for objects and List<Any?> for arrays.
     */
    fun value(): Any? =
        when (type) {
            JsonType.STRING -> {
                str
            }

            JsonType.FALSE -> {
                false
            }

            JsonType.TRUE -> {
                true
            }

            JsonType.NUMBER -> {
                num
            }

            JsonType.JSON -> {
                val r = arrayOrMap(0.toChar(), true)
                when (r.vc) {
                    '{' -> r.oi
                    '[' -> r.ai
                    else -> null
                }
            }

            else -> {
                null
            }
        }

    private class ArrayOrMap {
        var a: MutableList<JsonResult>? = null
        var ai: MutableList<Any?>? = null
        var o: MutableMap<String, JsonResult>? = null
        var oi: MutableMap<String, Any?>? = null
        var vc: Char = 0.toChar()
    }

    private fun arrayOrMap(
        vc: Char,
        valueize: Boolean,
    ): ArrayOrMap {
        val r = ArrayOrMap()
        val json = raw
        var i = 0
        var count = 0
        var key = JsonResult()
        var found = false
        if (vc == 0.toChar()) {
            while (i < json.length) {
                if (json[i] == '{' || json[i] == '[') {
                    r.vc = json[i]
                    i++
                    found = true
                    break
                }
                if (json[i] > ' ') break
                i++
            }
        } else {
            while (i < json.length) {
                if (json[i] == vc) {
                    i++
                    found = true
                    break
                }
                if (json[i] > ' ') break
                i++
            }
            if (found) r.vc = vc
        }
        if (found) {
            if (r.vc == '{') {
                if (valueize) r.oi = LinkedHashMap() else r.o = LinkedHashMap()
            } else {
                if (valueize) r.ai = ArrayList() else r.a = ArrayList()
            }
            while (i < json.length) {
                val c = json[i]
                if (c <= ' ') {
                    i++
                    continue
                }
                // get next value
                if (c == ']' || c == '}') break
                val value: JsonResult
                when (c) {
                    '{', '[' -> {
                        value =
                            JsonResult(JsonType.JSON, json.substring(i, minOf(Gjson.parseSquash(json, i), json.length)), index = i + index)
                    }

                    'n', 't', 'f' -> {
                        var e = i + 1
                        while (e < json.length && json[e] in 'a'..'z') e++
                        val t =
                            when (c) {
                                'n' -> JsonType.NULL
                                't' -> JsonType.TRUE
                                else -> JsonType.FALSE
                            }
                        value = JsonResult(t, json.substring(i, e), index = i + index)
                    }

                    '"' -> {
                        val (rawStr, str) = Gjson.toStr(json.substring(i))
                        value = JsonResult(JsonType.STRING, rawStr, str, 0.0, i + index)
                    }

                    else -> {
                        if (c in '0'..'9' || c == '-') {
                            var e = i + 1
                            while (e < json.length) {
                                val d = json[e]
                                if (d <= '-') {
                                    if (d <= ' ' || d == ',') break
                                } else if (d == ']' || d == '}') {
                                    break
                                }
                                e++
                            }
                            val numRaw = json.substring(i, e)
                            value = JsonResult(JsonType.NUMBER, numRaw, "", Gjson.parseFloat(numRaw) ?: 0.0, i + index)
                        } else {
                            i++
                            continue
                        }
                    }
                }
                i += value.raw.length - 1
                if (r.vc == '{') {
                    if (count % 2 == 0) {
                        key = value
                    } else {
                        if (valueize) {
                            val m = r.oi!!
                            if (!m.containsKey(key.str)) m[key.str] = value.value()
                        } else {
                            val m = r.o!!
                            if (!m.containsKey(key.str)) m[key.str] = value
                        }
                    }
                    count++
                } else {
                    if (valueize) r.ai!!.add(value.value()) else r.a!!.add(value)
                }
                i++
            }
        }
        val ix = indexes
        val a = r.a
        if (ix != null && a != null) {
            for (k in a.indices) {
                a[k] = a[k].with(if (ix.size != a.size) 0 else ix[k], a[k].indexes)
            }
        }
        return r
    }

    /** Searches this value for the given path (gjson Result.Get). */
    fun get(path: String): JsonResult {
        val r = Gjson.get(raw, path)
        val ix = r.indexes
        return if (ix != null) {
            r.with(r.index, IntArray(ix.size) { ix[it] + index })
        } else {
            r.with(r.index + index, null)
        }
    }

    /**
     * Returns true if this value is less than [token] (gjson Result.Less). Values of different
     * types are ordered Null < False < Number < String < True < JSON.
     */
    fun less(
        token: JsonResult,
        caseSensitive: Boolean,
    ): Boolean {
        if (type < token.type) return true
        if (type > token.type) return false
        if (type == JsonType.STRING) {
            return if (caseSensitive) str < token.str else stringLessInsensitive(str, token.str)
        }
        if (type == JsonType.NUMBER) return num < token.num
        return raw < token.raw
    }

    override fun equals(other: Any?): Boolean =
        other is JsonResult && other.type == type && other.raw == raw && other.str == str &&
            (other.num == num || (other.num.isNaN() && num.isNaN()))

    override fun hashCode(): Int = (type.hashCode() * 31 + raw.hashCode()) * 31 + str.hashCode()

    private companion object {
        fun parseGoBool(s: String): Boolean =
            when (s) {
                "1", "t", "true" -> true
                else -> false
            }

        fun parseIntOrNull(s: String): Long? {
            var i = 0
            var sign = false
            if (s.isNotEmpty() && s[0] == '-') {
                sign = true
                i++
            }
            if (i == s.length) return null
            var n = 0L
            while (i < s.length) {
                val c = s[i]
                if (c in '0'..'9') n = n * 10 + (c - '0') else return null
                i++
            }
            return if (sign) -n else n
        }

        fun safeInt(f: Double): Long? {
            if (f < -9007199254740991.0 || f > 9007199254740991.0) return null
            return f.toLong()
        }

        fun goFloatToLong(f: Double): Long = if (f.isNaN()) Long.MIN_VALUE else f.toLong()

        fun stringLessInsensitive(
            a: String,
            b: String,
        ): Boolean {
            var i = 0
            while (i < a.length && i < b.length) {
                var ac = a[i].code
                var bc = b[i].code
                if (ac in 'A'.code..'Z'.code) ac += 32
                if (bc in 'A'.code..'Z'.code) bc += 32
                if (ac < bc) return true
                if (ac > bc) return false
                i++
            }
            return a.length < b.length
        }
    }
}
