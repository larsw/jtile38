// Port of github.com/tidwall/geojson feature.go and circle.go (MIT License, Copyright (c) 2018 Joshua J Baker)
package io.jtile38.geojson

import io.jtile38.geojson.geo.Geo
import io.jtile38.geojson.geo.GoMath.cos
import io.jtile38.geojson.geo.GoMath.sin
import io.jtile38.geojson.geometry.IndexKind
import io.jtile38.geojson.geometry.IndexOptions
import io.jtile38.geojson.geometry.Line
import io.jtile38.geojson.geometry.Point
import io.jtile38.geojson.geometry.Poly
import io.jtile38.geojson.geometry.Rect
import io.jtile38.json.Gjson
import io.jtile38.json.Pretty
import kotlin.math.PI

/** A GeoJSON Feature wrapping a geometry and its members (Go: geojson.Feature). */
class Feature internal constructor(
    private val base: GeoObject,
    internal var extra: Extra?,
) : AbstractGeoObject(),
    Spatial {
    /** geojson.NewFeature: [members] is a JSON object of extra members, or "". */
    constructor(geometry: GeoObject, members: String) : this(geometry, membersExtra(members))

    override fun forEach(iter: (GeoObject) -> Boolean): Boolean = iter(this)

    override fun empty(): Boolean = base.empty()

    override fun valid(): Boolean = base.valid()

    override fun rect(): Rect = base.rect()

    override fun center(): Point = rect().center()

    fun base(): GeoObject = base

    override fun members(): String = extra?.members ?: ""

    override fun appendJson(sb: StringBuilder) {
        sb.append("""{"type":"Feature","geometry":""")
        base.appendJson(sb)
        appendExtra(extra, sb, true)
        sb.append('}')
    }

    override fun spatial(): Spatial = this

    override fun within(other: GeoObject): Boolean = other.contains(this)

    override fun contains(other: GeoObject): Boolean = base.contains(other)

    override fun withinRect(rect: Rect): Boolean = base.spatial().withinRect(rect)

    override fun withinPoint(point: Point): Boolean = base.spatial().withinPoint(point)

    override fun withinLine(line: Line): Boolean = base.spatial().withinLine(line)

    override fun withinPoly(poly: Poly): Boolean = base.spatial().withinPoly(poly)

    override fun intersects(other: GeoObject): Boolean = base.intersects(other)

    override fun intersectsPoint(point: Point): Boolean = base.spatial().intersectsPoint(point)

    override fun intersectsRect(rect: Rect): Boolean = base.spatial().intersectsRect(rect)

    override fun intersectsLine(line: Line): Boolean = base.spatial().intersectsLine(line)

    override fun intersectsPoly(poly: Poly): Boolean = base.spatial().intersectsPoly(poly)

    override fun numPoints(): Int = base.numPoints()

    override fun distance(other: GeoObject): Double = base.distance(other)

    override fun distancePoint(point: Point): Double = base.spatial().distancePoint(point)

    override fun distanceRect(rect: Rect): Double = base.spatial().distanceRect(rect)

    override fun distanceLine(line: Line): Double = base.spatial().distanceLine(line)

    override fun distancePoly(poly: Poly): Double = base.spatial().distancePoly(poly)

    private companion object {
        fun membersExtra(members0: String): Extra? {
            var members = members0.trim()
            if (members.isEmpty() || members == "{}") return null
            if (!Gjson.valid(members) || !Gjson.parse(members).isObject()) return null
            if (Gjson.get(members, "feature").exists()) {
                members = deleteTopLevelKey(members, "feature")
            }
            return Extra(members = Pretty.ugly(members))
        }

        /** Removes the first top level member named [key] from a JSON object (sjson.Delete). */
        fun deleteTopLevelKey(
            json: String,
            key: String,
        ): String {
            val root = Gjson.parse(json)
            val sb = StringBuilder("{")
            var n = 0
            var deleted = false
            root.forEach { k, v ->
                if (!deleted && k.str == key) {
                    deleted = true
                } else {
                    if (n > 0) sb.append(',')
                    sb.append(k.raw).append(':').append(v.raw)
                    n++
                }
                true
            }
            sb.append('}')
            return sb.toString()
        }
    }
}

/** Tile38's circle object: a center point with a radius in meters (Go: geojson.Circle). */
class Circle(
    center: Point,
    meters: Double,
    steps: Int,
) : AbstractGeoObject() {
    private val center: Point = center
    private val meters: Double = meters
    private val steps: Int = if (steps < 3) 3 else steps
    private val haversine: Double =
        if (meters > 0) Geo.distanceToHaversine(Geo.normalizeDistance(meters)) else 0.0

    private val obj: GeoObject by lazy { makeCircleObject(this.center, this.meters, this.steps) }

    override fun appendJson(sb: StringBuilder) {
        sb.append("""{"type":"Feature","geometry":""")
        sb.append("""{"type":"Point","coordinates":[""")
        appendJsonFloat(sb, center.x)
        sb.append(',')
        appendJsonFloat(sb, center.y)
        sb.append("""]},"properties":{"type":"Circle","radius":""")
        appendJsonFloat(sb, meters)
        sb.append(""","radius_units":"m"}}""")
    }

    fun meters(): Double = meters

    override fun center(): Point = center

    fun haversine(): Double = haversine

    fun haversineTo(p: Point): Double = Geo.haversine(p.y, p.x, center.y, center.x)

    override fun within(other: GeoObject): Boolean = other.contains(this)

    private fun containsPoint(p: Point): Boolean {
        val h = Geo.haversine(p.y, p.x, center.y, center.x)
        return h <= haversine
    }

    override fun contains(other: GeoObject): Boolean =
        when (other) {
            is GeoPoint -> containsPoint(other.center())
            is SimplePoint -> containsPoint(other.center())
            is Circle -> other.distance(this) < (other.meters + meters)
            is GeoCollection -> other.children().all { contains(it) }
            else -> getObject().contains(other)
        }

    override fun intersects(other: GeoObject): Boolean =
        when (other) {
            is GeoPoint -> containsPoint(other.center())
            is Circle -> other.distance(this) <= (other.meters + meters)
            is GeoCollection -> other.children().any { intersects(it) }
            is Feature -> intersects(other.base())
            else -> getObject().intersects(other)
        }

    override fun empty(): Boolean = false

    override fun valid(): Boolean = getObject().valid()

    override fun forEach(iter: (GeoObject) -> Boolean): Boolean = iter(this)

    override fun numPoints(): Int = 1

    override fun distance(other: GeoObject): Double = getObject().distance(other)

    override fun rect(): Rect = getObject().rect()

    override fun spatial(): Spatial = getObject().spatial()

    /** The polygon approximating this circle. */
    fun polygon(): GeoObject = getObject()

    private fun getObject(): GeoObject = obj

    override fun members(): String = ""

    private companion object {
        fun makeCircleObject(
            center: Point,
            meters0: Double,
            steps: Int,
        ): GeoObject {
            if (meters0 <= 0) {
                return Polygon(Poly(Rect(center, center)))
            }
            val meters = Geo.normalizeDistance(meters0)
            val points = ArrayList<Point>(steps + 1)
            val maxY = Geo.destinationPoint(center.y, center.x, meters, 0.0).first
            val maxX = Geo.destinationPoint(center.y, center.x, meters, 90.0).second
            val minY = Geo.destinationPoint(center.y, center.x, meters, 180.0).first
            val minX = Geo.destinationPoint(center.y, center.x, meters, 270.0).second
            val lons = (maxX - minX) / 2
            val lats = (maxY - minY) / 2
            var th = 0.0
            while (th <= 360.0) {
                val radians = (PI / 180) * th
                val x = center.x + lons * cos(radians)
                val y = center.y + lats * sin(radians)
                points.add(Point(x, y))
                th += 360.0 / steps.toDouble()
            }
            points.add(points[0])
            return Polygon(Poly(points, null, IndexOptions(IndexKind.None, 0)))
        }
    }
}
