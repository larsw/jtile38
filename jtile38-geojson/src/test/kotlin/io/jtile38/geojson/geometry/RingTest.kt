package io.jtile38.geojson.geometry

import kotlin.test.Test
import kotlin.test.assertTrue

@Suppress("UNUSED_VARIABLE", "UNUSED_VALUE", "VARIABLE_WITH_REDUNDANT_INITIALIZER", "CanBeVal", "NAME_SHADOWING")
class RingTest {
    @Test
    fun testRectGeometryDefaults() {
        val g: Geometry = R(0, 0, 0, 0)
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
    fun testRingXContainsPoint() {
        testDualRingX(octagon) { ring ->
            expect(!Rings.containsPoint(ring, P(0, 0), true).hit)
            expect(Rings.containsPoint(ring, P(0, 5), true).hit)
            expect(!Rings.containsPoint(ring, P(0, 5), false).hit)
            expect(Rings.containsPoint(ring, P(4, 4), true).hit)
            expect(!Rings.containsPoint(ring, P(1.4, 1.4), true).hit)
            expect(Rings.containsPoint(ring, P(1.5, 1.5), true).hit)
            expect(!Rings.containsPoint(ring, P(1.5, 1.5), false).hit)
        }
        var shape =
            listOf<Point>(
                P(0, 0),
                P(4, 0),
                P(4, 3),
                P(3, 4),
                P(1, 4),
                P(0, 3),
                P(0, 0),
            )
        var ring = Rings.newRing(shape, IndexOptions.DEFAULT)
        expect(!Rings.containsPoint(ring, P(0, 3.5), true).hit)
        expect(!Rings.containsPoint(ring, P(0.4, 3.5), true).hit)
        expect(Rings.containsPoint(ring, P(0.5, 3.5), true).hit)
        expect(Rings.containsPoint(ring, P(0.6, 3.5), true).hit)
        expect(Rings.containsPoint(ring, P(1, 3.5), true).hit)
        expect(Rings.containsPoint(ring, P(3, 3.5), true).hit)
        expect(Rings.containsPoint(ring, P(3.4, 3.5), true).hit)
        expect(Rings.containsPoint(ring, P(3.5, 3.5), true).hit)
        expect(!Rings.containsPoint(ring, P(3.9, 3.5), true).hit)
        expect(!Rings.containsPoint(ring, P(4, 3.5), true).hit)
        expect(!Rings.containsPoint(ring, P(4.1, 3.5), true).hit)
    }

    @Test
    fun testRingXIntersectsPoint() {
        testDualRingX(octagon) { ring ->
            expect(!Rings.intersectsPoint(ring, P(0, 0), true).hit)
            expect(Rings.intersectsPoint(ring, P(0, 5), true).hit)
            expect(!Rings.intersectsPoint(ring, P(0, 5), false).hit)
            expect(Rings.intersectsPoint(ring, P(4, 4), true).hit)
            expect(!Rings.intersectsPoint(ring, P(1.4, 1.4), true).hit)
            expect(Rings.intersectsPoint(ring, P(1.5, 1.5), true).hit)
            expect(!Rings.intersectsPoint(ring, P(1.5, 1.5), false).hit)
        }
    }

    @Test
    fun testRingXIntersectsSegment() {
        run {
            var ring =
                Rings.newRing(
                    listOf<Point>(
                        P(0, 0),
                        P(4, 0),
                        P(4, 4),
                        P(0, 4),
                        P(0, 0),
                    ),
                    IndexOptions.DEFAULT,
                )
            run {
                expect(Rings.intersectsSegment(ring, S(2, 2, 4, 4), true))
                expect(Rings.intersectsSegment(ring, S(2, 2, 4, 4), false))
            }
            run {
                expect(Rings.intersectsSegment(ring, S(2, 0, 4, 2), true))
                expect(Rings.intersectsSegment(ring, S(2, 0, 4, 2), false))
            }
            run {
                expect(Rings.intersectsSegment(ring, S(1, 0, 3, 0), true))
                expect(!Rings.intersectsSegment(ring, S(1, 0, 3, 0), false))
            }
            run {
                expect(Rings.intersectsSegment(ring, S(2, 4, 2, 0), true))
                expect(Rings.intersectsSegment(ring, S(2, 4, 2, 0), false))
            }
            run {
                expect(Rings.intersectsSegment(ring, S(2, 0, 2, -3), true))
                expect(!Rings.intersectsSegment(ring, S(2, 0, 2, -3), false))
            }
            run {
                expect(!Rings.intersectsSegment(ring, S(2, -1, 2, -3), true))
                expect(!Rings.intersectsSegment(ring, S(2, -1, 2, -3), false))
            }
            run {
                expect(Rings.intersectsSegment(ring, S(-1, 2, 3, 2), true))
                expect(Rings.intersectsSegment(ring, S(-1, 2, 3, 2), false))
            }
            run {
                expect(Rings.intersectsSegment(ring, S(-1, 3, 2, 1), true))
                expect(Rings.intersectsSegment(ring, S(-1, 3, 2, 1), false))
            }
            run {
                expect(Rings.intersectsSegment(ring, S(-1, 2, 5, 1), true))
                expect(Rings.intersectsSegment(ring, S(-1, 2, 5, 1), false))
            }
            run {
                expect(Rings.intersectsSegment(ring, S(2, 5, 1, -1), true))
                expect(Rings.intersectsSegment(ring, S(2, 5, 1, -1), false))
            }
            run {
                expect(Rings.intersectsSegment(ring, S(0, 4, 4, 0), true))
                expect(Rings.intersectsSegment(ring, S(0, 4, 4, 0), false))
            }
            run {
                var ring =
                    Rings.newRing(
                        listOf<Point>(
                            P(0, 0),
                            P(2, 0),
                            P(4, 0),
                            P(4, 4),
                            P(0, 4),
                            P(0, 0),
                        ),
                        IndexOptions.DEFAULT,
                    )
                expect(Rings.intersectsSegment(ring, S(1, 0, 3, 0), true))
                expect(!Rings.intersectsSegment(ring, S(1, 0, 3, 0), false))
            }
            run {
                var ring =
                    Rings.newRing(
                        listOf<Point>(
                            P(0, 0),
                            P(4, 0),
                            P(4, 4),
                            P(2, 4),
                            P(0, 4),
                            P(0, 0),
                        ),
                        IndexOptions.DEFAULT,
                    )
                expect(Rings.intersectsSegment(ring, S(0, 4, 4, 4), true))
                expect(!Rings.intersectsSegment(ring, S(0, 4, 4, 4), false))
            }
            run {
                var ring =
                    Rings.newRing(
                        listOf<Point>(
                            P(0, 0),
                            P(4, 0),
                            P(4, 4),
                            P(2, 4),
                            P(0, 4),
                            P(0, 0),
                        ),
                        IndexOptions.DEFAULT,
                    )
                expect(Rings.intersectsSegment(ring, S(-1, -2, 0, 0), true))
                expect(!Rings.intersectsSegment(ring, S(-1, -2, 0, 0), false))
            }
            run {
                var ring =
                    Rings.newRing(
                        listOf<Point>(
                            P(0, 0),
                            P(4, 0),
                            P(4, 4),
                            P(2, 4),
                            P(0, 4),
                            P(0, 0),
                        ),
                        IndexOptions.DEFAULT,
                    )
                expect(Rings.intersectsSegment(ring, S(0, 4, 5, 4), true))
                expect(!Rings.intersectsSegment(ring, S(0, 4, 5, 4), false))
            }
            run {
                var ring =
                    Rings.newRing(
                        listOf<Point>(
                            P(0, 0),
                            P(4, 0),
                            P(4, 4),
                            P(2, 4),
                            P(0, 4),
                            P(0, 0),
                        ),
                        IndexOptions.DEFAULT,
                    )
                expect(Rings.intersectsSegment(ring, S(1, 4, 5, 4), true))
                expect(!Rings.intersectsSegment(ring, S(1, 4, 5, 4), false))
            }
            run {
                var ring =
                    Rings.newRing(
                        listOf<Point>(
                            P(0, 0),
                            P(4, 0),
                            P(4, 4),
                            P(2, 4),
                            P(0, 4),
                            P(0, 0),
                        ),
                        IndexOptions.DEFAULT,
                    )
                expect(Rings.intersectsSegment(ring, S(1, 4, 4, 4), true))
                expect(!Rings.intersectsSegment(ring, S(1, 4, 4, 4), false))
            }
        }

        run {
            var shape = octagon
            run {
                var segs =
                    listOf<Segment>(
                        S(0, 5, -5, 5).move(-1, 0),
                        S(1.5, 1.5, -3.5, -3.5).move(-1, -1),
                        S(5, 0, 5, -5).move(0, -1),
                        S(8.5, 1.5, 13.5, -3.5).move(1, -1),
                        S(10, 5, 15, 5).move(1, 0),
                        S(8.5, 8.5, 13.5, 13.5).move(1, 1),
                        S(5, 10, 5, 15).move(0, 5),
                        S(1.5, 8.5, -3.5, 13.5).move(-1, 1),
                    )
                for ((i, seg__) in (segs).withIndex()) {
                    var seg = seg__
                    run {
                        testDualRingX(shape) { ring ->
                            expect(!Rings.intersectsSegment(ring, seg, true))
                            expect(!Rings.intersectsSegment(ring, seg, false))
                            seg = Segment(seg.b, seg.a)
                            expect(!Rings.intersectsSegment(ring, seg, true))
                            expect(!Rings.intersectsSegment(ring, seg, false))
                        }
                    }
                }
            }
            run {
                var segs =
                    listOf<Segment>(
                        S(0, 5, -5, 5),
                        S(1.5, 1.5, -3.5, -3.5),
                        S(5, 0, 5, -5),
                        S(8.5, 1.5, 13.5, -3.5),
                        S(10, 5, 15, 5),
                        S(8.5, 8.5, 13.5, 13.5),
                        S(5, 10, 5, 15),
                        S(1.5, 8.5, -3.5, 13.5),
                    )
                for ((i, seg__) in (segs).withIndex()) {
                    var seg = seg__
                    run {
                        testDualRingX(shape) { ring ->
                            expect(Rings.intersectsSegment(ring, seg, true))
                            expect(!Rings.intersectsSegment(ring, seg, false))
                            seg = Segment(seg.b, seg.a)
                            expect(Rings.intersectsSegment(ring, seg, true))
                            expect(!Rings.intersectsSegment(ring, seg, false))
                        }
                    }
                }
            }
            run {
                var segs =
                    listOf<Segment>(
                        S(0, 5, -5, 5).move(2.5, 0),
                        S(1.5, 1.5, -3.5, -3.5).move(2.5, 2.5),
                        S(5, 0, 5, -5).move(0, 2.5),
                        S(8.5, 1.5, 13.5, -3.5).move(-2.5, 2.5),
                        S(10, 5, 15, 5).move(-2.5, 0),
                        S(8.5, 8.5, 13.5, 13.5).move(-2.5, -2.5),
                        S(5, 10, 5, 15).move(0, -2.5),
                        S(1.5, 8.5, -3.5, 13.5).move(2.5, -2.5),
                    )
                for ((i, seg__) in (segs).withIndex()) {
                    var seg = seg__
                    run {
                        testDualRingX(shape) { ring ->
                            expect(Rings.intersectsSegment(ring, seg, true))
                            expect(Rings.intersectsSegment(ring, seg, false))
                            seg = Segment(seg.b, seg.a)
                            expect(Rings.intersectsSegment(ring, seg, true))
                            expect(Rings.intersectsSegment(ring, seg, false))
                        }
                    }
                }
            }
            run {
                var segs =
                    listOf<Segment>(
                        S(0, 5, -5, 5).move(5, 0),
                        S(1.5, 1.5, -3.5, -3.5).move(5, 5),
                        S(5, 0, 5, -5).move(0, 5),
                        S(8.5, 1.5, 13.5, -3.5).move(-5, 5),
                        S(10, 5, 15, 5).move(-5, 0),
                        S(8.5, 8.5, 13.5, 13.5).move(-5, -5),
                        S(5, 10, 5, 15).move(0, -5),
                        S(1.5, 8.5, -3.5, 13.5).move(5, -5),
                    )
                for ((i, seg__) in (segs).withIndex()) {
                    var seg = seg__
                    run {
                        testDualRingX(shape) { ring ->
                            expect(Rings.intersectsSegment(ring, seg, true))
                            expect(Rings.intersectsSegment(ring, seg, false))
                            seg = Segment(seg.b, seg.a)
                            expect(Rings.intersectsSegment(ring, seg, true))
                            expect(Rings.intersectsSegment(ring, seg, false))
                        }
                    }
                }
            }
            run {
                var segs =
                    listOf<Segment>(
                        S(0, 5, -5, 5).move(6, 0),
                        S(1.5, 1.5, -3.5, -3.5).move(6, 6),
                        S(5, 0, 5, -5).move(0, 6),
                        S(8.5, 1.5, 13.5, -3.5).move(-6, 6),
                        S(10, 5, 15, 5).move(-6, 0),
                        S(8.5, 8.5, 13.5, 13.5).move(-6, -6),
                        S(5, 10, 5, 15).move(0, -6),
                        S(1.5, 8.5, -3.5, 13.5).move(6, -6),
                    )
                for ((i, seg__) in (segs).withIndex()) {
                    var seg = seg__
                    run {
                        testDualRingX(shape) { ring ->
                            expect(Rings.intersectsSegment(ring, seg, true))
                            expect(Rings.intersectsSegment(ring, seg, false))
                            seg = Segment(seg.b, seg.a)
                            expect(Rings.intersectsSegment(ring, seg, true))
                            expect(Rings.intersectsSegment(ring, seg, false))
                        }
                    }
                }
            }
            run {
                var segs =
                    listOf<Segment>(
                        S(0, 5, 10, 5),
                        S(1.5, 1.5, 8.5, 8.5),
                        S(5, 0, 5, 10),
                        S(8.5, 1.5, 1.5, 8.5),
                    )
                for ((i, seg__) in (segs).withIndex()) {
                    var seg = seg__
                    run {
                        testDualRingX(shape) { ring ->
                            expect(Rings.intersectsSegment(ring, seg, true))
                            expect(Rings.intersectsSegment(ring, seg, false))
                            seg = Segment(seg.b, seg.a)
                            expect(Rings.intersectsSegment(ring, seg, true))
                            expect(Rings.intersectsSegment(ring, seg, false))
                        }
                    }
                }
            }
            run {
                var segs =
                    listOf<Segment>(
                        S(-1, 5, 11, 5),
                        S(0.5, 0.5, 9.5, 9.5),
                        S(5, -1, 5, 11),
                        S(9.5, 0.5, 0.5, 9.5),
                    )
                for ((i, seg__) in (segs).withIndex()) {
                    var seg = seg__
                    run {
                        testDualRingX(shape) { ring ->
                            expect(Rings.intersectsSegment(ring, seg, true))
                            expect(Rings.intersectsSegment(ring, seg, false))
                            seg = Segment(seg.b, seg.a)
                            expect(Rings.intersectsSegment(ring, seg, true))
                            expect(Rings.intersectsSegment(ring, seg, false))
                        }
                    }
                }
            }
        }

        run {
            var shape = concave1
            run {
                var segs =
                    listOf<Segment>(
                        S(0, 7.5, -5, 7.5).move(-1, 0),
                        S(2.5, 5, 2.5, 0).move(0, -1),
                        S(5, 2.5, 0, 2.5).move(-1, 0),
                        S(7.5, 0, 7.5, -5).move(0, -1),
                        S(10, 5, 15, 5).move(1, 0),
                        S(5, 10, 5, 15).move(0, 1),
                    )
                for ((i, seg__) in (segs).withIndex()) {
                    var seg = seg__
                    run {
                        testDualRingX(shape) { ring ->
                            expect(!Rings.intersectsSegment(ring, seg, true))
                            expect(!Rings.intersectsSegment(ring, seg, false))
                            seg = Segment(seg.b, seg.a)
                            expect(!Rings.intersectsSegment(ring, seg, true))
                            expect(!Rings.intersectsSegment(ring, seg, false))
                        }
                    }
                }
            }
            run {
                var segs =
                    listOf<Segment>(
                        S(0, 7.5, -5, 7.5),
                        S(2.5, 5, 2.5, 0),
                        S(5, 2.5, 0, 2.5),
                        S(7.5, 0, 7.5, -5),
                        S(10, 5, 15, 5),
                        S(5, 10, 5, 15),
                    )
                for ((i, seg__) in (segs).withIndex()) {
                    var seg = seg__
                    run {
                        testDualRingX(shape) { ring ->
                            expect(Rings.intersectsSegment(ring, seg, true))
                            expect(!Rings.intersectsSegment(ring, seg, false))
                            seg = Segment(seg.b, seg.a)
                            expect(Rings.intersectsSegment(ring, seg, true))
                            expect(!Rings.intersectsSegment(ring, seg, false))
                        }
                    }
                }
            }
            run {
                var segs =
                    listOf<Segment>(
                        S(0, 7.5, -5, 7.5).move(2.5, 0),
                        S(2.5, 5, 2.5, 0).move(0, 2.5),
                        S(5, 2.5, 0, 2.5).move(2.5, 0),
                        S(7.5, 0, 7.5, -5).move(0, 2.5),
                        S(10, 5, 15, 5).move(-2.5, 0),
                        S(5, 10, 5, 15).move(0, -2.5),
                    )
                for ((i, seg__) in (segs).withIndex()) {
                    var seg = seg__
                    run {
                        testDualRingX(shape) { ring ->
                            expect(Rings.intersectsSegment(ring, seg, true))
                            expect(Rings.intersectsSegment(ring, seg, false))
                            seg = Segment(seg.b, seg.a)
                            expect(Rings.intersectsSegment(ring, seg, true))
                            expect(Rings.intersectsSegment(ring, seg, false))
                        }
                    }
                }
            }
            run {
                var segs =
                    listOf<Segment>(
                        S(0, 7.5, -3, 7.5).move(3, 0),
                        S(2.5, 5, 2.5, 2).move(0, 3),
                        S(5, 2.5, 2, 2.5).move(3, 0),
                        S(7.5, 0, 7.5, -3).move(0, 3),
                        S(10, 5, 13, 5).move(-3, 0),
                        S(5, 10, 5, 13).move(0, -3),
                    )
                for ((i, seg__) in (segs).withIndex()) {
                    var seg = seg__
                    run {
                        testDualRingX(shape) { ring ->
                            expect(Rings.intersectsSegment(ring, seg, true))
                            expect(Rings.intersectsSegment(ring, seg, false))
                            seg = Segment(seg.b, seg.a)
                            expect(Rings.intersectsSegment(ring, seg, true))
                            expect(Rings.intersectsSegment(ring, seg, false))
                        }
                    }
                }
            }
            run {
                var segs =
                    listOf<Segment>(
                        S(0, 7.5, -3, 7.5).move(4, 0),
                        S(2.5, 5, 2.5, 2).move(0, 4),
                        S(5, 2.5, 2, 2.5).move(4, 0),
                        S(7.5, 0, 7.5, -3).move(0, 4),
                        S(10, 5, 13, 5).move(-4, 0),
                        S(5, 10, 5, 13).move(0, -4),
                    )
                for ((i, seg__) in (segs).withIndex()) {
                    var seg = seg__
                    run {
                        testDualRingX(shape) { ring ->
                            expect(Rings.intersectsSegment(ring, seg, true))
                            expect(Rings.intersectsSegment(ring, seg, false))
                            seg = Segment(seg.b, seg.a)
                            expect(Rings.intersectsSegment(ring, seg, true))
                            expect(Rings.intersectsSegment(ring, seg, false))
                        }
                    }
                }
            }
            run {
                var segs =
                    listOf<Segment>(
                        S(0, 7.5, 10, 7.5),
                        S(2.5, 5, 2.5, 10),
                        S(5, 2.5, 10, 2.5),
                        S(7.5, 0, 7.5, 10),
                    )
                for ((i, seg__) in (segs).withIndex()) {
                    var seg = seg__
                    run {
                        testDualRingX(shape) { ring ->
                            expect(Rings.intersectsSegment(ring, seg, true))
                            expect(Rings.intersectsSegment(ring, seg, false))
                            seg = Segment(seg.b, seg.a)
                            expect(Rings.intersectsSegment(ring, seg, true))
                            expect(Rings.intersectsSegment(ring, seg, false))
                        }
                    }
                }
            }
            run {
                var segs =
                    listOf<Segment>(
                        S(-1, 7.5, 11, 7.5),
                        S(2.5, 4, 2.5, 11),
                        S(4, 2.5, 11, 2.5),
                        S(7.5, -1, 7.5, 11),
                    )
                for ((i, seg__) in (segs).withIndex()) {
                    var seg = seg__
                    run {
                        testDualRingX(shape) { ring ->
                            expect(Rings.intersectsSegment(ring, seg, true))
                            expect(Rings.intersectsSegment(ring, seg, false))
                            seg = Segment(seg.b, seg.a)
                            expect(Rings.intersectsSegment(ring, seg, true))
                            expect(Rings.intersectsSegment(ring, seg, false))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun testRingXContainsSegment() {
        expect(Rings.containsSegment(R(0, 0, 10, 10), S(5, 5, 5, 5), true))
        expect(Rings.containsSegment(R(0, 0, 10, 10), S(5, 5, 5, 5), false))
        expect(Rings.containsSegment(R(0, 0, 10, 10), S(0, 0, 0, 0), true))
        expect(!Rings.containsSegment(R(0, 0, 10, 10), S(0, 0, 0, 0), false))
        expect(Rings.containsSegment(R(0, 0, 10, 10), S(10, 10, 10, 10), true))
        expect(!Rings.containsSegment(R(0, 0, 10, 10), S(10, 10, 10, 10), false))

        run {
            var shape = octagon
            run {
                var segs =
                    listOf<Segment>(
                        S(0, 5, -5, 5).move(-1, 0),
                        S(1.5, 1.5, -3.5, -3.5).move(-1, -1),
                        S(5, 0, 5, -5).move(0, -1),
                        S(8.5, 1.5, 13.5, -3.5).move(1, -1),
                        S(10, 5, 15, 5).move(1, 0),
                        S(8.5, 8.5, 13.5, 13.5).move(1, 1),
                        S(5, 10, 5, 15).move(0, 5),
                        S(1.5, 8.5, -3.5, 13.5).move(-1, 1),
                    )
                for ((i, seg__) in (segs).withIndex()) {
                    var seg = seg__
                    run {
                        testDualRingX(shape) { ring ->
                            expect(!Rings.containsSegment(ring, seg, true))
                            expect(!Rings.containsSegment(ring, seg, false))
                            seg = Segment(seg.b, seg.a)
                            expect(!Rings.containsSegment(ring, seg, true))
                            expect(!Rings.containsSegment(ring, seg, false))
                        }
                    }
                }
            }
            run {
                var segs =
                    listOf<Segment>(
                        S(0, 5, -5, 5),
                        S(1.5, 1.5, -3.5, -3.5),
                        S(5, 0, 5, -5),
                        S(8.5, 1.5, 13.5, -3.5),
                        S(10, 5, 15, 5),
                        S(8.5, 8.5, 13.5, 13.5),
                        S(5, 10, 5, 15),
                        S(1.5, 8.5, -3.5, 13.5),
                    )
                for ((i, seg__) in (segs).withIndex()) {
                    var seg = seg__
                    run {
                        testDualRingX(shape) { ring ->
                            expect(!Rings.containsSegment(ring, seg, true))
                            expect(!Rings.containsSegment(ring, seg, false))
                            seg = Segment(seg.b, seg.a)
                            expect(!Rings.containsSegment(ring, seg, true))
                            expect(!Rings.containsSegment(ring, seg, false))
                        }
                    }
                }
            }
            run {
                var segs =
                    listOf<Segment>(
                        S(0, 5, -5, 5).move(2.5, 0),
                        S(1.5, 1.5, -3.5, -3.5).move(2.5, 2.5),
                        S(5, 0, 5, -5).move(0, 2.5),
                        S(8.5, 1.5, 13.5, -3.5).move(-2.5, 2.5),
                        S(10, 5, 15, 5).move(-2.5, 0),
                        S(8.5, 8.5, 13.5, 13.5).move(-2.5, -2.5),
                        S(5, 10, 5, 15).move(0, -2.5),
                        S(1.5, 8.5, -3.5, 13.5).move(2.5, -2.5),
                    )
                for ((i, seg__) in (segs).withIndex()) {
                    var seg = seg__
                    run {
                        testDualRingX(shape) { ring ->
                            expect(!Rings.containsSegment(ring, seg, true))
                            expect(!Rings.containsSegment(ring, seg, false))
                            seg = Segment(seg.b, seg.a)
                            expect(!Rings.containsSegment(ring, seg, true))
                            expect(!Rings.containsSegment(ring, seg, false))
                        }
                    }
                }
            }
            run {
                var segs =
                    listOf<Segment>(
                        S(0, 5, -5, 5).move(5, 0),
                        S(1.5, 1.5, -3.5, -3.5).move(5, 5),
                        S(5, 0, 5, -5).move(0, 5),
                        S(8.5, 1.5, 13.5, -3.5).move(-5, 5),
                        S(10, 5, 15, 5).move(-5, 0),
                        S(8.5, 8.5, 13.5, 13.5).move(-5, -5),
                        S(5, 10, 5, 15).move(0, -5),
                        S(1.5, 8.5, -3.5, 13.5).move(5, -5),
                    )
                for ((i, seg__) in (segs).withIndex()) {
                    var seg = seg__
                    run {
                        testDualRingX(shape) { ring ->
                            expect(Rings.containsSegment(ring, seg, true))
                            expect(!Rings.containsSegment(ring, seg, false))
                            seg = Segment(seg.b, seg.a)
                            expect(Rings.containsSegment(ring, seg, true))
                            expect(!Rings.containsSegment(ring, seg, false))
                        }
                    }
                }
            }
            run {
                var segs =
                    listOf<Segment>(
                        S(0, 5, -5, 5).move(6, 0),
                        S(1.5, 1.5, -3.5, -3.5).move(6, 6),
                        S(5, 0, 5, -5).move(0, 6),
                        S(8.5, 1.5, 13.5, -3.5).move(-6, 6),
                        S(10, 5, 15, 5).move(-6, 0),
                        S(8.5, 8.5, 13.5, 13.5).move(-6, -6),
                        S(5, 10, 5, 15).move(0, -6),
                        S(1.5, 8.5, -3.5, 13.5).move(6, -6),
                    )
                for ((i, seg__) in (segs).withIndex()) {
                    var seg = seg__
                    run {
                        testDualRingX(shape) { ring ->
                            expect(Rings.containsSegment(ring, seg, true))
                            expect(Rings.containsSegment(ring, seg, false))
                            seg = Segment(seg.b, seg.a)
                            expect(Rings.containsSegment(ring, seg, true))
                            expect(Rings.containsSegment(ring, seg, false))
                        }
                    }
                }
            }
            run {
                var segs =
                    listOf<Segment>(
                        S(0, 5, 10, 5),
                        S(1.5, 1.5, 8.5, 8.5),
                        S(5, 0, 5, 10),
                        S(8.5, 1.5, 1.5, 8.5),
                    )
                for ((i, seg__) in (segs).withIndex()) {
                    var seg = seg__
                    run {
                        testDualRingX(shape) { ring ->
                            expect(Rings.containsSegment(ring, seg, true))
                            expect(!Rings.containsSegment(ring, seg, false))
                            seg = Segment(seg.b, seg.a)
                            expect(Rings.containsSegment(ring, seg, true))
                            expect(!Rings.containsSegment(ring, seg, false))
                        }
                    }
                }
            }
            run {
                var segs =
                    listOf<Segment>(
                        S(-1, 5, 11, 5),
                        S(0.5, 0.5, 9.5, 9.5),
                        S(5, -1, 5, 11),
                        S(9.5, 0.5, 0.5, 9.5),
                    )
                for ((i, seg__) in (segs).withIndex()) {
                    var seg = seg__
                    run {
                        testDualRingX(shape) { ring ->
                            expect(!Rings.containsSegment(ring, seg, true))
                            expect(!Rings.containsSegment(ring, seg, false))
                            seg = Segment(seg.b, seg.a)
                            expect(!Rings.containsSegment(ring, seg, true))
                            expect(!Rings.containsSegment(ring, seg, false))
                        }
                    }
                }
            }
        }

        run {
            var shape = concave1
            run {
                var segs =
                    listOf<Segment>(
                        S(0, 7.5, -5, 7.5).move(-1, 0),
                        S(2.5, 5, 2.5, 0).move(0, -1),
                        S(5, 2.5, 0, 2.5).move(-1, 0),
                        S(7.5, 0, 7.5, -5).move(0, -1),
                        S(10, 5, 15, 5).move(1, 0),
                        S(5, 10, 5, 15).move(0, 1),
                    )
                for ((i, seg__) in (segs).withIndex()) {
                    var seg = seg__
                    run {
                        testDualRingX(shape) { ring ->
                            expect(!Rings.containsSegment(ring, seg, true))
                            expect(!Rings.containsSegment(ring, seg, false))
                            seg = Segment(seg.b, seg.a)
                            expect(!Rings.containsSegment(ring, seg, true))
                            expect(!Rings.containsSegment(ring, seg, false))
                        }
                    }
                }
            }
            run {
                var segs =
                    listOf<Segment>(
                        S(0, 7.5, -5, 7.5),
                        S(2.5, 5, 2.5, 0),
                        S(5, 2.5, 0, 2.5),
                        S(7.5, 0, 7.5, -5),
                        S(10, 5, 15, 5),
                        S(5, 10, 5, 15),
                    )
                for ((i, seg__) in (segs).withIndex()) {
                    var seg = seg__
                    run {
                        testDualRingX(shape) { ring ->
                            expect(!Rings.containsSegment(ring, seg, true))
                            expect(!Rings.containsSegment(ring, seg, false))
                            seg = Segment(seg.b, seg.a)
                            expect(!Rings.containsSegment(ring, seg, true))
                            expect(!Rings.containsSegment(ring, seg, false))
                        }
                    }
                }
            }
            run {
                var segs =
                    listOf<Segment>(
                        S(0, 7.5, -5, 7.5).move(2.5, 0),
                        S(2.5, 5, 2.5, 0).move(0, 2.5),
                        S(5, 2.5, 0, 2.5).move(2.5, 0),
                        S(7.5, 0, 7.5, -5).move(0, 2.5),
                        S(10, 5, 15, 5).move(-2.5, 0),
                        S(5, 10, 5, 15).move(0, -2.5),
                    )
                for ((i, seg__) in (segs).withIndex()) {
                    var seg = seg__
                    run {
                        testDualRingX(shape) { ring ->
                            expect(!Rings.containsSegment(ring, seg, true))
                            expect(!Rings.containsSegment(ring, seg, false))
                            seg = Segment(seg.b, seg.a)
                            expect(!Rings.containsSegment(ring, seg, true))
                            expect(!Rings.containsSegment(ring, seg, false))
                        }
                    }
                }
            }
            run {
                var segs =
                    listOf<Segment>(
                        S(0, 7.5, -3, 7.5).move(3, 0),
                        S(2.5, 5, 2.5, 2).move(0, 3),
                        S(5, 2.5, 2, 2.5).move(3, 0),
                        S(7.5, 0, 7.5, -3).move(0, 3),
                        S(10, 5, 13, 5).move(-3, 0),
                        S(5, 10, 5, 13).move(0, -3),
                    )
                for ((i, seg__) in (segs).withIndex()) {
                    var seg = seg__
                    run {
                        testDualRingX(shape) { ring ->
                            expect(Rings.containsSegment(ring, seg, true))
                            expect(!Rings.containsSegment(ring, seg, false))
                            seg = Segment(seg.b, seg.a)
                            expect(Rings.containsSegment(ring, seg, true))
                            expect(!Rings.containsSegment(ring, seg, false))
                        }
                    }
                }
            }
            run {
                var segs =
                    listOf<Segment>(
                        S(0, 7.5, -3, 7.5).move(4, 0),
                        S(2.5, 5, 2.5, 2).move(0, 4),
                        S(5, 2.5, 2, 2.5).move(4, 0),
                        S(7.5, 0, 7.5, -3).move(0, 4),
                        S(10, 5, 13, 5).move(-4, 0),
                        S(5, 10, 5, 13).move(0, -4),
                    )
                for ((i, seg__) in (segs).withIndex()) {
                    var seg = seg__
                    run {
                        testDualRingX(shape) { ring ->
                            expect(Rings.containsSegment(ring, seg, true))
                            expect(Rings.containsSegment(ring, seg, false))
                            seg = Segment(seg.b, seg.a)
                            expect(Rings.containsSegment(ring, seg, true))
                            expect(Rings.containsSegment(ring, seg, false))
                        }
                    }
                }
            }
            run {
                var segs =
                    listOf<Segment>(
                        S(0, 7.5, 10, 7.5),
                        S(2.5, 5, 2.5, 10),
                        S(5, 2.5, 10, 2.5),
                        S(7.5, 0, 7.5, 10),
                    )
                for ((i, seg__) in (segs).withIndex()) {
                    var seg = seg__
                    run {
                        testDualRingX(shape) { ring ->
                            expect(Rings.containsSegment(ring, seg, true))
                            expect(!Rings.containsSegment(ring, seg, false))
                            seg = Segment(seg.b, seg.a)
                            expect(Rings.containsSegment(ring, seg, true))
                            expect(!Rings.containsSegment(ring, seg, false))
                        }
                    }
                }
            }
            run {
                var segs =
                    listOf<Segment>(
                        S(-1, 7.5, 11, 7.5),
                        S(2.5, 4, 2.5, 11),
                        S(4, 2.5, 11, 2.5),
                        S(7.5, -1, 7.5, 11),
                    )
                for ((i, seg__) in (segs).withIndex()) {
                    var seg = seg__
                    run {
                        testDualRingX(shape) { ring ->
                            expect(!Rings.containsSegment(ring, seg, true))
                            expect(!Rings.containsSegment(ring, seg, false))
                            seg = Segment(seg.b, seg.a)
                            expect(!Rings.containsSegment(ring, seg, true))
                            expect(!Rings.containsSegment(ring, seg, false))
                        }
                    }
                }
            }
        }

        run {
            var ring =
                Rings.newRing(
                    listOf<Point>(
                        P(0, 0),
                        P(4, 0),
                        P(4, 4),
                        P(3, 4),
                        P(2, 3),
                        P(1, 4),
                        P(0, 4),
                        P(0, 0),
                    ),
                    IndexOptions.DEFAULT,
                )
            run {
                expect(!Rings.containsSegment(ring, S(1.5, 3.5, 2.5, 3.5), true))
                expect(!Rings.containsSegment(ring, S(1.5, 3.5, 2.5, 3.5), false))
            }
            run {
                expect(!Rings.containsSegment(ring, S(1.0, 3.5, 2.5, 3.5), true))
                expect(!Rings.containsSegment(ring, S(1.0, 3.5, 2.5, 3.5), false))
            }
            run {
                expect(Rings.containsSegment(ring, S(1.25, 3.75, 1.75, 3.25), true))
                expect(!Rings.containsSegment(ring, S(1.25, 3.75, 1.75, 3.25), false))
            }
            run {
                expect(!Rings.containsSegment(ring, S(1.5, 3.5, 3, 3.5), true))
                expect(!Rings.containsSegment(ring, S(1.5, 3.5, 3, 3.5), false))
            }
            run {
                expect(!Rings.containsSegment(ring, S(1.0, 3.5, 3, 3.5), true))
                expect(!Rings.containsSegment(ring, S(1.0, 3.5, 3, 3.5), false))
            }
            run {
                var ring =
                    Rings.newRing(
                        listOf<Point>(
                            P(0, 0),
                            P(4, 0),
                            P(4, 4),
                            P(3, 4),
                            P(2, 5),
                            P(1, 4),
                            P(0, 4),
                            P(0, 0),
                        ),
                        IndexOptions.DEFAULT,
                    )
                expect(Rings.containsSegment(ring, S(1.5, 4.5, 2.5, 4.5), true))
                expect(!Rings.containsSegment(ring, S(1.5, 4.5, 2.5, 4.5), false))
            }
            run {
                var ring =
                    Rings.newRing(
                        listOf<Point>(
                            P(0, 0),
                            P(4, 0),
                            P(4, 4),
                            P(3, 4),
                            P(2.5, 3),
                            P(2, 4),
                            P(1.5, 3),
                            P(1, 4),
                            P(0, 4),
                            P(0, 0),
                        ),
                        IndexOptions.DEFAULT,
                    )
                expect(!Rings.containsSegment(ring, S(1.25, 3.5, 2.75, 3.5), true))
                expect(!Rings.containsSegment(ring, S(1.25, 3.5, 2.75, 3.5), false))
            }
            run {
                var ring =
                    Rings.newRing(
                        listOf<Point>(
                            P(0, 0),
                            P(4, 0),
                            P(4, 4),
                            P(3, 4),
                            P(2.5, 5),
                            P(2, 4),
                            P(1.5, 5),
                            P(1, 4),
                            P(0, 4),
                            P(0, 0),
                        ),
                        IndexOptions.DEFAULT,
                    )
                expect(!Rings.containsSegment(ring, S(1.25, 4.5, 2.75, 4.5), true))
                expect(!Rings.containsSegment(ring, S(1.25, 4.5, 2.75, 4.5), false))
            }
            run {
                expect(Rings.containsSegment(ring, S(1, 2, 3, 2), true))
                expect(Rings.containsSegment(ring, S(1, 2, 3, 2), false))
            }
            run {
                expect(Rings.containsSegment(ring, S(1, 3, 3, 2), true))
                expect(Rings.containsSegment(ring, S(1, 3, 3, 2), false))
            }
            run {
                expect(Rings.containsSegment(ring, S(1, 2, 3, 3), true))
                expect(Rings.containsSegment(ring, S(1, 2, 3, 3), false))
            }
            run {
                expect(Rings.containsSegment(ring, S(1.5, 3.5, 1.5, 2), true))
                expect(!Rings.containsSegment(ring, S(1.5, 3.5, 1.5, 2), false))
            }
            run {
                var ring =
                    Rings.newRing(
                        listOf<Point>(
                            P(0, 0),
                            P(2, 0),
                            P(4, 0),
                            P(4, 4),
                            P(3, 4),
                            P(2, 3),
                            P(1, 4),
                            P(0, 4),
                            P(0, 0),
                        ),
                        IndexOptions.DEFAULT,
                    )
                expect(Rings.containsSegment(ring, S(1, 0, 3, 0), true))
                expect(!Rings.containsSegment(ring, S(1, 0, 3, 0), false))
            }
            run {
                var ring =
                    Rings.newRing(
                        listOf<Point>(
                            P(0, 0),
                            P(4, 0),
                            P(2, 2),
                            P(0, 4),
                            P(0, 0),
                        ),
                        IndexOptions.DEFAULT,
                    )
                expect(Rings.containsSegment(ring, S(1, 3, 3, 1), true))
                expect(Rings.containsSegment(ring, S(3, 1, 1, 3), true))
                expect(!Rings.containsSegment(ring, S(1, 3, 3, 1), false))
                expect(!Rings.containsSegment(ring, S(3, 1, 1, 3), false))
            }
            run {
                expect(Rings.containsSegment(ring, S(1, 3, 3, 3), true))
                expect(!Rings.containsSegment(ring, S(1, 3, 3, 3), false))
            }
            run {
                expect(Rings.containsSegment(ring, S(1, 3, 2, 3), true))
                expect(!Rings.containsSegment(ring, S(1, 3, 2, 3), false))
            }
        }
    }

    @Test
    fun testRingXContainsRing() {
        run {
            expect(!Rings.containsRing(Rings.newRing(emptyList(), IndexOptions.DEFAULT), R(0, 0, 1, 1), true))
            expect(!Rings.containsRing(R(0, 0, 1, 1), Rings.newRing(emptyList(), IndexOptions.DEFAULT), true))
        }

        run {
            expect(Rings.containsRing(R(0, 0, 1, 1), R(0, 0, 1, 1), true))
            expect(!Rings.containsRing(R(0, 0, 1, 1), R(0, 0, 1, 1), false))
            expect(Rings.containsRing(Rings.newRing(concave1, IndexOptions.DEFAULT), Rings.newRing(concave1, IndexOptions.DEFAULT), true))
            expect(!Rings.containsRing(Rings.newRing(concave1, IndexOptions.DEFAULT), Rings.newRing(concave1, IndexOptions.DEFAULT), false))
            expect(Rings.containsRing(Rings.newRing(octagon, IndexOptions.DEFAULT), Rings.newRing(octagon, IndexOptions.DEFAULT), true))
            expect(!Rings.containsRing(Rings.newRing(octagon, IndexOptions.DEFAULT), Rings.newRing(octagon, IndexOptions.DEFAULT), false))
        }
        run {
            var ring =
                Rings.newRing(
                    listOf<Point>(
                        P(0, 0),
                        P(4, 0),
                        P(4, 4),
                        P(3, 4),
                        P(2, 3),
                        P(1, 4),
                        P(0, 4),
                        P(0, 0),
                    ),
                    IndexOptions.DEFAULT,
                )
            run {
                expect(Rings.containsRing(ring, R(1, 1, 3, 2), true))
                expect(Rings.containsRing(ring, R(1, 1, 3, 2), false))
            }
            run {
                expect(Rings.containsRing(ring, R(0, 0, 2, 1), true))
                expect(!Rings.containsRing(ring, R(0, 0, 2, 1), false))
            }
            run {
                expect(!Rings.containsRing(ring, R(-1.5, 1, 1.5, 2), true))
                expect(!Rings.containsRing(ring, R(-1.5, 1, 1.5, 2), false))
            }
            run {
                expect(!Rings.containsRing(ring, R(1, 2.5, 3, 3.5), true))
                expect(!Rings.containsRing(ring, R(1, 2.5, 3, 3.5), false))
            }
            run {
                expect(Rings.containsRing(ring, R(1, 2, 3, 3), true))
                expect(!Rings.containsRing(ring, R(1, 2, 3, 3), false))
            }

            ring =
                Rings.newRing(
                    listOf<Point>(
                        P(0, 0),
                        P(4, 0),
                        P(4, 4),
                        P(3, 4),
                        P(2, 5),
                        P(1, 4),
                        P(0, 4),
                        P(0, 0),
                    ),
                    IndexOptions.DEFAULT,
                )
            run {
                expect(Rings.containsRing(ring, R(1, 2, 3, 3), true))
                expect(Rings.containsRing(ring, R(1, 2, 3, 3), false))
            }
            run {
                expect(Rings.containsRing(ring, R(1, 3, 3, 4), true))
                expect(!Rings.containsRing(ring, R(1, 3, 3, 4), false))
            }
            run {
                expect(!Rings.containsRing(ring, R(1, 3.5, 3, 4.5), true))
                expect(!Rings.containsRing(ring, R(1, 3.5, 3, 4.5), false))
            }
            run {
                ring =
                    Rings.newRing(
                        listOf<Point>(
                            P(0, 0),
                            P(2, 0),
                            P(4, 0),
                            P(4, 4),
                            P(3, 4),
                            P(2, 5),
                            P(1, 4),
                            P(0, 4),
                            P(0, 0),
                        ),
                        IndexOptions.DEFAULT,
                    )
                expect(Rings.containsRing(ring, R(1, 0, 3, 1), true))
                expect(!Rings.containsRing(ring, R(1, 0, 3, 1), false))
            }
            run {
                ring =
                    Rings.newRing(
                        listOf<Point>(
                            P(0, 0),
                            P(4, 0),
                            P(4, 3),
                            P(2, 4),
                            P(0, 3),
                            P(0, 0),
                        ),
                        IndexOptions.DEFAULT,
                    )
                expect(Rings.containsRing(ring, R(1, 1, 3, 2), true))
                expect(Rings.containsRing(ring, R(1, 1, 3, 2), false))
            }
            ring =
                Rings.newRing(
                    listOf<Point>(
                        P(0, 0),
                        P(4, 0),
                        P(4, 3),
                        P(3, 4),
                        P(1, 4),
                        P(0, 3),
                        P(0, 0),
                    ),
                    IndexOptions.DEFAULT,
                )
            run {
                expect(Rings.containsRing(ring, R(1, 1, 3, 2), true))
                expect(Rings.containsRing(ring, R(1, 1, 3, 2), false))
            }
            run {
                expect(Rings.containsRing(ring, R(0, 1, 2, 2), true))
                expect(!Rings.containsRing(ring, R(0, 1, 2, 2), false))
            }
            run {
                expect(!Rings.containsRing(ring, R(-1, 1, 1, 2), true))
                expect(!Rings.containsRing(ring, R(-1, 1, 1, 2), false))
            }
            run {
                expect(Rings.containsRing(ring, R(0.5, 2.5, 2.5, 3.5), true))
                expect(!Rings.containsRing(ring, R(0.5, 2.5, 2.5, 3.5), false))
            }
            run {
                expect(!Rings.containsRing(ring, R(0.25, 2.75, 2.25, 3.75), true))
                expect(!Rings.containsRing(ring, R(0.25, 2.75, 2.25, 3.75), false))
            }
            run {
                expect(!Rings.containsRing(ring, R(-2, 1, -1, 2), true))
                expect(!Rings.containsRing(ring, R(-2, 1, -1, 2), false))
            }
            run {
                expect(!Rings.containsRing(ring, R(-0.5, 3.5, 0.5, 4.5), true))
                expect(!Rings.containsRing(ring, R(-0.5, 3.5, 0.5, 4.5), false))
            }
            run {
                expect(!Rings.containsRing(ring, R(-0.75, 3.75, 0.25, 4.75), true))
                expect(!Rings.containsRing(ring, R(-0.74, 3.75, 0.25, 4.75), false))
            }
            run {
                expect(!Rings.containsRing(ring, R(-1, -1, 5, 5), true))
                expect(!Rings.containsRing(ring, R(-1, -1, 5, 5), false))
            }
        }
        run {
            var shapes =
                listOf<Ring>(
                    R(0, 0, 10, 10),
                    Rings.newRing(octagon, IndexOptions.DEFAULT),
                    Rings.newRing(concave1, IndexOptions.DEFAULT),
                    Rings.newRing(concave2, IndexOptions.DEFAULT),
                    Rings.newRing(concave3, IndexOptions.DEFAULT),
                    Rings.newRing(concave4, IndexOptions.DEFAULT),
                    Rings.newRing(ri, IndexOptions.DEFAULT),
                )
            for ((i, shape__) in (shapes).withIndex()) {
                var shape = shape__
                run {
                    expect(Rings.containsRing(shape, shape, true))
                }
            }
        }

        run {
            var ring = Rings.newRing(ri, IndexOptions.DEFAULT)
            expect(Rings.containsRing(ring.rect(), ring, true))
            expect(Rings.containsRing(ring, ring, true))
        }
    }

    @Test
    fun testRingXIntersectsRing() {
        fun intersectsBothWays(
            ringA: Ring,
            ringB: Ring,
            allowOnEdge: Boolean,
        ): Boolean {
            val t1 = Rings.intersectsRing(ringA, ringB, allowOnEdge)
            val t2 = Rings.intersectsRing(ringB, ringA, allowOnEdge)
            check(t1 == t2) { "mismatch" }
            return t1
        }
        run {
            expect(!intersectsBothWays(Rings.newRing(emptyList(), IndexOptions.DEFAULT), R(0, 0, 1, 1), true))
            expect(!intersectsBothWays(R(0, 0, 1, 1), Rings.newRing(emptyList(), IndexOptions.DEFAULT), true))
        }
        run {
            var ring =
                Rings.newRing(
                    listOf<Point>(
                        P(0, 0),
                        P(4, 0),
                        P(4, 4),
                        P(3, 4),
                        P(2, 3),
                        P(1, 4),
                        P(0, 4),
                        P(0, 0),
                    ),
                    IndexOptions.DEFAULT,
                )
            run {
                expect(intersectsBothWays(ring, R(1, 1, 3, 2), true))
                expect(intersectsBothWays(ring, R(1, 1, 3, 2), false))
            }
            run {
                expect(intersectsBothWays(ring, R(0, 0, 2, 1), true))
                expect(intersectsBothWays(ring, R(0, 0, 2, 1), false))
            }
            run {
                expect(intersectsBothWays(ring, R(-1.5, 1, 1.5, 2), true))
                expect(intersectsBothWays(ring, R(-1.5, 1, 1.5, 2), false))
            }
            run {
                expect(intersectsBothWays(ring, R(1, 2.5, 3, 3.5), true))
                expect(intersectsBothWays(ring, R(1, 2.5, 3, 3.5), false))
            }
            run {
                expect(intersectsBothWays(ring, R(1, 2, 3, 3), true))
                expect(intersectsBothWays(ring, R(1, 2, 3, 3), false))
            }

            ring =
                Rings.newRing(
                    listOf<Point>(
                        P(0, 0),
                        P(4, 0),
                        P(4, 4),
                        P(3, 4),
                        P(2, 5),
                        P(1, 4),
                        P(0, 4),
                        P(0, 0),
                    ),
                    IndexOptions.DEFAULT,
                )
            run {
                expect(intersectsBothWays(ring, R(1, 2, 3, 3), true))
                expect(intersectsBothWays(ring, R(1, 2, 3, 3), false))
            }
            run {
                expect(intersectsBothWays(ring, R(1, 3, 3, 4), true))
                expect(intersectsBothWays(ring, R(1, 3, 3, 4), false))
            }
            run {
                expect(intersectsBothWays(ring, R(1, 3.5, 3, 4.5), true))
                expect(intersectsBothWays(ring, R(1, 3.5, 3, 4.5), false))
            }
            run {
                ring =
                    Rings.newRing(
                        listOf<Point>(
                            P(0, 0),
                            P(2, 0),
                            P(4, 0),
                            P(4, 4),
                            P(3, 4),
                            P(2, 5),
                            P(1, 4),
                            P(0, 4),
                            P(0, 0),
                        ),
                        IndexOptions.DEFAULT,
                    )
                expect(intersectsBothWays(ring, R(1, 0, 3, 1), true))
                expect(intersectsBothWays(ring, R(1, 0, 3, 1), false))
            }
            run {
                ring =
                    Rings.newRing(
                        listOf<Point>(
                            P(0, 0),
                            P(4, 0),
                            P(4, 3),
                            P(2, 4),
                            P(0, 3),
                            P(0, 0),
                        ),
                        IndexOptions.DEFAULT,
                    )
                expect(intersectsBothWays(ring, R(1, 1, 3, 2), true))
                expect(intersectsBothWays(ring, R(1, 1, 3, 2), false))
            }
            ring =
                Rings.newRing(
                    listOf<Point>(
                        P(0, 0),
                        P(4, 0),
                        P(4, 3),
                        P(3, 4),
                        P(1, 4),
                        P(0, 3),
                        P(0, 0),
                    ),
                    IndexOptions.DEFAULT,
                )
            run {
                expect(intersectsBothWays(ring, R(1, 1, 3, 2), true))
                expect(intersectsBothWays(ring, R(1, 1, 3, 2), false))
            }
            run {
                expect(intersectsBothWays(ring, R(0, 1, 2, 2), true))
                expect(intersectsBothWays(ring, R(0, 1, 2, 2), false))
            }
            run {
                expect(intersectsBothWays(ring, R(-1, 1, 1, 2), true))
                expect(intersectsBothWays(ring, R(-1, 1, 1, 2), false))
            }
            run {
                expect(intersectsBothWays(ring, R(0.5, 2.5, 2.5, 3.5), true))
                expect(intersectsBothWays(ring, R(0.5, 2.5, 2.5, 3.5), false))
            }
            run {
                expect(intersectsBothWays(ring, R(0.25, 2.75, 2.25, 3.75), true))
                expect(intersectsBothWays(ring, R(0.25, 2.75, 2.25, 3.75), false))
            }
            run {
                expect(!intersectsBothWays(ring, R(-2, 1, -1, 2), true))
                expect(!intersectsBothWays(ring, R(-2, 1, -1, 2), false))
            }
            run {
                expect(intersectsBothWays(ring, R(-0.5, 3.5, 0.5, 4.5), true))
                expect(!intersectsBothWays(ring, R(-0.5, 3.5, 0.5, 4.5), false))
            }
            run {
                expect(!intersectsBothWays(ring, R(-0.75, 3.75, 0.25, 4.75), true))
                expect(!intersectsBothWays(ring, R(-0.74, 3.75, 0.25, 4.75), false))
            }
            run {
                expect(Rings.intersectsRing(ring, R(-1, -1, 5, 5), true))
                expect(Rings.intersectsRing(ring, R(-1, -1, 5, 5), false))
            }
        }
    }

    @Test
    fun testRingXContainsLine() {
        run {
            var ring =
                Rings.newRing(
                    listOf<Point>(
                        P(0, 0),
                        P(4, 0),
                        P(4, 3),
                        P(3, 4),
                        P(1, 4),
                        P(0, 3),
                        P(0, 0),
                    ),
                    IndexOptions.DEFAULT,
                )

            fun makeLine(start: Point): Line =
                Line(
                    listOf<Point>(
                        start,
                        start.move(0, 1),
                        start.move(1, 1),
                        start.move(1, 2),
                        start.move(2, 2),
                    ),
                    IndexOptions.DEFAULT,
                )
            run {
                expect(Rings.containsLine(ring, makeLine(P(1, 1)), true))
                expect(Rings.containsLine(ring, makeLine(P(1, 1)), false))
            }
            run {
                expect(Rings.containsLine(ring, makeLine(P(0, 1)), true))
                expect(!Rings.containsLine(ring, makeLine(P(0, 1)), false))
            }
            run {
                expect(!Rings.containsLine(ring, makeLine(P(-0.5, 1)), true))
                expect(!Rings.containsLine(ring, makeLine(P(-0.5, 1)), false))
            }
            run {
                expect(Rings.containsLine(ring, makeLine(P(0, 2)), true))
                expect(!Rings.containsLine(ring, makeLine(P(0, 2)), false))
            }
            run {
                expect(!Rings.containsLine(ring, makeLine(P(0, 2.5)), true))
                expect(!Rings.containsLine(ring, makeLine(P(0, 2.5)), false))
            }
            run {
                expect(!Rings.containsLine(ring, makeLine(P(2, 2)), true))
                expect(!Rings.containsLine(ring, makeLine(P(2, 2)), false))
            }
            run {
                expect(!Rings.containsLine(ring, makeLine(P(2, 1.5)), true))
                expect(!Rings.containsLine(ring, makeLine(P(2, 1.5)), false))
            }
            run {
                expect(Rings.containsLine(ring, makeLine(P(2, 1)), true))
                expect(!Rings.containsLine(ring, makeLine(P(2, 1)), false))
            }
            run {
                expect(Rings.containsLine(ring, makeLine(P(2, 0)), true))
                expect(!Rings.containsLine(ring, makeLine(P(2, 0)), false))
            }
            run {
                expect(Rings.containsLine(ring, makeLine(P(1.5, 0)), true))
                expect(!Rings.containsLine(ring, makeLine(P(1.5, 0)), false))
            }
            run {
                expect(!Rings.containsLine(ring, makeLine(P(1, -1)), true))
                expect(!Rings.containsLine(ring, makeLine(P(1, -1)), false))
            }
            run {
                expect(!Rings.containsLine(ring, makeLine(P(1.5, -0.5)), true))
                expect(!Rings.containsLine(ring, makeLine(P(1.5, -0.5)), false))
            }
            run {
                expect(Rings.containsLine(ring, makeLine(P(0, 0)), true))
                expect(!Rings.containsLine(ring, makeLine(P(0, 0)), false))
            }
            run {
                expect(!Rings.containsLine(ring, makeLine(P(3, 1)), true))
                expect(!Rings.containsLine(ring, makeLine(P(3, 1)), false))
            }
            run {
                var line = L(P(-1, -1), P(-1, 2), P(2, 2), P(2, 5), P(5, 5))
                expect(!Rings.containsLine(ring, line, true))
                expect(!Rings.containsLine(ring, line, false))
            }
            run {
                var line = L(P(0, -1), P(0, 1), P(3, 3), P(5, 3))
                expect(!Rings.containsLine(ring, line, true))
                expect(!Rings.containsLine(ring, line, false))
            }
            run {
                var line = L(P(-1, 2), P(2, 1), P(5, 2))
                expect(!Rings.containsLine(ring, line, true))
                expect(!Rings.containsLine(ring, line, false))
            }
            run {
                var line = L(P(1, -1), P(3, 2), P(2, 5))
                expect(!Rings.containsLine(ring, line, true))
                expect(!Rings.containsLine(ring, line, false))
            }
            run {
                var line = L(P(3.5, 4.5), P(3.5, 3.5), P(5, 3.5), P(5, 2.5))
                expect(!Rings.containsLine(ring, line, true))
                expect(!Rings.containsLine(ring, line, false))
            }
            run {
                var line = L(P(4, 5), P(4, 4), P(5, 4), P(5, 3))
                expect(!Rings.containsLine(ring, line, true))
                expect(!Rings.containsLine(ring, line, false))
            }
            run {
                var line = L(P(1, -1), P(1, -2), P(2, -2), P(2, -3))
                expect(!Rings.containsLine(ring, line, true))
                expect(!Rings.containsLine(ring, line, false))
            }
        }
    }

    @Test
    fun testRingXIntersectsLine() {
        run {
            expect(!Rings.intersectsLine(R(0, 0, 0, 0), L(), true))
            expect(
                !Rings.intersectsLine(
                    R(0, 0, 10, 10),
                    L(P(-1, -1), P(11, -1), P(11, 11), P(-1, 11)),
                    true,
                ),
            )
            expect(
                Rings.intersectsLine(
                    R(0, 0, 10, 10),
                    L(P(-1, -1), P(11, -1), P(11, 11), P(-1, 11), P(5, -1)),
                    true,
                ),
            )
        }

        run {
            var ring =
                Rings.newRing(
                    listOf<Point>(
                        P(0, 0),
                        P(4, 0),
                        P(4, 3),
                        P(3, 4),
                        P(1, 4),
                        P(0, 3),
                        P(0, 0),
                    ),
                    IndexOptions.DEFAULT,
                )

            fun makeLine(start: Point): Line =
                Line(
                    listOf<Point>(
                        start,
                        start.move(0, 1),
                        start.move(1, 1),
                        start.move(1, 2),
                        start.move(2, 2),
                    ),
                    IndexOptions.DEFAULT,
                )
            run {
                expect(Rings.intersectsLine(ring, makeLine(P(1, 1)), true))
                expect(Rings.intersectsLine(ring, makeLine(P(1, 1)), false))
            }
            run {
                expect(Rings.intersectsLine(ring, makeLine(P(0, 1)), true))
                expect(Rings.intersectsLine(ring, makeLine(P(0, 1)), false))
            }
            run {
                expect(Rings.intersectsLine(ring, makeLine(P(-0.5, 1)), true))
                expect(Rings.intersectsLine(ring, makeLine(P(-0.5, 1)), false))
            }
            run {
                expect(Rings.intersectsLine(ring, makeLine(P(0, 2)), true))
                expect(Rings.intersectsLine(ring, makeLine(P(0, 2)), false))
            }
            run {
                expect(Rings.intersectsLine(ring, makeLine(P(0, 2.5)), true))
                expect(Rings.intersectsLine(ring, makeLine(P(0, 2.5)), false))
            }
            run {
                expect(Rings.intersectsLine(ring, makeLine(P(2, 2)), true))
                expect(Rings.intersectsLine(ring, makeLine(P(2, 2)), false))
            }
            run {
                expect(Rings.intersectsLine(ring, makeLine(P(2, 1.5)), true))
                expect(Rings.intersectsLine(ring, makeLine(P(2, 1.5)), false))
            }
            run {
                expect(Rings.intersectsLine(ring, makeLine(P(2, 1)), true))
                expect(Rings.intersectsLine(ring, makeLine(P(2, 1)), false))
            }
            run {
                expect(Rings.intersectsLine(ring, makeLine(P(2, 0)), true))
                expect(Rings.intersectsLine(ring, makeLine(P(2, 0)), false))
            }
            run {
                expect(Rings.intersectsLine(ring, makeLine(P(1.5, 0)), true))
                expect(Rings.intersectsLine(ring, makeLine(P(1.5, 0)), false))
            }
            run {
                expect(Rings.intersectsLine(ring, makeLine(P(1, -1)), true))
                expect(Rings.intersectsLine(ring, makeLine(P(1, -1)), false))
            }
            run {
                expect(Rings.intersectsLine(ring, makeLine(P(1.5, -0.5)), true))
                expect(Rings.intersectsLine(ring, makeLine(P(1.5, -0.5)), false))
            }
            run {
                expect(Rings.intersectsLine(ring, makeLine(P(0, 0)), true))
                expect(Rings.intersectsLine(ring, makeLine(P(0, 0)), false))
            }
            run {
                expect(Rings.intersectsLine(ring, makeLine(P(3, 1)), true))
                expect(Rings.intersectsLine(ring, makeLine(P(3, 1)), false))
            }
            run {
                var line = L(P(-1, -1), P(-1, 2), P(2, 2), P(2, 5), P(5, 5))
                expect(Rings.intersectsLine(ring, line, true))
                expect(Rings.intersectsLine(ring, line, false))
            }
            run {
                var line = L(P(0, -1), P(0, 1), P(3, 3), P(5, 3))
                expect(Rings.intersectsLine(ring, line, true))
                expect(Rings.intersectsLine(ring, line, false))
            }
            run {
                var line = L(P(-1, 2), P(2, 1), P(5, 2))
                expect(Rings.intersectsLine(ring, line, true))
                expect(Rings.intersectsLine(ring, line, false))
            }
            run {
                var line = L(P(1, -1), P(3, 2), P(2, 5))
                expect(Rings.intersectsLine(ring, line, true))
                expect(Rings.intersectsLine(ring, line, false))
            }
            run {
                var line = L(P(3.5, 4.5), P(3.5, 3.5), P(5, 3.5), P(5, 2.5))
                expect(Rings.intersectsLine(ring, line, true))
                expect(!Rings.intersectsLine(ring, line, false))
            }
            run {
                var line = L(P(4, 5), P(4, 4), P(5, 4), P(5, 3))
                expect(!Rings.intersectsLine(ring, line, true))
                expect(!Rings.intersectsLine(ring, line, false))
            }
            run {
                var line = L(P(1, -1), P(1, -2), P(2, -2), P(2, -3))
                expect(!Rings.intersectsLine(ring, line, true))
                expect(!Rings.intersectsLine(ring, line, false))
            }
        }
    }

    @Test
    fun testIssue14() {
        run {
            var exterior = MutableList(5) { P(0, 0) }
            exterior[0] = P(0, 0)
            exterior[1] = P(6000, 0)
            exterior[2] = P(10, 10.1)
            exterior[3] = P(0, 10)
            exterior[4] = P(0, 0)

            var polygon = Poly.of(exterior, null, null)

            var rectangle = Rect(P(2560, 0), P(5120, 2560))

            var flagContains = polygon.containsRect(rectangle)
            var flagIntersects = rectangle.intersectsPoly(polygon)
            var flagIntersects2 = polygon.intersectsRect(rectangle)
            if (!flagIntersects || flagContains || !flagIntersects2) {
                expect(false)
            }
        }
        run {
            var exterior = listOf<Point>(P(0, 0), P(5, 0), P(0, 1), P(0, 0))
            var polygon = Poly.of(exterior, null, null)
            var rectangle = Rect(P(2, -1), P(4.5, 1))
            var flagIntersects = polygon.intersectsRect(rectangle)
            var flagContains = polygon.containsRect(rectangle)
            var flagIntersects2 = polygon.intersectsRect(rectangle)
            if (!flagIntersects || flagContains || !flagIntersects2) {
                expect(false)
            }
        }
    }
}
