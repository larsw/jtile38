// Port of github.com/tidwall/geojson/geometry (MIT License, Copyright (c) 2018 Joshua J Baker)
package io.jtile38.geojson.geometry

/** A standard planar geometry. */
interface Geometry {
    fun rect(): Rect

    fun empty(): Boolean

    fun valid(): Boolean

    fun containsPoint(point: Point): Boolean

    fun intersectsPoint(point: Point): Boolean

    fun containsRect(rect: Rect): Boolean

    fun intersectsRect(rect: Rect): Boolean

    fun containsLine(line: Line?): Boolean

    fun intersectsLine(line: Line?): Boolean

    fun containsPoly(poly: Poly?): Boolean

    fun intersectsPoly(poly: Poly?): Boolean
}

object Geometries {
    /** The maximum bounds for any geo point. */
    @JvmField
    val WORLD_POLYGON: Poly =
        Poly.of(
            listOf(Point(-180.0, -90.0), Point(-180.0, 90.0), Point(180.0, 90.0), Point(180.0, -90.0), Point(-180.0, -90.0)),
            null,
            IndexOptions(IndexKind.None, 0),
        )
}

internal fun eqZero(x: Double): Boolean = !(x < 0 || x > 0)
