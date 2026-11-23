package io.jtile38.json

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Float formatting compatible with Go's `strconv.FormatFloat(f, 'f', -1, 64)`. */
object JsonFloat {
    fun format(d: Double): String {
        if (d.isNaN()) return "NaN"
        if (d == Double.POSITIVE_INFINITY) return "+Inf"
        if (d == Double.NEGATIVE_INFINITY) return "-Inf"
        if (d == 0.0) return if (1.0 / d < 0) "-0" else "0"
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return d.toLong().toString()
        }
        var bd = BigDecimal(d.toString())
        // Java's Double.toString renders at least two significant digits; Go uses the
        // shortest representation that round trips, which can be a single digit.
        if (bd.precision() == 2) {
            val one = bd.round(MathContext(1, RoundingMode.HALF_EVEN))
            if (one.toDouble() == d) bd = one
        }
        val s = bd.stripTrailingZeros().toPlainString()
        return s
    }

    fun append(
        sb: StringBuilder,
        d: Double,
    ) {
        sb.append(format(d))
    }
}
