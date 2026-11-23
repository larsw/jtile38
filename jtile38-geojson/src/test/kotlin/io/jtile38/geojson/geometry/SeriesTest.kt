package io.jtile38.geojson.geometry

import kotlin.test.Test
import kotlin.test.assertEquals

class SeriesTest {
    private fun segsOf(series: Series): List<Segment> = List(series.numSegments()) { series.segmentAt(it) }

    private fun searchAll(
        series: Series,
        rect: Rect = series.rect(),
    ): List<Segment> {
        val out = ArrayList<Segment>()
        series.search(rect) { seg, _ ->
            out.add(seg)
            true
        }
        return out
    }

    private fun checkSegsDups(
        a: List<Segment>,
        b: List<Segment>,
    ): Boolean = a.size == b.size && a.all { it in b }

    private fun closed(
        vararg xy: Int,
        opts: IndexOptions? = IndexOptions.DEFAULT,
    ): BaseSeries = makeSeries(xy.toList().chunked(2).map { P(it[0], it[1]) }, true, true, opts)

    @Test
    fun seriesEmpty() {
        expect(makeSeries(emptyList(), false, false, IndexOptions(IndexKind.None, 0)).empty())
    }

    @Test
    fun seriesIndex() {
        expect(makeSeries(emptyList(), false, false, IndexOptions(IndexKind.None, 0)).index() == null)
        expect(closed(0, 0, 10, 0, 10, 10, 0, 10, 0, 0, opts = IndexOptions(IndexKind.RTree, 1)).index() != null)
    }

    @Test
    fun seriesClockwise() {
        expect(!closed(0, 0, 10, 0, 10, 10, 0, 10, 0, 0).clockwise())
        expect(!closed(0, 0, 10, 0, 10, 10, 0, 10).clockwise())
        expect(!closed(0, 0, 10, 0, 10, 10).clockwise())
        expect(closed(0, 0, 0, 10, 10, 10, 10, 0, 0, 0).clockwise())
        expect(closed(0, 0, 0, 10, 10, 10, 10, 0).clockwise())
        expect(closed(0, 0, 0, 10, 10, 10).clockwise())
    }

    @Test
    fun seriesConvex() {
        expect(closed(0, 0, 4, 0, 4, 4, 0, 4, 0, 0).convex())
        expect(closed(0, 0, 4, 0, 4, 4, 3, 4, 1, 4, 0, 4, 0, 0).convex())
        expect(closed(0, 0, 4, 0, 4, 4, 2, 5, 0, 4, 0, 0).convex())
        expect(!closed(0, 0, 4, 0, 4, 4, 3, 4, 2, 5, 1, 4, 0, 4, 0, 0).convex())
        expect(!closed(0, 0, 4, 0, 4, 4, 3, 4, 2, 3, 1, 4, 0, 4, 0, 0).convex())
        expect(closed(0, 0, 4, 0, 4, 4, 0, 4, -1, 2, 0, 0).convex())
        expect(!closed(0, 0, 4, 0, 4, 4, 0, 4, 0, 3, -1, 2, 0, 0).convex())
        expect(!closed(0, 0, 4, 0, 4, 4, 0, 4, -1, 2, 0, 1, 0, 0).convex())
        expect(!closed(0, 0, 4, 0, 4, 4, 3, 3, 2, 5, 1, 3, 0, 4, 0, 0).convex())
        expect(!closed(0, 0, 0, 4, 1, 3, 4, 4, 2, 5, 3, 3, 4, 0, 0, 0).convex())
    }

    private fun testScanSeries(
        series: BaseSeries,
        index: Boolean,
        expectSegmentCount: Int,
        expectedEmpty: Boolean,
    ) {
        if (index) {
            series.indexKind = IndexKind.QuadTree
            series.buildIndex()
        } else {
            series.clearIndex()
        }
        val segs1 = segsOf(series)
        val segs2 = arrayOfNulls<Segment>(segs1.size)
        var count = 0
        series.search(series.rect()) { seg, idx ->
            segs2[idx] = seg
            count++
            true
        }
        expect(count == segs2.size)
        assertEquals(segs1, segs2.toList())
        assertEquals(expectSegmentCount, segs1.size)
        assertEquals(expectedEmpty, series.empty())
    }

    @Test
    fun seriesBasic() {
        var series = makeSeries(octagon, true, true, IndexOptions.DEFAULT)
        assertEquals(octagon, seriesCopyPoints(series))
        expect(series.convex())
        expect(series.rect() == R(0, 0, 10, 10))
        expect(series.closed())

        series = makeSeries(ri, true, true, IndexOptions.DEFAULT)
        testScanSeries(series, true, ri.size - 1, false)
        testScanSeries(series, false, ri.size - 1, false)

        val cases =
            listOf(
                Triple(emptyList<Point>(), false, 0 to true),
                Triple(listOf(P(5, 5)), false, 0 to true),
                Triple(listOf(P(5, 5), P(10, 10)), false, 1 to false),
                Triple(emptyList(), true, 0 to true),
                Triple(listOf(P(5, 5)), true, 0 to true),
                Triple(listOf(P(5, 5), P(10, 10)), true, 0 to true),
                Triple(listOf(P(5, 5), P(10, 10), P(10, 5)), true, 3 to false),
            )
        for ((points, isClosed, expected) in cases) {
            series = makeSeries(points, true, isClosed, IndexOptions.DEFAULT)
            testScanSeries(series, true, expected.first, expected.second)
            testScanSeries(series, false, expected.first, expected.second)
        }
    }

    @Test
    fun seriesSearch() {
        for (opts in listOf(IndexOptions.DEFAULT, IndexOptions(IndexKind.QuadTree, 1))) {
            val series = makeSeries(octagon, true, true, opts)
            expect(checkSegsDups(listOf(S(0, 3, 3, 0)), searchAll(series, R(0, 0, 0, 0))))
            expect(checkSegsDups(listOf(S(3, 10, 0, 7), S(0, 7, 0, 3), S(0, 3, 3, 0)), searchAll(series, R(0, 0, 0, 10))))
            expect(
                checkSegsDups(
                    listOf(S(3, 0, 7, 0), S(7, 10, 3, 10), S(3, 10, 0, 7), S(0, 7, 0, 3), S(0, 3, 3, 0)),
                    searchAll(series, R(0, 0, 5, 10)),
                ),
            )
            val all = searchAll(series, R(0, 0, 10, 10))
            expect(checkSegsDups(all, segsOf(series)))
            var first: Segment? = null
            series.search(R(0, 0, 10, 10)) { seg, _ ->
                expect(first == null)
                first = seg
                false
            }
            expect(first == all[0])
        }
    }

    @Test
    fun seriesBig() {
        // closed, using all but the last (repeated) point
        val series = makeSeries(ri.subList(0, ri.size - 1), true, true, IndexOptions.DEFAULT)
        expect(series.index() != null)
        val a = searchAll(series)
        expect(checkSegsDups(a, segsOf(series)))
        val series2 = makeSeries(ri, true, true, IndexOptions.DEFAULT)
        expect(checkSegsDups(a, segsOf(series2)))

        val opened = makeSeries(az, true, false, IndexOptions.DEFAULT)
        expect(checkSegsDups(searchAll(opened), segsOf(opened)))

        // the index must return exactly the segments a linear scan returns for any query
        val big = makeSeries(tx, true, true, IndexOptions.DEFAULT)
        val linear = makeSeries(tx, true, true, IndexOptions(IndexKind.None, 0))
        val r = big.rect()
        val w = r.max.x - r.min.x
        val h = r.max.y - r.min.y
        val rnd = java.util.Random(42)
        repeat(200) {
            val x = r.min.x + rnd.nextDouble() * w
            val y = r.min.y + rnd.nextDouble() * h
            val q = Rect(Point(x, y), Point(x + rnd.nextDouble() * w / 10, y + rnd.nextDouble() * h / 10))
            val i1 = HashSet<Int>()
            big.search(q) { _, idx -> i1.add(idx) }
            val i2 = HashSet<Int>()
            linear.search(q) { _, idx -> i2.add(idx) }
            assertEquals(i2, i1)
        }
    }

    @Test
    fun seriesMove() {
        for (shape in listOf(ri, octagon)) {
            val series = makeSeries(shape, true, true, IndexOptions.DEFAULT)
            series.clearIndex()
            var series2 = series.move(60.0, 70.0)
            expect(series2.numPoints() == shape.size)
            for (i in shape.indices) expect(series2.pointAt(i) == shape[i].move(60, 70))
            series.buildIndex()
            series2 = series.move(60.0, 70.0)
            for (i in shape.indices) expect(series2.pointAt(i) == shape[i].move(60, 70))
        }
    }

    @Test
    fun pointInBigPolygons() {
        // compare indexed and non indexed point-in-polygon on Texas
        val indexed = Poly.of(tx, null, IndexOptions.DEFAULT)
        val plain = Poly.of(tx, null, IndexOptions(IndexKind.None, 0))
        val r = indexed.rect()
        val rnd = java.util.Random(7)
        var inside = 0
        repeat(2000) {
            val p = Point(r.min.x + rnd.nextDouble() * (r.max.x - r.min.x), r.min.y + rnd.nextDouble() * (r.max.y - r.min.y))
            val a = indexed.containsPoint(p)
            assertEquals(plain.containsPoint(p), a)
            if (a) inside++
        }
        expect(inside > 0)
        expect(indexed.containsPoint(P(-97.7431, 30.2672))) // Austin
        expect(!indexed.containsPoint(P(-90.0715, 29.9511))) // New Orleans
    }
}
