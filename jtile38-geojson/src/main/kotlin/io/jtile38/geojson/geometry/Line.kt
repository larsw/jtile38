// Port of github.com/tidwall/geojson/geometry/line.go (MIT License, Copyright (c) 2018 Joshua J Baker)
package io.jtile38.geojson.geometry

/** An open series of points. */
class Line :
    BaseSeries,
    Geometry {
    /** Creates a new line from [points] (geometry.NewLine). */
    constructor(points: List<Point>, opts: IndexOptions? = null) : super(points, true, false, opts)

    internal constructor(series: BaseSeries) : super(series)

    override fun valid(): Boolean = super.valid()

    override fun move(
        deltaX: Double,
        deltaY: Double,
    ): Line = Line(super.move(deltaX, deltaY))

    override fun containsPoint(point: Point): Boolean {
        var contains = false
        search(Rect(point, point)) { seg, _ ->
            if (seg.raycast(point).on) {
                contains = true
                false
            } else {
                true
            }
        }
        return contains
    }

    override fun intersectsPoint(point: Point): Boolean = containsPoint(point)

    override fun containsRect(rect: Rect): Boolean = containsPoly(Poly(rect))

    override fun intersectsRect(rect: Rect): Boolean = rect.intersectsLine(this)

    override fun containsLine(line: Line?): Boolean {
        val other = line ?: return false
        if (empty() || other.empty()) return false
        // locate the first "other" segment that contains the first "line" segment.
        val lineNumSegments = numSegments()
        var segIdx = -1
        for (j in 0 until lineNumSegments) {
            if (segmentAt(j).containsSegment(other.segmentAt(0))) {
                segIdx = j
                break
            }
        }
        if (segIdx == -1) return false
        val otherNumSegments = other.numSegments()
        var i = 1
        while (i < otherNumSegments) {
            val lineSeg = segmentAt(segIdx)
            val otherSeg = other.segmentAt(i)
            if (lineSeg.containsSegment(otherSeg)) {
                i++
                continue
            }
            if (otherSeg.a == lineSeg.a) {
                // reverse it
                if (segIdx == 0) return false
                segIdx--
                i--
            } else if (otherSeg.a == lineSeg.b) {
                // forward it
                if (segIdx == lineNumSegments - 1) return false
                segIdx++
                i--
            }
            i++
        }
        return true
    }

    override fun intersectsLine(line: Line?): Boolean {
        val other0 = line ?: return false
        if (empty() || other0.empty()) return false
        if (!rect().intersectsRect(other0.rect())) return false
        var a: Line = this
        var b: Line = other0
        if (a.numPoints() > b.numPoints()) {
            val t = a
            a = b
            b = t
        }
        val n = a.numSegments()
        for (i in 0 until n) {
            val segA = a.segmentAt(i)
            var intersects = false
            b.search(segA.rect()) { segB, _ ->
                if (segA.intersectsSegment(segB)) {
                    intersects = true
                    false
                } else {
                    true
                }
            }
            if (intersects) return true
        }
        return false
    }

    override fun containsPoly(poly: Poly?): Boolean {
        if (poly == null || empty() || poly.empty()) return false
        val rect = poly.rect()
        if (rect.min.x != rect.max.x && rect.min.y != rect.max.y) return false
        // polygon can fit in a straight (vertical or horizontal) line
        val other = Line(BaseSeries(listOf(rect.min, rect.max), false, false, IndexOptions(IndexKind.None, 0)))
        other.rect = rect
        other.convex = false
        other.clockwise = false
        return containsLine(other)
    }

    override fun intersectsPoly(poly: Poly?): Boolean {
        if (poly == null) return false
        return poly.intersectsLine(this)
    }
}
