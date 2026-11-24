// A5
// SPDX-License-Identifier: Apache-2.0
// Copyright (c) A5 contributors
//
// Kotlin port of github.com/a5geo/a5-go (hilbert.go).
// Port Copyright jtile38 authors, Apache License 2.0.

package io.jtile38.a5

// Hilbert curve encoding for pentagonal spatial indexing. Maps 2D IJ coordinates to a 1D index S
// that preserves spatial locality. Quaternary digits (0–3) are Ints; S values are unsigned 64-bit
// values held in Longs.

/** Flip: no reflection. */
const val NO = 1

/** Flip: reflection. */
const val YES = -1

/** A position on the Hilbert curve: the last digit [k], an IJ [offset] and two flips. */
class Anchor(
    val k: Int,
    val offset: DoubleArray,
    val flips: IntArray,
)

/** Maps a quaternary digit to a KJ displacement given the flip state. */
private fun quaternaryToKJ(
    n: Int,
    flips: IntArray,
): DoubleArray {
    var p = doubleArrayOf(0.0, 0.0)
    var q = doubleArrayOf(0.0, 0.0)
    when {
        flips[0] == NO && flips[1] == NO -> {
            p = doubleArrayOf(1.0, 0.0)
            q = doubleArrayOf(0.0, 1.0)
        }

        flips[0] == YES && flips[1] == NO -> {
            p = doubleArrayOf(0.0, -1.0)
            q = doubleArrayOf(-1.0, 0.0)
        }

        flips[0] == NO && flips[1] == YES -> {
            p = doubleArrayOf(0.0, 1.0)
            q = doubleArrayOf(1.0, 0.0)
        }

        flips[0] == YES && flips[1] == YES -> {
            p = doubleArrayOf(-1.0, 0.0)
            q = doubleArrayOf(0.0, -1.0)
        }
    }
    return when (n) {
        1 -> p
        2 -> doubleArrayOf(q[0] + p[0], q[1] + p[1])
        3 -> doubleArrayOf(q[0] + 2 * p[0], q[1] + 2 * p[1])
        else -> doubleArrayOf(0.0, 0.0)
    }
}

/** The flip change for each quaternary digit. */
private val QUATERNARY_FLIP_TABLE =
    arrayOf(
        intArrayOf(NO, NO),
        intArrayOf(NO, YES),
        intArrayOf(NO, NO),
        intArrayOf(YES, NO),
    )

// Patterns used to rearrange cells when shifting, ensuring children always overlap with parent cells.
private val PATTERN = intArrayOf(0, 1, 3, 4, 5, 6, 7, 2)
private val PATTERN_FLIPPED = intArrayOf(0, 1, 2, 7, 3, 4, 5, 6)

/** The inverse permutation. */
private fun reversePattern(p: IntArray): IntArray {
    val r = IntArray(8)
    for ((i, v) in p.withIndex()) r[v] = i
    return r
}

private val PATTERN_REVERSED = reversePattern(PATTERN)
private val PATTERN_FLIPPED_REVERSED = reversePattern(PATTERN_FLIPPED)

private fun shiftDigits(
    digits: IntArray,
    i: Int,
    flips: IntArray,
    invertJ: Boolean,
    pat: IntArray,
) {
    if (i <= 0) return
    val parentK = digits[i]
    val childK = digits[i - 1]
    val f = flips[0] + flips[1]
    val needsShift: Boolean
    val first: Boolean
    if (invertJ != (f == 0)) {
        needsShift = parentK == 1 || parentK == 2
        first = parentK == 1
    } else {
        needsShift = parentK < 2
        first = parentK == 0
    }
    if (!needsShift) return
    var src = childK
    if (!first) src += 4
    val dst = pat[src]
    digits[i - 1] = dst % 4
    digits[i] = (parentK + 4 + dst / 4 - src / 4) % 4
}

/** Composes two flip states element-wise. */
private fun multiplyFlips(
    a: IntArray,
    b: IntArray,
): IntArray = intArrayOf(a[0] * b[0], a[1] * b[1])

/** Converts a Hilbert index [s] to an Anchor position. */
fun sToAnchor(
    s: Long,
    resolution: Int,
    orientation: Int,
): Anchor {
    val reverse = orientation == Orientation.VU || orientation == Orientation.WU || orientation == Orientation.VW
    val invertJ = orientation == Orientation.WV || orientation == Orientation.VW
    val flipIJ = orientation == Orientation.WU || orientation == Orientation.UW

    var input = s
    if (reverse) input = goShl(1L, 2L * resolution) - input - 1

    val anchor = sToAnchorInternal(input, resolution, invertJ, flipIJ)
    var offset = anchor.offset
    val flips = anchor.flips
    if (flipIJ) {
        offset = doubleArrayOf(offset[1], offset[0])
        if (flips[0] == YES) {
            offset[0] += -1.0
            offset[1] += 1.0
        }
        if (flips[1] == YES) {
            offset[0] -= -1.0
            offset[1] -= 1.0
        }
    }
    if (invertJ) {
        val i = offset[0]
        val j = offset[1]
        val newJ = goIntShl(resolution).toDouble() - (i + j)
        flips[0] = -flips[0]
        offset = doubleArrayOf(offset[0], newJ)
    }
    return Anchor(anchor.k, offset, flips)
}

/** Go's `int(1) << n` for a non-negative shift count (a negative count panics in Go). */
private fun goIntShl(n: Int): Long {
    require(n >= 0) { "negative shift amount" }
    return if (n >= 64) 0L else 1L shl n
}

private fun sToAnchorInternal(
    s: Long,
    resolution: Int,
    invertJ: Boolean,
    flipIJ: Boolean,
): Anchor {
    val offset = doubleArrayOf(0.0, 0.0)
    var flips = intArrayOf(NO, NO)

    // Extract quaternary digits (LSB first). Go's make([]Quaternary, 0, resolution) panics for a
    // negative resolution, as ArrayList does.
    val list = ArrayList<Int>(resolution)
    var input = s
    while (input != 0L || list.size < resolution) {
        list.add((input and 3L).toInt())
        input = input ushr 2
    }
    val digits = list.toIntArray()

    val pat = if (flipIJ) PATTERN_FLIPPED else PATTERN

    // First pass: shift digits from MSB to LSB.
    for (i in digits.size - 1 downTo 0) {
        shiftDigits(digits, i, flips, invertJ, pat)
        flips = multiplyFlips(flips, QUATERNARY_FLIP_TABLE[digits[i]])
    }

    // Second pass: build offset from MSB to LSB.
    flips = intArrayOf(NO, NO)
    for (i in digits.size - 1 downTo 0) {
        offset[0] *= 2
        offset[1] *= 2
        val child = quaternaryToKJ(digits[i], flips)
        offset[0] += child[0]
        offset[1] += child[1]
        flips = multiplyFlips(flips, QUATERNARY_FLIP_TABLE[digits[i]])
    }

    val k = if (digits.isNotEmpty()) digits[0] else 0
    val ij = kjToIJ(doubleArrayOf(offset[0], offset[1]))
    return Anchor(k, ij, flips)
}

/** Determines the quaternary digit for a scaled IJ offset. */
private fun ijToQuaternary(
    ij: DoubleArray,
    flips: IntArray,
): Int {
    val u = ij[0]
    val v = ij[1]
    var a = u + v
    var b = u
    var c = v
    if (flips[0] == YES) a = -(u + v)
    if (flips[1] == YES) b = -u
    if (flips[0] == YES) c = -v

    val f = flips[0] + flips[1]

    // Only one flip.
    if (f == 0) {
        return if (c < 1) {
            0
        } else if (b > 1) {
            3
        } else if (a > 1) {
            2
        } else {
            1
        }
    }
    // No flips or both.
    return if (a < 1) {
        0
    } else if (b > 1) {
        3
    } else if (c > 1) {
        2
    } else {
        1
    }
}

/** Converts 2D IJ coordinates to a Hilbert curve index S. */
fun ijToS(
    input: DoubleArray,
    resolution: Int,
    orientation: Int,
): Long {
    val reverse = orientation == Orientation.VU || orientation == Orientation.WU || orientation == Orientation.VW
    val invertJ = orientation == Orientation.WV || orientation == Orientation.VW
    val flipIJ = orientation == Orientation.WU || orientation == Orientation.UW

    var ij = input.copyOf()
    if (flipIJ) ij = doubleArrayOf(input[1], input[0])
    if (invertJ) {
        val i = ij[0]
        val j = ij[1]
        ij[1] = goIntShl(resolution).toDouble() - (i + j)
    }

    var s = ijToSInternal(ij, invertJ, flipIJ, resolution)
    if (reverse) s = goShl(1L, 2L * resolution) - s - 1
    return s
}

private fun ijToSInternal(
    input: DoubleArray,
    invertJ: Boolean,
    flipIJ: Boolean,
    resolution: Int,
): Long {
    val numDigits = resolution
    val digits = IntArray(numDigits)

    var flips = intArrayOf(NO, NO)
    val pivot = doubleArrayOf(0.0, 0.0)

    // Process digits from MSB to LSB.
    for (i in numDigits - 1 downTo 0) {
        val rel0 = input[0] - pivot[0]
        val rel1 = input[1] - pivot[1]
        val scale = goIntShl(i).toDouble()
        val scaled = doubleArrayOf(rel0 / scale, rel1 / scale)

        val digit = ijToQuaternary(scaled, flips)
        digits[i] = digit

        val child = quaternaryToKJ(digit, flips)
        val childIJ = kjToIJ(doubleArrayOf(child[0], child[1]))
        pivot[0] += childIJ[0] * scale
        pivot[1] += childIJ[1] * scale

        flips = multiplyFlips(flips, QUATERNARY_FLIP_TABLE[digit])
    }

    val pat = if (flipIJ) PATTERN_FLIPPED_REVERSED else PATTERN_REVERSED

    // Reverse shift pass.
    flips = intArrayOf(NO, NO)
    for (i in digits.indices) {
        flips = multiplyFlips(flips, QUATERNARY_FLIP_TABLE[digits[i]])
        shiftDigits(digits, i, flips, invertJ, pat)
    }

    // Combine digits into output.
    var output = 0L
    for (i in numDigits - 1 downTo 0) output += goShl(digits[i].toLong(), 2L * i)
    return output
}
