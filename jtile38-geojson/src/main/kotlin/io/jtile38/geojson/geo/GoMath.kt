// Port of the Go standard library math package (sin.go, tan.go, asin.go, atan.go, atan2.go,
// trig_reduce.go), Copyright 2009-2017 The Go Authors, BSD-style license (see NOTICE).
// The routines are derived from the Cephes Math Library, Copyright 1984-1992 Stephen L. Moshier.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.geojson.geo

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.withSign

/**
 * Trigonometric functions that return bit-identical results to Go's `math` package on amd64,
 * where these functions are implemented in pure Go. Tile38 distances and bearings depend on them,
 * so using them keeps jtile38's output identical to Tile38's.
 */
object GoMath {
    private const val PI4A = 7.85398125648498535156e-1
    private const val PI4B = 3.77489470793079817668e-8
    private const val PI4C = 2.69515142907905952645e-15
    private const val REDUCE_THRESHOLD = (1 shl 29).toDouble()

    private val SIN =
        doubleArrayOf(
            1.58962301576546568060e-10,
            -2.50507477628578072866e-8,
            2.75573136213857245213e-6,
            -1.98412698295895385996e-4,
            8.33333333332211858878e-3,
            -1.66666666666666307295e-1,
        )
    private val COS =
        doubleArrayOf(
            -1.13585365213876817300e-11,
            2.08757008419747316778e-9,
            -2.75573141792967388112e-7,
            2.48015872888517045348e-5,
            -1.38888888888730564116e-3,
            4.16666666666665929218e-2,
        )
    private val TAN_P = doubleArrayOf(-1.30936939181383777646e4, 1.15351664838587416140e6, -1.79565251976484877988e7)
    private val TAN_Q =
        doubleArrayOf(
            1.00000000000000000000e0,
            1.36812963470692954678e4,
            -1.32089234440210967447e6,
            2.50083801823357915839e7,
            -5.38695755929454629881e7,
        )

    private fun sinPoly(
        z: Double,
        zz: Double,
    ) = z + z * zz * ((((((SIN[0] * zz) + SIN[1]) * zz + SIN[2]) * zz + SIN[3]) * zz + SIN[4]) * zz + SIN[5])

    private fun cosPoly(zz: Double) =
        1.0 - 0.5 * zz + zz * zz * ((((((COS[0] * zz) + COS[1]) * zz + COS[2]) * zz + COS[3]) * zz + COS[4]) * zz + COS[5])

    /** Octant and reduced argument for a non-negative [x]: returns z and stores j in [jOut]. */
    private fun reduce(
        x: Double,
        jOut: LongArray,
        mapOdd: Boolean = true,
        mod8: Boolean = true,
    ): Double {
        if (x >= REDUCE_THRESHOLD) {
            val (j, z) = trigReduce(x)
            jOut[0] = j
            return z
        }
        var j = (x * (4 / Math.PI)).toLong()
        var y = j.toDouble()
        if (mapOdd && (j and 1L) == 1L) {
            j++
            y++
        }
        if (mod8) j = j and 7
        jOut[0] = j
        return ((x - y * PI4A) - y * PI4B) - y * PI4C
    }

    fun sin(xIn: Double): Double {
        if (xIn == 0.0 || xIn.isNaN()) return xIn
        if (xIn.isInfinite()) return Double.NaN
        var x = xIn
        var sign = false
        if (x < 0) {
            x = -x
            sign = true
        }
        val jb = LongArray(1)
        val z = reduce(x, jb)
        var j = jb[0]
        if (j > 3) {
            sign = !sign
            j -= 4
        }
        val zz = z * z
        val y = if (j == 1L || j == 2L) cosPoly(zz) else sinPoly(z, zz)
        return if (sign) -y else y
    }

    fun cos(xIn: Double): Double {
        if (xIn.isNaN() || xIn.isInfinite()) return Double.NaN
        val x = abs(xIn)
        var sign = false
        val jb = LongArray(1)
        val z = reduce(x, jb)
        var j = jb[0]
        if (j > 3) {
            j -= 4
            sign = !sign
        }
        if (j > 1) sign = !sign
        val zz = z * z
        val y = if (j == 1L || j == 2L) sinPoly(z, zz) else cosPoly(zz)
        return if (sign) -y else y
    }

    fun tan(xIn: Double): Double {
        if (xIn == 0.0 || xIn.isNaN()) return xIn
        if (xIn.isInfinite()) return Double.NaN
        var x = xIn
        var sign = false
        if (x < 0) {
            x = -x
            sign = true
        }
        val jb = LongArray(1)
        val z = reduce(x, jb, mod8 = false)
        val j = jb[0]
        val zz = z * z
        var y =
            if (zz > 1e-14) {
                z +
                    z *
                    (
                        zz * (((TAN_P[0] * zz) + TAN_P[1]) * zz + TAN_P[2]) /
                            ((((zz + TAN_Q[1]) * zz + TAN_Q[2]) * zz + TAN_Q[3]) * zz + TAN_Q[4])
                    )
            } else {
                z
            }
        if ((j and 2L) == 2L) y = -1 / y
        return if (sign) -y else y
    }

    private fun xatan(x: Double): Double {
        val p0 = -8.750608600031904122785e-01
        val p1 = -1.615753718733365076637e+01
        val p2 = -7.500855792314704667340e+01
        val p3 = -1.228866684490136173410e+02
        val p4 = -6.485021904942025371773e+01
        val q0 = +2.485846490142306297962e+01
        val q1 = +1.650270098316988542046e+02
        val q2 = +4.328810604912902668951e+02
        val q3 = +4.853903996359136964868e+02
        val q4 = +1.945506571482613964425e+02
        var z = x * x
        z = z * ((((p0 * z + p1) * z + p2) * z + p3) * z + p4) / (((((z + q0) * z + q1) * z + q2) * z + q3) * z + q4)
        return x * z + x
    }

    private fun satan(x: Double): Double {
        val morebits = 6.123233995736765886130e-17
        val tan3pio8 = 2.41421356237309504880
        if (x <= 0.66) return xatan(x)
        if (x > tan3pio8) return Math.PI / 2 - xatan(1 / x) + morebits
        return Math.PI / 4 + xatan((x - 1) / (x + 1)) + 0.5 * morebits
    }

    fun atan(x: Double): Double {
        if (x == 0.0) return x
        return if (x > 0) satan(x) else -satan(-x)
    }

    fun asin(xIn: Double): Double {
        if (xIn == 0.0) return xIn
        var x = xIn
        var sign = false
        if (x < 0) {
            x = -x
            sign = true
        }
        if (x > 1) return Double.NaN
        var temp = sqrt(1 - x * x)
        temp = if (x > 0.7) Math.PI / 2 - satan(temp / x) else satan(x / temp)
        return if (sign) -temp else temp
    }

    fun acos(x: Double): Double = Math.PI / 2 - asin(x)

    fun atan2(
        y: Double,
        x: Double,
    ): Double {
        when {
            y.isNaN() || x.isNaN() -> {
                return Double.NaN
            }

            y == 0.0 -> {
                return if (x >= 0 && !signbit(x)) 0.0.withSign(y) else Math.PI.withSign(y)
            }

            x == 0.0 -> {
                return (Math.PI / 2).withSign(y)
            }

            x.isInfinite() -> {
                if (x > 0) return if (y.isInfinite()) (Math.PI / 4).withSign(y) else 0.0.withSign(y)
                return if (y.isInfinite()) (3 * Math.PI / 4).withSign(y) else Math.PI.withSign(y)
            }

            y.isInfinite() -> {
                return (Math.PI / 2).withSign(y)
            }
        }
        val q = atan(y / x)
        if (x < 0) return if (q <= 0) q + Math.PI else q - Math.PI
        return q
    }

    private fun signbit(x: Double) = (java.lang.Double.doubleToRawLongBits(x) ushr 63) != 0L

    /** Bits of 4/pi (Go `mPi4`). */
    private val M_PI4: LongArray =
        listOf(
            "0000000000000001",
            "45f306dc9c882a53",
            "f84eafa3ea69bb81",
            "b6c52b3278872083",
            "fca2c757bd778ac3",
            "6e48dc74849ba5c0",
            "0c925dd413a32439",
            "fc3bd63962534e7d",
            "d1046bea5d768909",
            "d338e04d68befc82",
            "7323ac7306a673e9",
            "3908bf177bf25076",
            "3ff12fffbc0b301f",
            "de5e2316b414da3e",
            "da6cfd9e4f96136e",
            "9e8c7ecd3cbfd45a",
            "ea4f758fd7cbe2f6",
            "7a0e73ef14a525d4",
            "d7f6bf623f1aba10",
            "ac06608df8f6d757",
        ).map { java.lang.Long.parseUnsignedLong(it, 16) }.toLongArray()

    /** Payne-Hanek reduction for huge arguments (Go `trigReduce`). Returns (j, z). */
    private fun trigReduce(x: Double): Pair<Long, Double> {
        val pi4 = Math.PI / 4
        if (x < pi4) return 0L to x
        val shift = 52
        val mask = 0x7FFL
        val bias = 1023
        var ix = java.lang.Double.doubleToRawLongBits(x)
        val exp = ((ix ushr shift) and mask).toInt() - bias - shift
        ix = ix and (mask shl shift).inv()
        ix = ix or (1L shl shift)
        val digit = (exp + 61) / 64
        val bitshift = (exp + 61) % 64

        fun sh(
            a: Long,
            b: Long,
        ) = if (bitshift == 0) a else (a shl bitshift) or (b ushr (64 - bitshift))
        val z0 = sh(M_PI4[digit], M_PI4[digit + 1])
        val z1 = sh(M_PI4[digit + 1], M_PI4[digit + 2])
        val z2 = sh(M_PI4[digit + 2], M_PI4[digit + 3])
        val z2hi = Math.unsignedMultiplyHigh(z2, ix)
        val z1hi = Math.unsignedMultiplyHigh(z1, ix)
        val z1lo = z1 * ix
        val z0lo = z0 * ix
        val lo = z1lo + z2hi
        val c = if (java.lang.Long.compareUnsigned(lo, z1lo) < 0) 1L else 0L
        var hi = z0lo + z1hi + c
        var j = hi ushr 61
        hi = (hi shl 3) or (lo ushr 61)
        val lz = java.lang.Long.numberOfLeadingZeros(hi)
        val e = (bias - (lz + 1)).toLong()
        hi = (hi shl (lz + 1)) or (lo ushr (64 - (lz + 1)))
        hi = hi ushr (64 - shift)
        hi = hi or (e shl shift)
        var z = java.lang.Double.longBitsToDouble(hi)
        if ((j and 1L) == 1L) {
            j++
            j = j and 7
            z--
        }
        return j to z * pi4
    }
}
