// Port of tile38 internal/server/search.go, scan.go and token.go, Copyright Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.server

import io.jtile38.geojson.Circle
import io.jtile38.geojson.GeoObject
import io.jtile38.geojson.GeoPoint
import io.jtile38.geojson.GeoRect
import io.jtile38.geojson.geometry.Point
import io.jtile38.geojson.geometry.Rect
import io.jtile38.resp.RespValue

private const val DEFAULT_CIRCLE_STEPS = 64

/** Parsed options shared by SCAN, SEARCH, NEARBY, WITHIN and INTERSECTS. */
internal class SearchArgs {
    var key = ""
    var cursor = 0L
    var output = SearchOutput.OBJECTS
    var precision = 0
    var fence = false
    var distance = false
    val globs = ArrayList<String>()
    val wheres = ArrayList<WhereFilter>()
    val whereins = ArrayList<WhereInFilter>()
    var nofields = false
    var limit = 0L
    var sparse = 0
    var desc = false
    var clip = false
    var hasBuffer = false
    var obj: GeoObject? = null
    var detect: Set<String>? = null
    var accept: Set<String>? = null
    var nodwell = false
    var roam: RoamSpec? = null

    /** The geofence described by these arguments. */
    fun fenceSpec(cmd: String) = FenceSpec(cmd, key, obj, globs, distance, detect, accept, nodwell, roam)
}

/** A simple cursor over an argument list (Go `tokenval`). */
private class Tokens(
    private val vs: List<String>,
) {
    var pos = 0

    fun hasNext() = pos < vs.size

    fun peek(): String? = if (pos < vs.size) vs[pos] else null

    fun next(): String? = if (pos < vs.size) vs[pos++] else null

    /** Next token that must exist and be non-empty. */
    fun required(): String {
        val t = next()
        if (t == null || t == "") throw Errors.invalidNumberOfArguments()
        return t
    }

    fun remaining() = vs.size - pos
}

private fun detectExprToken(
    vs: List<String>,
    pos: Int,
): Boolean {
    val n = vs.size - pos
    if (n == 0) return false
    if (n == 1 || (n == 2 && vs[pos + 1].isEmpty())) return true
    val v = vs[pos + 1]
    if (v.isEmpty()) return false
    if (v[0] in 'a'..'z' || v[0] in 'A'..'Z') {
        return !(v.lowercase() == "inf")
    }
    return false
}

private fun parseBaseTokens(
    cmd: String,
    t: Tokens,
    a: SearchArgs,
    fromFence: Boolean = false,
) {
    a.fence = fromFence
    a.key = t.next() ?: throw Errors.invalidNumberOfArguments()
    if (a.key == "") throw Errors.invalidNumberOfArguments()
    var slimit = ""
    var ssparse = ""
    var scursor = ""
    var asc = false
    loop@ while (true) {
        val wtok = t.peek()
        if (wtok == null || wtok.isEmpty()) break
        when (wtok.lowercase()) {
            "buffer" -> {
                t.next()
                val sbuf = t.required()
                val buf = GoCompat.parseFloat(sbuf)
                if (buf == null || buf < 0 || buf.isInfinite() || buf.isNaN()) throw Errors.invalidArgument(sbuf)
                a.hasBuffer = true
            }

            "cursor" -> {
                t.next()
                if (scursor != "") throw Errors.duplicateArgument(wtok.uppercase())
                scursor = t.required()
            }

            "where" -> {
                t.next()
                if (detectExprToken(listOfRemaining(t), 0)) {
                    throw CommandException("WHERE expressions are not supported yet")
                }
                val name = t.next() ?: throw Errors.invalidNumberOfArguments()
                var smin = (t.next() ?: throw Errors.invalidNumberOfArguments()).lowercase()
                var smax = (t.next() ?: throw Errors.invalidNumberOfArguments()).lowercase()
                if (smax == "+inf" || smax == "inf") smax = "inf"
                var minx = false
                var maxx = false
                when (smin) {
                    "<", "<=", ">", ">=", "==", "!=" -> {}

                    else -> {
                        if (smin.startsWith("(")) {
                            minx = true
                            smin = smin.substring(1)
                        }
                        if (smax.startsWith("(")) {
                            maxx = true
                            smax = smax.substring(1)
                        }
                    }
                }
                a.wheres.add(WhereFilter(name, minx, FieldValue.of(smin), maxx, FieldValue.of(smax)))
            }

            "wherein" -> {
                t.next()
                val name = t.next() ?: throw Errors.invalidNumberOfArguments()
                val nvalsStr = t.next() ?: throw Errors.invalidNumberOfArguments()
                val nvals = GoCompat.parseUint(nvalsStr) ?: throw Errors.invalidArgument(nvalsStr)
                val vals = ArrayList<FieldValue>()
                for (i in 0 until nvals) vals.add(FieldValue.of(t.next() ?: throw Errors.invalidNumberOfArguments()))
                a.whereins.add(WhereInFilter(name, vals))
            }

            "whereeval", "whereevalsha" -> {
                throw CommandException("WHEREEVAL is not supported yet")
            }

            "nofields" -> {
                t.next()
                if (a.nofields) throw Errors.duplicateArgument(wtok.uppercase())
                a.nofields = true
            }

            "limit" -> {
                t.next()
                if (slimit != "") throw Errors.duplicateArgument(wtok.uppercase())
                slimit = t.required()
            }

            "sparse" -> {
                t.next()
                if (ssparse != "") throw Errors.duplicateArgument(wtok.uppercase())
                ssparse = t.required()
            }

            "fence" -> {
                t.next()
                if (a.fence && !fromFence) throw Errors.duplicateArgument(wtok.uppercase())
                a.fence = true
            }

            "commands" -> {
                t.next()
                if (a.accept != null) throw Errors.duplicateArgument(wtok.uppercase())
                val peek = t.required()
                val accept = LinkedHashSet<String>()
                for (part0 in peek.split(",")) {
                    val part = part0.trim().lowercase()
                    if (part in accept) throw Errors.duplicateArgument(part0)
                    accept.add(part)
                }
                a.accept = accept.ifEmpty { null }
            }

            "detect" -> {
                t.next()
                if (a.detect != null) throw Errors.duplicateArgument(wtok.uppercase())
                val peek = t.required()
                val detect = LinkedHashSet<String>()
                for (part0 in peek.split(",")) {
                    val part = part0.trim().lowercase()
                    if (part !in setOf("inside", "outside", "enter", "exit", "cross")) throw Errors.invalidArgument(peek)
                    if (part in detect) throw Errors.duplicateArgument(part0)
                    detect.add(part)
                }
                a.detect = detect.ifEmpty { setOf("inside", "outside", "enter", "exit", "cross") }
            }

            "nodwell" -> {
                t.next()
                // Upstream checks DESC/ASC here (a copy-paste quirk); kept for compatibility.
                if (a.desc || asc) throw Errors.duplicateArgument(wtok.uppercase())
                a.nodwell = true
            }

            "distance" -> {
                t.next()
                if (a.distance) throw Errors.duplicateArgument(wtok.uppercase())
                a.distance = true
            }

            "desc" -> {
                t.next()
                if (a.desc || asc) throw Errors.duplicateArgument(wtok.uppercase())
                a.desc = true
            }

            "asc" -> {
                t.next()
                if (a.desc || asc) throw Errors.duplicateArgument(wtok.uppercase())
                asc = true
            }

            "match" -> {
                t.next()
                a.globs.add(t.required())
            }

            "clip" -> {
                t.next()
                if (a.clip) throw Errors.duplicateArgument(wtok.uppercase())
                a.clip = true
            }

            "commands", "detect", "nodwell" -> {
                throw CommandException("FENCE is not supported yet")
            }

            else -> {
                break@loop
            }
        }
    }
    if (cmd == "scan" || cmd == "search") {
        if (ssparse != "") throw CommandException("SPARSE is not allowed for " + cmd.uppercase())
        if (a.fence) throw CommandException("FENCE is not allowed for " + cmd.uppercase())
    } else {
        if (a.desc) throw CommandException("DESC is not allowed for " + cmd.uppercase())
        if (asc) throw CommandException("ASC is not allowed for " + cmd.uppercase())
    }
    if (ssparse != "" && slimit != "") throw CommandException("LIMIT is not allowed when SPARSE is specified")
    if (scursor != "" && ssparse != "") throw CommandException("CURSOR is not allowed when SPARSE is specified")
    if (scursor != "" && a.fence) throw CommandException("CURSOR is not allowed when FENCE is specified")
    if (a.detect != null && !a.fence) throw CommandException("DETECT is not allowed when FENCE is not specified")
    a.output = SearchOutput.OBJECTS
    var sprecision = ""
    val which = t.peek()
    if (which != null && which != "") {
        var consumed = true
        when (which.lowercase()) {
            "count" -> {
                a.output = SearchOutput.COUNT
            }

            "objects" -> {
                a.output = SearchOutput.OBJECTS
            }

            "points" -> {
                a.output = SearchOutput.POINTS
            }

            "hashes" -> {
                a.output = SearchOutput.HASHES
                t.next()
                sprecision = t.required()
                consumed = false
            }

            "bounds" -> {
                a.output = SearchOutput.BOUNDS
            }

            "ids" -> {
                a.output = SearchOutput.IDS
            }

            else -> {
                if (cmd == "scan") throw Errors.invalidArgument(which)
                consumed = false
            }
        }
        if (consumed) t.next()
    }
    if (scursor != "") a.cursor = GoCompat.parseUint(scursor) ?: throw Errors.invalidArgument(scursor)
    if (sprecision != "") {
        val p = GoCompat.parseUint(sprecision) ?: throw Errors.invalidArgument(sprecision)
        if (p == 0L || p > 12) throw Errors.invalidArgument(sprecision)
        a.precision = p.toInt()
    }
    if (slimit != "") {
        val l = GoCompat.parseUint(slimit)
        if (l == null || l == 0L) throw Errors.invalidArgument(slimit)
        a.limit = l
    }
    if (ssparse != "") {
        val sp = GoCompat.parseUint(ssparse)
        if (sp == null || sp == 0L || sp > 16) throw Errors.invalidArgument(ssparse)
        a.sparse = sp.toInt()
        a.limit = Long.MAX_VALUE
    }
}

private fun listOfRemaining(t: Tokens): List<String> {
    val l = ArrayList<String>()
    val save = t.pos
    while (t.hasNext()) l.add(t.next()!!)
    t.pos = save
    return l
}

private fun parseF(s: String): Double = GoCompat.parseFloat(s) ?: throw Errors.invalidArgument(s)

private fun rectObj(
    minLat: Double,
    minLon: Double,
    maxLat: Double,
    maxLon: Double,
): GeoObject = GeoRect(Rect(Point(minLon, minLat), Point(maxLon, maxLat)))

/** Parses BOUNDS, HASH, QUADKEY and TILE areas. */
private fun parseRectArea(
    ltyp: String,
    t: Tokens,
): GeoObject {
    when (ltyp) {
        "bounds" -> {
            val s = Array(4) { t.required() }
            val v = s.map { parseF(it) }
            return rectObj(v[0], v[1], v[2], v[3])
        }

        "hash" -> {
            val b = Geohash.boundingBox(t.required())
            return rectObj(b.minLat, b.minLng, b.maxLat, b.maxLng)
        }

        "quadkey" -> {
            val key = t.required()
            val b = Bing.quadKeyToBounds(key) ?: throw Errors.invalidArgument(key)
            return rectObj(b[0], b[1], b[2], b[3])
        }

        "tile" -> {
            val sx = t.required()
            val sy = t.required()
            val sz = t.required()
            val x = GoCompat.parseInt(sx)?.takeIf { it >= 0 } ?: throw Errors.invalidArgument(sx)
            val y = GoCompat.parseInt(sy)?.takeIf { it >= 0 } ?: throw Errors.invalidArgument(sy)
            val z = GoCompat.parseInt(sz)?.takeIf { it in 0..23 } ?: throw Errors.invalidArgument(sz)
            val b = Bing.tileXYToBounds(x, y, z.toInt())
            return rectObj(b[0], b[1], b[2], b[3])
        }
    }
    throw CommandException("not a rectangle")
}

internal val NEARBY_TYPES = setOf("point")
internal val WITHIN_OR_INTERSECTS_TYPES = setOf("bounds", "hash", "tile", "quadkey", "get", "object", "circle", "point")

internal fun Server.searchArgs(
    cmd: String,
    vs: List<String>,
    types: Set<String>,
    fromFence: Boolean = false,
): SearchArgs {
    val t = Tokens(vs)
    val a = SearchArgs()
    parseBaseTokens(cmd, t, a, fromFence)
    var typ = t.required()
    if (a.output == SearchOutput.BOUNDS && (cmd == "within" || cmd == "intersects")) {
        if (GoCompat.parseFloat(typ) != null) {
            // The output was likely not specified; this is the search BOUNDS.
            a.output = SearchOutput.OBJECTS
            t.pos--
            typ = "BOUNDS"
        }
    }
    val ltyp = typ.lowercase()
    // roaming is allowed for nearby fences
    if (ltyp !in types && !(a.fence && ltyp == "roam" && cmd == "nearby")) throw Errors.invalidArgument(typ)
    when (ltyp) {
        "point" -> {
            val lat = parseF(t.required())
            val lon = parseF(t.required())
            if (cmd == "nearby") {
                val smeters = t.next()
                var meters = -1.0
                if (smeters != null && smeters != "") {
                    val m = GoCompat.parseFloat(smeters)
                    if (m == null || m < 0) throw Errors.invalidArgument(smeters)
                    meters = m
                }
                a.obj = Circle(Point(lon, lat), meters, DEFAULT_CIRCLE_STEPS)
            } else {
                a.obj = GeoPoint.of(Point(lon, lat))
            }
        }

        "circle" -> {
            if (a.clip) throw Errors.invalidArgument("cannot clip with $ltyp")
            val lat = parseF(t.required())
            val lon = parseF(t.required())
            val smeters = t.required()
            val meters = GoCompat.parseFloat(smeters)
            if (meters == null || meters < 0) throw Errors.invalidArgument(smeters)
            a.obj = Circle(Point(lon, lat), meters, DEFAULT_CIRCLE_STEPS)
        }

        "object" -> {
            if (a.clip) throw Errors.invalidArgument("cannot clip with object")
            a.obj = parseGeoJson(t.required())
        }

        "bounds", "hash", "tile", "quadkey" -> {
            a.obj = parseRectArea(ltyp, t)
        }

        "get" -> {
            if (a.clip) throw Errors.invalidArgument("cannot clip with get")
            val key = t.required()
            val id = t.required()
            val col = cols[key] ?: throw Errors.keyNotFound()
            a.obj = (col.get(id) ?: throw Errors.idNotFound()).geo
        }

        "roam" -> {
            val key = t.required()
            val id = t.required()
            val smeters = t.required()
            val meters = GoCompat.parseFloat(smeters) ?: throw Errors.invalidArgument(smeters)
            var scan = ""
            val next = t.next()
            if (next != null) {
                if (next.lowercase() != "scan") throw Errors.invalidArgument(next)
                scan = t.required()
            }
            a.roam = RoamSpec(key, id, Glob.isGlob(id), meters, scan)
        }
    }
    if (t.hasNext()) {
        val tok = t.required()
        if (tok.lowercase() != "clipby") throw Errors.invalidNumberOfArguments()
        throw CommandException("CLIPBY is not supported yet")
    }
    if (a.clip) throw CommandException("CLIP is not supported yet")
    if (a.hasBuffer) throw CommandException("BUFFER is not supported yet")
    return a
}

private fun Server.newScanWriter(
    out: StringBuilder,
    msg: Message,
    a: SearchArgs,
    matchValues: Boolean,
) = ScanWriter(
    out,
    msg,
    cols[a.key],
    a.output,
    a.precision,
    a.globs,
    matchValues,
    a.cursor,
    a.limit,
    a.wheres,
    a.whereins,
    a.nofields,
)

/** A scan writer that renders geofence messages (always JSON). */
internal fun Server.fenceScanWriter(
    msg: Message,
    a: SearchArgs,
): ScanWriter {
    val jmsg = Message(msg.args, msg.connType, OutputType.JSON)
    return ScanWriter(
        StringBuilder(),
        jmsg,
        cols[a.key],
        a.output,
        a.precision,
        a.globs,
        false,
        a.cursor,
        a.limit,
        a.wheres,
        a.whereins,
        a.nofields,
    )
}

private fun Server.finish(
    msg: Message,
    sw: ScanWriter,
    out: StringBuilder,
    start: Long,
): RespValue {
    sw.writeFoot()
    if (msg.outputType == OutputType.JSON) {
        out.append(",\"elapsed\":\"").append(elapsed(start)).append("\"}")
        return RespValue.string(out.toString())
    }
    return sw.respOut
}

/** Merges the key ranges of several MATCH patterns. */
private fun multiGlobParse(
    globs: List<String>,
    desc: Boolean,
): Array<String> {
    val limits = arrayOf("", "")
    for ((i, pattern) in globs.withIndex()) {
        val g = Glob.parse(pattern, desc)
        if (g.limits[0] == "" && g.limits[1] == "") {
            limits[0] = ""
            limits[1] = ""
            break
        }
        if (i == 0) {
            limits[0] = g.limits[0]
            limits[1] = g.limits[1]
        } else if (desc) {
            if (g.limits[0] > limits[0]) limits[0] = g.limits[0]
            if (g.limits[1] < limits[1]) limits[1] = g.limits[1]
        } else {
            if (g.limits[0] < limits[0]) limits[0] = g.limits[0]
            if (g.limits[1] > limits[1]) limits[1] = g.limits[1]
        }
    }
    return limits
}

/** SCAN key (options) (output) */
internal fun Server.cmdScan(msg: Message): RespValue {
    val start = System.nanoTime()
    val t = Tokens(msg.args.subList(1, msg.args.size))
    val a = SearchArgs()
    parseBaseTokens("scan", t, a)
    if (t.hasNext()) throw Errors.invalidNumberOfArguments()
    val out = StringBuilder()
    val sw = newScanWriter(out, msg, a, false)
    if (msg.outputType == OutputType.JSON) out.append("{\"ok\":true")
    val col = sw.col
    if (col != null) {
        if (sw.output == SearchOutput.COUNT && sw.wheres.isEmpty() && sw.whereins.isEmpty() && sw.globEverything) {
            sw.count = maxOf(col.count() - a.cursor, 0L)
        } else {
            val limits = multiGlobParse(sw.globs, a.desc)
            val iter: (Item) -> Boolean = { sw.pushObject(ScanParams(it)) }
            if (limits[0] == "" && limits[1] == "") {
                col.scan(a.desc, sw, iter)
            } else {
                col.scanRange(limits[0], limits[1], a.desc, sw, iter)
            }
        }
    }
    return finish(msg, sw, out, start)
}

/** SEARCH key (options) (output): iterates string values. */
internal fun Server.cmdSearch(msg: Message): RespValue {
    val start = System.nanoTime()
    val t = Tokens(msg.args.subList(1, msg.args.size))
    val a = SearchArgs()
    parseBaseTokens("search", t, a)
    if (t.hasNext()) throw Errors.invalidNumberOfArguments()
    val out = StringBuilder()
    val sw = newScanWriter(out, msg, a, true)
    if (msg.outputType == OutputType.JSON) out.append("{\"ok\":true")
    val col = sw.col
    if (col != null) {
        if (sw.output == SearchOutput.COUNT && sw.wheres.isEmpty() && sw.globEverything) {
            sw.count = maxOf(col.count() - a.cursor, 0L)
        } else {
            val limits = multiGlobParse(sw.globs, a.desc)
            val iter: (Item) -> Boolean = { sw.pushObject(ScanParams(it)) }
            if (limits[0] == "" && limits[1] == "") {
                col.searchValues(a.desc, sw, iter)
            } else {
                col.searchValuesRange(limits[0], limits[1], a.desc, sw, iter)
            }
        }
    }
    return finish(msg, sw, out, start)
}

/** NEARBY key (options) (output) POINT lat lon (meters) */
internal fun Server.cmdNearby(msg: Message): RespValue {
    val start = System.nanoTime()
    val a = searchArgs("nearby", msg.args.subList(1, msg.args.size), NEARBY_TYPES)
    if (a.fence) throw GoLive.Fence(a.fenceSpec("nearby"), fenceScanWriter(msg, a))
    val out = StringBuilder()
    val sw = newScanWriter(out, msg, a, false)
    if (msg.outputType == OutputType.JSON) out.append("{\"ok\":true")
    val col = sw.col
    if (col != null) {
        val target = a.obj as Circle
        val maxDist = target.meters()
        if (a.sparse > 0) {
            if (maxDist < 0) throw CommandException("cannot use SPARSE without a point distance")
            col.intersects(target, a.sparse, sw) { o ->
                val dist = if (a.distance) o.geo.distance(target) else 0.0
                sw.pushObject(ScanParams(o, dist, a.distance, noTest = false))
            }
        } else {
            col.nearby(target, sw) { o, dist ->
                if (maxDist > 0 && dist > maxDist) {
                    false
                } else {
                    sw.pushObject(ScanParams(o, if (a.distance) dist else 0.0, a.distance))
                }
            }
        }
    }
    return finish(msg, sw, out, start)
}

/** WITHIN / INTERSECTS key (options) (output) area */
internal fun Server.cmdWithinOrIntersects(
    cmd: String,
    msg: Message,
): RespValue {
    val start = System.nanoTime()
    val a = searchArgs(cmd, msg.args.subList(1, msg.args.size), WITHIN_OR_INTERSECTS_TYPES)
    if (a.fence) throw GoLive.Fence(a.fenceSpec(cmd), fenceScanWriter(msg, a))
    val out = StringBuilder()
    val sw = newScanWriter(out, msg, a, false)
    if (msg.outputType == OutputType.JSON) out.append("{\"ok\":true")
    val col = sw.col
    if (col != null) {
        val iter: (Item) -> Boolean = { sw.pushObject(ScanParams(it)) }
        if (cmd == "within") col.within(a.obj!!, a.sparse, sw, iter) else col.intersects(a.obj!!, a.sparse, sw, iter)
    }
    return finish(msg, sw, out, start)
}
