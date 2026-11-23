package io.jtile38.geojson.geo

import kotlin.test.Test
import kotlin.test.assertEquals

/** Compares [GoMath] bit for bit with Go's math package (values in go-math.txt were produced by Go). */
class GoMathTest {
    private fun d(hex: String) = java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong(hex, 16))

    @Test
    fun matchesGo() {
        val lines =
            GoMathTest::class.java
                .getResourceAsStream("/go-math.txt")!!
                .bufferedReader()
                .readLines()
        var mismatches = 0
        for (line in lines) {
            val p = line.split(" ")
            val (fn, expected) =
                if (p[0] == "atan2") {
                    GoMath.atan2(d(p[1]), d(p[2])) to d(p[3])
                } else {
                    val x = d(p[1])
                    val r =
                        when (p[0]) {
                            "sin" -> GoMath.sin(x)
                            "cos" -> GoMath.cos(x)
                            "tan" -> GoMath.tan(x)
                            "atan" -> GoMath.atan(x)
                            "asin" -> GoMath.asin(x)
                            "acos" -> GoMath.acos(x)
                            else -> error(p[0])
                        }
                    r to d(p[2])
                }
            if (java.lang.Double.doubleToRawLongBits(fn) != java.lang.Double.doubleToRawLongBits(expected)) {
                if (mismatches++ < 10) println("mismatch: $line -> ${java.lang.Long.toHexString(java.lang.Double.doubleToRawLongBits(fn))}")
            }
        }
        assertEquals(0, mismatches, "of ${lines.size} values")
    }
}
