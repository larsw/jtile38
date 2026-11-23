package io.jtile38.server

import kotlin.test.Test
import kotlin.test.assertEquals

/** Expected values were produced with github.com/mmcloughlin/geohash v0.10.0. */
class GeohashTest {
    @Test
    fun encode() {
        assertEquals("9tb7er", Geohash.encode(33.0001, -112.0001, 6))
        assertEquals("9tbnthxzr9w2", Geohash.encode(33.5123, -112.2693, 12))
    }

    @Test
    fun decode() {
        assertEquals(33.503 to -112.2, Geohash.decode("9tbnwg"))
        val b = Geohash.boundingBox("9tbnwg")
        assertEquals(33.5028076171875, b.minLat)
        assertEquals(33.50830078125, b.maxLat)
        assertEquals(-112.203369140625, b.minLng)
        assertEquals(-112.1923828125, b.maxLng)
    }
}
