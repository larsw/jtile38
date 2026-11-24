// A5
// SPDX-License-Identifier: Apache-2.0
// Copyright (c) A5 contributors
//
// Kotlin port of github.com/a5geo/a5-go (origin.go).
// Port Copyright jtile38 authors, Apache License 2.0.

package io.jtile38.a5

import io.jtile38.geojson.geo.GoMath
import kotlin.math.PI
import kotlin.math.sqrt

/** Which corners the Hilbert curve starts and ends at (a5-go Orientation). */
object Orientation {
    const val UV = 0
    const val VU = 1
    const val UW = 2
    const val WU = 3
    const val VW = 4
    const val WV = 5
}

/** One of the 12 dodecahedron face centers. */
class Origin(
    val id: Int,
    /** Spherical [theta, phi]. */
    val axis: DoubleArray,
    /** Quaternion [x, y, z, w]. */
    val quat: DoubleArray,
    val inverseQuat: DoubleArray,
    val angle: Double,
    /** Quintant orientation layout (5 [Orientation] values). */
    val orientation: IntArray,
    val firstQuintant: Int,
)

/** The internal representation of a cell before serialization; [s] is an unsigned 64-bit value. */
class A5Cell(
    val origin: Origin,
    val segment: Int,
    val s: Long,
    val resolution: Int,
)

// Quintant orientation layouts.
private val CLOCKWISE_FAN = intArrayOf(Orientation.VU, Orientation.UW, Orientation.VW, Orientation.VW, Orientation.VW)
private val CLOCKWISE_STEP = intArrayOf(Orientation.WU, Orientation.UW, Orientation.VW, Orientation.VU, Orientation.UW)
private val COUNTER_STEP = intArrayOf(Orientation.WU, Orientation.UV, Orientation.WV, Orientation.WU, Orientation.UW)
private val COUNTER_JUMP = intArrayOf(Orientation.VU, Orientation.UV, Orientation.WV, Orientation.WU, Orientation.UW)

private val QUINTANT_ORIENTATIONS =
    arrayOf(
        CLOCKWISE_FAN, // 0 Arctic
        COUNTER_JUMP, // 1 North America
        COUNTER_STEP, // 2 South America
        CLOCKWISE_STEP, // 3 North Atlantic & Western Europe & Africa
        COUNTER_STEP, // 4 South Atlantic & Africa
        COUNTER_JUMP, // 5 Europe, Middle East & Central Africa
        COUNTER_STEP, // 6 Indian Ocean
        CLOCKWISE_STEP, // 7 Asia
        CLOCKWISE_STEP, // 8 Australia
        CLOCKWISE_STEP, // 9 North Pacific
        COUNTER_JUMP, // 10 South Pacific
        COUNTER_JUMP, // 11 Antarctic
    )

private val QUINTANT_FIRST = intArrayOf(4, 2, 3, 2, 0, 4, 3, 2, 2, 0, 3, 0)

/** Maps generation order to Hilbert traversal order. */
private val ORIGIN_ORDER = intArrayOf(0, 1, 2, 4, 3, 5, 7, 8, 6, 11, 10, 9)

/** The 12 dodecahedron face centers, ordered for Hilbert traversal. */
val ORIGINS: List<Origin> = buildOrigins()

private class GenOrigin(
    val axis: DoubleArray,
    val angle: Double,
    val quat: DoubleArray,
    val genId: Int,
)

private fun buildOrigins(): List<Origin> {
    val sqrt5 = sqrt(5.0)
    val invSqrt5 = sqrt(0.2)

    // Half-angle sin/cos for rotation from pole to first ring.
    val sinAlpha = sqrt((1 - invSqrt5) / 2)
    val cosAlpha = sqrt((1 + invSqrt5) / 2)

    // Derived constants for face center projections.
    val bConst = sqrt((2.5 - sqrt5) / 10) // cos72 * sinAlpha
    val dConst = sqrt((1 + invSqrt5) / 8) // cos36 * sinAlpha
    val eConst = sqrt((1 - invSqrt5) / 8) // cos72 * cosAlpha
    val fConst = sqrt((3 - sqrt5) / 8) // sin36 * sinAlpha
    val gConst = sqrt((3 + sqrt5) / 8) // sin72 * cosAlpha
    val cConst = sqrt((2.5 + sqrt5) / 10) // cos36 * cosAlpha

    // Face centers projected onto z=0 plane, normalized.
    val faceCenters =
        arrayOf(
            doubleArrayOf(0.0, 0.0), // North pole
            doubleArrayOf(sinAlpha, 0.0), // Ring 1
            doubleArrayOf(bConst, 0.5), // Ring 1
            doubleArrayOf(-dConst, fConst), // Ring 1
            doubleArrayOf(-dConst, -fConst), // Ring 1
            doubleArrayOf(bConst, -0.5), // Ring 1
            doubleArrayOf(-cosAlpha, 0.0), // Ring 2
            doubleArrayOf(-eConst, -gConst), // Ring 2
            doubleArrayOf(cConst, -0.5), // Ring 2
            doubleArrayOf(cConst, 0.5), // Ring 2
            doubleArrayOf(-eConst, gConst), // Ring 2
            doubleArrayOf(0.0, 0.0), // South pole
        )

    // Axes of rotation: cross product with z-axis = [-y, x].
    val axes = Array(12) { i -> doubleArrayOf(-faceCenters[i][1], faceCenters[i][0]) }

    // Quaternions: [axis_x, axis_y, 0, cos_half_angle].
    val quaternions = Array(12) { DoubleArray(4) }
    quaternions[0] = doubleArrayOf(0.0, 0.0, 0.0, 1.0) // North pole: identity
    quaternions[11] = doubleArrayOf(0.0, -1.0, 0.0, 0.0) // South pole: 180° rotation
    for (i in 1..5) quaternions[i] = doubleArrayOf(axes[i][0], axes[i][1], 0.0, cosAlpha)
    for (i in 6..10) quaternions[i] = doubleArrayOf(axes[i][0], axes[i][1], 0.0, sinAlpha)

    // Generate origins in natural order (0-11).
    val generated = ArrayList<GenOrigin>()
    // North pole.
    generated.add(GenOrigin(doubleArrayOf(0.0, 0.0), 0.0, quaternions[0], 0))
    // Middle band.
    for (i in 0 until 5) {
        val alpha = i.toDouble() * TWO_PI_OVER_5
        val alpha2 = alpha + PI_OVER_5
        generated.add(GenOrigin(doubleArrayOf(alpha, INTERHEDRAL_ANGLE), PI_OVER_5, quaternions[i + 1], generated.size))
        generated.add(GenOrigin(doubleArrayOf(alpha2, PI - INTERHEDRAL_ANGLE), PI_OVER_5, quaternions[(i + 3) % 5 + 6], generated.size))
    }
    // South pole.
    generated.add(GenOrigin(doubleArrayOf(0.0, PI), 0.0, quaternions[11], generated.size))

    // Reorder by ORIGIN_ORDER (Hilbert traversal order) and assign final IDs.
    return ORIGIN_ORDER.mapIndexed { newId, genId ->
        val g = generated[genId]
        // Conjugate quaternion for inverse.
        val inv = doubleArrayOf(-g.quat[0], -g.quat[1], -g.quat[2], g.quat[3])
        Origin(newId, g.axis, g.quat, inv, g.angle, QUINTANT_ORIENTATIONS[genId], QUINTANT_FIRST[genId])
    }
}

/** True if the orientation layout is clockwise. */
private fun isClockwise(layout: IntArray): Boolean = layout.contentEquals(CLOCKWISE_FAN) || layout.contentEquals(CLOCKWISE_STEP)

/** Converts a quintant index to a segment index and orientation, returned as (segment, orientation). */
fun quintantToSegment(
    quintant: Int,
    origin: Origin,
): Pair<Int, Int> {
    val layout = origin.orientation
    val step = if (isClockwise(layout)) -1 else 1
    val delta = (quintant - origin.firstQuintant + 5) % 5
    val faceRelativeQuintant = (step * delta + 5) % 5
    val orientation = layout[faceRelativeQuintant]
    val segment = (origin.firstQuintant + faceRelativeQuintant) % 5
    return segment to orientation
}

/** Converts a segment index to a quintant index and orientation, returned as (quintant, orientation). */
fun segmentToQuintant(
    segment: Int,
    origin: Origin,
): Pair<Int, Int> {
    val layout = origin.orientation
    val step = if (isClockwise(layout)) -1 else 1
    val faceRelativeQuintant = (segment - origin.firstQuintant + 5) % 5
    val orientation = layout[faceRelativeQuintant]
    val quintant = (origin.firstQuintant + step * faceRelativeQuintant + 5) % 5
    return quintant to orientation
}

/** The closest dodecahedron face to a spherical point. */
fun findNearestOrigin(point: DoubleArray): Origin {
    var minDist = Double.POSITIVE_INFINITY
    var nearest = ORIGINS[0]
    for (o in ORIGINS) {
        val d = haversine(point, o.axis)
        if (d < minDist) {
            minDist = d
            nearest = o
        }
    }
    return nearest
}

/** Checks if a given origin is the nearest to a point. */
fun isNearestOrigin(
    point: DoubleArray,
    origin: Origin,
): Boolean = haversine(point, origin.axis) > 0.49999999

/** A modified haversine distance metric between two spherical points. */
fun haversine(
    point: DoubleArray,
    axis: DoubleArray,
): Double {
    val dtheta = axis[0] - point[0]
    val dphi = axis[1] - point[1]
    val a1 = GoMath.sin(dphi / 2)
    val a2 = GoMath.sin(dtheta / 2)
    return a1 * a1 + a2 * a2 * GoMath.sin(point[1]) * GoMath.sin(axis[1])
}
