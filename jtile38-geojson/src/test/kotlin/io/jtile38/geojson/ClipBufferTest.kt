package io.jtile38.geojson

import kotlin.test.Test
import kotlin.test.assertEquals

/** Compares Clip and Buffer with output recorded from Tile38's Go packages (src/test/go/clip-buffer-gen). */
class ClipBufferTest {
    private val objs =
        listOf(
            """{"type":"Point","coordinates":[-112.5,33.5]}""",
            """{"type":"Point","coordinates":[179.9,89.9]}""",
            """{"type":"LineString","coordinates":[[-113.5,33.1],[-112.7,33.6],[-112.2,33.3]]}""",
            """{"type":"Polygon","coordinates":[[[-113,33],[-112,33],[-112,34],[-113,34],[-113,33]],""" +
                """[[-112.8,33.2],[-112.6,33.2],[-112.6,33.4],[-112.8,33.4],[-112.8,33.2]]]}""",
            """{"type":"MultiPoint","coordinates":[[-112.5,33.5],[10,10]]}""",
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[1,2]},"properties":{"a":1}}""",
            """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[1,2]},""" +
                """"properties":{}}]}""",
            """{"type":"GeometryCollection","geometries":[{"type":"Point","coordinates":[1,2]}]}""",
        )

    @Test
    fun matchesGo() {
        val clipper =
            GeoJson.parse(
                """{"type":"Polygon","coordinates":[[[-112.9,33.3],[-112.3,33.3],[-112.3,33.7],[-112.9,33.7],[-112.9,33.3]]]}""",
            )
        val got = ArrayList<String>()
        for (s in objs) {
            val o = GeoJson.parse(s)
            for (m in listOf(1.0, 1000.0, 123456.789)) {
                got.add(
                    try {
                        Buffer.simple(o, m).json()
                    } catch (e: BufferException) {
                        "ERR ${e.message}"
                    },
                )
            }
            got.add(Clip.clip(o, clipper).json())
        }
        val want =
            javaClass
                .getResource("/clip-buffer-go.txt")!!
                .readText()
                .trimEnd('\n')
                .split('\n')
        assertEquals(want.size, got.size)
        for (i in want.indices) assertEquals(want[i], got[i], "case $i")
    }
}
