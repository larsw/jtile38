package io.jtile38.geojson.geometry

import kotlin.test.Test
import kotlin.test.assertTrue

@Suppress("UNUSED_VARIABLE", "UNUSED_VALUE", "VARIABLE_WITH_REDUNDANT_INITIALIZER", "CanBeVal", "NAME_SHADOWING")
class GeometryTest {
    @Test
    fun testLineNewLine() {
        var line = Line(u1, IndexOptions.DEFAULT)
        expect(!line.empty())
    }

    @Test
    fun testLineGeometryDefaults() {
        val g: Geometry = Line(emptyList())
        expect(g.empty())
        expect(g.rect() == R(0, 0, 0, 0))
        expect(!g.containsLine(null))
        expect(!g.containsLine(Line(emptyList())))
        expect(!g.containsPoint(P(0, 0)))
        expect(!g.containsPoly(null))
        expect(!g.containsPoly(Poly(null)))
        expect(!g.containsRect(R(0, 0, 0, 0)))
        expect(!g.intersectsLine(null))
        expect(!g.intersectsLine(Line(emptyList())))
        expect(!g.intersectsPoint(P(0, 0)))
        expect(!g.intersectsPoly(null))
        expect(!g.intersectsPoly(Poly(null)))
        expect(!g.intersectsRect(R(0, 0, 0, 0)))
    }

    @Test
    fun testLineMove() {
        var ln1 = L(P(0, 1), P(2, 3), P(4, 5))
        var ln2 = ln1.move(7, 8)
        expect(ln1.numPoints() == ln2.numPoints())
        for (i in 0 until ln2.numPoints()) {
            expect(ln2.pointAt(i) == ln1.pointAt(i).move(7, 8))
        }

        expect((Line(emptyList())).move(0, 0) != null)
    }

    @Test
    fun testLineContainsPoint() {
        var line = Line(u1, IndexOptions.DEFAULT)
        expect(line.containsPoint(P(0, 0)))
        expect(line.containsPoint(P(10, 10)))
        expect(line.containsPoint(P(0, 5)))
        expect(!line.containsPoint(P(5, 5)))
        line = Line(v1, IndexOptions.DEFAULT)
        expect(line.containsPoint(P(0, 10)))
        expect(!line.containsPoint(P(0, 0)))
        expect(line.containsPoint(P(5, 0)))
        expect(line.containsPoint(P(2.5, 5)))
        expect(!(Line(emptyList())).containsPoint(P(0, 0)))
    }

    @Test
    fun testLineIntersectsPoint() {
        var line = Line(v1, IndexOptions.DEFAULT)
        expect(line.intersectsPoint(P(0, 10)))
        expect(!line.intersectsPoint(P(0, 0)))
        expect(line.intersectsPoint(P(5, 0)))
        expect(line.intersectsPoint(P(2.5, 5)))
        expect(!(Line(emptyList())).intersectsPoint(P(0, 0)))
    }

    @Test
    fun testLineContainsRect() {
        var line = Line(v1, IndexOptions.DEFAULT)
        expect(!line.containsRect(R(0, 0, 10, 10)))
        expect(line.containsRect(R(0, 10, 0, 10)))
        line = Line(u1, IndexOptions.DEFAULT)
        expect(line.containsRect(R(0, 0, 0, 10)))
        expect(!(Line(emptyList())).containsRect(R(0, 0, 0, 0)))
    }

    @Test
    fun testLineIntersectsRect() {
        var line = Line(v1, IndexOptions.DEFAULT)
        expect(line.intersectsRect(R(0, 0, 10, 10)))
        expect(line.intersectsRect(R(0, 0, 2.5, 5)))
        expect(!line.intersectsRect(R(0, 0, 2.4, 5)))
        expect(!(Line(emptyList())).intersectsRect(R(0, 0, 0, 0)))
    }

    @Test
    fun testLineContainsLine() {
        expect(!P(15, 15).containsLine(L(P(15, 0), P(15, 15), P(30, 15))))
        expect(!P(15, 15).containsLine(L()))
        expect(!P(15, 15).containsLine(L(P(15, 15))))
        expect(P(15, 15).containsLine(L(P(15, 15), P(15, 15))))
        expect(R(0, 0, 30, 30).containsLine(L(P(15, 0), P(15, 15), P(30, 15))))
        expect(!R(0, 0, 30, 30).containsLine(L()))
        expect(!R(0, 0, 20, 20).containsLine(L(P(15, 0), P(15, 15), P(30, 15))))
        var ln1 = L(P(5, 0), P(5, 5), P(10, 5), P(10, 10), P(15, 10), P(15, 15))
        var lns =
            listOf<Line>(
                L(P(7, 5), P(10, 5), P(10, 10), P(12, 10)),
                L(P(7, 5), P(8, 5), P(10, 5), P(10, 10), P(12, 10)),
                L(
                    P(7, 5),
                    P(8, 5),
                    P(6, 5),
                    P(10, 5),
                    P(10, 8),
                    P(10, 5),
                    P(5, 5),
                    P(10, 5),
                    P(10, 10),
                    P(12, 10),
                ),
            )
        for (ln2 in lns) {
            expect(ln1.containsLine(ln2))
        }
        expect(!ln1.containsLine(L(P(5, -1), P(5, 5), P(10, 5))))
        expect(!ln1.containsLine(L(P(5, 0), P(5, 5), P(5, 0), P(10, 0))))
        expect(
            !ln1.containsLine(
                L(
                    P(5, 0),
                    P(5, 5),
                    P(10, 5),
                    P(10, 10),
                    P(15, 10),
                    P(15, 15),
                    P(20, 20),
                ),
            ),
        )
        expect(!ln1.containsLine(L()))
        expect(!L().containsLine(L(P(5, 0))))
        expect(!L(P(5, 0), P(10, 0)).containsLine(L(P(5, 0))))
        expect(R(0, 0, 30, 30).containsLine(L(P(15, 0), P(15, 15), P(30, 15))))
        expect(!R(0, 0, 30, 30).containsLine(L()))
    }

    @Test
    fun testLineClockwise() {
        expect(L(P(0, 0), P(0, 10), P(10, 10), P(10, 0), P(0, 0)).clockwise())
        expect(!L(P(0, 0), P(10, 0), P(10, 10), P(0, 10), P(0, 0)).clockwise())
        expect(L(P(0, 0), P(0, 10), P(10, 10)).clockwise())
        expect(!L(P(0, 0), P(10, 0), P(10, 10)).clockwise())
    }

    @Test
    fun testLineIntersectsLine() {
        var lns = listOf<List<Point>>(u1, u2, u3, u4, v1, v2, v3, v4)
        for (i in lns.indices) {
            for (j in lns.indices) {
                expect(
                    Line(lns[i], IndexOptions.DEFAULT).intersectsLine(
                        Line(lns[j], IndexOptions.DEFAULT),
                    ),
                )
            }
        }
        var line = Line(u1, IndexOptions.DEFAULT)
        expect(!line.intersectsLine(Line(emptyList(), IndexOptions.DEFAULT)))
        expect(!Line(emptyList(), IndexOptions.DEFAULT).intersectsLine(Line(emptyList(), IndexOptions.DEFAULT)))
        expect(!Line(emptyList(), IndexOptions.DEFAULT).intersectsLine(line))
        expect(line.intersectsLine(line.move(5, 0)))
        expect(line.intersectsLine(line.move(10, 0)))
        expect(!line.intersectsLine(line.move(11, 0)))
        expect(!L(v1).intersectsLine(L(v1).move(0, 1)))
        expect(!L(v1).intersectsLine(L(v1).move(0, -1)))
    }

    @Test
    fun testLineContainsPoly() {
        var line = Line(u1, IndexOptions.DEFAULT)
        var poly = Poly.of(octagon, null, IndexOptions.DEFAULT)
        expect(!line.containsPoly(poly))
        expect(
            line.containsPoly(
                Poly.of(
                    listOf<Point>(P(0, 10), P(0, 0), P(0, 10)),
                    null,
                    IndexOptions.DEFAULT,
                ),
            ),
        )
        expect(
            line.containsPoly(
                Poly.of(
                    listOf<Point>(P(0, 0), P(10, 0), P(0, 0)),
                    null,
                    IndexOptions.DEFAULT,
                ),
            ),
        )
        expect(
            !L().containsPoly(
                Poly.of(
                    listOf<Point>(P(0, 0), P(10, 0), P(0, 0)),
                    null,
                    IndexOptions.DEFAULT,
                ),
            ),
        )
        expect(!line.containsPoly(Poly.of(emptyList(), null, IndexOptions.DEFAULT)))
    }

    @Test
    fun testLineIntersectsPoly() {
        var line = Line(u1, IndexOptions.DEFAULT)
        var poly = Poly.of(octagon, null, IndexOptions.DEFAULT)
        expect(line.intersectsPoly(poly))
        expect(line.intersectsPoly(poly.move(5, 0)))
        expect(line.intersectsPoly(poly.move(10, 0)))
        expect(!line.intersectsPoly(poly.move(11, 0)))
        expect(!line.intersectsPoly(poly.move(15, 0)))
    }

    @Test
    fun testPointEmpty() {
        expect(!P(0, 0).empty())
        expect(P(Double.NaN, Double.NaN).empty())
    }

    @Test
    fun testPointGeometryDefaults() {
        val g: Geometry = P(0, 0)
        expect(!g.empty())
        expect(g.rect() == R(0, 0, 0, 0))
        expect(!g.containsLine(null))
        expect(!g.containsLine(Line(emptyList())))
        expect(g.containsPoint(P(0, 0)))
        expect(!g.containsPoly(null))
        expect(!g.containsPoly(Poly(null)))
        expect(g.containsRect(R(0, 0, 0, 0)))
        expect(!g.intersectsLine(null))
        expect(!g.intersectsLine(Line(emptyList())))
        expect(g.intersectsPoint(P(0, 0)))
        expect(!g.intersectsPoly(null))
        expect(!g.intersectsPoly(Poly(null)))
        expect(g.intersectsRect(R(0, 0, 0, 0)))
    }

    @Test
    fun testPointRect() {
        expect(P(5, 5).rect() == R(5, 5, 5, 5))
    }

    @Test
    fun testPointMove() {
        expect(P(5, 6).move(10, 10) == P(15, 16))
    }

    @Test
    fun testPointContainsPoint() {
        expect(P(5, 5).containsPoint(P(5, 5)))
        expect(!P(5, 5).containsPoint(P(6, 5)))
    }

    @Test
    fun testPointIntersectsPoint() {
        expect(P(5, 5).intersectsPoint(P(5, 5)))
        expect(!P(5, 5).intersectsPoint(P(6, 5)))
    }

    @Test
    fun testPointContainsRect() {
        expect(P(5, 5).containsRect(R(5, 5, 5, 5)))
        expect(!P(5, 5).containsRect(R(0, 0, 10, 10)))
    }

    @Test
    fun testPointIntersectsRect() {
        expect(P(5, 5).intersectsRect(R(5, 5, 5, 5)))
        expect(P(5, 5).intersectsRect(R(0, 0, 10, 10)))
        expect(P(0, 0).intersectsRect(R(0, 0, 10, 10)))
        expect(!P(-1, 0).intersectsRect(R(0, 0, 10, 10)))
    }

    @Test
    fun testPointContainsLine() {
        expect(!P(5, 5).containsLine(L()))
        expect(!P(5, 5).containsLine(L(P(5, 5))))
        expect(P(5, 5).containsLine(L(P(5, 5), P(5, 5))))
        expect(!P(5, 5).containsLine(L(P(5, 5), P(10, 10))))
    }

    @Test
    fun testPointIntersectsLine() {
        expect(!P(5, 5).intersectsLine(L()))
        expect(!P(5, 5).intersectsLine(L(P(5, 5))))
        expect(P(5, 5).intersectsLine(L(P(5, 5), P(5, 5))))
        expect(P(5, 5).intersectsLine(L(P(0, 0), P(10, 10))))
        expect(!P(6, 5).intersectsLine(L(P(0, 0), P(10, 10))))
    }

    @Test
    fun testPointContainsPoly() {
        expect(!P(5, 5).containsPoly(Poly.of(emptyList(), null, IndexOptions.DEFAULT)))
        expect(!P(5, 5).containsPoly(Poly.of(listOf<Point>(P(0, 0), P(10, 0)), null, IndexOptions.DEFAULT)))
        expect(!P(5, 5).containsPoly(Poly(R(0, 0, 10, 10))))
        expect(P(5, 5).containsPoly(Poly(R(5, 5, 5, 5))))
    }

    @Test
    fun testPointIntersectsPoly() {
        var octa = Poly.of(octagon, null, IndexOptions.DEFAULT)
        var concave1 = Poly.of(concave1, null, IndexOptions.DEFAULT)
        expect(!P(5, 5).intersectsPoly(Poly.of(emptyList(), null, IndexOptions.DEFAULT)))
        expect(!P(5, 5).intersectsPoly(Poly.of(listOf<Point>(P(0, 0), P(10, 0)), null, IndexOptions.DEFAULT)))
        expect(P(5, 5).intersectsPoly(octa))
        expect(P(0, 5).intersectsPoly(octa))
        expect(!P(1, 1).intersectsPoly(octa))
        expect(!P(4, 4).intersectsPoly(concave1))
        expect(P(5, 5).intersectsPoly(concave1))
        expect(P(6, 6).intersectsPoly(concave1))
    }

    @Test
    fun testPolyNewPoly() {
        dualPolyTest(octagon, null) { poly ->
            expect(!poly.empty())
        }
        var small = listOf<Point>(P(4, 4), P(6, 4), P(6, 6), P(4, 6), P(4, 4))
        dualPolyTest(octagon, listOf<List<Point>>(small)) { poly ->
            expect(!poly.empty())
        }
    }

    @Test
    fun testPolyGeometryDefaults() {
        val g: Geometry = Poly(null)
        expect(g.empty())
        expect(g.rect() == R(0, 0, 0, 0))
        expect(!g.containsLine(null))
        expect(!g.containsLine(Line(emptyList())))
        expect(!g.containsPoint(P(0, 0)))
        expect(!g.containsPoly(null))
        expect(!g.containsPoly(Poly(null)))
        expect(!g.containsRect(R(0, 0, 0, 0)))
        expect(!g.intersectsLine(null))
        expect(!g.intersectsLine(Line(emptyList())))
        expect(!g.intersectsPoint(P(0, 0)))
        expect(!g.intersectsPoly(null))
        expect(!g.intersectsPoly(Poly(null)))
        expect(!g.intersectsRect(R(0, 0, 0, 0)))
    }

    @Test
    fun testPolyRect() {
        dualPolyTest(octagon, null) { poly ->
            expect(poly.rect() == R(0, 0, 10, 10))
        }
        var small = listOf<Point>(P(4, 4), P(6, 4), P(6, 6), P(4, 6), P(4, 4))
        dualPolyTest(octagon, listOf<List<Point>>(small)) { poly ->
            expect(poly.rect() == R(0, 0, 10, 10))
        }
    }

    @Test
    fun testPolyMove() {
        var small = listOf<Point>(P(4, 4), P(6, 4), P(6, 6), P(4, 6), P(4, 4))
        dualPolyTest(octagon, listOf<List<Point>>(small)) { poly ->
            var poly2 = poly.move(5, 8)
            expect(poly2.rect() == R(5, 8, 15, 18))
        }
        var poly = Poly(R(0, 0, 10, 10), listOf<Ring>(R(2, 2, 8, 8)))
        var poly2 = poly.move(5, 8)
        expect(poly2.rect() == R(5, 8, 15, 18))
        expect(poly2.holes.size == 1)
        expect(poly2.holes[0].rect() == R(7, 10, 13, 16))

        expect((Poly(null)).move(0, 0) != null)
    }

    @Test
    fun testPolyContainsPoint() {
        var small = listOf<Point>(P(4, 4), P(6, 4), P(6, 6), P(4, 6), P(4, 4))
        dualPolyTest(octagon, listOf<List<Point>>(small)) { poly ->
            expect(!poly.containsPoint(P(0, 0)))
            expect(poly.containsPoint(P(0, 5)))
            expect(poly.containsPoint(P(3, 5)))
            expect(!poly.containsPoint(P(5, 5)))
        }
    }

    @Test
    fun testPolyIntersectsPoint() {
        var small = listOf<Point>(P(4, 4), P(6, 4), P(6, 6), P(4, 6), P(4, 4))
        dualPolyTest(octagon, listOf<List<Point>>(small)) { poly ->
            expect(!poly.intersectsPoint(P(0, 0)))
            expect(poly.intersectsPoint(P(0, 5)))
            expect(poly.intersectsPoint(P(3, 5)))
            expect(!poly.intersectsPoint(P(5, 5)))
        }
        expect(!(Poly(null)).intersectsPoint(P(0, 0)))
    }

    @Test
    fun testPolyContainsRect() {
        var ring = listOf<Point>(P(0, 0), P(10, 0), P(10, 10), P(0, 10), P(0, 0))
        var hole = listOf<Point>(P(4, 4), P(6, 4), P(6, 6), P(4, 6), P(4, 4))
        dualPolyTest(ring, listOf<List<Point>>(hole)) { poly ->
            expect(poly.containsRect(R(0, 0, 4, 4)))
            expect(!poly.containsRect(R(0, 0, 5, 5)))
            expect(!poly.containsRect(R(2, 2, 6, 6)))
            expect(!poly.containsRect(R(4.1, 4.1, 5.9, 5.9)))
            expect(!poly.containsRect(R(4.1, 4.1, 5.9, 5.9)))
        }
    }

    @Test
    fun testPolyIntersectsRect() {
        var small = listOf<Point>(P(4, 4), P(6, 4), P(6, 6), P(4, 6), P(4, 4))
        dualPolyTest(octagon, listOf<List<Point>>(small)) { poly ->
            expect(poly.intersectsRect(R(0, 4, 4, 6)))
            expect(poly.intersectsRect(R(-1, 4, 4, 6)))
            expect(poly.intersectsRect(R(4, 4, 6, 6)))
            expect(!poly.intersectsRect(R(4.1, 4.1, 5.9, 5.9)))
            expect(!poly.intersectsRect(R(0, 0, 1.4, 1.4)))
            expect(poly.intersectsRect(R(0, 0, 1.5, 1.5)))
            expect(!poly.intersectsRect(R(0, 0, 10, 10).move(11, 0)))
        }
    }

    @Test
    fun testPolyContainsLine() {
        var small = listOf<Point>(P(4, 4), P(6, 4), P(6, 6), P(4, 6), P(4, 4))
        dualPolyTest(octagon, listOf<List<Point>>(small)) { poly ->
            expect(poly.containsLine(L(P(3, 3), P(3, 7), P(7, 7), P(7, 3))))
            expect(!poly.containsLine(L(P(-1, 3), P(3, 7), P(7, 7), P(7, 3))))
            expect(poly.containsLine(L(P(4, 3), P(3, 7), P(7, 7), P(7, 3))))
            expect(!poly.containsLine(L(P(5, 3), P(3, 7), P(7, 7), P(7, 3))))
        }
    }

    @Test
    fun testPolyIntersectsLine() {
        var holes = listOf<List<Point>>(listOf<Point>(P(4, 4), P(6, 4), P(6, 6), P(4, 6), P(4, 4)))
        dualPolyTest(octagon, holes) { poly ->
            expect(poly.intersectsLine(L(P(3, 3), P(4, 4))))
            expect(poly.intersectsLine(L(P(-1, 3), P(3, 7), P(7, 7), P(7, 3))))
            expect(poly.intersectsLine(L(P(4, 3), P(3, 7), P(7, 7), P(7, 3))))
            expect(poly.intersectsLine(L(P(5, 3), P(3, 7), P(7, 7), P(7, 3))))
            expect(
                !poly.intersectsLine(
                    L(P(5, 3), P(3, 7), P(7, 7), P(7, 3)).move(11, 0),
                ),
            )
        }
    }

    @Test
    fun testPolyContainsPoly() {
        var holes1 = listOf<List<Point>>(listOf<Point>(P(4, 4), P(6, 4), P(6, 6), P(4, 6), P(4, 4)))
        var holes2 = listOf<List<Point>>(listOf<Point>(P(5, 4), P(7, 4), P(7, 6), P(5, 6), P(5, 4)))
        var poly1 = Poly.of(octagon, holes1, IndexOptions.DEFAULT)
        var poly2 = Poly.of(octagon, holes2, IndexOptions.DEFAULT)

        expect(!poly1.containsPoly(Poly.of(holes2[0], null, IndexOptions.DEFAULT)))
        expect(!poly1.containsPoly(poly2))

        dualPolyTest(octagon, holes1) { poly ->
            expect(poly.containsPoly(poly1))
            expect(!poly.containsPoly(poly1.move(1, 0)))
            expect(poly.containsPoly(Poly.of(holes1[0], null, IndexOptions.DEFAULT)))
            expect(!poly.containsPoly(Poly.of(holes2[0], null, IndexOptions.DEFAULT)))
        }
    }

    @Test
    fun testPolyClockwise() {
        expect(!Poly.of(bowtie, null, IndexOptions.DEFAULT).clockwise())
    }

    @Test
    fun test369() {
        var polyHoles =
            Poly.of(
                listOf<Point>(
                    P(-122.44154334068298, 37.73179457567642),
                    P(-122.43935465812682, 37.73179457567642),
                    P(-122.43935465812682, 37.7343740514423),
                    P(-122.44154334068298, 37.7343740514423),
                    P(-122.44154334068298, 37.73179457567642),
                ),
                listOf<List<Point>>(
                    listOf<Point>(
                        P(-122.44104981422423, 37.73286371140448),
                        P(-122.44104981422423, 37.73424677678513),
                        P(-122.43990182876587, 37.73424677678513),
                        P(-122.43990182876587, 37.73286371140448),
                        P(-122.44104981422423, 37.73286371140448),
                    ),
                    listOf<Point>(
                        P(-122.44109272956847, 37.731870943026074),
                        P(-122.43976235389708, 37.731870943026074),
                        P(-122.43976235389708, 37.7326855231885),
                        P(-122.44109272956847, 37.7326855231885),
                        P(-122.44109272956847, 37.731870943026074),
                    ),
                ),
                IndexOptions.DEFAULT,
            )
        var a =
            Poly.of(
                listOf<Point>(
                    P(-122.4408378, 37.7341129),
                    P(-122.4408378, 37.733),
                    P(-122.44, 37.733),
                    P(-122.44, 37.7343129),
                    P(-122.4408378, 37.7341129),
                ),
                null,
                IndexOptions.DEFAULT,
            )
        var b =
            Poly.of(
                listOf<Point>(
                    P(-122.44091033935547, 37.731981251280985),
                    P(-122.43994474411011, 37.731981251280985),
                    P(-122.43994474411011, 37.73254976045042),
                    P(-122.44091033935547, 37.73254976045042),
                    P(-122.44091033935547, 37.731981251280985),
                ),
                null,
                IndexOptions.DEFAULT,
            )
        var c =
            Poly.of(
                listOf<Point>(
                    P(-122.4408378, 37.7341129),
                    P(-122.4408378, 37.733),
                    P(-122.44, 37.733),
                    P(-122.44, 37.7341129),
                    P(-122.4408378, 37.7341129),
                ),
                null,
                IndexOptions.DEFAULT,
            )
        var d =
            Poly.of(
                listOf<Point>(
                    P(-182.4408378, 37.7341129),
                    P(-122.4408378, 37.733),
                    P(-122.44, 37.733),
                    P(-122.44, 37.7341129),
                    P(-122.4408378, 137.7341129),
                ),
                null,
                IndexOptions.DEFAULT,
            )
        expect(polyHoles.intersectsPoly(a))
        expect(!polyHoles.intersectsPoly(b))
        expect(!polyHoles.intersectsPoly(c))
        expect(a.intersectsPoly(polyHoles))
        expect(!b.intersectsPoly(polyHoles))
        expect(!c.intersectsPoly(polyHoles))
        expect(a.valid())
        expect(b.valid())
        expect(c.valid())
        expect(!d.valid())
    }

    @Test
    fun testRectCenter() {
        expect(R(0, 0, 10, 10).center() == P(5, 5))
        expect(R(0, 0, 0, 0).center() == P(0, 0))
    }

    @Test
    fun testRectArea() {
        expect(R(0, 0, 10, 10).area() == 100.0)
    }

    @Test
    fun testRectMove() {
        expect(R(1, 2, 3, 4).move(5, 6) == R(6, 8, 8, 10))
    }

    @Test
    fun testRectIndex() {
        expect((R(0, 0, 0, 0)).index() == null)
    }

    @Test
    fun testRectNumPoints() {
        expect(R(0, 0, 10, 10).numPoints() == 5)
    }

    @Test
    fun testRectNumSegments() {
        expect(R(0, 0, 10, 10).numSegments() == 4)
    }

    @Test
    fun testRectPointAt() {
        expect(R(0, 0, 10, 10).pointAt(0) == P(0, 0))
        expect(R(0, 0, 10, 10).pointAt(1) == P(10, 0))
        expect(R(0, 0, 10, 10).pointAt(2) == P(10, 10))
        expect(R(0, 0, 10, 10).pointAt(3) == P(0, 10))
        expect(R(0, 0, 10, 10).pointAt(4) == P(0, 0))
        kotlin.test.assertFails { R(0, 0, 10, 10).pointAt(5) }
    }

    @Test
    fun testRectSegmentAt() {
        expect(R(0, 0, 10, 10).segmentAt(0) == S(0, 0, 10, 0))
        expect(R(0, 0, 10, 10).segmentAt(1) == S(10, 0, 10, 10))
        expect(R(0, 0, 10, 10).segmentAt(2) == S(10, 10, 0, 10))
        expect(R(0, 0, 10, 10).segmentAt(3) == S(0, 10, 0, 0))
        kotlin.test.assertFails { R(0, 0, 10, 10).segmentAt(4) }
    }

    @Test
    fun testRectSearch() {
        var rect = R(0, 0, 10, 10)
        var count = 0
        rect.search(R(0, 0, 10, 10)) { seg, idx ->
            expect(rect.pointAt(idx) == seg.a)
            count++
            true
        }
        expect(count == 4)
        count = 0
        rect.search(R(0, 4, 10, 5)) { seg, idx ->
            expect(rect.pointAt(idx) == seg.a)
            count++
            true
        }
        expect(count == 2)
        count = 0
        rect.search(R(0, 4, 10, 5)) { seg, idx ->
            expect(rect.pointAt(idx) == seg.a)
            count++
            false
        }
        expect(count == 1)
    }

    @Test
    fun testRectEmpty() {
        expect(!R(0, 0, 10, 10).empty())
    }

    @Test
    fun testRectValid() {
        expect(R(0, 0, 10, 10).valid())
    }

    @Test
    fun testRectRect() {
        expect(R(0, 0, 10, 10).rect() == R(0, 0, 10, 10))
    }

    @Test
    fun testRectConvex() {
        expect(R(0, 0, 10, 10).convex())
    }

    @Test
    fun testRectContainsPoint() {
        for (xi in 0..10) {
            val x = xi.toDouble()
            for (yi in 0..10) {
                val y = yi.toDouble()
                expect(R(0, 0, 10, 10).containsPoint(P(x, y)))
            }
        }
        expect(!R(0, 0, 10, 10).containsPoint(P(-15, -15)))
        expect(!R(0, 0, 10, 10).containsPoint(P(-15, 5)))
        expect(!R(0, 0, 10, 10).containsPoint(P(-15, 15)))
        expect(!R(0, 0, 10, 10).containsPoint(P(0, -15)))
    }

    @Test
    fun testRectIntersectsPoint() {
        expect(R(0, 0, 10, 10).intersectsPoint(P(5, 5)))
        expect(!R(0, 0, 10, 10).intersectsPoint(P(15, 15)))
    }

    @Test
    fun testRectContainsRect() {
        expect(R(0, 0, 10, 10).containsRect(R(0, 0, 10, 10)))
        expect(R(0, 0, 10, 10).containsRect(R(2, 2, 10, 10)))
        expect(R(0, 0, 10, 10).containsRect(R(2, 2, 8, 8)))
        expect(R(0, 0, 10, 10).containsRect(R(0, 0, 8, 8)))
        expect(R(0, 0, 10, 10).containsRect(R(0, 2, 8, 8)))
        expect(R(0, 0, 10, 10).containsRect(R(2, 0, 8, 8)))
        expect(R(0, 0, 10, 10).containsRect(R(2, 2, 10, 8)))
        expect(R(0, 0, 10, 10).containsRect(R(2, 2, 8, 10)))
        expect(!R(0, 0, 10, 10).containsRect(R(-1, 0, 10, 10)))
        expect(!R(0, 0, 10, 10).containsRect(R(0, -1, 10, 10)))
        expect(!R(0, 0, 10, 10).containsRect(R(0, 0, 11, 10)))
        expect(!R(0, 0, 10, 10).containsRect(R(0, 0, 10, 11)))
    }

    @Test
    fun testRectIntersectsRect() {
        expect(R(0, 0, 10, 10).intersectsRect(R(0, 0, 10, 10)))
        expect(R(0, 0, 10, 10).intersectsRect(R(2, 2, 8, 8)))
        expect(R(0, 0, 10, 10).intersectsRect(R(-1, 0, 10, 10)))
        expect(R(0, 0, 10, 10).intersectsRect(R(0, -1, 10, 10)))
        expect(R(0, 0, 10, 10).intersectsRect(R(0, 0, 11, 10)))
        expect(R(0, 0, 10, 10).intersectsRect(R(0, 0, 10, 11)))
        expect(!R(0, 0, 10, 10).intersectsRect(R(11, 0, 21, 10)))
        expect(!R(0, 0, 10, 10).intersectsRect(R(0, 11, 10, 21)))
        expect(!R(0, 0, 10, 10).intersectsRect(R(11, 0, 21, 10)))
        expect(!R(0, 0, 10, 10).intersectsRect(R(11, 11, 21, 21)))
        expect(!R(0, 0, 10, 10).intersectsRect(R(-11, 11, 1, 21)))
        expect(!R(0, 0, 10, 10).intersectsRect(R(-11, -11, -1, -1)))
    }

    @Test
    fun testRectContainsLine() {
        expect(R(0, 0, 10, 10).containsLine(L(P(0, 0), P(10, 10))))
        expect(!R(0, 0, 10, 10).containsLine(L(P(0, 0), P(11, 11))))
        expect(!R(0, 0, 10, 10).containsLine(L()))
    }

    @Test
    fun testRectIntersectsLine() {
        expect(R(0, 0, 10, 10).intersectsLine(L(P(0, 0), P(10, 10))))
        expect(R(0, 0, 10, 10).intersectsLine(L(P(0, 0), P(11, 11))))
        expect(!R(0, 0, 10, 10).intersectsLine(L()))
        expect(!R(0, 0, 10, 10).intersectsLine(L(P(11, 11), P(12, 12))))
    }

    @Test
    fun testRectContainsPoly() {
        var oct = Poly.of(octagon, null, IndexOptions.DEFAULT)
        expect(R(0, 0, 10, 10).containsPoly(oct))
        expect(!R(0, 0, 10, 10).containsPoly(oct.move(1, 0)))
        expect(!R(0, 0, 10, 10).containsPoly(oct.move(1, 1)))
        expect(!R(0, 0, 10, 10).containsPoly(Poly.of(emptyList(), null, IndexOptions.DEFAULT)))
    }

    @Test
    fun testRectIntersectsPoly() {
        var oct = Poly.of(octagon, null, IndexOptions.DEFAULT)
        expect(R(0, 0, 10, 10).intersectsPoly(oct))
        expect(R(0, 0, 10, 10).intersectsPoly(oct.move(1, 0)))
        expect(R(0, 0, 10, 10).intersectsPoly(oct.move(0, 1)))
        expect(!R(0, 0, 10, 10).intersectsPoly(oct.move(10, 10)))
        expect(!R(0, 0, 10, 10).intersectsPoly(oct.move(11, 10)))
        expect(!R(0, 0, 10, 10).intersectsPoly(oct.move(-11, 0)))
        expect(!R(0, 0, 10, 10).intersectsPoly(Poly.of(emptyList(), null, IndexOptions.DEFAULT)))
    }

    @Test
    fun testRectClockwise() {
        expect(!R(10, 11, 12, 13).clockwise())
    }
}
