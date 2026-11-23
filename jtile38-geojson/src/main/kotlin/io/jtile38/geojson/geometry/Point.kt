// Port of github.com/tidwall/geojson/geometry (MIT License, Copyright (c) 2018 Joshua J Baker)
package io.jtile38.geojson.geometry

/**
 * A 2D point. Equality follows Go struct `==` semantics on float64 fields:
 * `0.0 == -0.0` and `NaN != NaN`.
 */
class Point(
    @JvmField val x: Double,
    @JvmField val y: Double,
) : Geometry {
    operator fun component1(): Double = x

    operator fun component2(): Double = y

    fun copy(
        x: Double = this.x,
        y: Double = this.y,
    ): Point = Point(x, y)

    fun move(
        deltaX: Double,
        deltaY: Double,
    ): Point = Point(x + deltaX, y + deltaY)

    override fun empty(): Boolean = x.isNaN() && y.isNaN()

    override fun valid(): Boolean = x >= -180 && x <= 180 && y >= -90 && y <= 90

    override fun rect(): Rect = Rect(this, this)

    override fun containsPoint(point: Point): Boolean = this == point

    override fun intersectsPoint(point: Point): Boolean = this == point

    override fun containsRect(rect: Rect): Boolean = rect() == rect

    override fun intersectsRect(rect: Rect): Boolean = rect.containsPoint(this)

    override fun containsLine(line: Line?): Boolean {
        if (line == null) return false
        return !line.empty() && line.rect() == rect()
    }

    override fun intersectsLine(line: Line?): Boolean {
        if (line == null) return false
        return line.intersectsPoint(this)
    }

    override fun containsPoly(poly: Poly?): Boolean {
        if (poly == null) return false
        return !poly.empty() && poly.rect() == rect()
    }

    override fun intersectsPoly(poly: Poly?): Boolean {
        if (poly == null) return false
        return poly.intersectsPoint(this)
    }

    override fun equals(other: Any?): Boolean = other is Point && x == other.x && y == other.y

    override fun hashCode(): Int = (x + 0.0).hashCode() * 31 + (y + 0.0).hashCode()

    override fun toString(): String = "Point(x=$x, y=$y)"
}
