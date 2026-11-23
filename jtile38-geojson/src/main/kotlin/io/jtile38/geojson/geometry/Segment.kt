// Port of github.com/tidwall/geojson/geometry (MIT License, Copyright (c) 2018 Joshua J Baker)
package io.jtile38.geojson.geometry

/** Result of [Segment.raycast]. */
class RaycastResult(
    @JvmField val inside: Boolean,
    @JvmField val on: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is RaycastResult && inside == other.inside && on == other.on

    override fun hashCode(): Int = (if (inside) 2 else 0) + (if (on) 1 else 0)

    override fun toString(): String = "RaycastResult(in=$inside, on=$on)"
}

/** A two point line. */
class Segment(
    @JvmField val a: Point,
    @JvmField val b: Point,
) {
    operator fun component1(): Point = a

    operator fun component2(): Point = b

    fun move(
        deltaX: Double,
        deltaY: Double,
    ): Segment = Segment(Point(a.x + deltaX, a.y + deltaY), Point(b.x + deltaX, b.y + deltaY))

    /** The outer boundaries of the segment. */
    fun rect(): Rect {
        var minX = a.x
        var minY = a.y
        var maxX = b.x
        var maxY = b.y
        if (minX > maxX) {
            val t = minX
            minX = maxX
            maxX = t
        }
        if (minY > maxY) {
            val t = minY
            minY = maxY
            maxY = t
        }
        return Rect(Point(minX, minY), Point(maxX, maxY))
    }

    fun collinearPoint(point: Point): Boolean {
        val cmpx = point.x - a.x
        val cmpy = point.y - a.y
        val rx = b.x - a.x
        val ry = b.y - a.y
        val cmpxr = cmpx * ry - cmpy * rx
        return eqZero(cmpxr)
    }

    fun containsPoint(point: Point): Boolean = raycast(point).on

    /** Detects if this segment intersects with [other]. */
    fun intersectsSegment(other: Segment): Boolean {
        val a = this.a
        val b = this.b
        val c = other.a
        val d = other.b
        // do the bounding boxes intersect?
        if (a.y > b.y) {
            if (c.y > d.y) {
                if (b.y > c.y || a.y < d.y) return false
            } else {
                if (b.y > d.y || a.y < c.y) return false
            }
        } else {
            if (c.y > d.y) {
                if (a.y > c.y || b.y < d.y) return false
            } else {
                if (a.y > d.y || b.y < c.y) return false
            }
        }
        if (a.x > b.x) {
            if (c.x > d.x) {
                if (b.x > c.x || a.x < d.x) return false
            } else {
                if (b.x > d.x || a.x < c.x) return false
            }
        } else {
            if (c.x > d.x) {
                if (a.x > c.x || b.x < d.x) return false
            } else {
                if (a.x > d.x || b.x < c.x) return false
            }
        }
        if (this.a == other.a || this.a == other.b || this.b == other.a || this.b == other.b) {
            return true
        }

        // the following code is from http://ideone.com/PnPJgb
        val cmpx = c.x - a.x
        val cmpy = c.y - a.y
        val rx = b.x - a.x
        val ry = b.y - a.y
        val cmpxr = cmpx * ry - cmpy * rx
        if (eqZero(cmpxr)) {
            // Lines are collinear, and so intersect if they have any overlap
            if (!(((c.x - a.x <= 0) != (c.x - b.x <= 0)) || ((c.y - a.y <= 0) != (c.y - b.y <= 0)))) {
                return raycast(other.a).on || raycast(other.b).on
            }
            return true
        }
        val sx = d.x - c.x
        val sy = d.y - c.y
        val cmpxs = cmpx * sy - cmpy * sx
        val rxs = rx * sy - ry * sx
        if (eqZero(rxs)) return false // segments are parallel.
        val rxsr = 1 / rxs
        val t = cmpxs * rxsr
        val u = cmpxr * rxsr
        if (!((t >= 0) && (t <= 1) && (u >= 0) && (u <= 1))) return false
        return true
    }

    /** Returns true if this segment contains [other]. */
    fun containsSegment(other: Segment): Boolean = raycast(other.a).on && raycast(other.b).on

    /** Performs the raycast operation. */
    fun raycast(point: Point): RaycastResult {
        val a = this.a
        val b = this.b
        val px = point.x
        var py = point.y
        // make sure that the point is inside the segment bounds
        if (a.y < b.y && (py < a.y || py > b.y)) {
            return NONE
        } else if (a.y > b.y && (py < b.y || py > a.y)) {
            return NONE
        }

        // test if point is in on the segment
        if (a.y == b.y) {
            if (a.x == b.x) {
                if (px == a.x && py == a.y) return ON
                return NONE
            }
            if (py == b.y) {
                // horizontal segment; check if the point in on the line
                if (a.x < b.x) {
                    if (px >= a.x && px <= b.x) return ON
                } else {
                    if (px >= b.x && px <= a.x) return ON
                }
            }
        }
        if (a.x == b.x && px == b.x) {
            // vertical segment; check if the point in on the line
            if (a.y < b.y) {
                if (py >= a.y && py <= b.y) return ON
            } else {
                if (py >= b.y && py <= a.y) return ON
            }
        }
        if ((px - a.x) / (b.x - a.x) == (py - a.y) / (b.y - a.y)) {
            return ON
        }

        // do the actual raycast here.
        while (py == a.y || py == b.y) {
            py = Math.nextUp(py)
        }
        if (a.y < b.y) {
            if (py < a.y || py > b.y) return NONE
        } else {
            if (py < b.y || py > a.y) return NONE
        }
        if (a.x > b.x) {
            if (px >= a.x) return NONE
            if (px <= b.x) return IN
        } else {
            if (px >= b.x) return NONE
            if (px <= a.x) return IN
        }
        if (a.y < b.y) {
            if ((py - a.y) / (px - a.x) >= (b.y - a.y) / (b.x - a.x)) return IN
        } else {
            if ((py - b.y) / (px - b.x) >= (a.y - b.y) / (a.x - b.x)) return IN
        }
        return NONE
    }

    override fun equals(other: Any?): Boolean = other is Segment && a == other.a && b == other.b

    override fun hashCode(): Int = a.hashCode() * 31 + b.hashCode()

    override fun toString(): String = "Segment(a=$a, b=$b)"

    private companion object {
        val NONE = RaycastResult(false, false)
        val ON = RaycastResult(false, true)
        val IN = RaycastResult(true, false)
    }
}
