// Port of tile38 internal/clip (MIT License, Copyright (c) 2016 Josh Baker)
package io.jtile38.geojson

import io.jtile38.geojson.geometry.IndexOptions
import io.jtile38.geojson.geometry.Line
import io.jtile38.geojson.geometry.Point
import io.jtile38.geojson.geometry.Poly
import io.jtile38.geojson.geometry.Rect
import io.jtile38.geojson.geometry.Segment

/** Clips GeoJSON objects to the bounding rectangle of another object (Tile38 internal/clip). */
object Clip {
    /** Clips the contents of [obj] to the rectangle of [clipper]. Unsupported types are returned as is. */
    fun clip(
        obj: GeoObject,
        clipper: GeoObject,
        opts: IndexOptions? = null,
    ): GeoObject =
        when (obj) {
            // only GeoJSON points, not SimplePoint, like the Go type switch on *geojson.Point
            is GeoPoint -> clipPoint(obj, clipper)

            is GeoRect -> clipRect(obj, clipper, opts)

            is LineString -> clipLineString(obj, clipper, opts)

            is Polygon -> clipPolygon(obj, clipper, opts)

            is Feature -> clipFeature(obj, clipper, opts)

            is CollectionObject -> clipCollection(obj, clipper, opts)

            else -> obj
        }

    /** Cohen-Sutherland line clipping; returns null when the segment is rejected. */
    private fun clipSegment(
        seg: Segment,
        rect: Rect,
    ): Segment? {
        val startCode = getCode(rect, seg.a)
        val endCode = getCode(rect, seg.b)
        return if ((startCode or endCode) == 0) {
            // trivially accept
            seg
        } else if ((startCode and endCode) != 0) {
            // trivially reject
            null
        } else if (startCode != 0) {
            // start is outside. get new start.
            val newStart = intersect(rect, startCode, seg.a, seg.b)
            clipSegment(Segment(newStart, seg.b), rect)
        } else {
            // end is outside. get new end.
            val newEnd = intersect(rect, endCode, seg.a, seg.b)
            clipSegment(Segment(seg.a, newEnd), rect)
        }
    }

    /** Sutherland-Hodgman polygon clipping. */
    private fun clipRing(
        ring0: List<Point>,
        bbox: Rect,
    ): List<Point> {
        // under 4 elements this is not a polygon ring!
        if (ring0.size < 4) return emptyList()
        var ring = ring0
        var resRing = ArrayList<Point>()
        var edge = 1
        while (edge <= 8) {
            var prev = ring[ring.size - 2]
            var prevInside = (getCode(bbox, prev) and edge) == 0
            for (p in ring) {
                val inside = (getCode(bbox, p) and edge) == 0
                if (prevInside && inside) {
                    // staying inside
                    resRing.add(p)
                } else if (prevInside && !inside) {
                    // leaving
                    resRing.add(intersect(bbox, edge, prev, p))
                } else if (!prevInside && inside) {
                    // entering
                    resRing.add(intersect(bbox, edge, prev, p))
                    resRing.add(p)
                }
                prev = p
                prevInside = inside
            }
            if (resRing.isNotEmpty() && resRing[0] != resRing[resRing.size - 1]) resRing.add(resRing[0])
            ring = resRing
            resRing = ArrayList()
            if (ring.isEmpty()) break
            edge *= 2
        }
        return ring
    }

    private fun getCode(
        bbox: Rect,
        point: Point,
    ): Int {
        var code = 0
        if (point.x < bbox.min.x) {
            code = code or 1 // left
        } else if (point.x > bbox.max.x) {
            code = code or 2 // right
        }
        if (point.y < bbox.min.y) {
            code = code or 4 // bottom
        } else if (point.y > bbox.max.y) {
            code = code or 8 // top
        }
        return code
    }

    private fun intersect(
        bbox: Rect,
        code: Int,
        start: Point,
        end: Point,
    ): Point =
        if ((code and 8) != 0) {
            // top
            Point(start.x + (end.x - start.x) * (bbox.max.y - start.y) / (end.y - start.y), bbox.max.y)
        } else if ((code and 4) != 0) {
            // bottom
            Point(start.x + (end.x - start.x) * (bbox.min.y - start.y) / (end.y - start.y), bbox.min.y)
        } else if ((code and 2) != 0) {
            // right
            Point(bbox.max.x, start.y + (end.y - start.y) * (bbox.max.x - start.x) / (end.x - start.x))
        } else if ((code and 1) != 0) {
            // left
            Point(bbox.min.x, start.y + (end.y - start.y) * (bbox.min.x - start.x) / (end.x - start.x))
        } else {
            // should not call intersect with the zero code
            Point(0.0, 0.0)
        }

    private fun clipPoint(
        point: GeoPoint,
        clipper: GeoObject,
    ): GeoObject = if (point.intersectsRect(clipper.rect())) point else MultiPoint(null)

    private fun clipRect(
        rect: GeoRect,
        clipper: GeoObject,
        opts: IndexOptions?,
    ): GeoObject {
        val base = rect.base()
        val points = List(base.numPoints()) { base.pointAt(it) }
        return clip(Polygon(Poly(points, null, opts)), clipper, opts)
    }

    private fun clipLineString(
        lineString: LineString,
        clipper: GeoObject,
        opts: IndexOptions?,
    ): GeoObject {
        val bbox = clipper.rect()
        val newPoints = ArrayList<List<Point>>()
        var line = ArrayList<Point>()
        val base = lineString.base()
        for (i in 0 until base.numSegments()) {
            val clipped = clipSegment(base.segmentAt(i), bbox) ?: continue
            if (line.isNotEmpty() && line[line.size - 1] != clipped.a) {
                newPoints.add(line)
                line = arrayListOf(clipped.a)
            } else if (line.isEmpty()) {
                line.add(clipped.a)
            }
            line.add(clipped.b)
        }
        if (line.isNotEmpty()) newPoints.add(line)
        val children = newPoints.map { Line(it, opts) }
        if (children.size == 1) return LineString(children[0])
        return MultiLineString(children)
    }

    private fun clipPolygon(
        polygon: Polygon,
        clipper: GeoObject,
        opts: IndexOptions?,
    ): GeoObject {
        val rect = clipper.rect()
        val newPoints = ArrayList<List<Point>>()
        val base = polygon.base()
        val rings = listOfNotNull(base.exterior) + base.holes
        for (ring in rings) {
            val ringPoints = List(ring.numPoints()) { ring.pointAt(it) }
            val clippedRing = clipRing(ringPoints, rect)
            if (clippedRing.isNotEmpty()) newPoints.add(clippedRing)
        }
        val exterior = if (newPoints.isNotEmpty()) newPoints[0] else emptyList()
        val holes = if (newPoints.size > 1) newPoints.subList(1, newPoints.size) else null
        val newPoly = Polygon(Poly(exterior, holes, opts))
        if (newPoly.empty()) return MultiPolygon(null)
        return newPoly
    }

    private fun clipFeature(
        feature: Feature,
        clipper: GeoObject,
        opts: IndexOptions?,
    ): GeoObject {
        val newFeature = clip(feature.base(), clipper, opts)
        return newFeature as? Feature ?: Feature(newFeature, feature.members())
    }

    private fun clipCollection(
        collection: CollectionObject,
        clipper: GeoObject,
        opts: IndexOptions?,
    ): GeoObject {
        val features = ArrayList<GeoObject>()
        for (child in collection.children()) {
            val feature = clip(child, clipper, opts)
            if (feature.empty()) continue
            features.add(feature as? Feature ?: Feature(feature, ""))
        }
        return FeatureCollection(features)
    }
}
