// A5
// SPDX-License-Identifier: Apache-2.0
// Copyright (c) A5 contributors
//
// Kotlin port of github.com/a5geo/a5-go (projection.go): Gnomonic, Polyhedral (IVEA) and
// Dodecahedron projections. Polyhedral projection adapted from DGGAL project (BSD 3-Clause,
// Ecere Corporation).
// Port Copyright jtile38 authors, Apache License 2.0.

package io.jtile38.a5

import io.jtile38.geojson.geo.GoMath
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// ---- Gnomonic Projection ----

/** Projects Spherical to Polar via gnomonic projection. */
internal fun gnomonicForward(s: DoubleArray): DoubleArray = doubleArrayOf(GoMath.tan(s[1]), s[0])

/** Unprojects Polar to Spherical via gnomonic projection. */
internal fun gnomonicInverse(p: DoubleArray): DoubleArray = doubleArrayOf(p[1], GoMath.atan(p[0]))

// ---- Spherical Triangle Area ----

internal fun sphericalTriangleArea(
    v1: DoubleArray,
    v2: DoubleArray,
    v3: DoubleArray,
): Double {
    val midA = vec3Normalize(vec3Lerp(v2, v3, 0.5))
    val midB = vec3Normalize(vec3Lerp(v3, v1, 0.5))
    val midC = vec3Normalize(vec3Lerp(v1, v2, 0.5))
    val s = tripleProduct(midA, midB, midC)
    val clamped = max(-1.0, min(1.0, s))
    if (abs(clamped) < 1e-8) return 2 * clamped
    return GoMath.asin(clamped) * 2
}

// ---- Polyhedral Projection (IVEA) ----

/** Projects a Cartesian point to Face coordinates using a spherical/face triangle pair. */
internal fun polyhedralForward(
    v: DoubleArray,
    st: Array<DoubleArray>,
    ft: Array<DoubleArray>,
): DoubleArray {
    val a = st[0]
    val b = st[1]
    val c = st[2]

    // Z = normalize(v - A), used for great circle intersection.
    val z = vec3Normalize(vec3Sub(v, a))
    val p = vec3Normalize(quadrupleProduct(a, z, b, c))

    val h = vectorDifference(a, v) / vectorDifference(a, p)
    val areaABC = sphericalTriangleArea(a, b, c)
    val scaledArea = h / areaABC

    val areaAPC = sphericalTriangleArea(a, p, c)
    val areaABP = sphericalTriangleArea(a, b, p)

    val bary = doubleArrayOf(1 - h, scaledArea * areaAPC, scaledArea * areaABP)
    return barycentricToFace(bary, ft[0], ft[1], ft[2])
}

/** acos(1 - 2*x*x) without precision loss for small x. */
private fun safeAcos(x: Double): Double {
    if (x < 1e-3) return 2 * x + x * x * x / 3
    return GoMath.acos(1 - 2 * x * x)
}

/** Unprojects Face coordinates to Cartesian using a spherical/face triangle pair. */
internal fun polyhedralInverse(
    facePoint: DoubleArray,
    ft: Array<DoubleArray>,
    st: Array<DoubleArray>,
): DoubleArray {
    val a = st[0]
    val b = st[1]
    val c = st[2]

    val bary = faceToBarycentric(facePoint, ft[0], ft[1], ft[2])

    val threshold = 1 - 1e-14
    if (bary[0] > threshold) return a
    if (bary[1] > threshold) return b
    if (bary[2] > threshold) return c

    val c1 = vec3Cross(b, c)
    val areaABC = sphericalTriangleArea(a, b, c)
    val h = 1 - bary[0]
    val r = bary[2] / h
    val alpha = r * areaABC
    val s = GoMath.sin(alpha)
    val halfC = GoMath.sin(alpha / 2)
    val cc = 2 * halfC * halfC

    val c01 = vec3Dot(a, b)
    val c12 = vec3Dot(b, c)
    val c20 = vec3Dot(c, a)
    val s12 = vec3Length(c1)

    val vv = vec3Dot(a, c1)
    val f = s * vv + cc * (c01 * c12 - c20)
    val g = cc * s12 * (1 + c01)
    val q = (2 / GoMath.acos(c12)) * GoMath.atan2(g, f)
    val pp = slerp(b, c, q)
    val k = vectorDifference(a, pp)
    val t = safeAcos(h * k) / safeAcos(k)
    return slerp(a, pp, t)
}

// ---- CRS (Coordinate Reference System) ----

/** The 62 reference vertices of the dodecahedron. */
private val CRS_VERTICES: List<DoubleArray> = buildCrs()

private fun buildCrs(): List<DoubleArray> {
    val crs = ArrayList<DoubleArray>(62)

    fun addCrsVertex(v: DoubleArray) {
        val n = vec3Normalize(v)
        for (existing in crs) {
            if (vec3Distance(n, existing) < 1e-5) return
        }
        crs.add(n)
    }

    // 12 face centers.
    for (o in ORIGINS) addCrsVertex(toCartesian(o.axis))

    // 20 vertices.
    val phiVertex = GoMath.atan(DISTANCE_TO_VERTEX)
    for (o in ORIGINS) {
        for (j in 0 until 5) {
            val theta = (2 * j + 1).toDouble() * PI / 5
            var v = toCartesian(doubleArrayOf(theta + o.angle, phiVertex))
            v = vec3TransformQuat(v, o.quat)
            addCrsVertex(v)
        }
    }

    // 30 edge midpoints.
    val phiMidpoint = GoMath.atan(DISTANCE_TO_EDGE)
    for (o in ORIGINS) {
        for (j in 0 until 5) {
            val theta = (2 * j).toDouble() * PI / 5
            var m = toCartesian(doubleArrayOf(theta + o.angle, phiMidpoint))
            m = vec3TransformQuat(m, o.quat)
            addCrsVertex(m)
        }
    }
    return crs
}

private fun getCrsVertex(point: DoubleArray): DoubleArray {
    for (v in CRS_VERTICES) {
        if (vec3Distance(point, v) < 1e-5) return v
    }
    // Should not happen with correct geometry.
    return point
}

// ---- Dodecahedron Projection ----

/** Projects between spherical and face coordinates, caching its triangles. */
private object DodecahedronProjection {
    private val faceTriangles = ConcurrentHashMap<Int, Array<DoubleArray>>()
    private val sphericalTriangles = ConcurrentHashMap<Int, Array<DoubleArray>>()

    private fun normalizeGamma(gamma: Double): Double {
        val segment = gamma / TWO_PI_OVER_5
        val sCenter = goRound(segment)
        return (segment - sCenter) * TWO_PI_OVER_5
    }

    fun getFaceTriangleIndex(gamma: Double): Int = ((goInt(floor(gamma / PI_OVER_5)) + 10) % 10).toInt()

    fun shouldReflect(p: DoubleArray): Boolean {
        val normalized = doubleArrayOf(p[0], normalizeGamma(p[1]))
        val faceCoord = toFace(normalized)
        return faceCoord[0] > DISTANCE_TO_EDGE
    }

    fun getFaceTriangle(
        idx: Int,
        reflected: Boolean,
        squashed: Boolean,
    ): Array<DoubleArray> {
        var key = idx
        if (reflected) key += if (squashed) 20 else 10
        faceTriangles[key]?.let { return it }
        val ft = if (reflected) reflectedFaceTriangle(idx, squashed) else baseFaceTriangle(idx)
        faceTriangles[key] = ft
        return ft
    }

    private fun baseFaceTriangle(idx: Int): Array<DoubleArray> {
        val quintant = (idx + 1) / 2 % 5
        val verts = getQuintantVerticesInternal(quintant)
        val vCenter = verts[0]
        val vCorner1 = verts[1]
        val vCorner2 = verts[2]
        val vMid = doubleArrayOf((vCorner1[0] + vCorner2[0]) * 0.5, (vCorner1[1] + vCorner2[1]) * 0.5)
        val even = idx % 2 == 0
        return if (even) arrayOf(vCenter, vMid, vCorner1) else arrayOf(vCenter, vCorner2, vMid)
    }

    private fun reflectedFaceTriangle(
        idx: Int,
        squashed: Boolean,
    ): Array<DoubleArray> {
        val base = baseFaceTriangle(idx)
        val a = doubleArrayOf(-base[0][0], -base[0][1]) // negate center
        val b = base[1]
        val c = base[2]
        val even = idx % 2 == 0
        val midpoint = if (even) b else c
        val factor = if (squashed) 1 + 1 / GoMath.cos(INTERHEDRAL_ANGLE) else 2.0
        a[0] += midpoint[0] * factor
        a[1] += midpoint[1] * factor
        return arrayOf(a, c, b)
    }

    fun getSphericalTriangle(
        idx: Int,
        originId: Int,
        reflected: Boolean,
    ): Array<DoubleArray> {
        var key = 10 * originId + idx
        if (reflected) key += 120
        sphericalTriangles[key]?.let { return it }
        val origin = ORIGINS[originId]
        val ft = getFaceTriangle(idx, reflected, true)
        val st =
            Array(3) { i ->
                val p = toPolar(ft[i])
                val rotated = doubleArrayOf(p[0], p[1] + origin.angle)
                var cart = toCartesian(gnomonicInverse(rotated))
                cart = vec3TransformQuat(cart, origin.quat)
                getCrsVertex(cart)
            }
        sphericalTriangles[key] = st
        return st
    }
}

/** Projects Spherical coordinates to Face coordinates of the origin [originId]. */
fun dodecForward(
    spherical: DoubleArray,
    originId: Int,
): DoubleArray {
    val origin = ORIGINS[originId]
    val cart = toCartesian(spherical)
    val rotated = vec3TransformQuat(cart, origin.inverseQuat)
    val projSpherical = toSpherical(rotated)
    val polar = gnomonicForward(projSpherical)
    polar[1] -= origin.angle
    val idx = DodecahedronProjection.getFaceTriangleIndex(polar[1])
    val reflected = DodecahedronProjection.shouldReflect(polar)
    val ft = DodecahedronProjection.getFaceTriangle(idx, reflected, false)
    val st = DodecahedronProjection.getSphericalTriangle(idx, originId, reflected)
    return polyhedralForward(cart, st, ft)
}

/** Unprojects Face coordinates of the origin [originId] to Spherical coordinates. */
fun dodecInverse(
    face: DoubleArray,
    originId: Int,
): DoubleArray {
    ORIGINS[originId] // bounds check, like Go's `_ = &Origins[originID]`
    val polar = toPolar(face)
    val idx = DodecahedronProjection.getFaceTriangleIndex(polar[1])
    val reflected = DodecahedronProjection.shouldReflect(polar)
    val ft = DodecahedronProjection.getFaceTriangle(idx, reflected, false)
    val st = DodecahedronProjection.getSphericalTriangle(idx, originId, reflected)
    val cart = polyhedralInverse(face, ft, st)
    return toSpherical(cart)
}
