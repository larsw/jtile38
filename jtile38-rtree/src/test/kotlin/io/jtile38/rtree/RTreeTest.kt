package io.jtile38.rtree

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RTreeTest {
    private class Item(
        val id: Int,
        val minX: Float,
        val minY: Float,
        val maxX: Float,
        val maxY: Float,
    )

    private fun randomItems(
        n: Int,
        rnd: Random,
        points: Boolean,
    ): List<Item> =
        List(n) { i ->
            val x = rnd.nextDouble(-180.0, 180.0).toFloat()
            val y = rnd.nextDouble(-90.0, 90.0).toFloat()
            if (points) {
                Item(i, x, y, x, y)
            } else {
                Item(i, x, y, x + rnd.nextDouble(0.0, 5.0).toFloat(), y + rnd.nextDouble(0.0, 5.0).toFloat())
            }
        }

    private fun intersects(
        a: Item,
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
    ) = !(minX > a.maxX || maxX < a.minX || minY > a.maxY || maxY < a.minY)

    @Test
    fun emptyTree() {
        val tr = RTree<Item>()
        assertEquals(0, tr.size())
        assertNull(tr.leftMost())
        var hits = 0
        tr.search(-180f, -90f, 180f, 90f) { _, _, _, _, _ ->
            hits++
            true
        }
        assertEquals(0, hits)
        assertTrue(!tr.delete(0f, 0f, 0f, 0f, Item(0, 0f, 0f, 0f, 0f)))
    }

    @Test
    fun insertSearchDelete() {
        for (points in listOf(true, false)) {
            val rnd = Random(42)
            val items = randomItems(20_000, rnd, points)
            val tr = RTree<Item>()
            items.forEach { tr.insert(it.minX, it.minY, it.maxX, it.maxY, it) }
            assertEquals(items.size, tr.size())

            repeat(200) {
                val x = rnd.nextDouble(-180.0, 170.0).toFloat()
                val y = rnd.nextDouble(-90.0, 80.0).toFloat()
                val w = rnd.nextDouble(0.0, 10.0).toFloat()
                val expected = items.filter { intersects(it, x, y, x + w, y + w) }.map { it.id }.toSet()
                val got = HashSet<Int>()
                tr.search(x, y, x + w, y + w) { _, _, _, _, d ->
                    got.add(d.id)
                    true
                }
                assertEquals(expected, got)
            }

            var all = 0
            tr.scan { _, _, _, _, _ ->
                all++
                true
            }
            assertEquals(items.size, all)

            assertEquals(items.minOf { it.minX }, tr.bounds()[0])
            assertEquals(items.maxOf { it.maxY }, tr.bounds()[3])
            assertEquals(items.minOf { it.minX }, tr.leftMost()!!.minX)
            assertEquals(items.maxOf { it.maxX }, tr.rightMost()!!.maxX)

            // delete half
            val (del, keep) = items.partition { it.id % 2 == 0 }
            del.forEach { assertTrue(tr.delete(it.minX, it.minY, it.maxX, it.maxY, it)) }
            assertEquals(keep.size, tr.size())
            val left = HashSet<Int>()
            tr.scan { _, _, _, _, d ->
                left.add(d.id)
                true
            }
            assertEquals(keep.map { it.id }.toSet(), left)
            // deleting again fails
            assertTrue(!tr.delete(del[0].minX, del[0].minY, del[0].maxX, del[0].maxY, del[0]))
            keep.forEach { assertTrue(tr.delete(it.minX, it.minY, it.maxX, it.maxY, it)) }
            assertEquals(0, tr.size())
            assertNull(tr.leftMost())
        }
    }

    @Test
    fun nearbyOrdersByDistance() {
        val rnd = Random(7)
        val items = randomItems(5_000, rnd, points = false)
        val tr = RTree<Item>()
        items.forEach { tr.insert(it.minX, it.minY, it.maxX, it.maxY, it) }
        val px = 10f
        val py = 20f

        fun boxDist(
            minX: Float,
            minY: Float,
            maxX: Float,
            maxY: Float,
        ): Double {
            var d = 0.0
            val dx = maxOf(px, minX) - minOf(px, maxX)
            if (dx > 0) d += dx.toDouble() * dx
            val dy = maxOf(py, minY) - minOf(py, maxY)
            if (dy > 0) d += dy.toDouble() * dy
            return d
        }
        val dists = ArrayList<Double>()
        tr.nearby({ a, b, c, d, _, _ -> boxDist(a, b, c, d) }) { _, _, _, _, _, dist ->
            dists.add(dist)
            true
        }
        assertEquals(items.size, dists.size)
        for (i in 1 until dists.size) assertTrue(dists[i - 1] <= dists[i])
    }

    @Test
    fun roundingIsOutward() {
        val rnd = Random(1)
        repeat(100_000) {
            val d = rnd.nextDouble(-180.0, 180.0)
            assertTrue(RTree.roundDown(d).toDouble() <= d)
            assertTrue(RTree.roundUp(d).toDouble() >= d)
        }
    }
}
