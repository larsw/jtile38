// Port of tile38 internal/server/crud.go, keys.go and stats.go, Copyright Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.server

import io.jtile38.geojson.GeoJson
import io.jtile38.geojson.GeoJsonException
import io.jtile38.geojson.GeoObject
import io.jtile38.geojson.GeoPoint
import io.jtile38.geojson.GeoRect
import io.jtile38.geojson.geometry.Point
import io.jtile38.geojson.geometry.Rect
import io.jtile38.resp.RespValue
import kotlin.math.max

/** Result of a write command: the response and the details needed for the AOF. */
class WriteResult(
    val res: RespValue,
    val details: CommandDetails,
)

private fun isReservedFieldName(name: String) = name == "z" || name == "lat" || name == "lon"

private fun parseF(s: String): Double = GoCompat.parseFloat(s) ?: throw Errors.invalidArgument(s)

/** Response kinds for GET and `SET/FSET ... RETURN`. */
internal class ReturnSpec(
    var kind: String = "object",
    var precision: Int = 0,
    var withFields: Boolean = false,
)

/** Parses the `RETURN [WITHFIELDS] [OBJECT|POINT|BOUNDS|HASH precision]` option; returns the new index. */
private fun parseReturn(
    args: List<String>,
    start: Int,
    spec: ReturnSpec,
): Int {
    var i = start
    var j = start
    while (j < start + 3) {
        if (j >= args.size) break
        when (args[j].lowercase()) {
            "withfields" -> {
                spec.withFields = true
                i += 1
            }

            "object", "point", "bounds" -> {
                spec.kind = args[j].lowercase()
                i += 1
            }

            "hash" -> {
                spec.kind = "hash"
                j++
                if (j == args.size) throw Errors.invalidNumberOfArguments()
                val p = GoCompat.parseInt(args[j])
                if (p == null || p < 1 || p > 12) throw Errors.invalidArgument(args[j])
                spec.precision = p.toInt()
                i += 2
            }
        }
        j++
    }
    return i
}

internal fun Server.buildObjectResponse(
    msg: Message,
    o: Item,
    start: Long,
    spec: ReturnSpec,
): RespValue = buildObjectResponse(msg, o, start, spec.kind, spec.precision, spec.withFields)

internal fun Server.buildObjectResponse(
    msg: Message,
    o: Item,
    start: Long,
    kind: String,
    precision: Int,
    withFields: Boolean,
): RespValue {
    val json = msg.outputType == OutputType.JSON
    val buf = StringBuilder()
    val vals = ArrayList<RespValue>(2)
    if (json) buf.append("{\"ok\":true")
    when (kind) {
        "object" -> {
            if (json) {
                buf.append(",\"object\":")
                o.geo.appendJson(buf)
            } else {
                vals.add(RespValue.string(o.toString()))
            }
        }

        "point" -> {
            if (json) {
                buf.append(",\"point\":")
                appendJsonSimplePoint(buf, o.geo)
            } else {
                val p = o.geo.center()
                val z = extractZCoordinate(o.geo)
                val pts = mutableListOf(RespValue.float(p.y), RespValue.float(p.x))
                if (z != 0.0) pts.add(RespValue.float(z))
                vals.add(RespValue.array(pts))
            }
        }

        "hash" -> {
            val c = o.geo.center()
            val h = Geohash.encode(c.y, c.x, precision)
            if (json) buf.append(",\"hash\":\"").append(h).append('"') else vals.add(RespValue.string(h))
        }

        "bounds" -> {
            if (json) {
                buf.append(",\"bounds\":")
                appendJsonSimpleBounds(buf, o.geo)
            } else {
                vals.add(respBounds(o))
            }
        }
    }
    if (withFields && o.fields.size() > 0) {
        if (json) {
            buf.append(",\"fields\":{")
            var i = 0
            o.fields.forEach {
                if (i++ > 0) buf.append(',')
                buf.append(GoCompat.jsonString(it.name)).append(':').append(it.value.json())
            }
            buf.append('}')
        } else {
            val fvals = ArrayList<RespValue>()
            o.fields.forEach {
                fvals.add(RespValue.string(it.name))
                fvals.add(RespValue.string(it.value.data))
            }
            vals.add(RespValue.array(fvals))
        }
    }
    if (json) {
        buf.append(",\"elapsed\":\"").append(elapsed(start)).append("\"}")
        return RespValue.string(buf.toString())
    }
    return if (withFields) RespValue.array(vals) else vals[0]
}

/** BOUNDS key */
internal fun Server.cmdBounds(msg: Message): RespValue {
    val start = System.nanoTime()
    if (msg.args.size != 2) throw Errors.invalidNumberOfArguments()
    val col = cols[msg.args[1]] ?: return nullOr(msg, Errors.keyNotFound())
    val b = col.bounds()
    if (msg.outputType == OutputType.JSON) {
        val sb = StringBuilder("{\"ok\":true,\"bounds\":")
        GeoRect(Rect(Point(b[0], b[1]), Point(b[2], b[3]))).appendJson(sb)
        sb.append(",\"elapsed\":\"").append(elapsed(start)).append("\"}")
        return RespValue.string(sb.toString())
    }
    return RespValue.array(
        listOf(
            RespValue.array(listOf(RespValue.float(b[0]), RespValue.float(b[1]))),
            RespValue.array(listOf(RespValue.float(b[2]), RespValue.float(b[3]))),
        ),
    )
}

private fun nullOr(
    msg: Message,
    err: CommandException,
): RespValue {
    if (msg.outputType == OutputType.RESP) return RespValue.NULL
    throw err
}

/** TYPE key */
internal fun Server.cmdType(msg: Message): RespValue {
    val start = System.nanoTime()
    if (msg.args.size != 2) throw Errors.invalidNumberOfArguments()
    if (cols[msg.args[1]] == null) {
        if (msg.outputType == OutputType.RESP) return RespValue.simple("none")
        throw Errors.keyNotFound()
    }
    if (msg.outputType == OutputType.JSON) {
        return RespValue.string("{\"ok\":true,\"type\":\"hash\",\"elapsed\":\"${elapsed(start)}\"}")
    }
    return RespValue.simple("hash")
}

/** GET key id (WITHFIELDS) (OBJECT|POINT|BOUNDS|(HASH geohash)) */
internal fun Server.cmdGet(msg: Message): RespValue {
    val start = System.nanoTime()
    val args = msg.args
    if (args.size < 3) throw Errors.invalidNumberOfArguments()
    val spec = ReturnSpec()
    var i = 3
    while (i < args.size) {
        when (args[i].lowercase()) {
            "withfields" -> {
                spec.withFields = true
            }

            "object", "point", "bounds" -> {
                spec.kind = args[i].lowercase()
            }

            "hash" -> {
                spec.kind = "hash"
                i++
                if (i == args.size) throw Errors.invalidNumberOfArguments()
                val p = GoCompat.parseInt(args[i])
                if (p == null || p < 1 || p > 12) throw Errors.invalidArgument(args[i])
                spec.precision = p.toInt()
            }

            else -> {
                throw Errors.invalidNumberOfArguments()
            }
        }
        i++
    }
    val col = cols[args[1]] ?: return nullOr(msg, Errors.keyNotFound())
    val o = col.get(args[2]) ?: return nullOr(msg, Errors.idNotFound())
    return buildObjectResponse(msg, o, start, spec)
}

/** DEL key id (ERRON404) */
internal fun Server.cmdDel(msg: Message): WriteResult {
    val start = System.nanoTime()
    val args = msg.args
    if (args.size < 3) throw Errors.invalidNumberOfArguments()
    val key = args[1]
    val id = args[2]
    var erron404 = false
    for (i in 3 until args.size) {
        if (args[i].lowercase() == "erron404") erron404 = true else throw Errors.invalidArgument(args[i])
    }
    var updated = false
    val col = cols[key]
    if (col != null) {
        if (col.delete(id) != null) {
            if (col.count() == 0) cols.remove(key)
            updated = true
        } else if (erron404) {
            throw Errors.idNotFound()
        }
    } else if (erron404) {
        throw Errors.keyNotFound()
    }
    val res = if (msg.outputType == OutputType.JSON) okJson(start) else RespValue.integer(if (updated) 1 else 0)
    return WriteResult(res, CommandDetails("del", key, updated))
}

/** PDEL key pattern */
internal fun Server.cmdPdel(msg: Message): WriteResult {
    val start = System.nanoTime()
    val args = msg.args
    if (args.size != 3) throw Errors.invalidNumberOfArguments()
    val key = args[1]
    val pattern = args[2]
    var deleted = 0
    val col = cols[key]
    if (col != null) {
        val g = Glob.parse(pattern, false)
        val ids = ArrayList<String>()
        val iter: (Item) -> Boolean = { o ->
            if (Glob.matches(pattern, o.id)) ids.add(o.id)
            true
        }
        if (g.limits[0] == "" && g.limits[1] == "") {
            col.scan(false, null, iter)
        } else {
            col.scanRange(g.limits[0], g.limits[1], false, null, iter)
        }
        for (id in ids) {
            col.delete(id)
            deleted++
        }
        if (col.count() == 0) cols.remove(key)
    }
    val res = if (msg.outputType == OutputType.JSON) okJson(start) else RespValue.integer(deleted)
    return WriteResult(res, CommandDetails("pdel", key, deleted > 0))
}

/** DROP key */
internal fun Server.cmdDrop(msg: Message): WriteResult {
    val start = System.nanoTime()
    if (msg.args.size != 2) throw Errors.invalidNumberOfArguments()
    val key = msg.args[1]
    val updated = cols.remove(key) != null
    val res = if (msg.outputType == OutputType.JSON) okJson(start) else RespValue.integer(if (updated) 1 else 0)
    return WriteResult(res, CommandDetails("drop", key, updated))
}

/** RENAME key newkey / RENAMENX key newkey */
internal fun Server.cmdRename(msg: Message): WriteResult {
    val start = System.nanoTime()
    val args = msg.args
    if (args.size != 3) throw Errors.invalidNumberOfArguments()
    val nx = args[0].lowercase() == "renamenx"
    val key = args[1]
    val newKey = args[2]
    val col = cols[key] ?: throw Errors.keyNotFound()
    var updated = false
    if (cols[newKey] == null) {
        updated = true
    } else if (!nx) {
        cols.remove(newKey)
        updated = true
    }
    if (updated) {
        cols.remove(key)
        cols[newKey] = col
    }
    val res =
        when {
            msg.outputType == OutputType.JSON -> okJson(start)
            !nx -> RespValue.OK
            else -> RespValue.integer(if (updated) 1 else 0)
        }
    return WriteResult(res, CommandDetails("rename", key, updated))
}

/** FLUSHDB */
internal fun Server.cmdFlushdb(msg: Message): WriteResult {
    val start = System.nanoTime()
    if (msg.args.size != 1) throw Errors.invalidNumberOfArguments()
    cols.clear()
    val res = if (msg.outputType == OutputType.JSON) okJson(start) else RespValue.OK
    return WriteResult(res, CommandDetails("flushdb", "", true))
}

/**
 * SET key id (FIELD name value ...) (EX seconds) (NX|XX) (RETURN ...)
 * (OBJECT geojson)|(POINT lat lon [z])|(BOUNDS minlat minlon maxlat maxlon)|(HASH geohash)|(STRING value)
 */
internal fun Server.cmdSet(msg: Message): WriteResult {
    val start = System.nanoTime()
    val args = msg.args
    if (args.size < 3) throw Errors.invalidNumberOfArguments()
    val key = args[1]
    val id = args[2]
    val fields = ArrayList<Field>()
    var ex = 0L
    var xx = false
    var nx = false
    var ret = false
    val spec = ReturnSpec()
    var oobj: GeoObject? = null
    var i = 3
    while (i < args.size) {
        when (args[i].lowercase()) {
            "field" -> {
                if (i + 2 >= args.size) throw Errors.invalidNumberOfArguments()
                val fkey = args[i + 1]
                val fval = args[i + 2]
                i += 2
                if (isReservedFieldName(fkey)) throw Errors.invalidArgument(fkey)
                fields.add(Field.make(fkey, fval))
            }

            "ex" -> {
                if (i + 1 >= args.size) throw Errors.invalidNumberOfArguments()
                val x = parseF(args[i + 1])
                i += 1
                ex = nowNanos() + (1_000_000_000.0 * x).toLong()
            }

            "nx" -> {
                if (xx) throw Errors.invalidArgument(args[i])
                nx = true
            }

            "xx" -> {
                if (nx) throw Errors.invalidArgument(args[i])
                xx = true
            }

            "return" -> {
                if (ret) throw Errors.invalidArgument(args[i])
                ret = true
                i = parseReturn(args, i, spec)
            }

            "string" -> {
                if (i + 1 >= args.size) throw Errors.invalidNumberOfArguments()
                oobj = StringObject(args[i + 1])
                i += 1
            }

            "point" -> {
                if (i + 2 >= args.size) throw Errors.invalidNumberOfArguments()
                val slat = args[i + 1]
                val slon = args[i + 2]
                i += 2
                var z: Double? = null
                if (i + 1 < args.size) {
                    z = GoCompat.parseFloat(args[i + 1])
                    if (z != null) i++
                }
                val y = parseF(slat)
                val x = parseF(slon)
                oobj = if (z == null) GeoPoint.of(Point(x, y)) else GeoPoint.ofZ(Point(x, y), z)
            }

            "bounds" -> {
                if (i + 4 >= args.size) throw Errors.invalidNumberOfArguments()
                val v = DoubleArray(4) { parseF(args[i + 1 + it]) }
                i += 4
                oobj = GeoRect(Rect(Point(v[1], v[0]), Point(v[3], v[2])))
            }

            "hash" -> {
                if (i + 1 >= args.size) throw Errors.invalidNumberOfArguments()
                val (lat, lon) = Geohash.decode(args[i + 1])
                i += 1
                oobj = GeoPoint.of(Point(lon, lat))
            }

            "object" -> {
                if (i + 1 >= args.size) throw Errors.invalidNumberOfArguments()
                oobj = parseGeoJson(args[i + 1])
                i += 1
            }

            else -> {
                throw Errors.invalidArgument(args[i])
            }
        }
        i++
    }
    if (oobj == null) throw Errors.invalidNumberOfArguments()

    fun nada(): WriteResult {
        if (msg.outputType == OutputType.JSON) throw if (nx) Errors.idAlreadyExists() else Errors.idNotFound()
        return WriteResult(RespValue.NULL, CommandDetails())
    }

    var col = cols[key]
    if (col == null) {
        if (xx) return nada()
        col = Collection()
        cols[key] = col
    }
    if (xx || nx) {
        val exists = col.get(id) != null
        if ((!exists && xx) || (exists && nx)) return nada()
    }
    var flist = col.get(id)?.fields ?: FieldList.EMPTY
    for (f in fields) flist = flist.set(f)
    val obj = Item(id, oobj, ex, flist)
    col.set(obj)
    val d = CommandDetails("set", key, true)
    if (ret) return WriteResult(buildObjectResponse(msg, obj, start, spec), d)
    return WriteResult(if (msg.outputType == OutputType.JSON) okJson(start) else RespValue.OK, d)
}

internal fun Server.parseGeoJson(data: String): GeoObject =
    try {
        GeoJson.parse(data, parseOptions)
    } catch (e: GeoJsonException) {
        throw CommandException(e.message ?: "invalid data")
    }

/** FSET key id (XX) field value (field value...) (RETURN ...) */
internal fun Server.cmdFset(msg: Message): WriteResult {
    val start = System.nanoTime()
    val args = msg.args
    if (args.size < 5) throw Errors.invalidNumberOfArguments()
    val key = args[1]
    val id = args[2]
    var xx = false
    var ret = false
    val spec = ReturnSpec()
    val fields = ArrayList<Field>()
    var i = 3
    while (i < args.size) {
        when (args[i].lowercase()) {
            "xx" -> {
                xx = true
            }

            "return" -> {
                if (ret) throw Errors.invalidArgument(args[i])
                ret = true
                i = parseReturn(args, i, spec)
            }

            else -> {
                val fkey = args[i]
                i++
                if (i == args.size) throw Errors.invalidNumberOfArguments()
                if (isReservedFieldName(fkey)) throw Errors.invalidArgument(fkey)
                fields.add(Field.make(fkey, args[i]))
            }
        }
        i++
    }
    val col = cols[key] ?: throw Errors.keyNotFound()
    val o = col.get(id)
    if (o == null && !xx) throw Errors.idNotFound()
    var updateCount = 0
    var obj: Item? = null
    if (o != null) {
        var ofields = o.fields
        for (f in fields) {
            if (!ofields.get(f.name).value.equalsValue(f.value)) {
                ofields = ofields.set(f)
                updateCount++
            }
        }
        obj = Item(id, o.geo, o.expires, ofields)
        col.set(obj)
    }
    val d = CommandDetails("fset", key, updateCount > 0)
    if (ret && obj != null) return WriteResult(buildObjectResponse(msg, obj, start, spec), d)
    val res = if (msg.outputType == OutputType.JSON) okJson(start) else RespValue.integer(updateCount)
    return WriteResult(res, d)
}

/** FGET key id field */
internal fun Server.cmdFget(msg: Message): RespValue {
    val start = System.nanoTime()
    val args = msg.args
    if (args.size < 4) throw Errors.invalidNumberOfArguments()
    val col = cols[args[1]] ?: throw Errors.keyNotFound()
    val o = col.get(args[2]) ?: throw Errors.idNotFound()
    val f = o.fields.get(args[3])
    if (msg.outputType == OutputType.JSON) {
        return RespValue.string("{\"ok\":true,\"value\":${f.value.json()},\"elapsed\":\"${elapsed(start)}\"}")
    }
    return RespValue.string(f.value.data)
}

/** EXPIRE key id seconds */
internal fun Server.cmdExpire(msg: Message): WriteResult {
    val start = System.nanoTime()
    val args = msg.args
    if (args.size != 4) throw Errors.invalidNumberOfArguments()
    val key = args[1]
    val id = args[2]
    val value = parseF(args[3])
    var ok = false
    val col = cols[key]
    if (col != null) {
        val o = col.get(id)
        if (o != null) {
            ok = true
            col.set(Item(id, o.geo, nowNanos() + (1_000_000_000.0 * value).toLong(), o.fields))
        }
    }
    val d = if (ok) CommandDetails("expire", key, true) else CommandDetails()
    if (msg.outputType == OutputType.JSON) {
        if (ok) return WriteResult(okJson(start), d)
        throw if (col == null) Errors.keyNotFound() else Errors.idNotFound()
    }
    return WriteResult(RespValue.integer(if (ok) 1 else 0), d)
}

/** PERSIST key id */
internal fun Server.cmdPersist(msg: Message): WriteResult {
    val start = System.nanoTime()
    val args = msg.args
    if (args.size != 3) throw Errors.invalidNumberOfArguments()
    val key = args[1]
    val id = args[2]
    val col = cols[key]
    if (col == null) {
        if (msg.outputType == OutputType.RESP) return WriteResult(RespValue.integer(0), CommandDetails())
        throw Errors.keyNotFound()
    }
    val o = col.get(id)
    if (o == null) {
        if (msg.outputType == OutputType.RESP) return WriteResult(RespValue.integer(0), CommandDetails())
        throw Errors.idNotFound()
    }
    var cleared = false
    if (o.expires != 0L) {
        col.set(Item(id, o.geo, 0, o.fields))
        cleared = true
    }
    val res = if (msg.outputType == OutputType.JSON) okJson(start) else RespValue.integer(if (cleared) 1 else 0)
    return WriteResult(res, CommandDetails("persist", key, cleared))
}

/** TTL key id */
internal fun Server.cmdTtl(msg: Message): RespValue {
    val start = System.nanoTime()
    val args = msg.args
    if (args.size != 3) throw Errors.invalidNumberOfArguments()
    val col = cols[args[1]]
    if (col == null) {
        if (msg.outputType == OutputType.JSON) throw Errors.keyNotFound()
        return RespValue.integer(-2)
    }
    val o = col.get(args[2])
    if (o == null) {
        if (msg.outputType == OutputType.JSON) throw Errors.idNotFound()
        return RespValue.integer(-2)
    }
    val ttl = if (o.expires == 0L) -1.0 else max((o.expires - nowNanos()).toDouble() / 1e9, 0.0)
    if (msg.outputType == OutputType.JSON) {
        return RespValue.string("{\"ok\":true,\"ttl\":${ttl.toLong()},\"elapsed\":\"${elapsed(start)}\"}")
    }
    return RespValue.integer(ttl.toLong())
}

/** EXISTS key id */
internal fun Server.cmdExists(msg: Message): RespValue {
    val start = System.nanoTime()
    if (msg.args.size != 3) throw Errors.invalidNumberOfArguments()
    val col = cols[msg.args[1]] ?: throw Errors.keyNotFound()
    val exists = col.get(msg.args[2]) != null
    if (msg.outputType == OutputType.JSON) {
        return RespValue.string("{\"ok\":true,\"exists\":$exists,\"elapsed\":\"${elapsed(start)}\"}")
    }
    return RespValue.bool(exists)
}

/** FEXISTS key id field */
internal fun Server.cmdFexists(msg: Message): RespValue {
    val start = System.nanoTime()
    if (msg.args.size != 4) throw Errors.invalidNumberOfArguments()
    val col = cols[msg.args[1]] ?: throw Errors.keyNotFound()
    val o = col.get(msg.args[2]) ?: throw Errors.idNotFound()
    val exists = o.fields.get(msg.args[3]).name != ""
    if (msg.outputType == OutputType.JSON) {
        return RespValue.string("{\"ok\":true,\"exists\":$exists,\"elapsed\":\"${elapsed(start)}\"}")
    }
    return RespValue.bool(exists)
}

/** KEYS pattern */
internal fun Server.cmdKeys(msg: Message): RespValue {
    val start = System.nanoTime()
    if (msg.args.size != 2) throw Errors.invalidNumberOfArguments()
    val pattern = msg.args[1]
    val keys = ArrayList<String>()
    val g = Glob.parse(pattern, false)
    if (g.limits[0] == "" && g.limits[1] == "") {
        for (key in cols.keys) if (Glob.matches(pattern, key)) keys.add(key)
    } else {
        for (key in cols.tailMap(g.limits[0], true).keys) {
            if (key > g.limits[1]) break
            if (Glob.matches(pattern, key)) keys.add(key)
        }
    }
    if (msg.outputType == OutputType.JSON) {
        val data = keys.joinToString(",", "[", "]") { GoCompat.jsonMarshalString(it) }
        return RespValue.string("{\"ok\":true,\"keys\":$data,\"elapsed\":\"${elapsed(start)}\"}")
    }
    return RespValue.array(keys.map { RespValue.string(it) })
}

/** STATS key (key...) */
internal fun Server.cmdStats(msg: Message): RespValue {
    val start = System.nanoTime()
    if (msg.args.size < 2) throw Errors.invalidNumberOfArguments()
    val json = msg.outputType == OutputType.JSON
    val vals = ArrayList<RespValue>()
    val ms = ArrayList<String>()
    for (key in msg.args.drop(1)) {
        val col = cols[key]
        if (col == null) {
            if (json) ms.add("null") else vals.add(RespValue.NULL)
            continue
        }
        val m =
            sortedMapOf<String, Any>(
                "in_memory_size" to col.totalWeight(),
                "num_objects" to col.count(),
                "num_points" to col.pointCount(),
                "num_strings" to col.stringCount(),
            )
        if (json) ms.add(jsonMap(m)) else vals.add(RespValue.array(respSimpleMap(m)))
    }
    if (json) {
        return RespValue.string("{\"ok\":true,\"stats\":${ms.joinToString(",", "[", "]")},\"elapsed\":\"${elapsed(start)}\"}")
    }
    return RespValue.array(vals)
}

/** SERVER (EXT) */
internal fun Server.cmdServer(msg: Message): RespValue {
    val start = System.nanoTime()
    for (a in msg.args.drop(1)) {
        if (a.lowercase() != "ext") throw Errors.invalidArgument(a)
    }
    val m = basicStats()
    if (msg.outputType == OutputType.JSON) {
        return RespValue.string("{\"ok\":true,\"stats\":${jsonMap(m)},\"elapsed\":\"${elapsed(start)}\"}")
    }
    return RespValue.array(respSimpleMap(m))
}

internal fun Server.basicStats(): Map<String, Any> {
    val rt = Runtime.getRuntime()
    val points = cols.values.sumOf { it.pointCount() }
    val heap = rt.totalMemory() - rt.freeMemory()
    return sortedMapOf(
        "id" to serverId,
        "http_transport" to true,
        "pid" to ProcessHandle.current().pid(),
        "aof_size" to aofSize(),
        "num_collections" to cols.size,
        "num_hooks" to 0,
        "in_memory_size" to cols.values.sumOf { it.totalWeight() },
        "num_points" to points,
        "num_objects" to cols.values.sumOf { it.count() },
        "num_strings" to cols.values.sumOf { it.stringCount() },
        "mem_alloc" to heap,
        "heap_size" to heap,
        "heap_released" to 0,
        "max_heap_size" to 0,
        "avg_item_size" to if (points != 0) heap / points else 0,
        "version" to VERSION,
        "pointer_size" to 8,
        "read_only" to false,
        "cpus" to rt.availableProcessors(),
        "threads" to Thread.activeCount(),
        "pending_events" to 0,
    )
}

private fun jsonMap(m: Map<String, Any>): String =
    m.entries.joinToString(",", "{", "}") { (k, v) ->
        GoCompat.jsonMarshalString(k) + ":" +
            when (v) {
                is String -> GoCompat.jsonMarshalString(v)
                is Double -> GoCompat.formatFloat(v)
                else -> v.toString()
            }
    }

private fun respSimpleMap(m: Map<String, Any>): List<RespValue> =
    m.entries.flatMap { (k, v) ->
        listOf(RespValue.string(k), RespValue.string(if (v is Double) GoCompat.formatFloat(v) else v.toString()))
    }
