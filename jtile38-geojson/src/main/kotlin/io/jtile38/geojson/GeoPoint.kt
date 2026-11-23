// Port of github.com/tidwall/geojson point.go and simplepoint.go (MIT License, Copyright (c) 2018 Joshua J Baker)
package io.jtile38.geojson

import io.jtile38.geojson.geometry.Line
import io.jtile38.geojson.geometry.Point
import io.jtile38.geojson.geometry.Poly
import io.jtile38.geojson.geometry.Rect

/** A GeoJSON Point, optionally with extra dimensions and members (Go: geojson.Point). */
class GeoPoint internal constructor(
    private val base: Point,
    internal val extra: Extra?,
) : AbstractGeoObject(),
    Spatial {
    override fun forEach(iter: (GeoObject) -> Boolean): Boolean = iter(this)

    override fun empty(): Boolean = base.empty()

    override fun valid(): Boolean = base.valid()

    override fun rect(): Rect = base.rect()

    override fun spatial(): Spatial = this

    override fun center(): Point = base

    fun base(): Point = base

    override fun appendJson(sb: StringBuilder) {
        sb.append("""{"type":"Point","coordinates":""")
        appendJsonPoint(sb, base, extra, 0)
        appendExtra(extra, sb, false)
        sb.append('}')
    }

    override fun within(other: GeoObject): Boolean = other.contains(this)

    override fun contains(other: GeoObject): Boolean = other.spatial().withinPoint(base)

    override fun intersects(other: GeoObject): Boolean {
        if (other is Circle) return other.contains(this)
        return other.spatial().intersectsPoint(base)
    }

    override fun withinRect(rect: Rect): Boolean = rect.containsPoint(base)

    override fun withinPoint(point: Point): Boolean = point.containsPoint(base)

    override fun withinLine(line: Line): Boolean = line.containsPoint(base)

    override fun withinPoly(poly: Poly): Boolean = poly.containsPoint(base)

    override fun intersectsPoint(point: Point): Boolean = base.intersectsPoint(point)

    override fun intersectsRect(rect: Rect): Boolean = base.intersectsRect(rect)

    override fun intersectsLine(line: Line): Boolean = base.intersectsLine(line)

    override fun intersectsPoly(poly: Poly): Boolean = base.intersectsPoly(poly)

    override fun numPoints(): Int = 1

    fun z(): Double {
        if (extra != null && extra.values.isNotEmpty()) return extra.values[0]
        return 0.0
    }

    override fun distance(other: GeoObject): Double = other.spatial().distancePoint(base)

    override fun distancePoint(point: Point): Double = geoDistancePoints(center(), point)

    override fun distanceRect(rect: Rect): Double = geoDistancePoints(center(), rect.center())

    override fun distanceLine(line: Line): Double = geoDistancePoints(center(), line.rect().center())

    override fun distancePoly(poly: Poly): Double = geoDistancePoints(center(), poly.rect().center())

    /** True if the point can be converted to a [SimplePoint]. */
    fun isSimple(): Boolean = extra == null

    override fun members(): String = extra?.members ?: ""

    companion object {
        /** geojson.NewPoint */
        @JvmStatic
        fun of(point: Point): GeoPoint = GeoPoint(point, null)

        /** geojson.NewPointZ */
        @JvmStatic
        fun ofZ(
            point: Point,
            z: Double,
        ): GeoPoint = GeoPoint(point, Extra(dims = 1, values = mutableListOf(z)))
    }
}

/** A 2D GeoJSON point without extra members (Go: geojson.SimplePoint). */
class SimplePoint(
    @JvmField val point: Point,
) : AbstractGeoObject(),
    Spatial {
    override fun forEach(iter: (GeoObject) -> Boolean): Boolean = iter(this)

    override fun empty(): Boolean = point.empty()

    override fun valid(): Boolean = point.valid()

    override fun rect(): Rect = point.rect()

    override fun spatial(): Spatial = this

    override fun center(): Point = point

    fun base(): Point = point

    override fun appendJson(sb: StringBuilder) {
        sb.append("""{"type":"Point","coordinates":""")
        appendJsonPoint(sb, point, null, 0)
        sb.append('}')
    }

    override fun within(other: GeoObject): Boolean = other.contains(this)

    override fun contains(other: GeoObject): Boolean = other.spatial().withinPoint(point)

    override fun intersects(other: GeoObject): Boolean {
        if (other is Circle) return other.contains(this)
        return other.spatial().intersectsPoint(point)
    }

    override fun withinRect(rect: Rect): Boolean = rect.containsPoint(point)

    override fun withinPoint(point: Point): Boolean = point.containsPoint(this.point)

    override fun withinLine(line: Line): Boolean = line.containsPoint(point)

    override fun withinPoly(poly: Poly): Boolean = poly.containsPoint(point)

    override fun intersectsPoint(point: Point): Boolean = this.point.intersectsPoint(point)

    override fun intersectsRect(rect: Rect): Boolean = point.intersectsRect(rect)

    override fun intersectsLine(line: Line): Boolean = point.intersectsLine(line)

    override fun intersectsPoly(poly: Poly): Boolean = point.intersectsPoly(poly)

    override fun numPoints(): Int = 1

    override fun distance(other: GeoObject): Double = other.spatial().distancePoint(point)

    override fun distancePoint(point: Point): Double = geoDistancePoints(center(), point)

    override fun distanceRect(rect: Rect): Double = geoDistancePoints(center(), rect.center())

    override fun distanceLine(line: Line): Double = geoDistancePoints(center(), line.rect().center())

    override fun distancePoly(poly: Poly): Double = geoDistancePoints(center(), poly.rect().center())

    override fun members(): String = ""
}

/** A rectangle object; serialized as a GeoJSON Polygon (Go: geojson.Rect). */
class GeoRect(
    private val base: Rect,
) : AbstractGeoObject(),
    Spatial {
    override fun forEach(iter: (GeoObject) -> Boolean): Boolean = iter(this)

    override fun empty(): Boolean = base.empty()

    override fun valid(): Boolean = base.valid()

    override fun rect(): Rect = base

    fun base(): Rect = base

    override fun center(): Point = base.center()

    /** Returns the rect as a Polygon object. */
    fun polygon(): GeoObject = Polygon(Poly(base))

    override fun appendJson(sb: StringBuilder) = polygon().appendJson(sb)

    override fun contains(other: GeoObject): Boolean = other.spatial().withinRect(base)

    override fun within(other: GeoObject): Boolean = other.contains(this)

    override fun withinRect(rect: Rect): Boolean = rect.containsRect(base)

    override fun withinPoint(point: Point): Boolean = point.containsRect(base)

    override fun withinLine(line: Line): Boolean = line.containsRect(base)

    override fun withinPoly(poly: Poly): Boolean = poly.containsRect(base)

    override fun intersects(other: GeoObject): Boolean = other.spatial().intersectsRect(base)

    override fun intersectsPoint(point: Point): Boolean = base.intersectsPoint(point)

    override fun intersectsRect(rect: Rect): Boolean = base.intersectsRect(rect)

    override fun intersectsLine(line: Line): Boolean = base.intersectsLine(line)

    override fun intersectsPoly(poly: Poly): Boolean = base.intersectsPoly(poly)

    override fun numPoints(): Int = 2

    override fun spatial(): Spatial = this

    override fun distance(other: GeoObject): Double = other.spatial().distanceRect(base)

    override fun distancePoint(point: Point): Double = geoDistancePoints(center(), point)

    override fun distanceRect(rect: Rect): Double = geoDistancePoints(center(), rect.center())

    override fun distanceLine(line: Line): Double = geoDistancePoints(center(), line.rect().center())

    override fun distancePoly(poly: Poly): Double = geoDistancePoints(center(), poly.rect().center())

    override fun members(): String = ""
}
