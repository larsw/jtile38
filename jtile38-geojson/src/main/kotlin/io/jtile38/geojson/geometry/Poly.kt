// Port of github.com/tidwall/geojson/geometry/poly.go (MIT License, Copyright (c) 2018 Joshua J Baker)
package io.jtile38.geojson.geometry

/** A polygon with an exterior ring and optional holes. A null exterior is an empty polygon. */
class Poly(
    @JvmField val exterior: Ring?,
    @JvmField val holes: List<Ring> = emptyList(),
) : Geometry {
    /** Creates a polygon from point lists (geometry.NewPoly). */
    constructor(exterior: List<Point>, holes: List<List<Point>>?, opts: IndexOptions?) : this(
        Rings.newRing(exterior, opts),
        holes?.map { Rings.newRing(it, opts) } ?: emptyList(),
    )

    fun clockwise(): Boolean = exterior?.clockwise() ?: false

    override fun empty(): Boolean = exterior?.empty() ?: true

    override fun valid(): Boolean {
        if (exterior?.valid() != true) return false
        for (hole in holes) {
            if (!hole.valid()) return false
        }
        return true
    }

    override fun rect(): Rect = exterior?.rect() ?: BaseSeries.ZERO_RECT

    /** Moves the polygon by delta, returning a new polygon. */
    fun move(
        deltaX: Double,
        deltaY: Double,
    ): Poly {
        val ext = exterior ?: return Poly(null)
        val nExt = moveRing(ext, deltaX, deltaY)
        val nHoles = holes.map { moveRing(it, deltaX, deltaY) }
        return Poly(nExt, nHoles)
    }

    private fun moveRing(
        ring: Ring,
        dx: Double,
        dy: Double,
    ): Ring =
        if (ring is BaseSeries) {
            ring.move(dx, dy)
        } else {
            BaseSeries(seriesCopyPoints(ring), false, true, IndexOptions.DEFAULT).move(dx, dy)
        }

    override fun containsPoint(point: Point): Boolean {
        val ext = exterior ?: return false
        if (!Rings.containsPoint(ext, point, true).hit) return false
        for (hole in holes) {
            if (Rings.containsPoint(hole, point, false).hit) return false
        }
        return true
    }

    override fun intersectsPoint(point: Point): Boolean = containsPoint(point)

    override fun containsRect(rect: Rect): Boolean = containsPoly(Poly(rect))

    override fun intersectsRect(rect: Rect): Boolean = intersectsPoly(Poly(rect))

    override fun containsLine(line: Line?): Boolean {
        val ext = exterior ?: return false
        if (line == null) return false
        if (!Rings.containsLine(ext, line, true)) return false
        for (hole in holes) {
            if (Rings.intersectsLine(hole, line, false)) return false
        }
        return true
    }

    override fun intersectsLine(line: Line?): Boolean {
        val ext = exterior ?: return false
        if (line == null) return false
        if (!Rings.intersectsLine(ext, line, true)) return false
        for (hole in holes) {
            if (Rings.containsLine(hole, line, false)) return false
        }
        return true
    }

    override fun containsPoly(poly: Poly?): Boolean {
        val ext = exterior ?: return false
        val otherExt = poly?.exterior ?: return false
        // 1) other exterior must be fully contained inside of the poly exterior.
        if (!Rings.containsRing(ext, otherExt, true)) return false
        // 2) ring cannot intersect poly holes
        var contains = true
        for (polyHole in holes) {
            if (Rings.intersectsRing(polyHole, otherExt, false)) {
                contains = false
                // 3) unless the poly hole is contain inside of a other hole
                for (otherHole in poly.holes) {
                    if (Rings.containsRing(otherHole, polyHole, true)) {
                        contains = true
                        break
                    }
                }
                if (!contains) break
            }
        }
        return contains
    }

    override fun intersectsPoly(poly: Poly?): Boolean {
        val ext = exterior ?: return false
        val otherExt = poly?.exterior ?: return false
        if (!Rings.intersectsRing(otherExt, ext, true)) return false
        for (hole in holes) {
            if (Rings.containsRing(hole, otherExt, false)) return false
        }
        for (hole in poly.holes) {
            if (Rings.containsRing(hole, ext, false)) return false
        }
        return true
    }

    companion object {
        /** geometry.NewPoly */
        @JvmStatic
        fun of(
            exterior: List<Point>,
            holes: List<List<Point>>? = null,
            opts: IndexOptions? = null,
        ): Poly = Poly(exterior, holes, opts)
    }
}
