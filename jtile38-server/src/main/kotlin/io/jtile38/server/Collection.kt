// Port of tile38 internal/collection, Copyright Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.server

import io.jtile38.geojson.GeoObject
import io.jtile38.geojson.geometry.Point
import io.jtile38.geojson.geometry.Rect
import io.jtile38.rtree.RTree
import java.util.TreeMap
import java.util.TreeSet
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Allows paging through Scan, Within, Intersects and Nearby. */
interface Cursor {
    fun offset(): Long

    fun step(count: Long)
}

/** A collection of objects (one Tile38 key). Not thread-safe; guarded by the server lock. */
class Collection {
    private val objs = TreeMap<String, Item>()
    private val spatial = RTree<Item>()
    private val values = TreeSet<Item>(BY_VALUE)
    private val expires = TreeSet<Item>(BY_EXPIRES)
    private var weight = 0
    private var points = 0
    private var objects = 0
    private var nobjects = 0

    fun count(): Int = objects + nobjects

    fun stringCount(): Int = nobjects

    fun pointCount(): Int = points

    fun totalWeight(): Int = weight

    /** Returns [minX, minY, maxX, maxY] of all spatial items. */
    fun bounds(): DoubleArray {
        val left = spatial.leftMost() ?: return DoubleArray(4)
        val bottom = spatial.bottomMost()!!
        val right = spatial.rightMost()!!
        val top = spatial.topMost()!!
        return doubleArrayOf(left.rect().min.x, bottom.rect().min.y, right.rect().max.x, top.rect().max.y)
    }

    private fun indexInsert(item: Item) {
        if (!item.geo.empty()) {
            val r = item.rect()
            spatial.insert(
                RTree.roundDown(r.min.x),
                RTree.roundDown(r.min.y),
                RTree.roundUp(r.max.x),
                RTree.roundUp(r.max.y),
                item,
            )
        }
    }

    private fun indexDelete(item: Item) {
        if (!item.geo.empty()) {
            val r = item.rect()
            spatial.delete(
                RTree.roundDown(r.min.x),
                RTree.roundDown(r.min.y),
                RTree.roundUp(r.max.x),
                RTree.roundUp(r.max.y),
                item,
            )
        }
    }

    /** Adds or replaces an object; returns the previous one. */
    fun set(obj: Item): Item? {
        val prev = objs.put(obj.id, obj)
        if (prev != null) {
            if (prev.isSpatial()) {
                indexDelete(prev)
                objects--
            } else {
                values.remove(prev)
                nobjects--
            }
            if (prev.expires != 0L) expires.remove(prev)
            points -= prev.geo.numPoints()
            weight -= prev.weight()
        }
        if (obj.isSpatial()) {
            indexInsert(obj)
            objects++
        } else {
            values.add(obj)
            nobjects++
        }
        if (obj.expires != 0L) expires.add(obj)
        points += obj.geo.numPoints()
        weight += obj.weight()
        return prev
    }

    /** Removes an object and returns it, or null if it did not exist. */
    fun delete(id: String): Item? {
        val prev = objs.remove(id) ?: return null
        if (prev.isSpatial()) {
            indexDelete(prev)
            objects--
        } else {
            values.remove(prev)
            nobjects--
        }
        if (prev.expires != 0L) expires.remove(prev)
        points -= prev.geo.numPoints()
        weight -= prev.weight()
        return prev
    }

    fun get(id: String): Item? = objs[id]

    private inline fun paged(
        cursor: Cursor?,
        crossinline iterator: (Item) -> Boolean,
    ): (Item) -> Boolean {
        val offset = cursor?.offset() ?: 0L
        cursor?.step(offset)
        var count = 0L
        return { o ->
            count++
            if (count <= offset) {
                true
            } else {
                cursor?.step(1)
                iterator(o)
            }
        }
    }

    private inline fun iterate(
        items: Iterable<Item>,
        iter: (Item) -> Boolean,
    ): Boolean {
        for (o in items) if (!iter(o)) return false
        return true
    }

    /** Iterates through the collection ids. */
    fun scan(
        desc: Boolean,
        cursor: Cursor?,
        iterator: (Item) -> Boolean,
    ): Boolean {
        val iter = paged(cursor, iterator)
        return iterate(if (desc) objs.descendingMap().values else objs.values, iter)
    }

    /** Iterates ids starting at [start] (inclusive) and stopping at [end] (exclusive). */
    fun scanRange(
        start: String,
        end: String,
        desc: Boolean,
        cursor: Cursor?,
        iterator: (Item) -> Boolean,
    ): Boolean {
        val offset = cursor?.offset() ?: 0L
        cursor?.step(offset)
        var count = 0L
        val items = if (desc) objs.headMap(start, true).descendingMap().values else objs.tailMap(start, true).values
        for (o in items) {
            count++
            if (count <= offset) continue
            cursor?.step(1)
            if (!desc) {
                if (o.id >= end) return true
            } else {
                if (o.id <= end) return true
            }
            if (!iterator(o)) return false
        }
        return true
    }

    /** Iterates through the string values. */
    fun searchValues(
        desc: Boolean,
        cursor: Cursor?,
        iterator: (Item) -> Boolean,
    ): Boolean {
        val iter = paged(cursor, iterator)
        return iterate(if (desc) values.descendingSet() else values, iter)
    }

    /** Iterates through the string values in the range [start, end). */
    fun searchValuesRange(
        start: String,
        end: String,
        desc: Boolean,
        cursor: Cursor?,
        iterator: (Item) -> Boolean,
    ): Boolean {
        val iter = paged(cursor, iterator)
        val pstart = Item("", StringObject(start), 0, FieldList.EMPTY)
        val pend = Item("", StringObject(end), 0, FieldList.EMPTY)
        if (desc) {
            for (o in values.headSet(pstart, true).descendingSet()) {
                if (!(BY_VALUE.compare(pend, o) < 0)) break
                if (!iter(o)) return false
            }
        } else {
            for (o in values.tailSet(pstart, true)) {
                if (!(BY_VALUE.compare(o, pend) < 0)) break
                if (!iter(o)) return false
            }
        }
        return true
    }

    /** Iterates ids greater than or equal to (or, when [desc], less than or equal to) [id]. */
    fun scanGreaterOrEqual(
        id: String,
        desc: Boolean,
        cursor: Cursor?,
        iterator: (Item) -> Boolean,
    ): Boolean {
        val iter = paged(cursor, iterator)
        return iterate(if (desc) objs.headMap(id, true).descendingMap().values else objs.tailMap(id, true).values, iter)
    }

    private fun geoSearch(
        rect: Rect,
        iter: (Item) -> Boolean,
    ): Boolean {
        var alive = true
        val minX = RTree.roundDown(rect.min.x)
        val minY = RTree.roundDown(rect.min.y)
        val maxX = RTree.roundUp(rect.max.x)
        val maxY = RTree.roundUp(rect.max.y)
        if (minX.isNaN() && minY.isNaN() && maxX.isNaN() && maxY.isNaN()) return alive
        spatial.search(minX, minY, maxX, maxY) { _, _, _, _, o ->
            alive = iter(o)
            alive
        }
        return alive
    }

    private fun geoSparse(
        obj: GeoObject,
        sparse: Int,
        iter: (Item) -> Pair<Boolean, Boolean>,
    ): Boolean {
        val matches = HashSet<String>()
        geoSparseInner(obj.rect(), sparse) { o ->
            var match = false
            var ok = true
            if (o.id !in matches) {
                val r = iter(o)
                match = r.first
                ok = r.second
                if (match) matches.add(o.id)
            }
            match to ok
        }
        return true
    }

    private fun geoSparseInner(
        rect: Rect,
        sparse: Int,
        iter: (Item) -> Pair<Boolean, Boolean>,
    ): Boolean {
        if (sparse > 0) {
            val w = rect.max.x - rect.min.x
            val h = rect.max.y - rect.min.y
            val quads =
                arrayOf(
                    Rect(Point(rect.min.x, rect.min.y + h / 2), Point(rect.min.x + w / 2, rect.max.y)),
                    Rect(Point(rect.min.x + w / 2, rect.min.y + h / 2), Point(rect.max.x, rect.max.y)),
                    Rect(Point(rect.min.x, rect.min.y), Point(rect.min.x + w / 2, rect.min.y + h / 2)),
                    Rect(Point(rect.min.x + w / 2, rect.min.y), Point(rect.max.x, rect.min.y + h / 2)),
                )
            for (quad in quads) {
                if (!geoSparseInner(quad, sparse - 1, iter)) return false
            }
            return true
        }
        var alive = true
        geoSearch(rect) { o ->
            val (match, ok) = iter(o)
            if (!ok) {
                alive = false
                false
            } else {
                !match
            }
        }
        return alive
    }

    /** All objects fully contained within [obj]. */
    fun within(
        obj: GeoObject,
        sparse: Int,
        cursor: Cursor?,
        iter: (Item) -> Boolean,
    ): Boolean = geoFilter(obj, sparse, cursor, { it.geo.within(obj) }, iter)

    /** All objects that intersect [obj]. */
    fun intersects(
        obj: GeoObject,
        sparse: Int,
        cursor: Cursor?,
        iter: (Item) -> Boolean,
    ): Boolean = geoFilter(obj, sparse, cursor, { it.geo.intersects(obj) }, iter)

    private fun geoFilter(
        obj: GeoObject,
        sparse: Int,
        cursor: Cursor?,
        test: (Item) -> Boolean,
        iter: (Item) -> Boolean,
    ): Boolean {
        val offset = cursor?.offset() ?: 0L
        cursor?.step(offset)
        var count = 0L
        if (sparse > 0) {
            return geoSparse(obj, sparse) { o ->
                count++
                if (count <= offset) {
                    false to true
                } else {
                    cursor?.step(1)
                    val match = test(o)
                    match to (if (match) iter(o) else false)
                }
            }
        }
        return geoSearch(obj.rect()) { o ->
            count++
            if (count <= offset) {
                true
            } else {
                cursor?.step(1)
                if (test(o)) iter(o) else true
            }
        }
    }

    /** Nearest neighbors of [target]'s center, ordered by geodetic distance in meters. */
    fun nearby(
        target: GeoObject,
        cursor: Cursor?,
        iter: (Item, Double) -> Boolean,
    ): Boolean {
        var alive = true
        val center = target.center()
        val offset = cursor?.offset() ?: 0L
        cursor?.step(offset)
        var count = 0L
        spatial.nearby({ minX, minY, maxX, maxY, data, item ->
            if (item) {
                val r = data!!.rect()
                EARTH_RADIUS * pointRectDistGeodeticDeg(center.y, center.x, r.min.y, r.min.x, r.max.y, r.max.x)
            } else {
                EARTH_RADIUS *
                    pointRectDistGeodeticDeg(
                        center.y,
                        center.x,
                        minY.toDouble(),
                        minX.toDouble(),
                        maxY.toDouble(),
                        maxX.toDouble(),
                    )
            }
        }) { _, _, _, _, o, dist ->
            count++
            if (count <= offset) {
                true
            } else {
                cursor?.step(1)
                alive = iter(o, dist)
                alive
            }
        }
        return alive
    }

    /** Iterates objects ordered by expiration time. */
    fun scanExpires(iter: (Item) -> Boolean) {
        for (o in expires) if (!iter(o)) return
    }

    companion object {
        private const val EARTH_RADIUS = 6371e3

        val BY_VALUE: Comparator<Item> =
            Comparator { a, b ->
                val c = a.toString().compareTo(b.toString())
                if (c != 0) c else a.id.compareTo(b.id)
            }

        val BY_EXPIRES: Comparator<Item> =
            Comparator { a, b ->
                val c = a.expires.compareTo(b.expires)
                if (c != 0) c else a.id.compareTo(b.id)
            }

        fun pointRectDistGeodeticDeg(
            pLat: Double,
            pLng: Double,
            minLat: Double,
            minLng: Double,
            maxLat: Double,
            maxLng: Double,
        ): Double =
            pointRectDistGeodeticRad(
                pLat * Math.PI / 180,
                pLng * Math.PI / 180,
                minLat * Math.PI / 180,
                minLng * Math.PI / 180,
                maxLat * Math.PI / 180,
                maxLng * Math.PI / 180,
            )

        private fun distRad(
            fa: Double,
            la: Double,
            fb: Double,
            lb: Double,
        ): Double {
            if (fa == fb && la == lb) return 0.0
            val sinDf = sin((fa - fb) / 2)
            val sinDl = sin((la - lb) / 2)
            return 2 * asin(sqrt(sinDf * sinDf + sinDl * sinDl * cos(fa) * cos(fb)))
        }

        /**
         * Schubert, Zimek & Kriegel (2013), "Geodetic Distance Queries on R-Trees for
         * Indexing Geographic Data".
         */
        private fun pointRectDistGeodeticRad(
            fq: Double,
            lq: Double,
            fl: Double,
            ll: Double,
            fh: Double,
            lh: Double,
        ): Double {
            val twoPi = 2 * Math.PI
            val halfPi = Math.PI / 2
            if (fl >= fh && ll >= lh) return distRad(fl, ll, fq, lq)
            if (ll <= lq && lq <= lh) {
                if (fl <= fq && fq <= fh) return 0.0
                if (fq < fl) return fl - fq
                return fq - fh
            }
            var dle = ll - lq
            var dlw = lq - lh
            if (dle < 0) dle += twoPi
            if (dlw < 0) dlw += twoPi
            val dl: Double
            val ledge: Double
            if (dle <= dlw) {
                dl = dle
                ledge = ll
            } else {
                dl = dlw
                ledge = lh
            }
            val sinDl = sin(dl)
            val cosDl = cos(dl)
            val tanFq = tan(fq)
            if (dl >= halfPi) {
                val fmid = (fh + fl) / 2
                if (tanFq >= tan(fmid) * cosDl) return distRad(fq, lq, fh, ledge)
                return distRad(fq, lq, fl, ledge)
            }
            if (tanFq >= tan(fh) * cosDl) return distRad(fq, lq, fh, ledge)
            if (tanFq <= tan(fl) * cosDl) return distRad(fq, lq, fl, ledge)
            return asin(cos(fq) * sinDl)
        }
    }
}
