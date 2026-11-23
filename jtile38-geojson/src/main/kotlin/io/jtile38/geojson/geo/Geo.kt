// Port of github.com/tidwall/geojson/geo (MIT License, Copyright (c) 2018 Joshua J Baker)
package io.jtile38.geojson.geo

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Spherical earth calculations. Angles are in degrees, distances in meters. */
object Geo {
    const val EARTH_RADIUS = 6371e3
    private const val RADIANS = PI / 180
    private const val DEGREES = 180 / PI
    private const val PI_R = PI * EARTH_RADIUS
    private const val TWO_PI_R = 2 * PI_R

    fun haversine(
        latA: Double,
        lonA: Double,
        latB: Double,
        lonB: Double,
    ): Double {
        val phi1 = latA * RADIANS
        val lambda1 = lonA * RADIANS
        val phi2 = latB * RADIANS
        val lambda2 = lonB * RADIANS
        val dPhi = phi2 - phi1
        val dLambda = lambda2 - lambda1
        val sdPhi2 = sin(dPhi / 2)
        val sdLambda2 = sin(dLambda / 2)
        return sdPhi2 * sdPhi2 + cos(phi1) * cos(phi2) * sdLambda2 * sdLambda2
    }

    fun normalizeDistance(meters: Double): Double = meters % TWO_PI_R

    /** Converts the given distance to its haversine. */
    fun distanceToHaversine(meters: Double): Double {
        val s = sin(0.5 * meters / EARTH_RADIUS)
        return s * s
    }

    fun distanceFromHaversine(haversine: Double): Double = EARTH_RADIUS * 2 * asin(sqrt(haversine))

    /** Returns the distance in meters between two points. */
    fun distanceTo(
        latA: Double,
        lonA: Double,
        latB: Double,
        lonB: Double,
    ): Double = distanceFromHaversine(haversine(latA, lonA, latB, lonB))

    /** Returns the destination (lat, lon) from a point based on a distance and bearing. */
    fun destinationPoint(
        lat: Double,
        lon: Double,
        meters: Double,
        bearingDegrees: Double,
    ): Pair<Double, Double> {
        val delta = meters / EARTH_RADIUS // angular distance in radians
        val theta = bearingDegrees * RADIANS
        val phi1 = lat * RADIANS
        val lambda1 = lon * RADIANS
        val phi2 = asin(sin(phi1) * cos(delta) + cos(phi1) * sin(delta) * cos(theta))
        var lambda2 = lambda1 + atan2(sin(theta) * sin(delta) * cos(phi1), cos(delta) - sin(phi1) * sin(phi2))
        lambda2 = (lambda2 + 3 * PI) % (2 * PI) - PI // normalise to -180..+180
        return Pair(phi2 * DEGREES, lambda2 * DEGREES)
    }

    /** Returns the (initial) bearing from point A to point B. */
    fun bearingTo(
        latA: Double,
        lonA: Double,
        latB: Double,
        lonB: Double,
    ): Double {
        val phi1 = latA * RADIANS
        val phi2 = latB * RADIANS
        val dLambda = (lonB - lonA) * RADIANS
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        val theta = atan2(y, x)
        return (theta * DEGREES + 360) % 360
    }

    /** Bounding box (minLat, minLon, maxLat, maxLon) surrounding a circle. */
    class LatLonRect(
        @JvmField val minLat: Double,
        @JvmField val minLon: Double,
        @JvmField val maxLat: Double,
        @JvmField val maxLon: Double,
    ) {
        operator fun component1() = minLat

        operator fun component2() = minLon

        operator fun component3() = maxLat

        operator fun component4() = maxLon
    }

    /** Calculates the bounding box surrounding a circle. */
    fun rectFromCenter(
        lat0: Double,
        lon0: Double,
        meters: Double,
    ): LatLonRect {
        val lat = lat0 * RADIANS
        val lon = lon0 * RADIANS
        // angular radius
        val r = meters / EARTH_RADIUS
        var minLat = lat - r
        var maxLat = lat + r
        var minLon: Double
        var maxLon: Double
        val rCos = cos(r)
        if (rCos > 0.999999999999999) {
            // the meters is too miniscule to derive the outer rectangle coordinates.
            minLat = lat
            minLon = lon
            maxLat = lat
            maxLon = lon
        } else {
            val latSin = sin(lat)
            val latCos = cos(lat)
            val latT = asin(latSin / rCos)
            val latTSin = sin(latT)
            val latTCos = cos(latT)
            val lonDelta = acos((rCos - latTSin * latSin) / (latTCos * latCos))
            minLon = lon - lonDelta
            maxLon = lon + lonDelta
        }
        // adjust for NORTH POLE
        if (maxLat > PI / 2) {
            minLon = -PI
            maxLat = PI / 2
            maxLon = PI
        }
        // adjust for SOUTH POLE
        if (minLat < -PI / 2) {
            minLat = -PI / 2
            minLon = -PI
            maxLon = PI
        }
        // adjust for WRAPAROUND
        if (minLon < -PI || maxLon > PI) {
            minLon = -PI
            maxLon = PI
        }
        return LatLonRect(minLat * DEGREES, minLon * DEGREES, maxLat * DEGREES, maxLon * DEGREES)
    }

    fun degsToSemi(degs: Double): Int = goFloatToInt32(degs * (2.0.pow(31) / 180.0))

    fun semiToDegs(semi: Int): Double = semi.toDouble() * (180.0 / 2.0.pow(31))

    private fun goFloatToInt32(f: Double): Int {
        // Go converts out of range floats in an implementation specific way; on amd64 the
        // result of int32(2^31) is math.MinInt32. Use wrapping conversion through Long.
        return f.toLong().toInt()
    }
}
