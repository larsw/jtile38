// A5
// SPDX-License-Identifier: Apache-2.0
// Copyright (c) A5 contributors
//
// Kotlin port of github.com/a5geo/a5-go (tiling.go).
// Port Copyright jtile38 authors, Apache License 2.0.

package io.jtile38.a5

import io.jtile38.geojson.geo.GoMath
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** The quintant index (0-4) for a polar angle. */
internal fun getQuintantPolar(p: DoubleArray): Int = ((goInt(goRound(p[1] / TWO_PI_OVER_5)) + 5) % 5).toInt()

/** A 2x2 rotation matrix for a quintant (column-major). */
private fun quintantRotation(quintant: Int): DoubleArray {
    val angle = TWO_PI_OVER_5 * quintant.toDouble()
    val cos = GoMath.cos(angle)
    val sin = GoMath.sin(angle)
    return doubleArrayOf(cos, sin, -sin, cos)
}

private fun mat2Transform(
    m: DoubleArray,
    v: DoubleArray,
): DoubleArray = doubleArrayOf(m[0] * v[0] + m[2] * v[1], m[1] * v[0] + m[3] * v[1])

/** The triangle vertices (u, v, w) of a quintant, rotated into position. */
internal fun getQuintantVerticesInternal(quintant: Int): Array<DoubleArray> {
    val rot = quintantRotation(quintant)
    return arrayOf(
        mat2Transform(rot, Pentagon.vertU),
        mat2Transform(rot, Pentagon.vertV),
        mat2Transform(rot, Pentagon.vertW),
    )
}

/** The vertices of a pentagon shape; [n] is 3 for quintant triangles and 5 for pentagons. */
class PentagonVertices(
    val verts: Array<DoubleArray>,
    val n: Int,
) {
    /** The centroid of the vertices. */
    fun center(): DoubleArray {
        val fn = n.toDouble()
        var sx = 0.0
        var sy = 0.0
        for (i in 0 until n) {
            sx += verts[i][0]
            sy += verts[i][1]
        }
        return doubleArrayOf(sx / fn, sy / fn)
    }

    /** Tests if a point is inside the pentagon: positive if inside, negative if outside. */
    fun containsPoint(point: DoubleArray): Double {
        var dMax = 1.0
        for (i in 0 until n) {
            var j = i + 1
            if (j >= n) j = 0
            val v1 = verts[i]
            val v2 = verts[j]
            val dx = v1[0] - v2[0]
            val dy = v1[1] - v2[1]
            val px = point[0] - v1[0]
            val py = point[1] - v1[1]
            val cross = dx * py - dy * px
            if (cross < 0) {
                val pLen = sqrt(px * px + py * py)
                dMax = min(dMax, cross / pLen)
            }
        }
        return dMax
    }
}

/** The pentagon vertices of a cell at a Hilbert [resolution], [quintant] and [anchor]. */
fun getPentagonVertices(
    resolution: Int,
    quintant: Int,
    anchor: Anchor,
): PentagonVertices {
    val verts =
        arrayOf(
            Pentagon.vertA.copyOf(),
            Pentagon.vertB.copyOf(),
            Pentagon.vertC.copyOf(),
            Pentagon.vertD.copyOf(),
            Pentagon.vertE.copyOf(),
        )

    fun swap(
        i: Int,
        j: Int,
    ) {
        val t = verts[i]
        verts[i] = verts[j]
        verts[j] = t
    }

    // Check winding and reverse if needed.
    val area = pentagonArea5(verts)
    if (area < 0) {
        swap(1, 4)
        swap(2, 3)
    }

    // Apply transformations based on anchor.
    val flips = anchor.flips
    if (flips[0] == NO && flips[1] == YES) {
        for (v in verts) {
            v[0] = -v[0]
            v[1] = -v[1]
        }
    }

    val k = anchor.k
    val f = flips[0] + flips[1]
    val shouldReflect = ((f == -2 || f == 2) && k > 1) || (f == 0 && (k == 0 || k == 3))
    if (shouldReflect) {
        for (v in verts) v[1] = -v[1]
        // Reverse winding.
        swap(0, 4)
        swap(1, 3)
    }

    val w = Pentagon.vertW
    if (flips[0] == YES && flips[1] == YES) {
        for (v in verts) {
            v[0] = -v[0]
            v[1] = -v[1]
        }
    } else if (flips[0] == YES) {
        for (v in verts) {
            v[0] -= w[0]
            v[1] -= w[1]
        }
    } else if (flips[1] == YES) {
        for (v in verts) {
            v[0] += w[0]
            v[1] += w[1]
        }
    }

    // Position within quintant using basis transform.
    val translation = ijToFace(anchor.offset)
    val scale = 1.0 / 2.0.pow(resolution.toDouble())
    val rot = quintantRotation(quintant)
    for (i in 0 until 5) {
        val v = verts[i]
        v[0] += translation[0]
        v[1] += translation[1]
        v[0] *= scale
        v[1] *= scale
        verts[i] = mat2Transform(rot, v)
    }
    return PentagonVertices(verts, 5)
}

/** The 5 vertices of a dodecahedron face. */
fun getFaceVertices(): PentagonVertices {
    val verts = Array(5) { i -> mat2Transform(quintantRotation(i), Pentagon.vertV) }
    // Reverse for correct winding.
    var t = verts[0]
    verts[0] = verts[4]
    verts[4] = t
    t = verts[1]
    verts[1] = verts[3]
    verts[3] = t
    return PentagonVertices(verts, 5)
}

/** The triangle vertices of a quintant as a [PentagonVertices] with 3 vertices. */
fun getQuintantVertices(quintant: Int): PentagonVertices {
    val vv = getQuintantVerticesInternal(quintant)
    return PentagonVertices(arrayOf(vv[0], vv[1], vv[2], doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0)), 3)
}

private fun pentagonArea5(verts: Array<DoubleArray>): Double {
    var area = 0.0
    for (i in 0 until 5) {
        val j = (i + 1) % 5
        area += (verts[j][0] - verts[i][0]) * (verts[j][1] + verts[i][1])
    }
    return area
}
