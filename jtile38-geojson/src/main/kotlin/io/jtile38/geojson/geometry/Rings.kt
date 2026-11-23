// Port of github.com/tidwall/geojson/geometry/ring.go (MIT License, Copyright (c) 2018 Joshua J Baker)
package io.jtile38.geojson.geometry

/** Ring relationship algorithms shared by rects, lines and polygons. */
internal object Rings {
    private const val COMPLEX_RING_MIN_POINTS = 16

    fun newRing(
        points: List<Point>,
        opts: IndexOptions?,
    ): Ring = BaseSeries(points, true, true, opts)

    class RingResult(
        @JvmField val hit: Boolean,
        @JvmField val idx: Int,
    )

    private val MISS = RingResult(false, -1)

    fun containsPoint(
        ring: Ring,
        point: Point,
        allowOnEdge: Boolean,
    ): RingResult {
        if (!ring.rect().containsPoint(point)) return MISS
        var inside = false
        var idx = -1
        val rect = Rect(Point(Double.NEGATIVE_INFINITY, point.y), Point(Double.POSITIVE_INFINITY, point.y))
        ring.search(rect) { seg, index ->
            // perform a raycast operation on the segments
            val res = seg.raycast(point)
            if (res.on) {
                inside = allowOnEdge
                idx = index
                false
            } else {
                if (res.inside) inside = !inside
                true
            }
        }
        return RingResult(inside, idx)
    }

    fun intersectsPoint(
        ring: Ring,
        point: Point,
        allowOnEdge: Boolean,
    ): RingResult = containsPoint(ring, point, allowOnEdge)

    fun containsSegment(
        ring: Ring,
        seg: Segment,
        allowOnEdge: Boolean,
    ): Boolean {
        if (!ring.rect().containsPoint(seg.a) || !ring.rect().containsPoint(seg.b)) return false

        // Test that segment points are contained in the ring.
        val resA = containsPoint(ring, seg.a, allowOnEdge)
        if (!resA.hit) return false
        if (seg.b == seg.a) return true
        val resB = containsPoint(ring, seg.b, allowOnEdge)
        if (!resB.hit) return false
        if (ring.convex()) {
            // ring is convex so the segment must be contained
            return true
        }

        // The ring is concave so it's possible that the segment crosses over the
        // edge of the ring.
        if (allowOnEdge) {
            // do some logic around seg points that are on the edge of the ring.
            if (resA.idx != -1) {
                // seg A is on a ring segment
                if (resB.idx != -1) {
                    // seg B is on a ring segment
                    if (resB.idx == resA.idx) {
                        // case (3): seg A and B share the same ring segment, so it must be on the inside.
                        return true
                    }
                    // case (1): seg A and seg B are on different segments.
                    var rSegA = ring.segmentAt(resA.idx)
                    var rSegB = ring.segmentAt(resB.idx)
                    if (rSegA.a == seg.a || rSegA.b == seg.a ||
                        rSegB.a == seg.a || rSegB.b == seg.a ||
                        rSegA.a == seg.b || rSegA.b == seg.b ||
                        rSegB.a == seg.b || rSegB.b == seg.b
                    ) {
                        return true
                    }
                    // fix the order of the segments
                    if (resB.idx < resA.idx) {
                        val t = rSegA
                        rSegA = rSegB
                        rSegB = t
                    }
                    val pts = arrayOf(rSegA.a, rSegA.b, rSegB.a, rSegB.b, rSegA.a)
                    // calc winding order
                    var cwc = 0.0
                    for (i in 0 until pts.size - 1) {
                        val a = pts[i]
                        val b = pts[i + 1]
                        cwc += (b.x - a.x) * (b.y + a.y)
                    }
                    val clockwise = cwc > 0
                    if (clockwise != ring.clockwise()) {
                        // on the outside
                        return false
                    }
                    // the passover space is on the inside of the ring. check if seg
                    // intersects any ring segments where A and B are not on.
                    var intersects = false
                    ring.search(seg.rect()) { seg2, _ ->
                        if (seg.intersectsSegment(seg2) && !seg2.raycast(seg.a).on && !seg2.raycast(seg.b).on) {
                            intersects = true
                            false
                        } else {
                            true
                        }
                    }
                    return !intersects
                }
                // case (4): seg A is on a ring segment, but seg B is not.
                var intersects = false
                ring.search(seg.rect()) { seg2, _ ->
                    if (seg.intersectsSegment(seg2) && !seg2.raycast(seg.a).on) {
                        intersects = true
                        false
                    } else {
                        true
                    }
                }
                return !intersects
            } else if (resB.idx != -1) {
                // case (2): seg B is on a ring segment, but seg A is not.
                var intersects = false
                ring.search(seg.rect()) { seg2, _ ->
                    if (seg.intersectsSegment(seg2) && !seg2.raycast(seg.b).on) {
                        intersects = true
                        false
                    } else {
                        true
                    }
                }
                return !intersects
            }
            // case (5) (15)
            var intersects = false
            ring.search(seg.rect()) { seg2, _ ->
                if (seg.intersectsSegment(seg2) && !seg.raycast(seg2.a).on && !seg.raycast(seg2.b).on) {
                    intersects = true
                    false
                } else {
                    true
                }
            }
            return !intersects
        }

        // allowOnEdge is false. (not allow on edge)
        var intersects = false
        ring.search(seg.rect()) { seg2, _ ->
            if (seg.intersectsSegment(seg2)) {
                intersects = true
                false
            } else {
                true
            }
        }
        return !intersects
    }

    /** Detects if the segment intersects the ring. */
    fun intersectsSegment(
        ring: Ring,
        seg: Segment,
        allowOnEdge: Boolean,
    ): Boolean {
        if (!seg.rect().intersectsRect(ring.rect())) return false
        // Quick check that either point is inside of the ring
        if (containsPoint(ring, seg.a, allowOnEdge).hit) return true
        if (containsPoint(ring, seg.b, allowOnEdge).hit) return true
        // Neither point A or B is inside the the ring. It's possible that both are on the
        // outside and are passing over segments. If the segment passes over at least two
        // ring segments then it's intersecting.
        var count = 0
        var segAOn = false
        var segBOn = false
        ring.search(seg.rect()) search@{ seg2, _ ->
            if (seg.intersectsSegment(seg2)) {
                if (!allowOnEdge) {
                    // for segments that are not allowed on the edge, extra care must be taken.
                    if (!(seg.collinearPoint(seg2.a) && seg.collinearPoint(seg2.b))) {
                        if (!segAOn && (seg.a == seg2.a || seg.a == seg2.b)) {
                            segAOn = true
                            return@search true
                        }
                        if (!segBOn && (seg.b == seg2.a || seg.b == seg2.b)) {
                            segBOn = true
                            return@search true
                        }
                        count++
                    }
                } else {
                    count++
                }
            }
            count < 2
        }
        return count >= 2
    }

    fun containsRing(
        ring: Ring,
        other: Ring,
        allowOnEdge: Boolean,
    ): Boolean {
        if (ring.empty() || other.empty()) return false
        if (other.numPoints() >= COMPLEX_RING_MIN_POINTS) {
            // inner ring has a lot of points, so let just check if the rect ring is fully
            // contained before we do the complicated stuff.
            if (containsRing(ring, other.rect(), allowOnEdge)) return true
        }
        // test if the inner rect does not contain the outer rect
        if (!ring.rect().containsRect(other.rect())) {
            // not contained so it's not possible for the outer ring to contain the inner ring
            return false
        }
        if (ring.convex()) {
            // outer ring is convex so test that all inner points are inside of the outer ring
            val n = other.numPoints()
            for (i in 0 until n) {
                if (!containsPoint(ring, other.pointAt(i), allowOnEdge).hit) return false
            }
        } else {
            // outer ring is concave so let's make sure that all inner segments are fully
            // contained inside of the outer ring.
            val n = other.numSegments()
            for (i in 0 until n) {
                if (!containsSegment(ring, other.segmentAt(i), allowOnEdge)) return false
            }
        }
        return true
    }

    fun intersectsRing(
        ring0: Ring,
        other0: Ring,
        allowOnEdge: Boolean,
    ): Boolean {
        var ring = ring0
        var other = other0
        if (ring.empty() || other.empty()) return false
        // check outer and inner rects intersection first
        if (!ring.rect().intersectsRect(other.rect())) return false
        if (other.rect().area() > ring.rect().area()) {
            // swap the rings so that the inner ring is smaller than the outer ring
            val t = ring
            ring = other
            other = t
        }
        val n = other.numSegments()
        for (i in 0 until n) {
            if (intersectsSegment(ring, other.segmentAt(i), allowOnEdge)) return true
        }
        return false
    }

    fun containsLine(
        ring: Ring,
        line: Line,
        allowOnEdge: Boolean,
    ): Boolean =
        // shares the same logic
        containsRing(ring, line, allowOnEdge)

    fun intersectsLine(
        ring: Ring,
        line: Line,
        allowOnEdge: Boolean,
    ): Boolean {
        if (ring.empty() || line.empty()) return false
        // check outer and inner rects intersection first
        if (!ring.rect().intersectsRect(line.rect())) return false
        // check if any points are inside ring
        val np = line.numPoints()
        for (i in 0 until np) {
            if (containsPoint(ring, line.pointAt(i), allowOnEdge).hit) return true
        }
        val ns = line.numSegments()
        for (i in 0 until ns) {
            if (intersectsSegment(ring, line.segmentAt(i), allowOnEdge)) return true
        }
        return false
    }
}
