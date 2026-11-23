// Helpers that reproduce Go standard library behavior Tile38 relies on
// (strconv parsing, time.Duration formatting, encoding/json strings).
// Copyright jtile38 authors, MIT License.

package io.jtile38.server

import io.jtile38.resp.RespValue

/** Go-compatible conversions. Strings are byte strings (one char per byte). */
object GoCompat {
    private val decimal = Regex("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")

    /** Go `strconv.ParseFloat(s, 64)`; returns null where Go returns an error. */
    fun parseFloat(s: String): Double? {
        if (decimal.matches(s)) {
            val d = s.toDouble()
            return if (d.isInfinite()) null else d
        }
        return when (s.lowercase()) {
            "inf", "+inf", "infinity", "+infinity" -> Double.POSITIVE_INFINITY
            "-inf", "-infinity" -> Double.NEGATIVE_INFINITY
            "nan" -> Double.NaN
            else -> null
        }
    }

    /** Go `strconv.ParseInt(s, 10, 64)`. */
    fun parseInt(s: String): Long? {
        if (s.isEmpty()) return null
        val body = if (s[0] == '+' || s[0] == '-') s.substring(1) else s
        if (body.isEmpty() || body.any { it !in '0'..'9' }) return null
        return s.toLongOrNull()
    }

    /** Go `strconv.ParseUint(s, 10, 64)`, limited to the signed 64-bit range. */
    fun parseUint(s: String): Long? {
        if (s.isEmpty() || s.any { it !in '0'..'9' }) return null
        return s.toLongOrNull()
    }

    /** Go `strconv.FormatFloat(f, 'f', -1, 64)`. */
    fun formatFloat(f: Double): String = RespValue.formatFloat(f)

    /** UTF-8 encoded micro sign as a byte string. */
    private const val MICRO = "Âµ"

    /** Go `time.Duration.String()` of [nanos], as a byte string. */
    fun duration(nanos: Long): String {
        if (nanos == 0L) return "0s"
        val sign = if (nanos < 0) "-" else ""
        var u = if (nanos < 0) -nanos else nanos
        if (u < 1_000_000_000L) {
            return when {
                u < 1_000L -> sign + u + "ns"
                u < 1_000_000L -> sign + frac(u, 3) + MICRO + "s"
                else -> sign + frac(u, 6) + "ms"
            }
        }
        val sb = StringBuilder()
        val fracDigits = fracPart(u, 9)
        u /= 1_000_000_000L
        sb.insert(0, "s")
        sb.insert(0, fracDigits)
        sb.insert(0, u % 60)
        u /= 60
        if (u > 0) {
            sb.insert(0, "m")
            sb.insert(0, u % 60)
            u /= 60
            if (u > 0) {
                sb.insert(0, "h")
                sb.insert(0, u)
            }
        }
        return sign + sb
    }

    private fun pow10(n: Int): Long {
        var p = 1L
        repeat(n) { p *= 10 }
        return p
    }

    private fun fracPart(
        v: Long,
        prec: Int,
    ): String {
        var f = v % pow10(prec)
        val digits = CharArray(prec)
        var print = false
        val sb = StringBuilder()
        for (i in prec - 1 downTo 0) {
            val digit = (f % 10).toInt()
            print = print || digit != 0
            digits[i] = if (print) '0' + digit else ' '
            f /= 10
        }
        for (c in digits) if (c != ' ') sb.append(c)
        return if (sb.isEmpty()) "" else ".$sb"
    }

    private fun frac(
        v: Long,
        prec: Int,
    ): String = (v / pow10(prec)).toString() + fracPart(v, prec)

    /** Go `encoding/json` string encoding (HTML-escaped) of a byte string. */
    fun jsonMarshalString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        var i = 0
        while (i < s.length) {
            val b = s[i].code
            if (b < 0x80) {
                when {
                    b == '"'.code -> {
                        sb.append("\\\"")
                    }

                    b == '\\'.code -> {
                        sb.append("\\\\")
                    }

                    b == '\n'.code -> {
                        sb.append("\\n")
                    }

                    b == '\r'.code -> {
                        sb.append("\\r")
                    }

                    b == '\t'.code -> {
                        sb.append("\\t")
                    }

                    b == 0x08 -> {
                        sb.append("\\b")
                    }

                    b == 0x0c -> {
                        sb.append("\\f")
                    }

                    b < 0x20 || b == '<'.code || b == '>'.code || b == '&'.code -> {
                        sb.append("\\u00").append(HEX[b shr 4]).append(HEX[b and 0xf])
                    }

                    else -> {
                        sb.append(b.toChar())
                    }
                }
                i++
                continue
            }
            val (rune, size) = Utf8.decodeRune(s, i)
            when {
                rune == Utf8.RUNE_ERROR && size == 1 -> sb.append("\\ufffd")
                rune == 0x2028 || rune == 0x2029 -> sb.append("\\u202").append(HEX[rune and 0xf])
                else -> sb.append(s, i, i + size)
            }
            i += size
        }
        sb.append('"')
        return sb.toString()
    }

    /** Tile38 `jsonString`: plain quoting unless escaping is needed. */
    fun jsonString(s: String): String {
        for (c in s) {
            if (c < ' ' || c == '\\' || c == '"' || c.code > 126) return jsonMarshalString(s)
        }
        return "\"" + s + "\""
    }

    private const val HEX = "0123456789abcdef"
}

/** UTF-8 helpers over byte strings. */
object Utf8 {
    const val RUNE_ERROR = 0xFFFD

    /** Go `utf8.DecodeRuneInString`: returns (rune, size); size 0 for empty input. */
    fun decodeRune(
        s: String,
        i: Int,
    ): Pair<Int, Int> {
        if (i >= s.length) return RUNE_ERROR to 0
        val b0 = s[i].code
        if (b0 < 0x80) return b0 to 1

        fun cont(k: Int): Int = if (i + k < s.length) s[i + k].code else -1

        fun isCont(c: Int) = c in 0x80..0xBF
        return when (b0) {
            in 0xC2..0xDF -> {
                val b1 = cont(1)
                if (isCont(b1)) (((b0 and 0x1F) shl 6) or (b1 and 0x3F)) to 2 else RUNE_ERROR to 1
            }

            in 0xE0..0xEF -> {
                val b1 = cont(1)
                val b2 = cont(2)
                val lo = if (b0 == 0xE0) 0xA0 else 0x80
                val hi = if (b0 == 0xED) 0x9F else 0xBF
                if (b1 in lo..hi && isCont(b2)) {
                    (((b0 and 0x0F) shl 12) or ((b1 and 0x3F) shl 6) or (b2 and 0x3F)) to 3
                } else {
                    RUNE_ERROR to 1
                }
            }

            in 0xF0..0xF4 -> {
                val b1 = cont(1)
                val b2 = cont(2)
                val b3 = cont(3)
                val lo = if (b0 == 0xF0) 0x90 else 0x80
                val hi = if (b0 == 0xF4) 0x8F else 0xBF
                if (b1 in lo..hi && isCont(b2) && isCont(b3)) {
                    (((b0 and 0x07) shl 18) or ((b1 and 0x3F) shl 12) or ((b2 and 0x3F) shl 6) or (b3 and 0x3F)) to 4
                } else {
                    RUNE_ERROR to 1
                }
            }

            else -> {
                RUNE_ERROR to 1
            }
        }
    }

    /** Converts a Java (UTF-16) string into a UTF-8 byte string. */
    fun toByteString(s: String): String = String(s.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
}
