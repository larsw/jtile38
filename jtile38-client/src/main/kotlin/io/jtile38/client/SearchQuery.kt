// Copyright jtile38 authors, MIT License.

package io.jtile38.client

import io.jtile38.client.Tile38Client.Companion.arg
import io.jtile38.resp.RespValue

/**
 * Builder for SCAN, SEARCH, NEARBY, WITHIN and INTERSECTS. Set options, then an area (not for SCAN
 * and SEARCH), then call one of the `as...` methods or [count] to run the query.
 */
class SearchQuery internal constructor(
    private val client: Tile38Client,
    private val command: String,
    private val key: String,
) {
    private val options = ArrayList<String>()
    private var area: List<String> = emptyList()
    private var withDistance = false

    fun cursor(cursor: Long) = apply { options += listOf("CURSOR", cursor.toString()) }

    fun limit(limit: Long) = apply { options += listOf("LIMIT", limit.toString()) }

    /** Glob pattern for ids (or values for SEARCH). May be given several times. */
    fun match(pattern: String) = apply { options += listOf("MATCH", pattern) }

    /** Field range filter, inclusive. Use `"-inf"`/`"+inf"` for open ranges. */
    fun where(
        field: String,
        min: Any,
        max: Any,
    ) = apply { options += listOf("WHERE", field, arg(min), arg(max)) }

    fun whereIn(
        field: String,
        vararg values: Any,
    ) = apply { options += listOf("WHEREIN", field, values.size.toString()) + values.map { arg(it) } }

    fun noFields() = apply { options += "NOFIELDS" }

    fun asc() = apply { options += "ASC" }

    fun desc() = apply { options += "DESC" }

    /** Include the distance in meters in NEARBY results. */
    fun distance() =
        apply {
            options += "DISTANCE"
            withDistance = true
        }

    fun sparse(sparse: Int) = apply { options += listOf("SPARSE", sparse.toString()) }

    // ---- areas ------------------------------------------------------------

    /** A point; for NEARBY an optional radius in meters limits the results. */
    @JvmOverloads
    fun point(
        lat: Double,
        lon: Double,
        meters: Double? = null,
    ) = area(listOf("POINT", arg(lat), arg(lon)) + listOfNotNull(meters?.let { arg(it) }))

    fun circle(
        lat: Double,
        lon: Double,
        meters: Double,
    ) = area(listOf("CIRCLE", arg(lat), arg(lon), arg(meters)))

    fun bounds(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
    ) = area(listOf("BOUNDS", arg(minLat), arg(minLon), arg(maxLat), arg(maxLon)))

    fun hash(geohash: String) = area(listOf("HASH", geohash))

    fun quadkey(quadkey: String) = area(listOf("QUADKEY", quadkey))

    fun tile(
        x: Long,
        y: Long,
        z: Int,
    ) = area(listOf("TILE", x.toString(), y.toString(), z.toString()))

    fun geoJson(json: String) = area(listOf("OBJECT", json))

    /** Use an existing object as the area. */
    fun get(
        key: String,
        id: String,
    ) = area(listOf("GET", key, id))

    private fun area(a: List<String>) = apply { area = a }

    // ---- outputs ------------------------------------------------------------

    fun count(): Long = Tile38Client.int(run("COUNT"))

    fun asIds(): SearchPage = page(run("IDS")) { v -> if (v is RespValue.Array) hit(v, json = false) else SearchHit(v.text()!!) }

    fun asObjects(): SearchPage = page(run("OBJECTS")) { hit(it as RespValue.Array, json = true) }

    fun asPoints(): SearchPage =
        page(run("POINTS")) {
            hit(it as RespValue.Array, json = false) { h, v -> h.copy(point = Tile38Client.point(v)) }
        }

    fun asBounds(): SearchPage =
        page(run("BOUNDS")) {
            hit(it as RespValue.Array, json = false) { h, v -> h.copy(bounds = Tile38Client.bounds(v)) }
        }

    fun asHashes(precision: Int): SearchPage =
        page(run("HASHES", precision.toString())) { hit(it as RespValue.Array, json = false) { h, v -> h.copy(hash = v.text()) } }

    private fun run(vararg output: String): RespValue {
        val needsArea = command != "SCAN" && command != "SEARCH"
        if (needsArea && area.isEmpty()) throw IllegalStateException("$command requires an area")
        return client.execute(listOf(command, key) + options + output + area)
    }

    private fun page(
        r: RespValue,
        map: (RespValue) -> SearchHit,
    ): SearchPage {
        val arr = (r as RespValue.Array).values!!
        return SearchPage(Tile38Client.int(arr[0]), (arr[1] as RespValue.Array).values.orEmpty().map(map))
    }

    /** Parses `[id, value?, fields?, distance?]`; the value is decoded by [value] or kept as JSON. */
    private fun hit(
        a: RespValue.Array,
        json: Boolean,
        value: ((SearchHit, RespValue) -> SearchHit)? = null,
    ): SearchHit {
        val vs = a.values!!
        var h = SearchHit(vs[0].text()!!)
        var i = 1
        if (json && i < vs.size) h = h.copy(json = vs[i++].text())
        if (value != null && i < vs.size) h = value(h, vs[i++])
        if (i < vs.size && vs[i] is RespValue.Array) h = h.copy(fields = Tile38Client.pairs(vs[i++]))
        if (i < vs.size) h = h.copy(distance = vs[i].text()!!.toDouble())
        return h
    }
}
