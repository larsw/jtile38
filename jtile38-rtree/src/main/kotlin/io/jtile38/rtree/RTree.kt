// Port of github.com/tidwall/rtree (v1.10.0), Copyright 2021 Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.rtree

/**
 * A 2D R-tree with single-precision coordinates, as used by Tile38 for its
 * spatial index. The algorithms (insertion with ordered leaves/branches,
 * largest-axis edge-snap splitting, deletion with reinsertion and kNN
 * search with a priority queue) follow tidwall/rtree.
 *
 * Coordinates are stored as `Float` to keep memory usage low; callers that
 * index `Double` rectangles must round outward (see [RTree.roundDown] and
 * [RTree.roundUp]).
 *
 * Not thread-safe.
 */
class RTree<T> {
    private var count = 0
    private val rect = FloatArray(4)
    private var root: Node? = null

    /** Number of items in the tree. */
    fun size(): Int = count

    /** Inserts [data] with the rectangle ([minX], [minY])-([maxX], [maxY]). */
    fun insert(
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
        data: T,
    ) {
        val ir = floatArrayOf(minX, minY, maxX, maxY)
        insert(ir, data)
    }

    private fun insert(
        ir: FloatArray,
        data: Any?,
    ) {
        if (root == null) {
            root = Node(leaf = true)
            System.arraycopy(ir, 0, rect, 0, 4)
        }
        val r = root!!
        val res = nodeInsert(rect, 0, r, ir, data)
        if (res == SPLIT) {
            val left = r
            val right = splitNode(rect, 0, left)
            val nroot = Node(leaf = false)
            left.rectInto(nroot.rects, 0)
            right.rectInto(nroot.rects, 1)
            nroot.items[0] = left
            nroot.items[1] = right
            nroot.count = 2
            root = nroot
            insert(ir, data)
            nroot.sort()
            return
        }
        if (res == GROWN) {
            expand(rect, 0, ir, 0)
            if (!root!!.leaf) root!!.sort()
        }
        count++
    }

    /**
     * Inserts into [n]; `nr`/`nri` address the rectangle of [n] in its parent.
     * Returns [SPLIT], [GROWN] or [NONE].
     */
    private fun nodeInsert(
        nr: FloatArray,
        nri: Int,
        n: Node,
        ir: FloatArray,
        data: Any?,
    ): Int {
        if (n.leaf) {
            if (n.count == MAX_ENTRIES) return SPLIT
            val index = n.rsearch(ir[0])
            val c = n.count
            System.arraycopy(n.rects, index * 4, n.rects, (index + 1) * 4, (c - index) * 4)
            System.arraycopy(n.items, index, n.items, index + 1, c - index)
            System.arraycopy(ir, 0, n.rects, index * 4, 4)
            n.items[index] = data
            n.count++
            return if (!contains(nr, nri, ir, 0)) GROWN else NONE
        }
        // choose a subtree
        var index = -1
        var narea = 0f
        for (i in 0 until n.count) {
            if (contains(n.rects, i, ir, 0)) {
                val area = area(n.rects, i)
                if (index == -1 || area < narea) {
                    index = i
                    narea = area
                }
            }
        }
        if (index == -1) index = n.chooseLeastEnlargement(ir)

        val child = n.items[index] as Node
        val res = nodeInsert(n.rects, index, child, ir, data)
        if (res == SPLIT) {
            if (n.count == MAX_ENTRIES) return SPLIT
            val left = child
            val right = splitNode(n.rects, index, left)
            left.rectInto(n.rects, index)
            val c = n.count
            System.arraycopy(n.rects, (index + 1) * 4, n.rects, (index + 2) * 4, (c - index - 1) * 4)
            System.arraycopy(n.items, index + 1, n.items, index + 2, c - index - 1)
            right.rectInto(n.rects, index + 1)
            n.items[index + 1] = right
            n.count++
            if (n.rects[index * 4] > n.rects[(index + 1) * 4]) n.swap(index + 1, index)
            n.orderToRight(index + 1)
            return nodeInsert(nr, nri, n, ir, data)
        }
        if (res == GROWN) {
            expand(n.rects, index, ir, 0)
            n.orderToLeft(index)
            return if (!contains(nr, nri, ir, 0)) GROWN else NONE
        }
        return NONE
    }

    private fun splitNode(
        r: FloatArray,
        ri: Int,
        left: Node,
    ): Node {
        val axis = if (r[ri * 4 + 3] - r[ri * 4 + 1] > r[ri * 4 + 2] - r[ri * 4]) 1 else 0
        val right = Node(left.leaf)
        var i = 0
        while (i < left.count) {
            val minDist = left.rects[i * 4 + axis] - r[ri * 4 + axis]
            val maxDist = r[ri * 4 + 2 + axis] - left.rects[i * 4 + 2 + axis]
            if (!(minDist < maxDist)) {
                moveRectAtIndexInto(left, i, right)
                i--
            }
            i++
        }
        if (left.count < 2) {
            right.qsort(0, right.count, axis, rev = true, max = false)
            while (left.count < 2) moveRectAtIndexInto(right, right.count - 1, left)
        } else if (right.count < 2) {
            left.qsort(0, left.count, axis, rev = true, max = true)
            while (right.count < 2) moveRectAtIndexInto(left, left.count - 1, right)
        }
        if (!right.isSorted()) right.sort()
        if (!left.isSorted()) left.sort()
        return right
    }

    private fun moveRectAtIndexInto(
        from: Node,
        index: Int,
        into: Node,
    ) {
        System.arraycopy(from.rects, index * 4, into.rects, into.count * 4, 4)
        System.arraycopy(from.rects, (from.count - 1) * 4, from.rects, index * 4, 4)
        into.items[into.count] = from.items[index]
        from.items[index] = from.items[from.count - 1]
        from.items[from.count - 1] = null
        from.count--
        into.count++
    }

    /** Calls [iter] for every item whose rectangle intersects the target. Stop by returning false. */
    fun search(
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
        iter: (minX: Float, minY: Float, maxX: Float, maxY: Float, data: T) -> Boolean,
    ) {
        val r = root ?: return
        val target = floatArrayOf(minX, minY, maxX, maxY)
        if (intersects(target, 0, rect, 0)) search(r, target, iter)
    }

    @Suppress("UNCHECKED_CAST")
    private fun search(
        n: Node,
        target: FloatArray,
        iter: (Float, Float, Float, Float, T) -> Boolean,
    ): Boolean {
        val rs = n.rects
        if (n.leaf) {
            for (i in 0 until n.count) {
                if (intersects(rs, i, target, 0)) {
                    if (!iter(rs[i * 4], rs[i * 4 + 1], rs[i * 4 + 2], rs[i * 4 + 3], n.items[i] as T)) return false
                }
            }
            return true
        }
        for (i in 0 until n.count) {
            if (intersects(target, 0, rs, i)) {
                if (!search(n.items[i] as Node, target, iter)) return false
            }
        }
        return true
    }

    /** Iterates over all items. Stop by returning false. */
    fun scan(iter: (minX: Float, minY: Float, maxX: Float, maxY: Float, data: T) -> Boolean) {
        root?.let { scan(it, iter) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun scan(
        n: Node,
        iter: (Float, Float, Float, Float, T) -> Boolean,
    ): Boolean {
        val rs = n.rects
        for (i in 0 until n.count) {
            if (n.leaf) {
                if (!iter(rs[i * 4], rs[i * 4 + 1], rs[i * 4 + 2], rs[i * 4 + 3], n.items[i] as T)) return false
            } else if (!scan(n.items[i] as Node, iter)) {
                return false
            }
        }
        return true
    }

    /** Deletes the item equal to [data] whose rectangle is contained by the given one. */
    fun delete(
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
        data: T,
    ): Boolean {
        val ir = floatArrayOf(minX, minY, maxX, maxY)
        val r = root ?: return false
        if (!contains(rect, 0, ir, 0)) return false
        val reinsert = ArrayList<Node>()
        val removed = nodeDelete(rect, 0, r, ir, data, reinsert)
        if (removed == NOT_REMOVED) return false
        count--
        for (n in reinsert) count -= n.deepCount()
        if (count == 0) {
            root = null
            rect.fill(0f)
        } else {
            var rt = root!!
            while (!rt.leaf && rt.count == 1) rt = rt.items[0] as Node
            root = rt
        }
        for (n in reinsert) nodeReinsert(n)
        return true
    }

    /** Returns [NOT_REMOVED], [REMOVED] or [REMOVED_SHRUNK]. */
    private fun nodeDelete(
        nr: FloatArray,
        nri: Int,
        n: Node,
        ir: FloatArray,
        data: Any?,
        reinsert: MutableList<Node>,
    ): Int {
        val rs = n.rects
        if (n.leaf) {
            for (i in 0 until n.count) {
                if (contains(ir, 0, rs, i) && n.items[i] == data) {
                    val c = n.count
                    System.arraycopy(rs, (i + 1) * 4, rs, i * 4, (c - i - 1) * 4)
                    System.arraycopy(n.items, i + 1, n.items, i, c - i - 1)
                    n.items[c - 1] = null
                    n.count--
                    val shrunk = onEdge(ir, 0, nr, nri)
                    if (shrunk) n.rectInto(nr, nri)
                    return if (shrunk) REMOVED_SHRUNK else REMOVED
                }
            }
            return NOT_REMOVED
        }
        val crect = FloatArray(4)
        for (i in 0 until n.count) {
            if (!contains(rs, i, ir, 0)) continue
            System.arraycopy(rs, i * 4, crect, 0, 4)
            val child = n.items[i] as Node
            val res = nodeDelete(rs, i, child, ir, data, reinsert)
            if (res == NOT_REMOVED) continue
            if (child.count == 0) {
                reinsert.add(child)
                val c = n.count
                System.arraycopy(rs, (i + 1) * 4, rs, i * 4, (c - i - 1) * 4)
                System.arraycopy(n.items, i + 1, n.items, i, c - i - 1)
                n.items[c - 1] = null
                n.count--
                n.rectInto(nr, nri)
                return REMOVED_SHRUNK
            }
            var shrunk = res == REMOVED_SHRUNK
            if (shrunk) {
                shrunk = !equals(rs, i, crect, 0)
                if (shrunk) n.rectInto(nr, nri)
                n.orderToRight(i)
            }
            return if (shrunk) REMOVED_SHRUNK else REMOVED
        }
        return NOT_REMOVED
    }

    private fun nodeReinsert(n: Node) {
        if (n.leaf) {
            for (i in 0 until n.count) {
                insert(n.rects.copyOfRange(i * 4, i * 4 + 4), n.items[i])
            }
        } else {
            for (i in 0 until n.count) nodeReinsert(n.items[i] as Node)
        }
    }

    /** Replaces an item. If the old item does not exist then the new item is not inserted. */
    fun replace(
        oldMinX: Float,
        oldMinY: Float,
        oldMaxX: Float,
        oldMaxY: Float,
        oldData: T,
        newMinX: Float,
        newMinY: Float,
        newMaxX: Float,
        newMaxY: Float,
        newData: T,
    ) {
        if (delete(oldMinX, oldMinY, oldMaxX, oldMaxY, oldData)) {
            insert(newMinX, newMinY, newMaxX, newMaxY, newData)
        }
    }

    /** Minimum bounding rectangle of all items as `[minX, minY, maxX, maxY]`. */
    fun bounds(): FloatArray = rect.copyOf()

    /** Item with the smallest minX, or null when empty. */
    fun leftMost(): T? = root?.let { minist(it, 0) }

    /** Item with the smallest minY, or null when empty. */
    fun bottomMost(): T? = root?.let { minist(it, 1) }

    /** Item with the largest maxX, or null when empty. */
    fun rightMost(): T? = root?.let { maxist(it, 0) }

    /** Item with the largest maxY, or null when empty. */
    fun topMost(): T? = root?.let { maxist(it, 1) }

    @Suppress("UNCHECKED_CAST")
    private fun minist(
        n: Node,
        dim: Int,
    ): T? {
        var j = 0
        var m = 0f
        for (i in 0 until n.count) {
            val v = n.rects[i * 4 + dim]
            if (i == 0 || v < m) {
                j = i
                m = v
            }
        }
        return if (n.leaf) n.items[j] as T else minist(n.items[j] as Node, dim)
    }

    @Suppress("UNCHECKED_CAST")
    private fun maxist(
        n: Node,
        dim: Int,
    ): T? {
        var j = 0
        var m = 0f
        for (i in 0 until n.count) {
            val v = n.rects[i * 4 + 2 + dim]
            if (i == 0 || v > m) {
                j = i
                m = v
            }
        }
        return if (n.leaf) n.items[j] as T else maxist(n.items[j] as Node, dim)
    }

    /**
     * kNN-type search. [dist] computes the distance to a node rectangle
     * (`item == false`, `data == null`) or to an item (`item == true`).
     * [iter] receives items ordered from smallest to largest distance.
     */
    @Suppress("UNCHECKED_CAST")
    fun nearby(
        dist: (minX: Float, minY: Float, maxX: Float, maxY: Float, data: T?, item: Boolean) -> Double,
        iter: (minX: Float, minY: Float, maxX: Float, maxY: Float, data: T, dist: Double) -> Boolean,
    ) {
        val r = root ?: return
        val q = Queue()
        q.push(QNode(0.0, rect.copyOf(), null, r))
        while (true) {
            val qn = q.pop() ?: return
            val node = qn.node
            if (node == null) {
                val rr = qn.rect
                if (!iter(rr[0], rr[1], rr[2], rr[3], qn.data as T, qn.dist)) return
            } else {
                val rs = node.rects
                for (i in 0 until node.count) {
                    val o = i * 4
                    if (node.leaf) {
                        val item = node.items[i] as T
                        q.push(
                            QNode(
                                dist(rs[o], rs[o + 1], rs[o + 2], rs[o + 3], item, true),
                                rs.copyOfRange(o, o + 4),
                                item,
                                null,
                            ),
                        )
                    } else {
                        q.push(
                            QNode(
                                dist(rs[o], rs[o + 1], rs[o + 2], rs[o + 3], null, false),
                                rs.copyOfRange(o, o + 4),
                                null,
                                node.items[i] as Node,
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Deletes all items. */
    fun clear() {
        count = 0
        rect.fill(0f)
        root = null
    }

    private class Node(
        val leaf: Boolean,
    ) {
        var count = 0
        val rects = FloatArray(MAX_ENTRIES * 4)

        /** Items for a leaf, child [Node]s for a branch. */
        val items = arrayOfNulls<Any>(MAX_ENTRIES)

        fun rectInto(
            dst: FloatArray,
            di: Int,
        ) {
            val o = di * 4
            System.arraycopy(rects, 0, dst, o, 4)
            for (i in 1 until count) expand(dst, di, rects, i)
        }

        fun rsearch(key: Float): Int {
            for (i in 0 until count) {
                if (!(rects[i * 4] < key)) return i
            }
            return count
        }

        fun swap(
            i: Int,
            j: Int,
        ) {
            for (k in 0 until 4) {
                val t = rects[i * 4 + k]
                rects[i * 4 + k] = rects[j * 4 + k]
                rects[j * 4 + k] = t
            }
            val t = items[i]
            items[i] = items[j]
            items[j] = t
        }

        fun orderToRight(start: Int): Int {
            var idx = start
            while (idx < count - 1 && rects[(idx + 1) * 4] < rects[idx * 4]) {
                swap(idx + 1, idx)
                idx++
            }
            return idx
        }

        fun orderToLeft(start: Int): Int {
            var idx = start
            while (idx > 0 && rects[idx * 4] < rects[(idx - 1) * 4]) {
                swap(idx, idx - 1)
                idx--
            }
            return idx
        }

        fun chooseLeastEnlargement(ir: FloatArray): Int {
            var j = -1
            var jenlargement = 0f
            var jarea = 0f
            for (i in 0 until count) {
                val o = i * 4
                val uarea =
                    (maxOf(rects[o + 2], ir[2]) - minOf(rects[o], ir[0])) *
                        (maxOf(rects[o + 3], ir[3]) - minOf(rects[o + 1], ir[1]))
                val area = area(rects, i)
                val enlargement = uarea - area
                if (j == -1 || enlargement < jenlargement || (!(enlargement > jenlargement) && area < jarea)) {
                    j = i
                    jenlargement = enlargement
                    jarea = area
                }
            }
            return j
        }

        fun sort() = qsort(0, count, 0, rev = false, max = false)

        fun isSorted(): Boolean {
            for (i in 1 until count) if (rects[i * 4] < rects[(i - 1) * 4]) return false
            return true
        }

        fun qsort(
            s: Int,
            e: Int,
            axis: Int,
            rev: Boolean,
            max: Boolean,
        ) {
            val nrects = e - s
            if (nrects < 2) return
            var left = 0
            val right = nrects - 1
            val pivot = nrects / 2
            swap(s + pivot, s + right)
            val off = if (max) 2 + axis else axis
            for (i in 0 until nrects) {
                val vi = rects[(s + i) * 4 + off]
                val vr = rects[(s + right) * 4 + off]
                if (if (!rev) vi < vr else vr < vi) {
                    swap(s + i, s + left)
                    left++
                }
            }
            swap(s + left, s + right)
            qsort(s, s + left, axis, rev, max)
            qsort(s + left + 1, e, axis, rev, max)
        }

        fun deepCount(): Int {
            if (leaf) return count
            var c = 0
            for (i in 0 until count) c += (items[i] as Node).deepCount()
            return c
        }
    }

    private class QNode(
        val dist: Double,
        val rect: FloatArray,
        val data: Any?,
        val node: Node?,
    )

    /** Binary min-heap with the exact push/pop tie-breaking of tidwall/rtree. */
    private class Queue {
        private var nodes = arrayOfNulls<QNode>(64)
        private var size = 0

        fun push(n: QNode) {
            if (size == nodes.size) nodes = nodes.copyOf(size * 2)
            nodes[size++] = n
            var i = size - 1
            var parent = (i - 1) / 2
            while (i != 0 && nodes[parent]!!.dist > nodes[i]!!.dist) {
                val t = nodes[parent]
                nodes[parent] = nodes[i]
                nodes[i] = t
                i = parent
                parent = (i - 1) / 2
            }
        }

        fun pop(): QNode? {
            if (size == 0) return null
            val n = nodes[0]
            nodes[0] = nodes[size - 1]
            nodes[--size] = null
            var i = 0
            while (true) {
                var smallest = i
                val left = i * 2 + 1
                val right = i * 2 + 2
                if (left < size && nodes[left]!!.dist <= nodes[smallest]!!.dist) smallest = left
                if (right < size && nodes[right]!!.dist <= nodes[smallest]!!.dist) smallest = right
                if (smallest == i) break
                val t = nodes[smallest]
                nodes[smallest] = nodes[i]
                nodes[i] = t
                i = smallest
            }
            return n
        }
    }

    companion object {
        const val MAX_ENTRIES = 64

        private const val NONE = 0
        private const val SPLIT = 1
        private const val GROWN = 2
        private const val NOT_REMOVED = 0
        private const val REMOVED = 1
        private const val REMOVED_SHRUNK = 2

        private const val RND_TOWARDS = 1.0 - 1.0 / 8388608.0
        private const val RND_AWAY = 1.0 + 1.0 / 8388608.0

        /** Rounds [d] to a float that is less than or equal to it (Tile38 `rtreeValueDown`). */
        @JvmStatic
        fun roundDown(d: Double): Float {
            var f = d.toFloat()
            if (f.toDouble() > d) f = if (d < 0) (d * RND_AWAY).toFloat() else (d * RND_TOWARDS).toFloat()
            return f
        }

        /** Rounds [d] to a float that is greater than or equal to it (Tile38 `rtreeValueUp`). */
        @JvmStatic
        fun roundUp(d: Double): Float {
            var f = d.toFloat()
            if (f.toDouble() < d) f = if (d < 0) (d * RND_TOWARDS).toFloat() else (d * RND_AWAY).toFloat()
            return f
        }

        private fun expand(
            r: FloatArray,
            ri: Int,
            b: FloatArray,
            bi: Int,
        ) {
            val o = ri * 4
            val p = bi * 4
            if (b[p] < r[o]) r[o] = b[p]
            if (b[p + 2] > r[o + 2]) r[o + 2] = b[p + 2]
            if (b[p + 1] < r[o + 1]) r[o + 1] = b[p + 1]
            if (b[p + 3] > r[o + 3]) r[o + 3] = b[p + 3]
        }

        /** True when rect b is fully contained in rect r. */
        private fun contains(
            r: FloatArray,
            ri: Int,
            b: FloatArray,
            bi: Int,
        ): Boolean {
            val o = ri * 4
            val p = bi * 4
            if (b[p] < r[o] || b[p + 2] > r[o + 2]) return false
            if (b[p + 1] < r[o + 1] || b[p + 3] > r[o + 3]) return false
            return true
        }

        private fun intersects(
            r: FloatArray,
            ri: Int,
            b: FloatArray,
            bi: Int,
        ): Boolean {
            val o = ri * 4
            val p = bi * 4
            if (b[p] > r[o + 2] || b[p + 2] < r[o]) return false
            if (b[p + 1] > r[o + 3] || b[p + 3] < r[o + 1]) return false
            return true
        }

        private fun area(
            r: FloatArray,
            ri: Int,
        ): Float {
            val o = ri * 4
            return (r[o + 2] - r[o]) * (r[o + 3] - r[o + 1])
        }

        private fun equals(
            r: FloatArray,
            ri: Int,
            b: FloatArray,
            bi: Int,
        ): Boolean {
            val o = ri * 4
            val p = bi * 4
            return !(
                r[o] < b[p] || r[o] > b[p] || r[o + 1] < b[p + 1] || r[o + 1] > b[p + 1] ||
                    r[o + 2] < b[p + 2] || r[o + 2] > b[p + 2] || r[o + 3] < b[p + 3] || r[o + 3] > b[p + 3]
            )
        }

        /** True when r is on the edge of b. */
        private fun onEdge(
            r: FloatArray,
            ri: Int,
            b: FloatArray,
            bi: Int,
        ): Boolean {
            val o = ri * 4
            val p = bi * 4
            return !(r[o] > b[p] && r[o + 1] > b[p + 1] && r[o + 2] < b[p + 2] && r[o + 3] < b[p + 3])
        }
    }
}
