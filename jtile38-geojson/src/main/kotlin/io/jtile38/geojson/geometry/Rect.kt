// Port of github.com/tidwall/geojson/geometry (MIT License, Copyright (c) 2018 Joshua J Baker)
package io.jtile38.geojson.geometry

/** An axis aligned rectangle. Also usable as a closed ring (Series). */
class Rect(
    @JvmField val min: Point,
    @JvmField val max: Point,
) : Geometry,
    Series {
    constructor(minX: Double, minY: Double, maxX: Double, maxY: Double) : this(Point(minX, minY), Point(maxX, maxY))

    operator fun component1(): Point = min

    operator fun component2(): Point = max

    fun copy(
        min: Point = this.min,
        max: Point = this.max,
    ): Rect = Rect(min, max)

    fun move(
        deltaX: Double,
        deltaY: Double,
    ): Rect = Rect(Point(min.x + deltaX, min.y + deltaY), Point(max.x + deltaX, max.y + deltaY))

    override fun index(): Any? = null

    override fun clockwise(): Boolean = false

    fun center(): Point = Point((max.x + min.x) / 2, (max.y + min.y) / 2)

    fun area(): Double = (max.x - min.x) * (max.y - min.y)

    override fun numPoints(): Int = 5

    override fun numSegments(): Int = 4

    override fun pointAt(index: Int): Point =
        when (index) {
            0 -> Point(min.x, min.y)
            1 -> Point(max.x, min.y)
            2 -> Point(max.x, max.y)
            3 -> Point(min.x, max.y)
            4 -> Point(min.x, min.y)
            else -> throw IndexOutOfBoundsException("index out of range [$index] with length 0")
        }

    override fun segmentAt(index: Int): Segment =
        when (index) {
            0 -> Segment(Point(min.x, min.y), Point(max.x, min.y))
            1 -> Segment(Point(max.x, min.y), Point(max.x, max.y))
            2 -> Segment(Point(max.x, max.y), Point(min.x, max.y))
            3 -> Segment(Point(min.x, max.y), Point(min.x, min.y))
            else -> throw IndexOutOfBoundsException("index out of range [$index] with length 0")
        }

    override fun search(
        rect: Rect,
        iter: (seg: Segment, idx: Int) -> Boolean,
    ) {
        var idx = 0
        for (i in 0 until numSegments()) {
            val seg = segmentAt(i)
            if (seg.rect().intersectsRect(rect)) {
                if (!iter(seg, idx)) break
            }
            idx++
        }
    }

    override fun empty(): Boolean = false

    override fun valid(): Boolean = min.valid() && max.valid()

    override fun rect(): Rect = this

    override fun convex(): Boolean = true

    override fun containsPoint(point: Point): Boolean = point.x >= min.x && point.x <= max.x && point.y >= min.y && point.y <= max.y

    override fun intersectsPoint(point: Point): Boolean = containsPoint(point)

    override fun containsRect(rect: Rect): Boolean {
        if (rect.min.x < min.x || rect.max.x > max.x) return false
        if (rect.min.y < min.y || rect.max.y > max.y) return false
        return true
    }

    override fun intersectsRect(rect: Rect): Boolean {
        if (min.y > rect.max.y || max.y < rect.min.y) return false
        if (min.x > rect.max.x || max.x < rect.min.x) return false
        return true
    }

    override fun containsLine(line: Line?): Boolean {
        if (line == null) return false
        return !line.empty() && containsRect(line.rect())
    }

    override fun intersectsLine(line: Line?): Boolean {
        if (line == null) return false
        return Rings.intersectsLine(this, line, true)
    }

    override fun containsPoly(poly: Poly?): Boolean {
        if (poly == null) return false
        return !poly.empty() && containsRect(poly.rect())
    }

    override fun intersectsPoly(poly: Poly?): Boolean {
        if (poly == null) return false
        return poly.intersectsRect(this)
    }

    override fun equals(other: Any?): Boolean = other is Rect && min == other.min && max == other.max

    override fun hashCode(): Int = min.hashCode() * 31 + max.hashCode()

    override fun toString(): String = "Rect(min=$min, max=$max)"
}
