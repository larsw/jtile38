// Port of github.com/tidwall/geojson/geometry (MIT License, Copyright (c) 2018 Joshua J Baker)
package io.jtile38.geojson.geometry

/** The kind of segment index to build for large series. */
enum class IndexKind {
    None,
    RTree,
    QuadTree,
    ;

    override fun toString(): String = name
}

/** Segment indexing options. */
data class IndexOptions(
    val kind: IndexKind = IndexKind.QuadTree,
    val minPoints: Int = 64,
) {
    companion object {
        @JvmField
        val DEFAULT = IndexOptions(IndexKind.QuadTree, 64)
    }
}

/**
 * A series of points with utilities for efficiently accessing segments from rectangle
 * queries, making stuff like point-in-polygon lookups very quick.
 */
interface Series {
    fun rect(): Rect

    fun empty(): Boolean

    fun convex(): Boolean

    fun clockwise(): Boolean

    fun numPoints(): Int

    fun numSegments(): Int

    fun pointAt(index: Int): Point

    fun segmentAt(index: Int): Segment

    fun search(
        rect: Rect,
        iter: (seg: Segment, idx: Int) -> Boolean,
    )

    fun index(): Any?

    fun valid(): Boolean
}

/** A ring is a closed series. */
typealias Ring = Series

internal fun seriesCopyPoints(series: Series): List<Point> = List(series.numPoints()) { series.pointAt(it) }

/** Concrete type containing all that is needed to make a [Series]. */
open class BaseSeries internal constructor(
    points: List<Point>,
    copyPoints: Boolean,
    closed: Boolean,
    opts: IndexOptions?,
) : Series {
    internal var closed: Boolean = closed
    internal var clockwise: Boolean = false
    internal var convex: Boolean = false
    internal var indexKind: IndexKind = IndexKind.None
    internal var index: QNode? = null
    internal var rect: Rect = ZERO_RECT
    internal var points: List<Point> = if (copyPoints) ArrayList(points) else points

    init {
        val o = opts ?: IndexOptions.DEFAULT
        processPoints(this.points, closed)
        if (o.minPoints != 0 && this.points.size >= o.minPoints) {
            indexKind = o.kind
            buildIndex()
        }
    }

    /** Copies the state of another series (Go struct copy). */
    internal constructor(other: BaseSeries) : this(emptyList(), false, other.closed, IndexOptions(IndexKind.None, 0)) {
        closed = other.closed
        clockwise = other.clockwise
        convex = other.convex
        indexKind = other.indexKind
        index = other.index
        rect = other.rect
        points = other.points
    }

    override fun index(): Any? = index

    override fun clockwise(): Boolean = clockwise

    /** Moves the series by delta, returning a new series. */
    open fun move(
        deltaX: Double,
        deltaY: Double,
    ): BaseSeries {
        val npoints = points.map { Point(it.x + deltaX, it.y + deltaY) }
        val nseries = BaseSeries(npoints, false, closed, null)
        nseries.indexKind = indexKind
        if (index != null) nseries.buildIndex()
        return nseries
    }

    /** True if the series does not take up space. */
    override fun empty(): Boolean = (closed && points.size < 3) || points.size < 2

    override fun valid(): Boolean = points.all { it.valid() }

    override fun rect(): Rect = rect

    override fun convex(): Boolean = convex

    fun closed(): Boolean = closed

    override fun numPoints(): Int = points.size

    override fun pointAt(index: Int): Point = points[index]

    override fun search(
        rect: Rect,
        iter: (seg: Segment, idx: Int) -> Boolean,
    ) {
        val idx = index
        if (idx == null) {
            val n = numSegments()
            for (i in 0 until n) {
                val seg = segmentAt(i)
                if (seg.rect().intersectsRect(rect)) {
                    if (!iter(seg, i)) return
                }
            }
        } else {
            idx.search(this, this.rect, rect, iter)
        }
    }

    override fun numSegments(): Int {
        if (closed) {
            if (points.size < 3) return 0
            if (points[points.size - 1] == points[0]) return points.size - 1
            return points.size
        }
        if (points.size < 2) return 0
        return points.size - 1
    }

    override fun segmentAt(index: Int): Segment {
        val a = points[index]
        val b = if (index == points.size - 1) points[0] else points[index + 1]
        return Segment(a, b)
    }

    /**
     * Tests if the ring is convex, calculates the outer rectangle and the winding
     * direction in one pass.
     */
    private fun processPoints(
        points: List<Point>,
        closed: Boolean,
    ) {
        if ((closed && points.size < 3) || points.size < 2) return
        var concave = false
        var dir = 0
        var cwc = 0.0
        var minX = 0.0
        var minY = 0.0
        var maxX = 0.0
        var maxY = 0.0
        val n = points.size
        for (i in 0 until n) {
            val p = points[i]
            // process the rectangle inflation
            if (i == 0) {
                minX = p.x
                minY = p.y
                maxX = p.x
                maxY = p.y
            } else {
                if (p.x < minX) {
                    minX = p.x
                } else if (p.x > maxX) {
                    maxX = p.x
                }
                if (p.y < minY) {
                    minY = p.y
                } else if (p.y > maxY) {
                    maxY = p.y
                }
            }
            // gather some point positions for concave and clockwise detection
            val a = p
            val b: Point
            val c: Point
            if (i == n - 1) {
                b = points[0]
                c = points[1]
            } else if (i == n - 2) {
                b = points[i + 1]
                c = points[0]
            } else {
                b = points[i + 1]
                c = points[i + 2]
            }
            // process the clockwise detection
            cwc += (b.x - a.x) * (b.y + a.y)
            // process the convex calculation
            if (concave) continue
            val zCrossProduct = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (dir == 0) {
                if (zCrossProduct < 0) {
                    dir = -1
                } else if (zCrossProduct > 0) {
                    dir = 1
                }
            } else if (zCrossProduct < 0) {
                if (dir == 1) concave = true
            } else if (zCrossProduct > 0) {
                if (dir == -1) concave = true
            }
        }
        convex = !concave
        rect = Rect(Point(minX, minY), Point(maxX, maxY))
        clockwise = cwc > 0
    }

    internal fun clearIndex() {
        index = null
    }

    internal fun buildIndex() {
        if (index != null) return
        when (indexKind) {
            IndexKind.None -> {}

            // The Go library offers a compressed R-tree and a compressed quadtree. Both
            // are served by the same uncompressed quadtree here.
            IndexKind.RTree, IndexKind.QuadTree -> {
                val root = QNode()
                val n = numSegments()
                for (i in 0 until n) {
                    root.insert(this, rect, segmentAt(i).rect(), i, 0)
                }
                index = root
            }
        }
    }

    internal companion object {
        val ZERO_RECT = Rect(Point(0.0, 0.0), Point(0.0, 0.0))
    }
}

/** Quadtree segment index (uncompressed port of geometry/qtree.go). */
internal class QNode {
    var split = false
    var items: IntArray = IntArray(4)
    var count = 0
    val quads = arrayOfNulls<QNode>(4)

    private fun add(item: Int) {
        if (count == items.size) items = items.copyOf(items.size * 2)
        items[count++] = item
    }

    fun insert(
        series: BaseSeries,
        bounds: Rect,
        rect: Rect,
        item: Int,
        depth: Int,
    ) {
        if (depth == MAX_DEPTH) {
            add(item)
        } else if (split) {
            val q = chooseQuad(bounds, rect)
            if (q == -1) {
                add(item)
            } else {
                val qb = quadBounds(bounds, q)
                val node = quads[q] ?: QNode().also { quads[q] = it }
                node.insert(series, qb, rect, item, depth + 1)
            }
        } else if (count == MAX_ITEMS) {
            // split qnode, keep current items in place
            val old = items.copyOf(count)
            items = IntArray(4)
            count = 0
            for (iitem in old) {
                val irect = series.segmentAt(iitem).rect()
                val q = chooseQuad(bounds, irect)
                if (q == -1) {
                    add(iitem)
                } else {
                    val qb = quadBounds(bounds, q)
                    val node = quads[q] ?: QNode().also { quads[q] = it }
                    node.insert(series, qb, irect, iitem, depth + 1)
                }
            }
            split = true
            insert(series, bounds, rect, item, depth)
        } else {
            add(item)
        }
    }

    fun search(
        series: BaseSeries,
        bounds: Rect,
        rect: Rect,
        iter: (Segment, Int) -> Boolean,
    ): Boolean {
        for (k in 0 until count) {
            val item = items[k]
            val seg = series.segmentAt(item)
            if (seg.rect().intersectsRect(rect)) {
                if (!iter(seg, item)) return false
            }
        }
        if (split) {
            for (q in 0 until 4) {
                val node = quads[q] ?: continue
                val qb = quadBounds(bounds, q)
                if (qb.intersectsRect(rect)) {
                    if (!node.search(series, qb, rect, iter)) return false
                }
            }
        }
        return true
    }

    companion object {
        const val MAX_ITEMS = 32
        const val MAX_DEPTH = 16

        fun chooseQuad(
            bounds: Rect,
            rect: Rect,
        ): Int {
            val midX = (bounds.min.x + bounds.max.x) / 2
            val midY = (bounds.min.y + bounds.max.y) / 2
            if (rect.max.x < midX) {
                if (rect.max.y < midY) return 2
                if (rect.min.y < midY) return -1
                return 0
            }
            if (rect.min.x < midX) return -1
            if (rect.max.y < midY) return 3
            if (rect.min.y < midY) return -1
            return 1
        }

        fun quadBounds(
            bounds: Rect,
            q: Int,
        ): Rect {
            val midX = (bounds.min.x + bounds.max.x) / 2
            val midY = (bounds.min.y + bounds.max.y) / 2
            return when (q) {
                0 -> Rect(Point(bounds.min.x, midY), Point(midX, bounds.max.y))
                1 -> Rect(Point(midX, midY), Point(bounds.max.x, bounds.max.y))
                2 -> Rect(Point(bounds.min.x, bounds.min.y), Point(midX, midY))
                3 -> Rect(Point(midX, bounds.min.y), Point(bounds.max.x, midY))
                else -> Rect(Point(0.0, 0.0), Point(0.0, 0.0))
            }
        }
    }
}
