// A5
// SPDX-License-Identifier: Apache-2.0
// Copyright (c) A5 contributors
//
// Kotlin port of github.com/a5geo/a5-go (vector.go, coordinates.go, authalic.go, pentagon.go).
// Port Copyright jtile38 authors, Apache License 2.0.

package io.jtile38.a5

import io.jtile38.geojson.geo.GoMath
import kotlin.math.sqrt

// Coordinate systems are DoubleArrays, mirroring a5-go's fixed-size array types:
//   LonLat [lon, lat] in degrees; Spherical [theta, phi] in radians (phi is the colatitude);
//   Cartesian [x, y, z] on the unit sphere; Face, IJ, KJ [x, y]; Polar [rho, gamma];
//   Barycentric [b0, b1, b2].
// Functions return new arrays and never modify their arguments.

// ---- vector.go ----

internal fun vec3Dot(
    a: DoubleArray,
    b: DoubleArray,
): Double = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

internal fun vec3Cross(
    a: DoubleArray,
    b: DoubleArray,
): DoubleArray =
    doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

internal fun vec3Length(v: DoubleArray): Double = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

internal fun vec3Normalize(v: DoubleArray): DoubleArray {
    val l = vec3Length(v)
    if (l == 0.0) return v
    return doubleArrayOf(v[0] / l, v[1] / l, v[2] / l)
}

internal fun vec3Sub(
    a: DoubleArray,
    b: DoubleArray,
): DoubleArray = doubleArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])

internal fun vec3Add(
    a: DoubleArray,
    b: DoubleArray,
): DoubleArray = doubleArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])

internal fun vec3Scale(
    v: DoubleArray,
    s: Double,
): DoubleArray = doubleArrayOf(v[0] * s, v[1] * s, v[2] * s)

internal fun vec3Lerp(
    a: DoubleArray,
    b: DoubleArray,
    t: Double,
): DoubleArray =
    doubleArrayOf(
        a[0] + (b[0] - a[0]) * t,
        a[1] + (b[1] - a[1]) * t,
        a[2] + (b[2] - a[2]) * t,
    )

internal fun vec3Angle(
    a: DoubleArray,
    b: DoubleArray,
): Double {
    var d = vec3Dot(a, b)
    if (d > 1) d = 1.0
    if (d < -1) d = -1.0
    return GoMath.acos(d)
}

internal fun vec3Distance(
    a: DoubleArray,
    b: DoubleArray,
): Double = vec3Length(vec3Sub(a, b))

/** Applies a quaternion rotation (format [x, y, z, w]) to a vector. */
internal fun vec3TransformQuat(
    v: DoubleArray,
    q: DoubleArray,
): DoubleArray {
    val qx = q[0]
    val qy = q[1]
    val qz = q[2]
    val qw = q[3]
    // q * v * q^-1
    val ix = qw * v[0] + qy * v[2] - qz * v[1]
    val iy = qw * v[1] + qz * v[0] - qx * v[2]
    val iz = qw * v[2] + qx * v[1] - qy * v[0]
    val iw = -qx * v[0] - qy * v[1] - qz * v[2]
    return doubleArrayOf(
        ix * qw + iw * (-qx) + iy * (-qz) - iz * (-qy),
        iy * qw + iw * (-qy) + iz * (-qx) - ix * (-qz),
        iz * qw + iw * (-qz) + ix * (-qy) - iy * (-qx),
    )
}

/** A stable difference measure between two unit vectors. */
internal fun vectorDifference(
    a: DoubleArray,
    b: DoubleArray,
): Double {
    val mid = vec3Normalize(vec3Lerp(a, b, 0.5))
    val c = vec3Cross(a, mid)
    val d = vec3Length(c)
    if (d < 1e-8) {
        val ab = vec3Sub(a, b)
        return 0.5 * vec3Length(ab)
    }
    return d
}

/** dot(a, cross(b, c)). */
internal fun tripleProduct(
    a: DoubleArray,
    b: DoubleArray,
    c: DoubleArray,
): Double = vec3Dot(a, vec3Cross(b, c))

/** cross(A,B) in the context of great circle intersection. */
internal fun quadrupleProduct(
    a: DoubleArray,
    b: DoubleArray,
    c: DoubleArray,
    d: DoubleArray,
): DoubleArray {
    val cd = vec3Cross(c, d)
    val tACD = vec3Dot(a, cd)
    val tBCD = vec3Dot(b, cd)
    val sa = vec3Scale(a, tBCD)
    val sb = vec3Scale(b, tACD)
    return vec3Sub(sb, sa)
}

/** Spherical linear interpolation. */
internal fun slerp(
    a: DoubleArray,
    b: DoubleArray,
    t: Double,
): DoubleArray {
    val gamma = vec3Angle(a, b)
    if (gamma < 1e-12) return vec3Lerp(a, b, t)
    val sinG = GoMath.sin(gamma)
    val wA = GoMath.sin((1 - t) * gamma) / sinG
    val wB = GoMath.sin(t * gamma) / sinG
    return vec3Add(vec3Scale(a, wA), vec3Scale(b, wB))
}

// ---- authalic.go ----

// Authalic conversion coefficients from https://arxiv.org/pdf/2212.05818
// Clenshaw summation (order 6) for geodetic ↔ authalic latitude conversion.
// Adaptation from DGGAL project (BSD 3-Clause, Ecere Corporation).

private val GEODETIC_TO_AUTHALIC =
    doubleArrayOf(
        -2.2392098386786394e-03,
        2.1308606513250217e-06,
        -2.5592576864212742e-09,
        3.3701965267802837e-12,
        -4.6675453126112487e-15,
        6.6749287038481596e-18,
    )

private val AUTHALIC_TO_GEODETIC =
    doubleArrayOf(
        2.2392089963541657e-03,
        2.8831978048607556e-06,
        5.0862207399726603e-09,
        1.0201812377816100e-11,
        2.1912872306767718e-14,
        4.9284235482523806e-17,
    )

/** Transforms a latitude using Clenshaw summation. */
private fun applyCoefficients(
    phi: Double,
    c: DoubleArray,
): Double {
    val sinPhi = GoMath.sin(phi)
    val cosPhi = GoMath.cos(phi)
    val x = 2 * (cosPhi - sinPhi) * (cosPhi + sinPhi)
    var u0 = x * c[5] + c[4]
    var u1 = x * u0 + c[3]
    u0 = x * u1 - u0 + c[2]
    u1 = x * u0 - u1 + c[1]
    u0 = x * u1 - u0 + c[0]
    return phi + 2 * sinPhi * cosPhi * u0
}

/** Geodetic latitude to authalic latitude (radians). */
internal fun authalicForward(phi: Double): Double = applyCoefficients(phi, GEODETIC_TO_AUTHALIC)

/** Authalic latitude to geodetic latitude (radians). */
internal fun authalicInverse(phi: Double): Double = applyCoefficients(phi, AUTHALIC_TO_GEODETIC)

// ---- coordinates.go ----

fun degToRad(deg: Double): Double = deg * PI_OVER_180

fun radToDeg(rad: Double): Double = rad * D180_OVER_PI

/** Face coordinates to Polar. */
fun toPolar(xy: DoubleArray): DoubleArray {
    val rho = sqrt(xy[0] * xy[0] + xy[1] * xy[1])
    val gamma = GoMath.atan2(xy[1], xy[0])
    return doubleArrayOf(rho, gamma)
}

/** Polar coordinates to Face. */
fun toFace(p: DoubleArray): DoubleArray = doubleArrayOf(p[0] * GoMath.cos(p[1]), p[0] * GoMath.sin(p[1]))

/** Face coordinates to IJ using the pentagon basis inverse. */
fun faceToIJ(f: DoubleArray): DoubleArray {
    val bi = Pentagon.basisInverse
    return doubleArrayOf(bi[0] * f[0] + bi[2] * f[1], bi[1] * f[0] + bi[3] * f[1])
}

/** IJ coordinates to Face using the pentagon basis. */
fun ijToFace(ij: DoubleArray): DoubleArray {
    val b = Pentagon.basis
    return doubleArrayOf(b[0] * ij[0] + b[2] * ij[1], b[1] * ij[0] + b[3] * ij[1])
}

/** IJ to KJ basis (K = I + J). */
fun ijToKJ(ij: DoubleArray): DoubleArray = doubleArrayOf(ij[0] + ij[1], ij[1])

/** KJ to IJ basis (I = K - J). */
fun kjToIJ(kj: DoubleArray): DoubleArray = doubleArrayOf(kj[0] - kj[1], kj[1])

/** Cartesian to Spherical coordinates. */
fun toSpherical(xyz: DoubleArray): DoubleArray {
    val theta = GoMath.atan2(xyz[1], xyz[0])
    val r = sqrt(xyz[0] * xyz[0] + xyz[1] * xyz[1] + xyz[2] * xyz[2])
    val phi = GoMath.acos(xyz[2] / r)
    return doubleArrayOf(theta, phi)
}

/** Spherical to Cartesian coordinates on the unit sphere. */
fun toCartesian(s: DoubleArray): DoubleArray {
    val sinPhi = GoMath.sin(s[1])
    return doubleArrayOf(sinPhi * GoMath.cos(s[0]), sinPhi * GoMath.sin(s[0]), GoMath.cos(s[1]))
}

/** Geographic LonLat to Spherical, applying the longitude offset and authalic latitude. */
fun fromLonLat(ll: DoubleArray): DoubleArray {
    val theta = degToRad(ll[0] + LONGITUDE_OFFSET)
    val geodeticLat = degToRad(ll[1])
    val authalicLat = authalicForward(geodeticLat)
    val phi = PI_OVER_2 - authalicLat
    return doubleArrayOf(theta, phi)
}

/** Spherical coordinates to geographic LonLat. */
fun toLonLat(s: DoubleArray): DoubleArray {
    var longitude = radToDeg(s[0]) - LONGITUDE_OFFSET
    // Normalize to [-180, 180]. Kotlin's % on doubles is the exact remainder, like Go's math.Mod.
    longitude = (longitude + 540) % 360 - 180
    val authalicLat = PI_OVER_2 - s[1]
    val geodeticLat = authalicInverse(authalicLat)
    val latitude = radToDeg(geodeticLat)
    return doubleArrayOf(longitude, latitude)
}

/** Barycentric coordinates of a point within the triangle p1, p2, p3. */
fun faceToBarycentric(
    p: DoubleArray,
    p1: DoubleArray,
    p2: DoubleArray,
    p3: DoubleArray,
): DoubleArray {
    val d310 = p1[0] - p3[0]
    val d311 = p1[1] - p3[1]
    val d230 = p3[0] - p2[0]
    val d231 = p3[1] - p2[1]
    val d3p0 = p[0] - p3[0]
    val d3p1 = p[1] - p3[1]
    val det = d230 * d311 - d231 * d310
    val b0 = (d230 * d3p1 - d231 * d3p0) / det
    val b1 = (d310 * d3p1 - d311 * d3p0) / det
    val b2 = 1 - (b0 + b1)
    return doubleArrayOf(b0, b1, b2)
}

/** Barycentric coordinates back to Face. */
fun barycentricToFace(
    b: DoubleArray,
    p1: DoubleArray,
    p2: DoubleArray,
    p3: DoubleArray,
): DoubleArray =
    doubleArrayOf(
        b[0] * p1[0] + b[1] * p2[0] + b[2] * p3[0],
        b[0] * p1[1] + b[1] * p2[1] + b[2] * p3[1],
    )

// ---- pentagon.go ----

/** Pentagon vertices and lattice basis vectors (pentagon.go init). */
internal object Pentagon {
    // Pentagon vertices in Face coordinates (after scaling and rotation).
    val vertA: DoubleArray
    val vertB: DoubleArray
    val vertC: DoubleArray
    val vertD: DoubleArray
    val vertE: DoubleArray

    // Lattice basis vectors.
    val vertU: DoubleArray
    val vertV: DoubleArray
    val vertW: DoubleArray

    /** 2x2 matrices in gl-matrix column-major order: [m00, m01, m10, m11]. */
    val basis: DoubleArray
    val basisInverse: DoubleArray

    init {
        // Raw pentagon vertices (before scaling/rotation).
        vertA = doubleArrayOf(0.0, 0.0)
        vertB = doubleArrayOf(0.0, 1.0)
        vertC = doubleArrayOf(0.7885966681787006, 1.6149108024237764)
        vertD = doubleArrayOf(1.6171013659387945, 1.054928690397459)
        vertE = doubleArrayOf(GoMath.cos(PI_OVER_10), GoMath.sin(PI_OVER_10))

        // Distance to edge midpoint.
        val cLen = sqrt(vertC[0] * vertC[0] + vertC[1] * vertC[1])
        val edgeMidpointD = 2 * cLen * GoMath.cos(PI_OVER_5)

        // Rotation to align lattice growth direction AC with x-axis.
        val basisRotation = PI_OVER_5 - GoMath.atan2(vertC[1], vertC[0])

        // Scale to match unit sphere.
        val scale = 2 * DISTANCE_TO_EDGE / edgeMidpointD

        // Apply scale and rotation to all vertices.
        for (v in listOf(vertA, vertB, vertC, vertD, vertE)) {
            v[0] *= scale
            v[1] *= scale
            val x = v[0] * GoMath.cos(basisRotation) - v[1] * GoMath.sin(basisRotation)
            val y = v[0] * GoMath.sin(basisRotation) + v[1] * GoMath.cos(basisRotation)
            v[0] = x
            v[1] = y
        }

        // Triangle vertices (u, v, w) for lattice basis.
        val bisectorAngle = GoMath.atan2(vertC[1], vertC[0]) - PI_OVER_5
        val l = DISTANCE_TO_EDGE / GoMath.cos(PI_OVER_5)

        val vAngle = bisectorAngle + PI_OVER_5
        vertV = doubleArrayOf(l * GoMath.cos(vAngle), l * GoMath.sin(vAngle))

        val wAngle = bisectorAngle - PI_OVER_5
        vertW = doubleArrayOf(l * GoMath.cos(wAngle), l * GoMath.sin(wAngle))

        vertU = doubleArrayOf(0.0, 0.0)

        // BASIS matrix (column-major): [v[0], v[1], w[0], w[1]]
        basis = doubleArrayOf(vertV[0], vertV[1], vertW[0], vertW[1])

        // Invert 2x2 matrix: for [a,b,c,d], inverse = (1/det) * [d,-b,-c,a]
        val det = basis[0] * basis[3] - basis[2] * basis[1]
        val invDet = 1.0 / det
        basisInverse = doubleArrayOf(basis[3] * invDet, -basis[1] * invDet, -basis[2] * invDet, basis[0] * invDet)
    }
}
