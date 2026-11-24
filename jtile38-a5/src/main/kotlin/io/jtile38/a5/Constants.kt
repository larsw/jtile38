// A5
// SPDX-License-Identifier: Apache-2.0
// Copyright (c) A5 contributors
//
// Kotlin port of github.com/a5geo/a5-go (constants.go and Go language helpers).
// Port Copyright jtile38 authors, Apache License 2.0.

package io.jtile38.a5

import io.jtile38.geojson.geo.GoMath
import kotlin.math.PI
import kotlin.math.sqrt

/** Golden ratio. */
val PHI = (1 + sqrt(5.0)) / 2

const val TWO_PI = 2 * PI

// Go folds these constant expressions with exact arithmetic before rounding to float64, which
// can differ in the last bit from evaluating them in doubles, so the exact values are spelled out.

/** 2π/5 (Go constant `2 * math.Pi / 5`). */
val TWO_PI_OVER_5 = Double.fromBits(0x3ff41b2f769cf0e0)

/** π/5. */
val PI_OVER_5 = Double.fromBits(0x3fe41b2f769cf0e0)

/** π/10. */
val PI_OVER_10 = Double.fromBits(0x3fd41b2f769cf0e0)

/** π/180 (Go constant `math.Pi / 180`). */
internal val PI_OVER_180 = Double.fromBits(0x3f91df46a2529d39)

/** 180/π (Go constant `180 / math.Pi`). */
internal val D180_OVER_PI = Double.fromBits(0x404ca5dc1a63c1f8)

/** π/2; halving is exact, so this equals Go's constant. */
internal const val PI_OVER_2 = PI / 2

// Angles between dodecahedron faces.
val DIHEDRAL_ANGLE = 2 * GoMath.atan(PHI) // 116.565°
val INTERHEDRAL_ANGLE = PI - DIHEDRAL_ANGLE // 63.435°
val FACE_EDGE_ANGLE = -0.5 * PI + GoMath.acos(-1 / sqrt(3 - PHI))

// Distances from center of pentagon face.
val DISTANCE_TO_EDGE = (sqrt(5.0) - 1) / 2 // φ - 1
val DISTANCE_TO_VERTEX = 3 - sqrt(5.0) // 2 * (2 - φ)

// Dodecahedron sphere radii (normalized to unit inscribed sphere radius).
const val R_INSCRIBED = 1.0
val R_MIDEDGE = sqrt(3 - PHI)
val R_CIRCUMSCRIBED = sqrt(3.0) * R_MIDEDGE / PHI

/**
 * Longitude offset: angle between Greenwich meridian and the vector between the first two
 * dodecahedron face centers. Chosen so 99.9% of world population falls in the first 8.5 faces
 * along the Hilbert curve.
 */
const val LONGITUDE_OFFSET = 93.0 // degrees

/** Go's math.Round: half away from zero. */
internal fun goRound(x: Double): Double {
    val t = kotlin.math.truncate(x)
    return if (kotlin.math.abs(x - t) >= 0.5) t + kotlin.math.sign(x) else t
}

/**
 * Go's float64 to int conversion on amd64: truncation, with NaN and out of range values
 * becoming math.MinInt64.
 */
internal fun goInt(x: Double): Long =
    if (x.isNaN() || x >= 9.223372036854775807E18 ||
        x < -9.223372036854775808E18
    ) {
        Long.MIN_VALUE
    } else {
        x.toLong()
    }

/**
 * Go's `x << n` for uint64 values, with [n] holding the bits of an unsigned shift count: counts
 * of 64 or more (including "negative" counts, which are huge unsigned values) give 0.
 */
internal fun goShl(
    x: Long,
    n: Long,
): Long = if (n in 0..63) x shl n.toInt() else 0L

/** Go's `x >> n` for uint64 values (see [goShl]). */
internal fun goShr(
    x: Long,
    n: Long,
): Long = if (n in 0..63) x ushr n.toInt() else 0L
