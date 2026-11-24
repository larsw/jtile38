// Port of tile38 internal/buffer (MIT License, Copyright (c) 2016 Josh Baker)
package io.jtile38.geojson

import io.jtile38.geojson.geo.Geo
import io.jtile38.geojson.geo.GoMath
import io.jtile38.geojson.geometry.IndexKind
import io.jtile38.geojson.geometry.IndexOptions
import io.jtile38.geojson.geometry.Point
import io.jtile38.geojson.geometry.Poly
import io.jtile38.geojson.geometry.Segment
import io.jtile38.geojson.geometry.Series
import io.jtile38.json.Gjson
import kotlin.math.PI

/** Thrown for objects that cannot be buffered. */
class BufferException(
    message: String,
) : Exception(message)

/**
 * A very simple buffer operation on GeoJSON objects (Tile38 internal/buffer): points become
 * 16-sided polygons, segments become rectangles with round end caps, and polygons get their
 * edges buffered. Pole and antimeridian crossings are not handled, as in Tile38.
 */
object Buffer {
    private const val BUFFER_STEPS = 15

    /** Buffers [g] by [meters]; zero or negative distances return [g] unchanged. */
    fun simple(
        g: GeoObject,
        meters: Double,
    ): GeoObject {
        if (meters <= 0) return g
        if (meters.isInfinite() || meters.isNaN()) throw BufferException("invalid meters")
        return when (g) {
            is GeoPoint -> bufferSimplePoint(g.base(), meters)
            is SimplePoint -> bufferSimplePoint(g.base(), meters)
            is MultiPoint -> bufferSimpleGeometries(g.base(), meters)
            is LineString -> GeometryCollection(appendBufferSimpleSeries(ArrayList(), g.base(), meters))
            is MultiLineString -> bufferSimpleGeometries(g.base(), meters)
            is Polygon -> bufferSimplePolygon(g, meters)
            is MultiPolygon -> bufferSimpleGeometries(g.base(), meters)
            is FeatureCollection -> FeatureCollection(g.base().map { simple(it, meters) })
            is Feature -> Feature(simple(g.base(), meters), g.members())
            is Circle -> simple(g.polygon(), meters)
            else -> throw BufferException("cannot buffer " + Gjson.get(g.json(), "type").string() + " type")
        }
    }

    private fun bufferSimplePoint(
        p: Point,
        meters0: Double,
    ): Polygon {
        val meters = Geo.normalizeDistance(meters0)
        val points = ArrayList<Point>(BUFFER_STEPS + 1)
        // calc the four corners
        val maxY = Geo.destinationPoint(p.y, p.x, meters, 0.0).first
        val maxX = Geo.destinationPoint(p.y, p.x, meters, 90.0).second
        val minY = Geo.destinationPoint(p.y, p.x, meters, 180.0).first
        val minX = Geo.destinationPoint(p.y, p.x, meters, 270.0).second
        // use the half width of the lat and lon
        val lons = (maxX - minX) / 2
        val lats = (maxY - minY) / 2
        // generate the circle polygon
        var th = 0.0
        while (th <= 360.0) {
            val radians = (PI / 180) * th
            points.add(Point(p.x + lons * GoMath.cos(radians), p.y + lats * GoMath.sin(radians)))
            th += 360.0 / BUFFER_STEPS
        }
        // add last connecting point, make a total of steps+1
        points.add(points[0])
        return Polygon(Poly(points, null, IndexOptions(IndexKind.None, IndexOptions.DEFAULT.minPoints)))
    }

    private fun bufferSimpleGeometries(
        objs: List<GeoObject>,
        meters: Double,
    ): GeometryCollection = GeometryCollection(objs.map { simple(it, meters) })

    /** Buffers a series and appends its parts to [dst]. */
    private fun appendBufferSimpleSeries(
        dst: MutableList<GeoObject>,
        s: Series,
        meters: Double,
    ): MutableList<GeoObject> {
        for (i in 0 until s.numSegments()) appendSimpleBufferSegment(dst, s.segmentAt(i), meters, i == 0)
        return dst
    }

    /** Buffers a segment and appends its parts to [dst]. */
    private fun appendSimpleBufferSegment(
        dst: MutableList<GeoObject>,
        seg: Segment,
        meters: Double,
        first: Boolean,
    ) {
        // endcap A
        if (first) dst.add(bufferSimplePoint(seg.a, meters))
        // line polygon
        val bear1 = Geo.bearingTo(seg.a.y, seg.a.x, seg.b.y, seg.b.x)
        val (lat1, lon1) = Geo.destinationPoint(seg.a.y, seg.a.x, meters, bear1 - 90)
        val (lat2, lon2) = Geo.destinationPoint(seg.a.y, seg.a.x, meters, bear1 + 90)
        val bear2 = Geo.bearingTo(seg.b.y, seg.b.x, seg.a.y, seg.a.x)
        val (lat3, lon3) = Geo.destinationPoint(seg.b.y, seg.b.x, meters, bear2 - 90)
        val (lat4, lon4) = Geo.destinationPoint(seg.b.y, seg.b.x, meters, bear2 + 90)
        val ring = listOf(Point(lon1, lat1), Point(lon2, lat2), Point(lon3, lat3), Point(lon4, lat4), Point(lon1, lat1))
        dst.add(Polygon(Poly(ring, null, null)))
        // endcap B
        dst.add(bufferSimplePoint(seg.b, meters))
    }

    private fun bufferSimplePolygon(
        p: Polygon,
        meters: Double,
    ): GeometryCollection {
        val geoms = ArrayList<GeoObject>()
        val b = p.base()
        b.exterior?.let { appendBufferSimpleSeries(geoms, it, meters) }
        for (hole in b.holes) appendBufferSimpleSeries(geoms, hole, meters)
        geoms.add(p)
        return GeometryCollection(geoms)
    }
}
