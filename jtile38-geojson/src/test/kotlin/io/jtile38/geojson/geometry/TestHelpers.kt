@file:Suppress("ktlint:standard:function-naming")

package io.jtile38.geojson.geometry

import kotlin.test.assertTrue

fun P(
    x: Number,
    y: Number,
): Point = Point(x.toDouble(), y.toDouble())

fun S(
    ax: Number,
    ay: Number,
    bx: Number,
    by: Number,
): Segment = Segment(P(ax, ay), P(bx, by))

fun R(
    minX: Number,
    minY: Number,
    maxX: Number,
    maxY: Number,
): Rect = Rect(P(minX, minY), P(maxX, maxY))

fun L(vararg points: Point): Line = Line(points.toList(), IndexOptions.DEFAULT)

fun L(points: List<Point>): Line = Line(points, IndexOptions.DEFAULT)

fun expect(what: Boolean) = assertTrue(what, "expectation failure")

fun Segment.move(
    dx: Number,
    dy: Number,
): Segment = move(dx.toDouble(), dy.toDouble())

fun Rect.move(
    dx: Number,
    dy: Number,
): Rect = move(dx.toDouble(), dy.toDouble())

fun Point.move(
    dx: Number,
    dy: Number,
): Point = move(dx.toDouble(), dy.toDouble())

fun Line.move(
    dx: Number,
    dy: Number,
): Line = move(dx.toDouble(), dy.toDouble())

fun Poly.move(
    dx: Number,
    dy: Number,
): Poly = move(dx.toDouble(), dy.toDouble())

internal fun makeSeries(
    points: List<Point>,
    copyPoints: Boolean,
    closed: Boolean,
    opts: IndexOptions?,
): BaseSeries = BaseSeries(points, copyPoints, closed, opts)

private fun pts(vararg xy: Int): List<Point> = xy.toList().chunked(2).map { P(it[0], it[1]) }

val rectangle = pts(0, 0, 10, 0, 10, 10, 0, 10, 0, 0)
val pentagon = pts(2, 2, 8, 0, 10, 6, 5, 10, 0, 6, 2, 2)
val triangle = pts(0, 0, 10, 0, 5, 10, 0, 0)
val trapezoid = pts(0, 0, 10, 0, 8, 10, 2, 10, 0, 0)
val octagon = pts(3, 0, 7, 0, 10, 3, 10, 7, 7, 10, 3, 10, 0, 7, 0, 3, 3, 0)
val concave1 = pts(5, 0, 10, 0, 10, 10, 0, 10, 0, 5, 5, 5, 5, 0)
val concave2 = pts(0, 0, 5, 0, 5, 5, 10, 5, 10, 10, 0, 10, 0, 0)
val concave3 = pts(0, 0, 10, 0, 10, 5, 5, 5, 5, 10, 0, 10, 0, 0)
val concave4 = pts(0, 0, 10, 0, 10, 10, 5, 10, 5, 5, 0, 5, 0, 0)
val bowtie = pts(0, 0, 5, 4, 10, 0, 10, 10, 5, 6, 0, 10, 0, 0)
val notClosed = pts(0, 0, 10, 0, 10, 10, 0, 10)
val u1 = pts(0, 10, 0, 0, 10, 0, 10, 10)
val u2 = pts(0, 0, 10, 0, 10, 10, 0, 10)
val u3 = pts(10, 0, 10, 10, 0, 10, 0, 0)
val u4 = pts(10, 10, 0, 10, 0, 0, 10, 0)
val v1 = pts(0, 10, 5, 0, 10, 10)
val v2 = pts(0, 0, 10, 5, 0, 10)
val v3 = pts(10, 0, 5, 10, 0, 0)
val v4 = pts(10, 10, 0, 5, 10, 0)

private val states: Map<String, List<Point>> by lazy {
    val text =
        Thread
            .currentThread()
            .contextClassLoader
            .getResource("states.txt")!!
            .readText()
    text.lines().filter { it.isNotBlank() }.associate { line ->
        val parts = line.split(' ')
        parts[0] to
            parts.drop(1).map { p ->
                val (x, y) = p.split(',')
                Point(x.toDouble(), y.toDouble())
            }
    }
}

val az: List<Point> get() = states.getValue("az")
val tx: List<Point> get() = states.getValue("tx")
val ri: List<Point> get() = states.getValue("ri")

/** Runs [do] against a ring without and with a segment index. */
fun testDualRingX(
    points: List<Point>,
    block: (Ring) -> Unit,
) {
    val simple = Rings.newRing(points, IndexOptions.DEFAULT) as BaseSeries
    simple.clearIndex()
    block(simple)
    val indexed = Rings.newRing(points, IndexOptions(IndexKind.QuadTree, 1)) as BaseSeries
    indexed.buildIndex()
    block(indexed)
}

/** Runs [do] against a polygon without and with a segment index. */
fun dualPolyTest(
    exterior: List<Point>,
    holes: List<List<Point>>?,
    block: (Poly) -> Unit,
) {
    val simple = Poly.of(exterior, holes, IndexOptions.DEFAULT)
    (simple.exterior as BaseSeries).clearIndex()
    simple.holes.forEach { (it as BaseSeries).clearIndex() }
    block(simple)
    block(Poly.of(exterior, holes, IndexOptions(IndexKind.QuadTree, 1)))
}
