package io.jtile38.geojson

import io.jtile38.json.Gjson
import io.jtile38.json.JsonFloat
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Compares parse results and JSON output with reference output produced by the Go library
 * (github.com/tidwall/geojson v1.4.6), stored in go-compat.txt as pairs of quoted lines.
 */
class GoCompatTest {
    @Test
    fun matchesGoOutput() {
        val lines =
            javaClass.classLoader
                .getResource("go-compat.txt")!!
                .readText()
                .lines()
                .filter { it.isNotEmpty() }
        var n = 0
        for (i in lines.indices step 2) {
            val input = Gjson.parse(lines[i]).str
            val expected = Gjson.parse(lines[i + 1]).str
            val actual =
                try {
                    val o = GeoJson.parse(input)
                    val c = o.center()
                    val r = o.rect()
                    listOf(
                        o.json(),
                        o.members(),
                        JsonFloat.format(c.x),
                        JsonFloat.format(c.y),
                        JsonFloat.format(r.min.x),
                        JsonFloat.format(r.max.y),
                        o.numPoints().toString(),
                    ).joinToString("|")
                } catch (e: GeoJsonException) {
                    "ERR ${e.message}"
                }
            assertEquals(expected, actual, "input: $input")
            n++
        }
        assertEquals(22, n)
    }
}
