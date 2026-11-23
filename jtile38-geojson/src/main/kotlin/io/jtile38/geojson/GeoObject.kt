// Port of github.com/tidwall/geojson (MIT License, Copyright (c) 2018 Joshua J Baker)
package io.jtile38.geojson

import io.jtile38.geojson.geo.Geo
import io.jtile38.geojson.geometry.Line
import io.jtile38.geojson.geometry.Point
import io.jtile38.geojson.geometry.Poly
import io.jtile38.geojson.geometry.Rect
import io.jtile38.geojson.geometry.Series
import io.jtile38.json.Gjson
import io.jtile38.json.JsonFloat

/** A GeoJSON object (Go: geojson.Object). */
interface GeoObject {
    fun empty(): Boolean

    fun valid(): Boolean

    fun rect(): Rect

    fun center(): Point

    fun contains(other: GeoObject): Boolean

    fun within(other: GeoObject): Boolean

    fun intersects(other: GeoObject): Boolean

    fun appendJson(sb: StringBuilder)

    fun json(): String

    fun distance(other: GeoObject): Double

    fun numPoints(): Int

    fun forEach(iter: (GeoObject) -> Boolean): Boolean

    fun spatial(): Spatial

    /** A valid JSON object containing extra members such as "bbox", "id" and "properties", or "". */
    fun members(): String
}

/** Spatial relationship operations against planar geometries. */
interface Spatial {
    fun withinRect(rect: Rect): Boolean

    fun withinPoint(point: Point): Boolean

    fun withinLine(line: Line): Boolean

    fun withinPoly(poly: Poly): Boolean

    fun intersectsRect(rect: Rect): Boolean

    fun intersectsPoint(point: Point): Boolean

    fun intersectsLine(line: Line): Boolean

    fun intersectsPoly(poly: Poly): Boolean

    fun distanceRect(rect: Rect): Double

    fun distancePoint(point: Point): Double

    fun distanceLine(line: Line): Double

    fun distancePoly(poly: Poly): Double
}

/** A spatial that never matches anything. */
object EmptySpatial : Spatial {
    override fun withinRect(rect: Rect) = false

    override fun withinPoint(point: Point) = false

    override fun withinLine(line: Line) = false

    override fun withinPoly(poly: Poly) = false

    override fun intersectsRect(rect: Rect) = false

    override fun intersectsPoint(point: Point) = false

    override fun intersectsLine(line: Line) = false

    override fun intersectsPoly(poly: Poly) = false

    override fun distanceRect(rect: Rect) = 0.0

    override fun distancePoint(point: Point) = 0.0

    override fun distanceLine(line: Line) = 0.0

    override fun distancePoly(poly: Poly) = 0.0
}

/** A searchable collection type (Go: geojson.Collection). */
interface GeoCollection {
    fun children(): List<GeoObject>

    fun indexed(): Boolean

    fun search(
        rect: Rect,
        iter: (child: GeoObject) -> Boolean,
    )
}

/** Error raised while parsing GeoJSON. The message matches the Go library's error text. */
class GeoJsonException(
    message: String,
) : RuntimeException(message)

/** Options controlling [GeoJson.parse] (Go: geojson.ParseOptions). */
data class ParseOptions(
    /** Index children of collections when there are at least this many; 0 disables. */
    val indexChildren: Int = 64,
    /** Index the segments of a polygon or linestring with at least this many points; 0 disables. */
    val indexGeometry: Int = 64,
    val indexGeometryKind: io.jtile38.geojson.geometry.IndexKind = io.jtile38.geojson.geometry.IndexKind.QuadTree,
    /** Fail when a GeoJSON object is invalid. */
    val requireValid: Boolean = false,
    /** Return [SimplePoint] for points with only 2D coordinates and no extra members. */
    val allowSimplePoints: Boolean = false,
    /** Disable Tile38's special Circle feature syntax. */
    val disableCircleType: Boolean = false,
    /** Return [GeoRect] for polygons that are perfect counter clockwise rectangles. */
    val allowRects: Boolean = false,
) {
    companion object {
        @JvmField
        val DEFAULT = ParseOptions()
    }
}

/** Extra coordinate dimensions and members. */
internal class Extra(
    var dims: Int = 0,
    val values: MutableList<Double> = ArrayList(),
    var members: String = "",
) {
    fun appendJsonExtra(
        dst: StringBuilder,
        propertiesRequired: Boolean,
    ) {
        if (members.isNotEmpty()) {
            dst.append(',')
            dst.append(members, 1, members.length - 1)
            if (propertiesRequired) {
                if (!Gjson.get(members, "properties").exists()) {
                    dst.append(""","properties":{}""")
                }
            }
        } else if (propertiesRequired) {
            dst.append(""","properties":{}""")
        }
    }
}

internal object Errors {
    const val DATA_INVALID = "invalid data"
    const val TYPE_INVALID = "invalid type"
    const val TYPE_MISSING = "missing type"
    const val COORDINATES_INVALID = "invalid coordinates"
    const val COORDINATES_MISSING = "missing coordinates"
    const val GEOMETRY_MISSING = "missing geometry"
    const val FEATURES_MISSING = "missing features"
    const val FEATURES_INVALID = "invalid features"
    const val GEOMETRIES_MISSING = "missing geometries"
    const val GEOMETRIES_INVALID = "invalid geometries"
    const val CIRCLE_RADIUS_UNITS_INVALID = "invalid circle radius units"

    fun typeUnknown(t: String) = "type '$t' is unknown"
}

internal fun appendExtra(
    ex: Extra?,
    dst: StringBuilder,
    propertiesRequired: Boolean,
) {
    if (ex != null) {
        ex.appendJsonExtra(dst, propertiesRequired)
    } else if (propertiesRequired) {
        dst.append(""","properties":{}""")
    }
}

internal fun appendJsonFloat(
    dst: StringBuilder,
    f: Double,
) {
    if (f.isNaN() || f.isInfinite()) {
        dst.append("null")
    } else {
        JsonFloat.append(dst, f)
    }
}

internal fun appendJsonPoint(
    dst: StringBuilder,
    point: Point,
    ex: Extra?,
    idx: Int,
) {
    dst.append('[')
    appendJsonFloat(dst, point.x)
    dst.append(',')
    appendJsonFloat(dst, point.y)
    if (ex != null) {
        val dims = ex.dims
        for (i in 0 until dims) {
            dst.append(',')
            appendJsonFloat(dst, ex.values[idx * dims + i])
        }
    }
    dst.append(']')
}

internal fun appendJsonSeries(
    dst: StringBuilder,
    series: Series,
    ex: Extra?,
    pidx0: Int,
): Int {
    var pidx = pidx0
    dst.append('[')
    val n = series.numPoints()
    for (i in 0 until n) {
        if (i > 0) dst.append(',')
        appendJsonPoint(dst, series.pointAt(i), ex, pidx)
        pidx++
    }
    dst.append(']')
    return pidx
}

internal fun unionRects(
    a: Rect,
    b: Rect,
): Rect {
    var minX = a.min.x
    var minY = a.min.y
    var maxX = a.max.x
    var maxY = a.max.y
    if (b.min.x < minX) minX = b.min.x
    if (b.max.x > maxX) maxX = b.max.x
    if (b.min.y < minY) minY = b.min.y
    if (b.max.y > maxY) maxY = b.max.y
    return Rect(Point(minX, minY), Point(maxX, maxY))
}

internal fun geoDistancePoints(
    a: Point,
    b: Point,
): Double = Geo.distanceTo(a.y, a.x, b.y, b.x)

/** Base class providing the JSON string helpers. */
abstract class AbstractGeoObject : GeoObject {
    override fun json(): String {
        val sb = StringBuilder()
        appendJson(sb)
        return sb.toString()
    }

    override fun toString(): String = json()
}
