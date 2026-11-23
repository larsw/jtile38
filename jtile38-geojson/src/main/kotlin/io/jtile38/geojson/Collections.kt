// Port of github.com/tidwall/geojson collection.go and the multi/collection types (MIT License, Copyright (c) 2018 Joshua J Baker)
package io.jtile38.geojson

import io.jtile38.geojson.geometry.Line
import io.jtile38.geojson.geometry.Point
import io.jtile38.geojson.geometry.Poly
import io.jtile38.geojson.geometry.Rect
import io.jtile38.json.Gjson

/** Shared implementation of the GeoJSON collection types (Go: geojson.collection). */
abstract class CollectionObject internal constructor() :
    AbstractGeoObject(),
    GeoCollection,
    Spatial {
        internal var childList: List<GeoObject> = emptyList()
        internal var extra: Extra? = null
        private var prect: Rect = Rect(Point(0.0, 0.0), Point(0.0, 0.0))
        private var pempty: Boolean = false
        private var tree: Boolean = false

        override fun indexed(): Boolean = tree

        override fun children(): List<GeoObject> = childList

        override fun forEach(iter: (GeoObject) -> Boolean): Boolean {
            for (child in childList) {
                if (!child.forEach(iter)) return false
            }
            return true
        }

        fun base(): List<GeoObject> = childList

        override fun search(
            rect: Rect,
            iter: (child: GeoObject) -> Boolean,
        ) {
            // The Go library uses an R-tree for large collections; a linear scan yields the
            // same set of candidates.
            for (child in childList) {
                if (child.empty()) continue
                if (child.rect().intersectsRect(rect)) {
                    if (!iter(child)) break
                }
            }
        }

        override fun empty(): Boolean = pempty

        override fun valid(): Boolean = rect().valid()

        override fun rect(): Rect = prect

        override fun center(): Point = rect().center()

        override fun within(other: GeoObject): Boolean = other.contains(this)

        override fun contains(other: GeoObject): Boolean {
            if (empty()) return false
            var objContained = false
            other.forEach { geom ->
                if (geom.empty()) return@forEach true
                var geomContained = false
                search(geom.rect()) { child ->
                    if (child.contains(geom)) {
                        geomContained = true
                        false
                    } else {
                        true
                    }
                }
                if (!geomContained) {
                    objContained = false
                    false
                } else {
                    objContained = true
                    true
                }
            }
            return objContained
        }

        override fun spatial(): Spatial = this

        private inline fun withinCount(
            rect: Rect,
            crossinline test: (Spatial) -> Boolean,
        ): Boolean {
            if (empty()) return false
            var n = 0
            search(rect) { child ->
                if (test(child.spatial())) {
                    n++
                    true
                } else {
                    false
                }
            }
            return n == childList.size
        }

        override fun withinRect(rect: Rect): Boolean = withinCount(rect) { it.withinRect(rect) }

        override fun withinPoint(point: Point): Boolean = withinCount(point.rect()) { it.withinPoint(point) }

        override fun withinLine(line: Line): Boolean = withinCount(line.rect()) { it.withinLine(line) }

        override fun withinPoly(poly: Poly): Boolean = withinCount(poly.rect()) { it.withinPoly(poly) }

        override fun intersects(other: GeoObject): Boolean {
            var intersects = false
            other.forEach { geom ->
                if (geom.empty()) return@forEach true
                search(geom.rect()) { child ->
                    if (child.intersects(geom)) {
                        intersects = true
                        false
                    } else {
                        true
                    }
                }
                !intersects
            }
            return intersects
        }

        private inline fun anyIntersects(
            rect: Rect,
            crossinline test: (Spatial) -> Boolean,
        ): Boolean {
            var intersects = false
            search(rect) { child ->
                if (test(child.spatial())) {
                    intersects = true
                    false
                } else {
                    true
                }
            }
            return intersects
        }

        override fun intersectsPoint(point: Point): Boolean = anyIntersects(point.rect()) { it.intersectsPoint(point) }

        override fun intersectsRect(rect: Rect): Boolean = anyIntersects(rect) { it.intersectsRect(rect) }

        override fun intersectsLine(line: Line): Boolean = anyIntersects(line.rect()) { it.intersectsLine(line) }

        override fun intersectsPoly(poly: Poly): Boolean = anyIntersects(poly.rect()) { it.intersectsPoly(poly) }

        override fun numPoints(): Int = childList.sumOf { it.numPoints() }

        internal fun parseInitRectIndex(opts: ParseOptions) {
            pempty = true
            var count = 0
            for (child in childList) {
                if (child.empty()) continue
                if (pempty) pempty = false
                if (count == 0) {
                    prect = child.rect()
                } else {
                    prect = if (childList.size == 1) child.rect() else unionRects(prect, child.rect())
                }
                count++
            }
            tree = count > 0 && opts.indexChildren != 0 && count >= opts.indexChildren
        }

        override fun distance(other: GeoObject): Double = other.spatial().distancePoint(center())

        override fun distancePoint(point: Point): Double = geoDistancePoints(center(), point)

        override fun distanceRect(rect: Rect): Double = geoDistancePoints(center(), rect.center())

        override fun distanceLine(line: Line): Double = geoDistancePoints(center(), line.rect().center())

        override fun distancePoly(poly: Poly): Double = geoDistancePoints(center(), poly.rect().center())

        override fun members(): String = extra?.members ?: ""

        internal fun appendCoordinatesCollection(
            sb: StringBuilder,
            type: String,
        ) {
            sb.append("""{"type":"""").append(type).append("""","coordinates":[""")
            for ((i, child) in childList.withIndex()) {
                if (i > 0) sb.append(',')
                sb.append(Gjson.get(child.json(), "coordinates").string())
            }
            sb.append(']')
            appendExtra(extra, sb, false)
            sb.append('}')
        }

        internal fun appendObjectCollection(
            sb: StringBuilder,
            type: String,
            key: String,
        ) {
            sb
                .append("""{"type":"""")
                .append(type)
                .append("""","""")
                .append(key)
                .append("""":[""")
            for ((i, child) in childList.withIndex()) {
                if (i > 0) sb.append(',')
                child.appendJson(sb)
            }
            sb.append(']')
            appendExtra(extra, sb, false)
            sb.append('}')
        }
    }

/** A GeoJSON MultiPoint. */
class MultiPoint internal constructor() : CollectionObject() {
    /** geojson.NewMultiPoint */
    constructor(points: List<Point>?) : this() {
        childList = points?.map { GeoPoint.of(it) } ?: emptyList()
        parseInitRectIndex(ParseOptions.DEFAULT)
    }

    override fun appendJson(sb: StringBuilder) = appendCoordinatesCollection(sb, "MultiPoint")
}

/** A GeoJSON MultiLineString. */
class MultiLineString internal constructor() : CollectionObject() {
    /** geojson.NewMultiLineString */
    constructor(lines: List<Line>?) : this() {
        childList = lines?.map { LineString(it) } ?: emptyList()
        parseInitRectIndex(ParseOptions.DEFAULT)
    }

    override fun appendJson(sb: StringBuilder) = appendCoordinatesCollection(sb, "MultiLineString")

    override fun valid(): Boolean {
        var valid = true
        for (p in childList) if (!p.valid()) valid = false
        return valid
    }
}

/** A GeoJSON MultiPolygon. */
class MultiPolygon internal constructor() : CollectionObject() {
    /** geojson.NewMultiPolygon */
    constructor(polys: List<Poly>?) : this() {
        childList = polys?.map { Polygon(it) } ?: emptyList()
        parseInitRectIndex(ParseOptions.DEFAULT)
    }

    override fun appendJson(sb: StringBuilder) = appendCoordinatesCollection(sb, "MultiPolygon")

    override fun valid(): Boolean {
        var valid = true
        for (p in childList) if (!p.valid()) valid = false
        return valid
    }
}

/** A GeoJSON GeometryCollection. */
class GeometryCollection internal constructor() : CollectionObject() {
    /** geojson.NewGeometryCollection */
    constructor(geometries: List<GeoObject>?) : this() {
        childList = geometries ?: emptyList()
        parseInitRectIndex(ParseOptions.DEFAULT)
    }

    override fun appendJson(sb: StringBuilder) = appendObjectCollection(sb, "GeometryCollection", "geometries")
}

/** A GeoJSON FeatureCollection. */
class FeatureCollection internal constructor() : CollectionObject() {
    /** geojson.NewFeatureCollection */
    constructor(features: List<GeoObject>?) : this() {
        childList = features ?: emptyList()
        parseInitRectIndex(ParseOptions.DEFAULT)
    }

    override fun appendJson(sb: StringBuilder) = appendObjectCollection(sb, "FeatureCollection", "features")
}
