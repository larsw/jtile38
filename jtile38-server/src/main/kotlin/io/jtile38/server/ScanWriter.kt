// Port of tile38 internal/server/scanner.go, Copyright Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.server

import io.jtile38.geojson.Feature
import io.jtile38.geojson.GeoObject
import io.jtile38.geojson.GeoPoint
import io.jtile38.json.Gjson
import io.jtile38.resp.RespValue
import java.util.TreeSet

enum class SearchOutput { UNKNOWN, IDS, OBJECTS, COUNT, POINTS, HASHES, BOUNDS }

const val LIMIT_ITEMS = 100L

/** A WHERE filter: `name min max`, `name op value`, or an expression (then [name] holds the expression). */
class WhereFilter(
    val name: String,
    val minx: Boolean,
    val min: FieldValue,
    val maxx: Boolean,
    val max: FieldValue,
    val expr: Boolean = false,
) {
    companion object {
        fun expr(expression: String) = WhereFilter(expression, false, FieldValue.ZERO, false, FieldValue.ZERO, expr = true)
    }

    /** Go `whereT.matchExpr`. */
    fun matchExpr(o: Item): Boolean = ExprWhere.match(name, o)

    fun matchField(value: FieldValue): Boolean {
        when (min.data) {
            "<" -> return value.less(max)
            "<=" -> return !max.less(value)
            ">" -> return max.less(value)
            ">=" -> return !value.less(max)
            "==" -> return value.equalsValue(max)
            "!=" -> return !value.equalsValue(max)
        }
        if (!minx) {
            if (value.less(min)) return false
        } else if (!min.less(value)) {
            return false
        }
        if (!maxx) {
            if (max.less(value)) return false
        } else if (!value.less(max)) {
            return false
        }
        return true
    }
}

class WhereInFilter(
    val name: String,
    val values: List<FieldValue>,
) {
    fun match(value: FieldValue): Boolean = values.any { it.equalsValue(value) }
}

/** Per-object parameters for [ScanWriter.pushObject]. */
class ScanParams(
    val obj: Item,
    val dist: Double = 0.0,
    val distOutput: Boolean = false,
    val noTest: Boolean = false,
)

fun extractZCoordinate(o: GeoObject): Double {
    var g: GeoObject = o
    while (true) {
        g =
            when (g) {
                is GeoPoint -> return g.z()
                is Feature -> g.base()
                else -> return 0.0
            }
    }
}

fun appendJsonSimpleBounds(
    sb: StringBuilder,
    o: GeoObject,
) {
    val r = o.rect()
    sb
        .append("{\"sw\":{\"lat\":")
        .append(GoCompat.formatFloat(r.min.y))
        .append(",\"lon\":")
        .append(GoCompat.formatFloat(r.min.x))
        .append("},\"ne\":{\"lat\":")
        .append(GoCompat.formatFloat(r.max.y))
        .append(",\"lon\":")
        .append(GoCompat.formatFloat(r.max.x))
        .append("}}")
}

fun appendJsonSimplePoint(
    sb: StringBuilder,
    o: GeoObject,
) {
    val p = o.center()
    val z = extractZCoordinate(o)
    sb
        .append("{\"lat\":")
        .append(GoCompat.formatFloat(p.y))
        .append(",\"lon\":")
        .append(GoCompat.formatFloat(p.x))
    if (z != 0.0) sb.append(",\"z\":").append(GoCompat.formatFloat(z))
    sb.append('}')
}

private fun isPathKey(
    s: String,
    key: String,
) = s.startsWith(key) && (s == key || s[key.length] == '.')

fun getFieldValue(
    o: Item,
    name: String,
): FieldValue {
    if (isPathKey(name, "properties")) {
        var res = Gjson.get(o.geo.members(), "properties")
        if (res.exists()) {
            if (name != "properties") res = res.get(name.substring("properties".length + 1))
            if (res.exists()) return FieldValue.of(res.raw)
        }
    }
    if (name == "z") return FieldValue.of(GoCompat.formatFloat(extractZCoordinate(o.geo)))
    return o.fields.get(name).value
}

/** Collects and serializes the results of SCAN, SEARCH, NEARBY, WITHIN and INTERSECTS. */
class ScanWriter(
    private val out: StringBuilder,
    private val msg: Message,
    val col: Collection?,
    val output: SearchOutput,
    private val precision: Int,
    val globs: List<String>,
    private val matchValues: Boolean,
    private val cursor: Long,
    limitIn: Long,
    val wheres: List<WhereFilter>,
    val whereins: List<WhereInFilter>,
    internal val nofields: Boolean,
) : Cursor {
    private val limit: Long = if (limitIn == 0L) (if (output == SearchOutput.COUNT) Long.MAX_VALUE else LIMIT_ITEMS) else limitIn
    private val fkeys = TreeSet<String>()
    private var numberIters = 0L
    private var numberItems = 0L
    private var hitLimit = false
    private var once = false
    var count = 0L
    private val values = ArrayList<RespValue>()
    private val filled = ArrayList<ScanParams>()
    val globEverything = globs.isEmpty() || (globs.size == 1 && globs[0] == "*")
    var respOut: RespValue = RespValue.NULL
        private set

    /** When true, fields are written per object as a JSON object (used by geofence messages). */
    var fullFields = false

    private fun hasFieldsOutput() =
        when (output) {
            SearchOutput.OBJECTS, SearchOutput.POINTS, SearchOutput.HASHES, SearchOutput.BOUNDS -> !nofields
            else -> false
        }

    fun writeFoot() {
        if (msg.outputType == OutputType.JSON) {
            if (fkeys.isNotEmpty() && hasFieldsOutput()) {
                out.append(",\"fields\":[")
                fkeys.forEachIndexed { i, name ->
                    if (i > 0) out.append(',')
                    out.append(GoCompat.jsonString(name))
                }
                out.append(']')
            }
            when (output) {
                SearchOutput.IDS -> {
                    out.append(",\"ids\":[")
                }

                SearchOutput.OBJECTS -> {
                    out.append(",\"objects\":[")
                }

                SearchOutput.POINTS -> {
                    out.append(",\"points\":[")
                }

                SearchOutput.BOUNDS -> {
                    out.append(",\"bounds\":[")
                }

                SearchOutput.HASHES -> {
                    out.append(",\"hashes\":[")
                }

                else -> {}
            }
        }
        for (opts in filled) writeFilled(opts)
        val cur = if (hitLimit) numberIters else 0L
        if (msg.outputType == OutputType.JSON) {
            if (output != SearchOutput.COUNT) out.append(']')
            out.append(",\"count\":").append(count)
            out.append(",\"cursor\":").append(cur)
        } else {
            respOut =
                if (output == SearchOutput.COUNT) {
                    RespValue.integer(count)
                } else {
                    RespValue.array(listOf(RespValue.integer(cur), RespValue.array(ArrayList(values))))
                }
        }
    }

    internal fun fieldMatch(o: Item): Boolean {
        for (w in wheres) {
            if (w.expr) {
                if (!w.matchExpr(o)) return false
            } else if (!w.matchField(getFieldValue(o, w.name))) {
                return false
            }
        }
        for (w in whereins) if (!w.match(getFieldValue(o, w.name))) return false
        return true
    }

    private fun globMatch(o: Item): Boolean {
        if (globEverything) return true
        val v = if (matchValues) o.toString() else o.id
        return globs.any { Glob.matches(it, v) }
    }

    /** Glob and field test, as done for every search candidate. */
    internal fun testObject(o: Item): Boolean = globMatch(o) && fieldMatch(o)

    /** Pushes and immediately writes a single object (geofence messages). */
    internal fun writeObject(opts: ScanParams) {
        val n = filled.size
        pushObject(opts)
        if (filled.size > n) {
            writeFilled(filled[filled.size - 1])
            while (filled.size > n) filled.removeAt(filled.size - 1)
        }
    }

    /** Returns and clears what has been written so far. */
    internal fun takeOutput(): String {
        val s = out.toString()
        out.setLength(0)
        return s
    }

    override fun offset(): Long = cursor

    private var steps = 0L

    override fun step(count: Long) {
        numberIters += count
        // like upstream's nextStep: check the TIMEOUT deadline every 256 steps
        if ((++steps and 255L) == 255L) msg.deadline?.check()
    }

    /** Returns false when iteration should stop. */
    fun pushObject(opts: ScanParams): Boolean {
        if (!opts.noTest) {
            if (!globMatch(opts.obj)) return true
            if (!fieldMatch(opts.obj)) return true
        }
        count++
        if (output == SearchOutput.COUNT) return count < limit
        if (!fullFields) opts.obj.fields.forEach { fkeys.add(it.name) }
        filled.add(opts)
        numberItems++
        if (numberItems == limit) {
            hitLimit = true
            return false
        }
        return true
    }

    private fun writeFilled(opts: ScanParams) {
        val o = opts.obj
        val withDist = opts.distOutput || opts.dist > 0
        if (msg.outputType == OutputType.JSON) {
            if (once) out.append(',') else once = true
            var jsfields = ""
            if (hasFieldsOutput() && fullFields) {
                if (o.fields.size() > 0) {
                    val sb = StringBuilder(",\"fields\":{")
                    var i = 0
                    o.fields.forEach {
                        if (!it.value.isZero()) {
                            if (i++ > 0) sb.append(',')
                            sb.append(GoCompat.jsonString(it.name)).append(':').append(it.value.json())
                        }
                    }
                    sb.append('}')
                    jsfields = sb.toString()
                }
            } else if (hasFieldsOutput() && fkeys.isNotEmpty()) {
                val sb = StringBuilder(",\"fields\":[")
                fkeys.forEachIndexed { i, name ->
                    if (i > 0) sb.append(',')
                    sb.append(
                        o.fields
                            .get(name)
                            .value
                            .json(),
                    )
                }
                sb.append(']')
                jsfields = sb.toString()
            }
            if (output == SearchOutput.IDS) {
                if (withDist) {
                    out
                        .append("{\"id\":")
                        .append(GoCompat.jsonString(o.id))
                        .append(",\"distance\":")
                        .append(GoCompat.formatFloat(opts.dist))
                        .append('}')
                } else {
                    out.append(GoCompat.jsonString(o.id))
                }
            } else {
                out.append("{\"id\":").append(GoCompat.jsonString(o.id))
                when (output) {
                    SearchOutput.OBJECTS -> {
                        out.append(",\"object\":")
                        o.geo.appendJson(out)
                    }

                    SearchOutput.POINTS -> {
                        out.append(",\"point\":")
                        appendJsonSimplePoint(out, o.geo)
                    }

                    SearchOutput.HASHES -> {
                        val c = o.geo.center()
                        out.append(",\"hash\":\"").append(Geohash.encode(c.y, c.x, precision)).append('"')
                    }

                    SearchOutput.BOUNDS -> {
                        out.append(",\"bounds\":")
                        appendJsonSimpleBounds(out, o.geo)
                    }

                    else -> {}
                }
                out.append(jsfields)
                if (withDist) out.append(",\"distance\":").append(GoCompat.formatFloat(opts.dist))
                out.append('}')
            }
            return
        }
        val vals = ArrayList<RespValue>(3)
        vals.add(RespValue.string(o.id))
        if (output == SearchOutput.IDS) {
            if (withDist) {
                vals.add(RespValue.float(opts.dist))
                values.add(RespValue.array(vals))
            } else {
                values.add(vals[0])
            }
            return
        }
        when (output) {
            SearchOutput.OBJECTS -> {
                vals.add(RespValue.string(o.toString()))
            }

            SearchOutput.POINTS -> {
                val p = o.geo.center()
                val z = extractZCoordinate(o.geo)
                val pts = mutableListOf(RespValue.float(p.y), RespValue.float(p.x))
                if (z != 0.0) pts.add(RespValue.float(z))
                vals.add(RespValue.array(pts))
            }

            SearchOutput.HASHES -> {
                val c = o.geo.center()
                vals.add(RespValue.string(Geohash.encode(c.y, c.x, precision)))
            }

            SearchOutput.BOUNDS -> {
                vals.add(respBounds(o))
            }

            else -> {}
        }
        if (hasFieldsOutput()) {
            val fvals = ArrayList<RespValue>()
            o.fields.forEach {
                if (!it.value.isZero()) {
                    fvals.add(RespValue.string(it.name))
                    fvals.add(RespValue.string(it.value.data))
                }
            }
            if (fvals.isNotEmpty()) vals.add(RespValue.array(fvals))
        }
        if (withDist) vals.add(RespValue.float(opts.dist))
        values.add(RespValue.array(vals))
    }
}

fun respBounds(o: Item): RespValue {
    val r = o.rect()
    return RespValue.array(
        listOf(
            RespValue.array(listOf(RespValue.float(r.min.y), RespValue.float(r.min.x))),
            RespValue.array(listOf(RespValue.float(r.max.y), RespValue.float(r.max.x))),
        ),
    )
}
