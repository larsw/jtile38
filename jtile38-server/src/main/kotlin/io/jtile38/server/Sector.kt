// Port of github.com/iwpnd/sectr (sectr.go), Copyright (c) 2021 Benjamin Ramser, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.server

import io.jtile38.geojson.geo.GoMath

/** Builds circular sector polygons as GeoJSON, like `sectr.NewSector(...).JSON()`. */
internal object Sector {
    private const val EARTH_RADIUS = 6371008.8
    private const val STEPS = 64

    private fun rad(deg: Double) = deg * Math.PI / 180

    private fun deg(rad: Double) = rad * 180 / Math.PI

    /** Go `math.Round`: half away from zero. */
    private fun goRound(x: Double): Double = if (x < 0) -Math.round(-x).toDouble() else Math.round(x).toDouble()

    /** Destination (lon, lat) from [lon]/[lat] after [distance] meters on [bearing], rounded to 1e-8 degrees. */
    private fun terminal(
        lon: Double,
        lat: Double,
        distance: Double,
        bearing: Double,
    ): DoubleArray {
        val phi1 = rad(lat)
        val lambda1 = rad(lon)
        val b = rad(bearing)
        val d = distance / EARTH_RADIUS
        val phi2 = GoMath.asin(GoMath.sin(phi1) * GoMath.cos(d) + GoMath.cos(phi1) * GoMath.sin(d) * GoMath.cos(b))
        val lambda2 =
            lambda1 + GoMath.atan2(GoMath.sin(b) * GoMath.sin(d) * GoMath.cos(phi1), GoMath.cos(d) - GoMath.sin(phi1) * GoMath.sin(phi2))
        return doubleArrayOf(goRound(deg(lambda2) * 100000000) / 100000000, goRound(deg(phi2) * 100000000) / 100000000)
    }

    private fun bearingToAngle(bearing: Double): Double {
        val angle = bearing % 360
        return if (angle < 0) angle + 360 else angle
    }

    /** The sector polygon as a GeoJSON string. */
    fun json(
        lat: Double,
        lon: Double,
        radius: Double,
        bearing1: Double,
        bearing2: Double,
    ): String {
        val pts = ArrayList<DoubleArray>()
        val origin = doubleArrayOf(lon, lat)
        val angle1 = bearingToAngle(bearing1)
        val angle2 = bearingToAngle(bearing2)
        if (angle1 == angle2) {
            for (i in 1 until STEPS) pts.add(terminal(lon, lat, radius, (i * -360 / STEPS).toDouble()))
            pts.add(pts[0])
        } else {
            val start = angle1
            val end = if (angle1 < angle2) angle2 else angle2 + 360
            var alpha = start
            pts.add(origin)
            var i = 1
            while (true) {
                if (alpha < end) {
                    pts.add(terminal(lon, lat, radius, alpha))
                    alpha = start + ((i * 360) / STEPS).toDouble()
                }
                if (alpha >= end) {
                    pts.add(terminal(lon, lat, radius, end))
                    pts.add(origin)
                    break
                }
                i++
            }
        }
        return "{\"type\":\"Polygon\",\"coordinates\":[[" +
            pts.joinToString(",") { "[" + goJsonFloat(it[0]) + "," + goJsonFloat(it[1]) + "]" } + "]]}"
    }

    /** Go `encoding/json` float64 formatting. */
    private fun goJsonFloat(f: Double): String {
        val abs = kotlin.math.abs(f)
        if (abs != 0.0 && (abs < 1e-6 || abs >= 1e21)) {
            // exponent form, e.g. 1e-07; not reachable for coordinates but kept for completeness
            var s =
                java.lang.Double
                    .toString(f)
                    .replace("E", "e")
            if (!s.contains("e-")) s = s.replace("e", "e+")
            return s
        }
        return GoCompat.formatFloat(f)
    }
}
