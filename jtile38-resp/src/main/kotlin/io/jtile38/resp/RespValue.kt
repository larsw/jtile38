// Port of github.com/tidwall/resp (Value), Copyright 2016 Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.resp

import java.io.ByteArrayOutputStream
import java.math.BigDecimal

/**
 * A RESP (REdis Serialization Protocol) value.
 *
 * Strings are "byte strings": each char holds one byte (ISO-8859-1), which
 * mirrors Go's byte-oriented `string` type and keeps values binary safe.
 */
sealed class RespValue {
    /** Serializes this value to RESP wire format. */
    fun marshal(): ByteArray {
        val out = ByteArrayOutputStream()
        writeTo(out)
        return out.toByteArray()
    }

    /** Appends the RESP encoding of this value to [out]. */
    abstract fun writeTo(out: ByteArrayOutputStream)

    /** Same as Go `resp.Value.String()`. */
    abstract override fun toString(): String

    data class SimpleString(
        val value: String,
    ) : RespValue() {
        override fun writeTo(out: ByteArrayOutputStream) = writeLine(out, '+', singleLine(value))

        override fun toString() = singleLine(value)
    }

    data class Error(
        val message: String,
    ) : RespValue() {
        override fun writeTo(out: ByteArrayOutputStream) = writeLine(out, '-', singleLine(message))

        override fun toString() = singleLine(message)
    }

    data class Integer(
        val value: Long,
    ) : RespValue() {
        override fun writeTo(out: ByteArrayOutputStream) = writeLine(out, ':', value.toString())

        override fun toString() = value.toString()
    }

    /** A bulk string; `value == null` encodes the RESP null bulk string `$-1`. */
    data class BulkString(
        val value: String?,
    ) : RespValue() {
        override fun writeTo(out: ByteArrayOutputStream) {
            if (value == null) {
                out.writeLatin1("$-1\r\n")
                return
            }
            out.writeLatin1("$" + value.length + "\r\n")
            out.writeLatin1(value)
            out.writeLatin1("\r\n")
        }

        override fun toString() = value ?: ""
    }

    /** An array; `values == null` encodes the RESP null array `*-1`. */
    data class Array(
        val values: List<RespValue>?,
    ) : RespValue() {
        override fun writeTo(out: ByteArrayOutputStream) {
            if (values == null) {
                out.writeLatin1("*-1\r\n")
                return
            }
            out.writeLatin1("*" + values.size + "\r\n")
            for (v in values) v.writeTo(out)
        }

        /** Go's `fmt.Sprintf("%v", []Value)` formatting, e.g. `[a b [c d]]`. */
        override fun toString() = values?.joinToString(" ", "[", "]") ?: "[]"
    }

    companion object {
        @JvmField
        val OK: RespValue = SimpleString("OK")

        @JvmField
        val NULL: RespValue = BulkString(null)

        @JvmStatic
        fun string(s: String): RespValue = BulkString(s)

        @JvmStatic
        fun simple(s: String): RespValue = SimpleString(s)

        @JvmStatic
        fun error(s: String): RespValue = Error(s)

        @JvmStatic
        fun integer(i: Long): RespValue = Integer(i)

        @JvmStatic
        fun integer(i: Int): RespValue = Integer(i.toLong())

        @JvmStatic
        fun bool(b: Boolean): RespValue = Integer(if (b) 1 else 0)

        @JvmStatic
        fun array(values: List<RespValue>): RespValue = Array(values)

        /** Bulk string with Go `strconv.FormatFloat(f, 'f', -1, 64)` formatting. */
        @JvmStatic
        fun float(f: Double): RespValue = BulkString(formatFloat(f))

        /** Go `strconv.FormatFloat(f, 'f', -1, 64)`. */
        @JvmStatic
        fun formatFloat(f: Double): String {
            if (f.isNaN()) return "NaN"
            if (f.isInfinite()) return if (f > 0) "+Inf" else "-Inf"
            if (f == 0.0) return if (1.0 / f < 0) "-0" else "0"
            val s = BigDecimal(f.toString()).stripTrailingZeros().toPlainString()
            return s
        }

        private fun singleLine(s: String): String {
            if (s.none { it < ' ' }) return s
            val b = StringBuilder(s.length)
            for (c in s) b.append(if (c < ' ') ' ' else c)
            return b.toString()
        }

        private fun writeLine(
            out: ByteArrayOutputStream,
            prefix: Char,
            s: String,
        ) {
            out.write(prefix.code)
            out.writeLatin1(s)
            out.write('\r'.code)
            out.write('\n'.code)
        }
    }
}

internal fun ByteArrayOutputStream.writeLatin1(s: String) {
    for (c in s) write(c.code)
}
