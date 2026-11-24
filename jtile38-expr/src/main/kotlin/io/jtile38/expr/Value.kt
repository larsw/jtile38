// Port of github.com/tidwall/expr v0.14.0 (MIT License, Copyright (c) 2022 Joshua J Baker)
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.expr

internal enum class Kind { UNDEF, NULL, BOOL, FLOAT, INT, UINT, STR, FUNC, OBJ, ARR }

/**
 * The result of [Expr.eval] (Go `expr.Value`): undefined, null, a boolean, a number (float64,
 * int64 or uint64), a string, a function name, a custom object or an array.
 *
 * Strings are byte strings (one char per byte), like Go strings.
 */
class Value internal constructor(
    internal val kind: Kind,
    /** Number bits: the double bits, the int64, the uint64 bits, or 1/0 for booleans. */
    internal val bits: Long = 0,
    internal val str: String = "",
    internal val obj: Any? = null,
    internal val arr: List<Value>? = null,
) {
    val isUndefined: Boolean get() = kind == Kind.UNDEF

    val isNull: Boolean get() = kind == Kind.NULL

    val isArray: Boolean get() = kind == Kind.ARR

    internal fun asBool() = bits != 0L

    internal fun asFloat64() = Double.fromBits(bits)

    internal fun asUint64() = bits.toULong()

    /** A boolean representation. */
    fun bool(): Boolean =
        when (kind) {
            Kind.UNDEF, Kind.NULL -> false

            Kind.BOOL -> asBool()

            Kind.FLOAT -> Conv.ftot(asFloat64())

            Kind.INT -> Conv.itot(bits)

            Kind.UINT -> Conv.utot(asUint64())

            Kind.STR -> Conv.atot(str)

            Kind.OBJ -> Conv.vtot(obj)

            // fallback to using a floating point conversion
            else -> Conv.ftot(float64())
        }

    /** A float64 representation. */
    fun float64(): Double =
        when (kind) {
            Kind.NULL -> 0.0

            Kind.BOOL -> Conv.ttof(asBool())

            Kind.FLOAT -> asFloat64()

            Kind.INT -> Conv.itof(bits)

            Kind.UINT -> Conv.utof(asUint64())

            Kind.STR -> Conv.atof(str)

            Kind.OBJ -> Conv.vtof(obj)

            Kind.ARR -> Conv.atof(toString())

            // everything else NaN
            else -> Double.NaN
        }

    /** Same as [float64]. */
    fun number(): Double = float64()

    /** An int64 representation. */
    fun int64(): Long =
        when (kind) {
            Kind.BOOL -> Conv.ttoi(asBool())
            Kind.FLOAT -> Conv.ftoi(asFloat64())
            Kind.INT -> bits
            Kind.UINT -> Conv.utoi(asUint64())
            Kind.STR -> Conv.atoi(str)
            Kind.OBJ -> Conv.vtoi(obj)
            Kind.ARR -> Conv.atoi(toString())
            else -> 0
        }

    /** A uint64 representation. */
    fun uint64(): ULong =
        when (kind) {
            Kind.BOOL -> Conv.ttou(asBool())
            Kind.FLOAT -> Conv.ftou(asFloat64())
            Kind.INT -> Conv.itou(bits)
            Kind.UINT -> asUint64()
            Kind.STR -> Conv.atou(str)
            Kind.OBJ -> Conv.vtou(obj)
            Kind.ARR -> Conv.atou(toString())
            else -> 0u
        }

    /** The string representation (Go `Value.String()`). */
    override fun toString(): String =
        when (kind) {
            Kind.UNDEF -> "undefined"
            Kind.NULL -> "null"
            Kind.BOOL -> Conv.ttoa(asBool())
            Kind.FLOAT -> Conv.ftoa(asFloat64())
            Kind.INT -> Conv.itoa(bits)
            Kind.UINT -> Conv.utoa(asUint64())
            Kind.STR -> str
            Kind.FUNC -> "[Function: $str]"
            Kind.OBJ -> Conv.vtoa(obj)
            Kind.ARR -> arr!!.joinToString(",") { it.toString() }
        }

    /**
     * The native representation (Go `Value.Value()`): Boolean, Double, Long, ULong, String, the
     * custom object, a List of values, or null.
     */
    fun value(): Any? =
        when (kind) {
            Kind.BOOL -> asBool()
            Kind.FLOAT -> asFloat64()
            Kind.INT -> bits
            Kind.UINT -> asUint64()
            Kind.STR -> str
            Kind.OBJ -> obj
            Kind.ARR -> arr
            else -> null
        }

    /** The values of an array, or null for other kinds. */
    fun array(): List<Value>? = if (kind == Kind.ARR) arr else null

    /** The value at index [i] of an array, or undefined. */
    fun at(i: Int): Value {
        if (kind == Kind.ARR) {
            val a = arr!!
            if (i >= 0 && i < a.size) return a[i]
        }
        return UNDEFINED
    }

    /** The length of a string or array, zero for other types. */
    fun len(): Int =
        when (kind) {
            Kind.STR -> str.length
            Kind.ARR -> arr!!.size
            else -> 0
        }

    internal fun funcIdent(): String = if (kind == Kind.FUNC) str else ""

    fun typeOf(): String =
        when (kind) {
            Kind.UNDEF -> "undefined"
            Kind.BOOL -> "boolean"
            Kind.FLOAT, Kind.INT, Kind.UINT -> "number"
            Kind.STR -> "string"
            Kind.FUNC -> "function"
            else -> "object"
        }

    internal fun isnum(): Boolean =
        when (kind) {
            Kind.FLOAT, Kind.INT, Kind.UINT, Kind.BOOL, Kind.NULL, Kind.UNDEF -> true
            else -> false
        }

    companion object {
        @JvmField
        val UNDEFINED = Value(Kind.UNDEF)

        @JvmField
        val NULL = Value(Kind.NULL)

        @JvmStatic
        fun bool(t: Boolean) = Value(Kind.BOOL, if (t) 1 else 0)

        @JvmStatic
        fun float64(x: Double) = Value(Kind.FLOAT, x.toRawBits())

        @JvmStatic
        fun number(x: Double) = float64(x)

        @JvmStatic
        fun int64(x: Long) = Value(Kind.INT, x)

        @JvmStatic
        fun uint64(x: ULong) = Value(Kind.UINT, x.toLong())

        @JvmStatic
        fun string(s: String) = Value(Kind.STR, str = s)

        /** A function value; calls are handled by [Extender.call]. */
        @JvmStatic
        fun function(name: String) = Value(Kind.FUNC, str = name)

        /** A custom user-defined object; operators on it are handled by [Extender.op]. */
        @JvmStatic
        fun obj(o: Any?) = Value(Kind.OBJ, obj = o)

        @JvmStatic
        fun array(values: List<Value>) = Value(Kind.ARR, arr = values)
    }
}
