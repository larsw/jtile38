// Port of github.com/tidwall/conv v0.1.0 (MIT License, Copyright (c) 2022 Joshua J Baker)
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.expr

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Go `interface{ Bool() bool }`, used when converting custom objects. */
interface Booler {
    fun bool(): Boolean
}

/** Go `interface{ Float64() float64 }`. */
interface Floater {
    fun float64(): Double
}

/** Go `interface{ Int64() int64 }`. */
interface Inter {
    fun int64(): Long
}

/** Go `interface{ Uint64() uint64 }`. */
interface Uinter {
    fun uint64(): ULong
}

/**
 * Go `interface{ String() string }`. Kotlin's `toString` is not used for this, because in Go
 * only types that define `String()` take part in the conversion.
 */
interface Stringer {
    fun string(): String
}

/**
 * Conversions between bool, float64, int64, uint64, string and arbitrary values that follow
 * Javascript-like rules (github.com/tidwall/conv). Strings are byte strings (one char per
 * byte), like Go strings.
 */
object Conv {
    private const val MAX_UINT64: ULong = ULong.MAX_VALUE
    private val maxUint64Float = Math.nextAfter(1.8446744073709551615E19, 0.0)
    private val maxInt64Float = Math.nextAfter(9.223372036854775807E18, 0.0)
    private val minInt64Float = Math.nextAfter(-9.223372036854775808E18, 1.0)
    private const val MAX_SAFE = 9007199254740991.0

    // ---- bool ----

    fun ttoi(t: Boolean): Long = if (t) 1 else 0

    fun ttou(t: Boolean): ULong = if (t) 1u else 0u

    fun ttof(t: Boolean): Double = if (t) 1.0 else 0.0

    fun ttoa(t: Boolean): String = if (t) "true" else "false"

    // ---- float64 ----

    fun ftot(f: Double): Boolean = f < 0 || f > 0

    fun ftoi(f0: Double): Long {
        var f = f0
        if (f.isNaN()) return 0
        if (f < -MAX_SAFE || f > MAX_SAFE) {
            // Outside of the range for a correct binary representation as an integer.
            if (f < 0) {
                f = Math.ceil(f)
                if (f < minInt64Float) return Long.MIN_VALUE
            } else {
                f = Math.floor(f)
                if (f > maxInt64Float) return Long.MAX_VALUE
            }
        }
        return f.toLong()
    }

    fun ftou(f0: Double): ULong {
        var f = f0
        if (f.isNaN()) return 0u
        if (f < 0) return 0u
        if (f > MAX_SAFE) {
            f = Math.floor(f)
            if (f > maxUint64Float) return MAX_UINT64
        }
        return f.toULong()
    }

    /** Returns "Infinity", "-Infinity" and "NaN" for the special values. */
    fun ftoa(f: Double): String =
        when {
            f.isNaN() -> "NaN"
            f == Double.POSITIVE_INFINITY -> "Infinity"
            f == Double.NEGATIVE_INFINITY -> "-Infinity"
            else -> formatFloat(f)
        }

    // ---- int64 ----

    fun itot(i: Long): Boolean = i != 0L

    fun itof(i: Long): Double = i.toDouble()

    fun itou(i: Long): ULong = if (i < 0) 0u else i.toULong()

    fun itoa(i: Long): String = i.toString()

    // ---- uint64 ----

    fun utot(u: ULong): Boolean = u != 0uL

    fun utof(u: ULong): Double = u.toDouble()

    fun utoi(u: ULong): Long = if (u > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else u.toLong()

    fun utoa(u: ULong): String = u.toString()

    // ---- string ----

    private fun isnumch(c: Char) = (c in '0'..'9') || c == '.'

    private fun parseFloat(a: String): Double? {
        if (a.isEmpty()) return null
        if (a.length == 1 || isnumch(a[0]) || (a[0] == '-' && isnumch(a[1])) || (a[0] == '+' && isnumch(a[1]))) {
            return GoStrconv.parseFloat(a)
        }
        return when (a) {
            "+Infinity", "Infinity" -> Double.POSITIVE_INFINITY
            "-Infinity" -> Double.NEGATIVE_INFINITY
            "NaN" -> Double.NaN
            else -> null
        }
    }

    /** Always true unless the string is empty. */
    fun atot(a: String): Boolean = a != ""

    /** Returns NaN for invalid syntax. */
    fun atof(a: String): Double = parseFloat(a) ?: Double.NaN

    /** Returns 0 for invalid syntax. */
    fun atoi(a: String): Long {
        GoStrconv.parseInt(a)?.let { return it }
        parseFloat(a)?.let { return ftoi(it) }
        return 0
    }

    /** Returns 0 for invalid syntax. */
    fun atou(a: String): ULong {
        GoStrconv.parseUint(a, 10)?.let { return it }
        parseFloat(a)?.let { return ftou(it) }
        return 0u
    }

    // ---- any ----

    fun vtot(v: Any?): Boolean =
        when (v) {
            is Boolean -> v

            is Byte -> itot(v.toLong())

            is Short -> itot(v.toLong())

            is Int -> itot(v.toLong())

            is Long -> itot(v)

            is UByte -> utot(v.toULong())

            is UShort -> utot(v.toULong())

            is UInt -> utot(v.toULong())

            is ULong -> utot(v)

            is Double -> ftot(v)

            is Float -> ftot(v.toDouble())

            is String -> atot(v)

            // order matters (bool,int,uint,string,float)
            is Booler -> v.bool()

            is Inter -> itot(v.int64())

            is Uinter -> utot(v.uint64())

            is Stringer -> atot(v.string())

            is Floater -> ftot(v.float64())

            else -> false
        }

    fun vtof(v: Any?): Double =
        when (v) {
            is Boolean -> ttof(v)

            is Byte -> itof(v.toLong())

            is Short -> itof(v.toLong())

            is Int -> itof(v.toLong())

            is Long -> itof(v)

            is UByte -> utof(v.toULong())

            is UShort -> utof(v.toULong())

            is UInt -> utof(v.toULong())

            is ULong -> utof(v)

            is Double -> v

            is Float -> v.toDouble()

            is String -> atof(v)

            // order matters (float,int,uint,string,bool)
            is Floater -> v.float64()

            is Inter -> itof(v.int64())

            is Uinter -> utof(v.uint64())

            is Stringer -> atof(v.string())

            is Booler -> ttof(v.bool())

            else -> Double.NaN
        }

    fun vtoi(v: Any?): Long =
        when (v) {
            is Boolean -> ttoi(v)

            is Byte -> v.toLong()

            is Short -> v.toLong()

            is Int -> v.toLong()

            is Long -> v

            is UByte -> utoi(v.toULong())

            is UShort -> utoi(v.toULong())

            is UInt -> utoi(v.toULong())

            is ULong -> utoi(v)

            is Double -> ftoi(v)

            is Float -> ftoi(v.toDouble())

            is String -> atoi(v)

            // order matters (int,uint,float,string,bool)
            is Inter -> v.int64()

            is Uinter -> utoi(v.uint64())

            is Floater -> ftoi(v.float64())

            is Stringer -> atoi(v.string())

            is Booler -> ttoi(v.bool())

            else -> 0
        }

    fun vtou(v: Any?): ULong =
        when (v) {
            is Boolean -> ttou(v)

            is Byte -> itou(v.toLong())

            is Short -> itou(v.toLong())

            is Int -> itou(v.toLong())

            is Long -> itou(v)

            is UByte -> v.toULong()

            is UShort -> v.toULong()

            is UInt -> v.toULong()

            is ULong -> v

            is Double -> ftou(v)

            is Float -> ftou(v.toDouble())

            is String -> atou(v)

            // order matters (uint,int,float,string,bool)
            is Uinter -> v.uint64()

            is Inter -> itou(v.int64())

            is Floater -> ftou(v.float64())

            is Stringer -> atou(v.string())

            is Booler -> ttou(v.bool())

            else -> 0u
        }

    fun vtoa(v: Any?): String =
        when (v) {
            is Boolean -> ttoa(v)

            is Byte -> itoa(v.toLong())

            is Short -> itoa(v.toLong())

            is Int -> itoa(v.toLong())

            is Long -> itoa(v)

            is UByte -> utoa(v.toULong())

            is UShort -> utoa(v.toULong())

            is UInt -> utoa(v.toULong())

            is ULong -> utoa(v)

            is Double -> ftoa(v)

            is Float -> ftoa(v.toDouble())

            is String -> v

            // order matters (string,int,uint,float,bool)
            is Stringer -> v.string()

            is Inter -> itoa(v.int64())

            is Uinter -> utoa(v.uint64())

            is Floater -> ftoa(v.float64())

            is Booler -> ttoa(v.bool())

            else -> ""
        }

    /** Go `strconv.FormatFloat(f, 'f', -1, 64)` for finite values. */
    fun formatFloat(d: Double): String {
        if (d.isNaN()) return "NaN"
        if (d == Double.POSITIVE_INFINITY) return "+Inf"
        if (d == Double.NEGATIVE_INFINITY) return "-Inf"
        if (d == 0.0) return if (1.0 / d < 0) "-0" else "0"
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return d.toLong().toString()
        var bd = BigDecimal(d.toString())
        // Java's Double.toString renders at least two significant digits; Go uses the
        // shortest representation that round trips, which can be a single digit.
        if (bd.precision() == 2) {
            val one = bd.round(MathContext(1, RoundingMode.HALF_EVEN))
            if (one.toDouble() == d) bd = one
        }
        return bd.stripTrailingZeros().toPlainString()
    }
}

/** The parts of Go's strconv package used by expr and conv. Returns null where Go returns an error. */
internal object GoStrconv {
    private val decimal = Regex("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")
    private val hexFloat = Regex("[+-]?0[xX]([0-9a-fA-F]+\\.?[0-9a-fA-F]*|\\.[0-9a-fA-F]+)[pP][+-]?\\d+")

    /**
     * Go `strconv.ParseFloat(s, 64)`. Out of range values are errors, like in Go. Not supported:
     * underscores between the digits of base-prefixed numbers.
     */
    fun parseFloat(s: String): Double? {
        if (decimal.matches(s) || hexFloat.matches(s)) {
            val d = s.toDouble()
            return if (d.isInfinite()) null else d
        }
        var t = s
        var sign = 1.0
        if (t.isNotEmpty() && (t[0] == '+' || t[0] == '-')) {
            if (t[0] == '-') sign = -1.0
            t = t.substring(1)
            val l = t.lowercase()
            return if (l == "inf" || l == "infinity") sign * Double.POSITIVE_INFINITY else null
        }
        return when (t.lowercase()) {
            "inf", "infinity" -> Double.POSITIVE_INFINITY
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

    /** Go `strconv.ParseUint(s, base, 64)` for base 10 or 16. */
    fun parseUint(
        s: String,
        base: Int,
    ): ULong? {
        if (s.isEmpty()) return null
        for (c in s) {
            val ok = if (base == 16) Character.digit(c, 16) >= 0 && c.code < 128 else c in '0'..'9'
            if (!ok) return null
        }
        return try {
            java.lang.Long
                .parseUnsignedLong(s, base)
                .toULong()
        } catch (_: NumberFormatException) {
            null
        }
    }
}
