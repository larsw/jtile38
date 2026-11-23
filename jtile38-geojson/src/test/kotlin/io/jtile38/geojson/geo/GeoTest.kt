package io.jtile38.geojson.geo

import java.util.Random
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeoTest {
    private fun feq(
        a: Double,
        b: Double,
    ) = abs(a - b) < 0.0000001

    @Test
    fun geoCalc() {
        val dist = 172853.26908429610193707048892974853515625
        val bearing = 320.8560640269032546711969189345836639404296875
        val latA = 33.112
        val lonA = -112.123
        val latB = 34.312
        val lonB = -113.311
        assertTrue(feq(Geo.distanceTo(latA, lonA, latB, lonB), dist))
        assertTrue(feq(Geo.bearingTo(latA, lonA, latB, lonB), bearing))
        val (lat, lon) = Geo.destinationPoint(latA, lonA, dist, bearing)
        assertTrue(feq(lat, latB))
        assertTrue(feq(lon, lonB))

        val dist1 = 1600000.0
        for ((la, lo) in listOf(0.0 to 179.0, 0.0 to -179.0)) {
            val r = Geo.rectFromCenter(la, lo, dist1)
            assertEquals(-180.0, r.minLon)
            assertEquals(180.0, r.maxLon)
        }
        for ((la, lo, m) in listOf(Triple(89.0, 90.0, dist1), Triple(13.0257553, 77.6672509, 9000000.0))) {
            val r = Geo.rectFromCenter(la, lo, m)
            assertEquals(-180.0, r.minLon)
            assertEquals(90.0, r.maxLat)
            assertEquals(180.0, r.maxLon)
        }
        val s = Geo.rectFromCenter(-89.0, 90.0, dist1)
        assertEquals(-90.0, s.minLat)
        assertEquals(-180.0, s.minLon)
        assertEquals(180.0, s.maxLon)
    }

    @Test
    fun haversineOrdering() {
        val rnd = Random(1)
        val latA = rnd.nextDouble() * 180 - 90
        val lonA = rnd.nextDouble() * 360 - 180
        repeat(10000) {
            val latB = rnd.nextDouble() * 180 - 90
            val lonB = rnd.nextDouble() * 360 - 180
            val latC = rnd.nextDouble() * 180 - 90
            val lonC = rnd.nextDouble() * 360 - 180
            val h1 = Geo.haversine(latA, lonA, latB, lonB)
            val h2 = Geo.haversine(latA, lonA, latC, lonC)
            val m1 = Geo.distanceTo(latA, lonA, latB, lonB)
            val m2 = Geo.distanceTo(latA, lonA, latC, lonC)
            when {
                h1 < h2 -> assertTrue(m1 < m2)
                h1 == h2 -> assertEquals(m1, m2)
                else -> assertTrue(m1 > m2)
            }
        }
    }

    @Test
    fun normalizeDistance() {
        val rnd = Random(2)
        repeat(10000) {
            val meters1 = rnd.nextDouble() * Geo.EARTH_RADIUS * 3
            val meters2 = Geo.normalizeDistance(meters1)
            assertTrue(meters2 <= Math.PI * Geo.EARTH_RADIUS * 2)
            val d1 = floor(Geo.distanceToHaversine(meters2) * 1e8)
            val d2 = floor(Geo.distanceToHaversine(meters1) * 1e8)
            assertEquals(d2, d1)
        }
    }

    @Test
    fun rectFromCenterNoNaN() {
        for ((x, y, m) in listOf(Triple(-66.609685, 65.431713, 0.000711), Triple(-53.82321297037157, -51.02891855590538, 0.10))) {
            val r = Geo.rectFromCenter(y, x, m)
            assertFalse(r.minLat.isNaN() || r.minLon.isNaN() || r.maxLat.isNaN() || r.maxLon.isNaN())
        }
    }

    @Test
    fun semi() {
        val rnd = Random(3)
        var dists = 0.0
        var largest = 0.0
        val n = 200_000
        for (i in 0 until n) {
            var latA = rnd.nextDouble() * 180 - 90
            var lonA = rnd.nextDouble() * 360 - 180
            when (i) {
                0 -> {
                    latA = -90.0
                    lonA = -180.0
                }

                1 -> {
                    latA = 0.0
                    lonA = -180.0
                }

                2 -> {
                    latA = 90.0
                    lonA = -180.0
                }

                3 -> {
                    latA = 90.0
                    lonA = 0.0
                }

                4 -> {
                    latA = 90.0
                    lonA = 180.0
                }

                5 -> {
                    latA = 0.0
                    lonA = 180.0
                }

                6 -> {
                    latA = -90.0
                    lonA = 180.0
                }

                7 -> {
                    latA = -90.0
                    lonA = 0.0
                }
            }
            val latB = Geo.semiToDegs(Geo.degsToSemi(latA))
            val lonB = Geo.semiToDegs(Geo.degsToSemi(lonA))
            val d = Geo.distanceTo(latA, lonA, latB, lonB)
            if (d > largest) largest = d
            dists += d
        }
        assertTrue(dists / n <= 0.007 && largest <= 0.015)
    }
}
