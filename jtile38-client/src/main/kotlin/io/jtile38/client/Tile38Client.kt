// Copyright jtile38 authors, MIT License.

package io.jtile38.client

import io.jtile38.resp.RespValue
import java.io.Closeable
import java.util.concurrent.LinkedBlockingDeque

/**
 * A thread-safe Tile38 client with a small connection pool.
 *
 * ```kotlin
 * Tile38Client("localhost", 9851).use { t38 ->
 *     t38.set("fleet", "truck1").field("speed", 90).point(33.5123, -112.2693)
 *     val page = t38.nearby("fleet").distance().point(33.5, -112.2, 10_000.0).asObjects()
 * }
 * ```
 *
 * Connections use RESP output. Error replies are thrown as [Tile38Exception].
 */
class Tile38Client
    @JvmOverloads
    constructor(
        val host: String = "127.0.0.1",
        val port: Int = 9851,
        private val maxIdleConnections: Int = 8,
        private val password: String? = null,
    ) : Closeable {
        private val idle = LinkedBlockingDeque<Tile38Connection>()

        @Volatile
        private var closed = false

        private fun borrow(): Tile38Connection {
            check(!closed) { "client is closed" }
            idle.pollFirst()?.let { if (it.isOpen) return it }
            val c = Tile38Connection(host, port)
            try {
                if (password != null) checkReply(c.execute("AUTH", password))
                checkReply(c.execute("OUTPUT", "resp"))
            } catch (e: Exception) {
                c.close()
                throw e
            }
            return c
        }

        private fun release(c: Tile38Connection) {
            if (!c.isOpen || closed || idle.size >= maxIdleConnections) c.close() else idle.offerFirst(c)
        }

        /** Runs [block] with a pooled connection, e.g. for pipelines of raw commands. */
        fun <T> withConnection(block: (Tile38Connection) -> T): T {
            val c = borrow()
            try {
                return block(c)
            } finally {
                release(c)
            }
        }

        /** Executes any command and returns the raw reply; error replies are thrown. */
        fun execute(args: List<String>): RespValue = checkReply(withConnection { it.execute(args) })

        /** Vararg variant of [execute]. */
        fun execute(vararg args: Any): RespValue = execute(args.map { arg(it) })

        override fun close() {
            closed = true
            while (true) (idle.pollFirst() ?: break).close()
        }

        // ---- server ------------------------------------------------------------

        fun ping(): Boolean = execute("PING").text() == "PONG"

        /** Server statistics as reported by SERVER. */
        fun server(): Map<String, String> = pairs(execute("SERVER"))

        fun flushDb() {
            execute("FLUSHDB")
        }

        // ---- keys and objects ------------------------------------------------------------

        /** Starts a SET command; finish it with one of the object methods such as [SetCommand.point]. */
        fun set(
            key: String,
            id: String,
        ): SetCommand = SetCommand(this, key, id)

        /** GET as GeoJSON (or the raw string value); null when the key or id does not exist. */
        @JvmOverloads
        fun get(
            key: String,
            id: String,
            withFields: Boolean = false,
        ): GetResult? {
            val args = mutableListOf<Any>("GET", key, id)
            if (withFields) args.add("WITHFIELDS")
            val r = execute(*args.toTypedArray())
            if (r.isNull()) return null
            if (!withFields) return GetResult(r.text()!!, emptyMap())
            val arr = (r as RespValue.Array).values!!
            return GetResult(arr[0].text()!!, if (arr.size > 1) pairs(arr[1]) else emptyMap())
        }

        /** The center point of an object; null when it does not exist. */
        fun getPoint(
            key: String,
            id: String,
        ): Point? = execute("GET", key, id, "POINT").let { if (it.isNull()) null else point(it) }

        fun getBounds(
            key: String,
            id: String,
        ): Bounds? = execute("GET", key, id, "BOUNDS").let { if (it.isNull()) null else bounds(it) }

        fun getHash(
            key: String,
            id: String,
            precision: Int,
        ): String? = execute("GET", key, id, "HASH", precision).text()

        /** Sets fields on an existing object and returns the number of fields that changed. */
        @JvmOverloads
        fun fset(
            key: String,
            id: String,
            fields: Map<String, Any>,
            xx: Boolean = false,
        ): Long {
            val args = mutableListOf<Any>("FSET", key, id)
            if (xx) args.add("XX")
            fields.forEach { (k, v) ->
                args.add(k)
                args.add(v)
            }
            return int(execute(*args.toTypedArray()))
        }

        fun fget(
            key: String,
            id: String,
            field: String,
        ): String? = execute("FGET", key, id, field).text()

        fun exists(
            key: String,
            id: String,
        ): Boolean = int(execute("EXISTS", key, id)) == 1L

        fun fexists(
            key: String,
            id: String,
            field: String,
        ): Boolean = int(execute("FEXISTS", key, id, field)) == 1L

        /** Deletes an object; returns true if it existed. */
        fun del(
            key: String,
            id: String,
        ): Boolean = int(execute("DEL", key, id)) == 1L

        /** Deletes all objects whose id matches [pattern]; returns the number deleted. */
        fun pdel(
            key: String,
            pattern: String,
        ): Long = int(execute("PDEL", key, pattern))

        /** Deletes a whole collection; returns true if it existed. */
        fun drop(key: String): Boolean = int(execute("DROP", key)) == 1L

        fun rename(
            key: String,
            newKey: String,
        ) {
            execute("RENAME", key, newKey)
        }

        /** Renames only if [newKey] does not exist; returns true if renamed. */
        fun renameNx(
            key: String,
            newKey: String,
        ): Boolean = int(execute("RENAMENX", key, newKey)) == 1L

        /** Sets a timeout in seconds; returns true if the object exists. */
        fun expire(
            key: String,
            id: String,
            seconds: Double,
        ): Boolean = int(execute("EXPIRE", key, id, seconds)) == 1L

        /** Removes a timeout; returns true if a timeout was removed. */
        fun persist(
            key: String,
            id: String,
        ): Boolean = int(execute("PERSIST", key, id)) == 1L

        /** Remaining time to live in seconds, -1 for no timeout and -2 if the object does not exist. */
        fun ttl(
            key: String,
            id: String,
        ): Long = int(execute("TTL", key, id))

        fun keys(pattern: String = "*"): List<String> = (execute("KEYS", pattern) as RespValue.Array).values.orEmpty().map { it.text()!! }

        /** Bounding box of all objects in a collection; null when it does not exist. */
        fun bounds(key: String): Bounds? =
            execute("BOUNDS", key).let {
                if (it.isNull()) {
                    null
                } else {
                    // Unlike GET ... BOUNDS, the BOUNDS command replies with [lon lat] pairs.
                    val b = bounds(it)
                    Bounds(b.minLon, b.minLat, b.maxLon, b.maxLat)
                }
            }

        /** Statistics per key; null entries for keys that do not exist. */
        fun stats(vararg keys: String): List<CollectionStats?> =
            (execute("STATS", *keys) as RespValue.Array).values.orEmpty().map { v ->
                if (v.isNull()) {
                    null
                } else {
                    val m = pairs(v)
                    CollectionStats(
                        m["in_memory_size"]!!.toLong(),
                        m["num_objects"]!!.toLong(),
                        m["num_points"]!!.toLong(),
                        m["num_strings"]!!.toLong(),
                    )
                }
            }

        // ---- searches ------------------------------------------------------------

        /** SCAN: iterate a collection in id order. */
        fun scan(key: String) = SearchQuery(this, "SCAN", key)

        /** SEARCH: iterate the string values of a collection. */
        fun search(key: String) = SearchQuery(this, "SEARCH", key)

        /** NEARBY: kNN search; set the area with [SearchQuery.point]. */
        fun nearby(key: String) = SearchQuery(this, "NEARBY", key)

        /** WITHIN: objects fully contained in an area. */
        fun within(key: String) = SearchQuery(this, "WITHIN", key)

        /** INTERSECTS: objects intersecting an area. */
        fun intersects(key: String) = SearchQuery(this, "INTERSECTS", key)

        internal companion object {
            fun arg(v: Any): String =
                when (v) {
                    is Double -> {
                        if (v == Math.rint(v) && !v.isInfinite() &&
                            kotlin.math.abs(v) < 1e15
                        ) {
                            v.toLong().toString()
                        } else {
                            v.toString()
                        }
                    }

                    is Float -> {
                        arg(v.toDouble())
                    }

                    else -> {
                        v.toString()
                    }
                }

            fun checkReply(r: RespValue): RespValue {
                if (r is RespValue.Error) throw Tile38Exception(utf8(r.message).removePrefix("ERR "))
                return r
            }

            fun RespValue.isNull() = (this is RespValue.BulkString && value == null) || (this is RespValue.Array && values == null)

            fun int(r: RespValue): Long = (r as? RespValue.Integer)?.value ?: r.text()!!.toLong()

            fun pairs(r: RespValue): Map<String, String> {
                val vs = (r as RespValue.Array).values.orEmpty()
                val m = LinkedHashMap<String, String>()
                for (i in 0 until vs.size - 1 step 2) m[vs[i].text()!!] = vs[i + 1].text()!!
                return m
            }

            fun point(r: RespValue): Point {
                val v = (r as RespValue.Array).values!!.map { it.text()!!.toDouble() }
                return Point(v[0], v[1], v.getOrNull(2))
            }

            fun bounds(r: RespValue): Bounds {
                val v = (r as RespValue.Array).values!!.map { point(it) }
                return Bounds(v[0].lat, v[0].lon, v[1].lat, v[1].lon)
            }
        }
    }

/** Builder for SET. Options first, then one terminal object method that sends the command. */
class SetCommand internal constructor(
    private val client: Tile38Client,
    private val key: String,
    private val id: String,
) {
    private val options = ArrayList<String>()

    fun field(
        name: String,
        value: Any,
    ) = apply { options += listOf("FIELD", name, Tile38Client.arg(value)) }

    /** Expire the object after [seconds]. */
    fun expire(seconds: Double) = apply { options += listOf("EX", Tile38Client.arg(seconds)) }

    /** Only set if the object does not exist. */
    fun nx() = apply { options += "NX" }

    /** Only set if the object already exists. */
    fun xx() = apply { options += "XX" }

    /** Returns true if the object was set, false if prevented by NX or XX. */
    @JvmOverloads
    fun point(
        lat: Double,
        lon: Double,
        z: Double? = null,
    ): Boolean = send(listOf("POINT", lat, lon) + listOfNotNull(z))

    fun bounds(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
    ): Boolean = send(listOf("BOUNDS", minLat, minLon, maxLat, maxLon))

    fun hash(geohash: String): Boolean = send(listOf("HASH", geohash))

    /** Any GeoJSON object, feature or collection. */
    fun geoJson(json: String): Boolean = send(listOf("OBJECT", json))

    /** A non-spatial string value. */
    fun string(value: String): Boolean = send(listOf("STRING", value))

    private fun send(obj: List<Any>): Boolean {
        val args = listOf("SET", key, id) + options + obj.map { Tile38Client.arg(it) }
        val r = client.execute(args)
        return !(r is RespValue.BulkString && r.value == null)
    }
}
