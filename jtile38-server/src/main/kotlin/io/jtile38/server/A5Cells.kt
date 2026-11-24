// Port of tile38 internal/server/a5.go, Copyright Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.server

import io.jtile38.a5.cellVertices
import io.jtile38.a5.lonLatToCell
import io.jtile38.a5.toLonLat
import io.jtile38.a5.toSpherical
import io.jtile38.geojson.GeoObject
import io.jtile38.geojson.Polygon
import io.jtile38.geojson.geometry.Point
import io.jtile38.geojson.geometry.Poly

/**
 * A5 is a discrete global grid that partitions the world into equal-area pentagonal cells. Cell
 * IDs are 64-bit integers (held in Longs) and resolutions range from 0 (whole-world faces) to 30
 * (sub-centimeter). These helpers bridge the A5 port (jtile38-a5) and the geojson types, like
 * Tile38's a5.go.
 */
internal object A5Cells {
    /** The finest A5 resolution. */
    const val MAX_RESOLUTION = 30

    /** Reports whether [res] is a valid A5 resolution for encoding. */
    fun validResolution(res: Long): Boolean = res in 0..MAX_RESOLUTION

    /**
     * Encodes a lon/lat point to its containing A5 cell at [res], as a hexadecimal string (the A5
     * ecosystem convention). Like Tile38, an encoding error gives "".
     */
    fun encodePoint(
        lon: Double,
        lat: Double,
        res: Int,
    ): String =
        try {
            java.lang.Long.toUnsignedString(lonLatToCell(doubleArrayOf(lon, lat), res), 16)
        } catch (_: io.jtile38.a5.A5Exception) {
            ""
        }

    /**
     * Parses an A5 cell ID: hexadecimal, optionally with a 0x prefix (Go strconv.ParseUint(s, 16,
     * 64) after trimming "0x" and then "0X"). Returns null when invalid.
     */
    fun decodeCell(s0: String): Long? {
        var s = s0.removePrefix("0x")
        s = s.removePrefix("0X")
        if (s.isEmpty()) return null
        var n = 0L
        for (c in s) {
            val d =
                when (c) {
                    in '0'..'9' -> c - '0'
                    in 'a'..'f' -> c - 'a' + 10
                    in 'A'..'F' -> c - 'A' + 10
                    else -> return null
                }
            if (n ushr 60 != 0L) return null // overflow
            n = (n shl 4) or d.toLong()
        }
        return n
    }

    /**
     * The pentagon boundary of an A5 cell as a polygon, for use as a WITHIN/INTERSECTS area; null
     * when the cell has no boundary. Tile38 crashes on IDs that a5-go cannot decode; jtile38 treats
     * them as invalid too.
     */
    fun cellPolygon(cellId: Long): GeoObject? {
        val verts =
            try {
                cellVertices(cellId)
            } catch (_: RuntimeException) {
                return null
            }
        if (verts.size < 3) return null
        val ring = ArrayList<Point>(verts.size + 1)
        for (c in verts) {
            val ll = toLonLat(toSpherical(c))
            ring.add(Point(ll[0], ll[1]))
        }
        // close the ring
        ring.add(ring[0])
        return Polygon(Poly(ring, null, null))
    }

    /**
     * The center of a cell as a point, for SET A5; null for IDs that a5-go cannot decode (Tile38
     * crashes on those).
     */
    fun cellCenter(cellId: Long): Point? =
        try {
            val ll = io.jtile38.a5.cellToLonLat(cellId)
            Point(ll[0], ll[1])
        } catch (_: RuntimeException) {
            null
        }
}
