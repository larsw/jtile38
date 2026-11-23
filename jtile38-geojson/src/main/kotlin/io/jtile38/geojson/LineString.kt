// Port of github.com/tidwall/geojson linestring.go and polygon.go (MIT License, Copyright (c) 2018 Joshua J Baker)
package io.jtile38.geojson

import io.jtile38.geojson.geometry.Line
import io.jtile38.geojson.geometry.Point
import io.jtile38.geojson.geometry.Poly
import io.jtile38.geojson.geometry.Rect

/** A GeoJSON LineString (Go: geojson.LineString). */
class LineString internal constructor(
    private val base: Line,
    internal val extra: Extra?,
) : AbstractGeoObject(),
    Spatial {
    /** geojson.NewLineString */
    constructor(line: Line) : this(line, null)

    override fun empty(): Boolean = base.empty()

    override fun valid(): Boolean = base.valid()

    override fun rect(): Rect = base.rect()

    override fun center(): Point = rect().center()

    fun base(): Line = base

    override fun appendJson(sb: StringBuilder) {
        sb.append("""{"type":"LineString","coordinates":""")
        appendJsonSeries(sb, base, extra, 0)
        appendExtra(extra, sb, false)
        sb.append('}')
    }

    override fun spatial(): Spatial = this

    override fun forEach(iter: (GeoObject) -> Boolean): Boolean = iter(this)

    override fun within(other: GeoObject): Boolean = other.contains(this)

    override fun contains(other: GeoObject): Boolean = other.spatial().withinLine(base)

    override fun intersects(other: GeoObject): Boolean = other.spatial().intersectsLine(base)

    override fun withinRect(rect: Rect): Boolean = rect.containsLine(base)

    override fun withinPoint(point: Point): Boolean = point.containsLine(base)

    override fun withinLine(line: Line): Boolean = line.containsLine(base)

    override fun withinPoly(poly: Poly): Boolean = poly.containsLine(base)

    override fun intersectsPoint(point: Point): Boolean = base.intersectsPoint(point)

    override fun intersectsRect(rect: Rect): Boolean = base.intersectsRect(rect)

    override fun intersectsLine(line: Line): Boolean = base.intersectsLine(line)

    override fun intersectsPoly(poly: Poly): Boolean = base.intersectsPoly(poly)

    override fun numPoints(): Int = base.numPoints()

    override fun distance(other: GeoObject): Double = other.spatial().distanceLine(base)

    override fun distancePoint(point: Point): Double = geoDistancePoints(center(), point)

    override fun distanceRect(rect: Rect): Double = geoDistancePoints(center(), rect.center())

    override fun distanceLine(line: Line): Double = geoDistancePoints(center(), line.rect().center())

    override fun distancePoly(poly: Poly): Double = geoDistancePoints(center(), poly.rect().center())

    override fun members(): String = extra?.members ?: ""
}

/** A GeoJSON Polygon (Go: geojson.Polygon). */
class Polygon internal constructor(
    private val base: Poly,
    internal val extra: Extra?,
) : AbstractGeoObject(),
    Spatial {
    /** geojson.NewPolygon; a null poly yields an empty polygon. */
    constructor(poly: Poly?) : this(poly ?: Poly(null), null)

    override fun empty(): Boolean = base.empty()

    override fun valid(): Boolean = base.valid()

    override fun rect(): Rect = base.rect()

    override fun center(): Point = rect().center()

    fun base(): Poly = base

    override fun appendJson(sb: StringBuilder) {
        sb.append("""{"type":"Polygon","coordinates":[""")
        if (!base.empty()) {
            var pidx = appendJsonSeries(sb, base.exterior!!, extra, 0)
            for (hole in base.holes) {
                sb.append(',')
                pidx = appendJsonSeries(sb, hole, extra, pidx)
            }
        }
        sb.append(']')
        appendExtra(extra, sb, false)
        sb.append('}')
    }

    override fun spatial(): Spatial = this

    override fun forEach(iter: (GeoObject) -> Boolean): Boolean = iter(this)

    override fun within(other: GeoObject): Boolean = other.contains(this)

    override fun contains(other: GeoObject): Boolean = other.spatial().withinPoly(base)

    override fun withinRect(rect: Rect): Boolean = rect.containsPoly(base)

    override fun withinPoint(point: Point): Boolean = point.containsPoly(base)

    override fun withinLine(line: Line): Boolean = line.containsPoly(base)

    override fun withinPoly(poly: Poly): Boolean = poly.containsPoly(base)

    override fun intersects(other: GeoObject): Boolean = other.spatial().intersectsPoly(base)

    override fun intersectsPoint(point: Point): Boolean = base.intersectsPoint(point)

    override fun intersectsRect(rect: Rect): Boolean = base.intersectsRect(rect)

    override fun intersectsLine(line: Line): Boolean = base.intersectsLine(line)

    override fun intersectsPoly(poly: Poly): Boolean = base.intersectsPoly(poly)

    override fun numPoints(): Int {
        var n = base.exterior?.numPoints() ?: 0
        for (hole in base.holes) n += hole.numPoints()
        return n
    }

    override fun distance(other: GeoObject): Double = other.spatial().distancePoly(base)

    override fun distancePoint(point: Point): Double = geoDistancePoints(center(), point)

    override fun distanceRect(rect: Rect): Double = geoDistancePoints(center(), rect.center())

    override fun distanceLine(line: Line): Double = geoDistancePoints(center(), line.rect().center())

    override fun distancePoly(poly: Poly): Double = geoDistancePoints(center(), poly.rect().center())

    fun hasExtra(): Boolean = extra != null

    override fun members(): String = extra?.members ?: ""
}
