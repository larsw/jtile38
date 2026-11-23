// Port of parts of github.com/tidwall/gjson (MIT License, Copyright (c) 2016 Josh Baker)
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
 * [num] the numeric value for numbers, and [index] the position of [raw] in the source
 * document (0 when unknown).
 */
class JsonResult(
    val type: JsonType = JsonType.NULL,
    val raw: String = "",
    val str: String = "",
    val num: Double = 0.0,
    val index: Int = 0,
) {
    /** True if the value exists (gjson Result.Exists). */
    fun exists(): Boolean = type != JsonType.NULL || raw.isNotEmpty()

    fun isObject(): Boolean = type == JsonType.JSON && raw.isNotEmpty() && raw[0] == '{'

    fun isArray(): Boolean = type == JsonType.JSON && raw.isNotEmpty() && raw[0] == '['

    fun isBool(): Boolean = type == JsonType.TRUE || type == JsonType.FALSE

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
     * Iterates through values. For objects the key and value of each member are passed;
     * for arrays the key is a NUMBER result holding the element index. Non-container values
     * are passed once with an empty key.
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
        while (i < json.length) {
            var key: JsonResult
            if (obj) {
                if (json[i] != '"') {
                    i++
                    continue
                }
                val s = i
                val ps = Gjson.parseString(json, i + 1) ?: return
                i = ps.end
                val kraw = json.substring(s, i)
                val kstr =
                    if (ps.escaped) {
                        Gjson.unescape(kraw.substring(1, kraw.length - 1))
                    } else {
                        kraw.substring(1, kraw.length - 1)
                    }
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
            val value = JsonResult(v.type, v.raw, v.str, v.num, s + index)
            if (!iter(key, value)) return
        }
    }

    /** Returns the elements of an array; a non-array value yields a single element list. */
    fun array(): List<JsonResult> {
        if (type == JsonType.NULL) return emptyList()
        if (!isArray()) return listOf(this)
        val out = ArrayList<JsonResult>()
        forEach { _, v ->
            out.add(v)
            true
        }
        return out
    }

    /** Returns the members of an object as an ordered map; empty for non-objects. */
    fun map(): Map<String, JsonResult> {
        val out = LinkedHashMap<String, JsonResult>()
        if (!isObject()) return out
        forEach { k, v ->
            out[k.str] = v
            true
        }
        return out
    }

    /** Searches this value for the given path. */
    fun get(path: String): JsonResult {
        val r = Gjson.get(raw, path)
        return if (r.exists()) JsonResult(r.type, r.raw, r.str, r.num, r.index + index) else r
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
    }
}
