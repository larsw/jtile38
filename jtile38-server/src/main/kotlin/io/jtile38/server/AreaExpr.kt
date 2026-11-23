// Port of tile38 internal/server/expression.go, test.go and parseAreaExpression (token.go),
// Copyright Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.server

import io.jtile38.geojson.Circle
import io.jtile38.geojson.GeoObject
import io.jtile38.geojson.GeoPoint
import io.jtile38.geojson.GeoRect
import io.jtile38.geojson.geometry.Point
import io.jtile38.geojson.geometry.Rect
import io.jtile38.resp.RespValue

/** An area, or AND/OR of areas, optionally negated (Go `areaExpression`). */
internal class AreaExpr(
    var negate: Boolean = false,
    var obj: GeoObject? = null,
    var op: Op = Op.NOOP,
    val children: MutableList<AreaExpr> = ArrayList(),
) {
    enum class Op { NOOP, AND, OR }

    private fun maybeNegate(v: Boolean) = if (negate) !v else v

    private fun testObject(
        o: GeoObject,
        objObj: (GeoObject, GeoObject) -> Boolean,
        exprObj: (AreaExpr, GeoObject) -> Boolean,
    ): Boolean {
        obj?.let { return objObj(it, o) }
        return when (op) {
            Op.AND -> children.all { exprObj(it, o) }
            Op.OR -> children.any { exprObj(it, o) }
            Op.NOOP -> false
        }
    }

    private fun rawIntersects(o: GeoObject): Boolean = testObject(o, { a, b -> a.intersects(b) }, { e, b -> e.intersects(b) })

    private fun rawWithin(o: GeoObject): Boolean = testObject(o, { a, b -> a.within(b) }, { e, b -> e.within(b) })

    fun intersects(o: GeoObject): Boolean = maybeNegate(rawIntersects(o))

    fun within(o: GeoObject): Boolean = maybeNegate(rawWithin(o))

    private fun testExpression(
        other: AreaExpr,
        exprObj: (AreaExpr, GeoObject) -> Boolean,
        rawExprExpr: (AreaExpr, AreaExpr) -> Boolean,
        exprExpr: (AreaExpr, AreaExpr) -> Boolean,
    ): Boolean {
        if (other.negate) {
            val opposite = AreaExpr(!negate, obj, op, children)
            val nonNegated = AreaExpr(false, other.obj, other.op, other.children)
            return exprExpr(opposite, nonNegated)
        }
        other.obj?.let { return exprObj(this, it) }
        return when (other.op) {
            Op.AND -> other.children.all { rawExprExpr(this, it) }
            Op.OR -> other.children.any { rawExprExpr(this, it) }
            Op.NOOP -> false
        }
    }

    private fun rawIntersectsExpr(other: AreaExpr): Boolean =
        testExpression(other, { e, o -> e.rawIntersects(o) }, { a, b -> a.rawIntersectsExpr(b) }, { a, b -> a.intersectsExpr(b) })

    private fun rawWithinExpr(other: AreaExpr): Boolean =
        testExpression(other, { e, o -> e.rawWithin(o) }, { a, b -> a.rawWithinExpr(b) }, { a, b -> a.withinExpr(b) })

    fun intersectsExpr(other: AreaExpr): Boolean = maybeNegate(rawIntersectsExpr(other))

    fun withinExpr(other: AreaExpr): Boolean = maybeNegate(rawWithinExpr(other))
}

/** A cursor over command arguments (Go `tokenval`). */
private class Vs(
    val args: List<String>,
    var pos: Int = 0,
) {
    fun next(): String? = if (pos < args.size) args[pos++] else null

    fun peek(): String? = args.getOrNull(pos)

    fun required(): String = next()?.takeIf { it != "" } ?: throw Errors.invalidNumberOfArguments()

    fun remaining() = args.size - pos
}

private fun parseF(s: String): Double = GoCompat.parseFloat(s) ?: throw Errors.invalidArgument(s)

private fun rect(
    minLat: Double,
    minLon: Double,
    maxLat: Double,
    maxLon: Double,
): GeoObject = GeoRect(Rect(Point(minLon, minLat), Point(maxLon, maxLat)))

/** Go `parseArea`: one area for TEST and area expressions. */
private fun Server.parseArea(
    vs: Vs,
    doClip: Boolean,
): GeoObject? {
    val typ = vs.required()
    val ltyp = typ.lowercase()

    fun noClip() {
        if (doClip) throw CommandException("invalid clip type '$typ'")
    }
    return when (ltyp) {
        "point" -> {
            val slat = vs.required()
            val slon = vs.required()
            val lat = parseF(slat)
            val lon = parseF(slon)
            GeoPoint.of(Point(lon, lat))
        }

        "sector" -> {
            noClip()
            val s = List(5) { vs.required() }
            val v = s.map { parseF(it) }
            if (v[3] == v[4]) throw CommandException("equal bearings (${s[3]} == ${s[4]}), use CIRCLE instead")
            parseGeoJson(Sector.json(v[0], v[1], v[2], v[3], v[4]))
        }

        "circle" -> {
            noClip()
            val slat = vs.required()
            val slon = vs.required()
            val lat = parseF(slat)
            val lon = parseF(slon)
            val smeters = vs.required()
            val meters = parseF(smeters)
            if (meters < 0) throw Errors.invalidArgument(smeters)
            Circle(Point(lon, lat), meters, 64)
        }

        "object" -> {
            noClip()
            parseGeoJson(vs.required())
        }

        "bounds" -> {
            val v = List(4) { vs.required() }.map { parseF(it) }
            rect(v[0], v[1], v[2], v[3])
        }

        "hash" -> {
            val b = Geohash.boundingBox(vs.required())
            rect(b.minLat, b.minLng, b.maxLat, b.maxLng)
        }

        "quadkey" -> {
            val key = vs.required()
            val b = Bing.quadKeyToBounds(key) ?: throw Errors.invalidArgument(key)
            rect(b[0], b[1], b[2], b[3])
        }

        "tile" -> {
            val sx = vs.required()
            val sy = vs.required()
            val sz = vs.required()
            val x = GoCompat.parseInt(sx) ?: throw Errors.invalidArgument(sx)
            val y = GoCompat.parseInt(sy) ?: throw Errors.invalidArgument(sy)
            val z = GoCompat.parseUint(sz) ?: throw Errors.invalidArgument(sz)
            val b = Bing.tileXYToBounds(x, y, z.coerceAtMost(62).toInt())
            rect(b[0], b[1], b[2], b[3])
        }

        "get" -> {
            noClip()
            val key = vs.required()
            val id = vs.required()
            val col = cols[key] ?: throw Errors.keyNotFound()
            (col.get(id) ?: throw Errors.idNotFound()).geo
        }

        else -> {
            null
        }
    }
}

/** Go `parseAreaExpression`: areas combined with AND, OR, NOT and parentheses. */
private fun Server.parseAreaExpression(
    vs: Vs,
    doClip: Boolean,
): AreaExpr {
    val stack = ArrayList<AreaExpr>()
    var ae: AreaExpr? = null
    var negate = false
    var needObj = false
    loop@ while (true) {
        val wtok = vs.peek()
        if (wtok == null || wtok.isEmpty()) break
        when (wtok.lowercase()) {
            "(" -> {
                val e = AreaExpr(negate = negate)
                negate = false
                needObj = false
                ae?.children?.add(e)
                ae = e
                stack.add(e)
                vs.next()
            }

            ")" -> {
                if (needObj) throw Errors.invalidArgument(")")
                if (stack.isEmpty()) throw Errors.invalidArgument(")")
                ae = stack.removeAt(stack.size - 1)
                vs.next()
            }

            "not" -> {
                negate = !negate
                needObj = true
                vs.next()
            }

            "and" -> {
                if (needObj) throw Errors.invalidArgument("and")
                needObj = true
                val cur = ae ?: throw Errors.invalidArgument("and")
                if (cur.obj == null) {
                    when (cur.op) {
                        AreaExpr.Op.OR -> {
                            val n = cur.children.size
                            if (n < 2) throw Errors.invalidNumberOfArguments()
                            val last = cur.children.removeAt(n - 1)
                            cur.children.add(AreaExpr(op = AreaExpr.Op.AND, children = mutableListOf(last)))
                        }

                        AreaExpr.Op.NOOP -> {
                            cur.op = AreaExpr.Op.AND
                        }

                        else -> {}
                    }
                } else {
                    ae = AreaExpr(op = AreaExpr.Op.AND, children = mutableListOf(cur))
                }
                vs.next()
            }

            "or" -> {
                if (needObj) throw Errors.invalidArgument("or")
                needObj = true
                val cur = ae ?: throw Errors.invalidArgument("or")
                if (cur.obj == null) {
                    when (cur.op) {
                        AreaExpr.Op.AND -> {
                            if (cur.children.size < 2) throw Errors.invalidNumberOfArguments()
                            ae = AreaExpr(op = AreaExpr.Op.OR, children = mutableListOf(cur))
                        }

                        AreaExpr.Op.NOOP -> {
                            cur.op = AreaExpr.Op.OR
                        }

                        else -> {}
                    }
                } else {
                    ae = AreaExpr(op = AreaExpr.Op.OR, children = mutableListOf(cur))
                }
                vs.next()
            }

            "point", "circle", "object", "bounds", "hash", "quadkey", "tile", "get", "sector" -> {
                val obj = parseArea(vs, doClip)
                val e = AreaExpr(negate = negate, obj = obj)
                negate = false
                needObj = false
                val cur = ae
                if (cur == null) ae = e else cur.children.add(e)
            }

            else -> {
                break@loop
            }
        }
    }
    val res = ae
    if (stack.isNotEmpty() || needObj || res == null || (res.obj == null && res.children.isEmpty())) {
        throw Errors.invalidNumberOfArguments()
    }
    return res
}

/** TEST area INTERSECTS|WITHIN [CLIP] area */
internal fun Server.cmdTest(msg: Message): RespValue {
    val start = System.nanoTime()
    val vs = Vs(msg.args, 1)
    val area1 = parseAreaExpression(vs, false)
    val test = vs.next()?.takeIf { it != "" } ?: throw Errors.invalidNumberOfArguments()
    val lTest = test.lowercase()
    if (lTest != "within" && lTest != "intersects") throw Errors.invalidArgument(test)
    var doClip = false
    val wtok = vs.peek()
    if (wtok != null && wtok.lowercase() == "clip") {
        vs.next()
        if (lTest != "intersects") throw Errors.invalidArgument(wtok)
        doClip = true
    }
    val area2 = parseAreaExpression(vs, doClip)
    if (doClip && (area1.obj == null || area2.obj == null)) throw Errors.invalidArgument("clip")
    if (vs.remaining() != 0) throw Errors.invalidNumberOfArguments()
    val result = if (lTest == "within") area1.withinExpr(area2) else area1.intersectsExpr(area2)
    if (result && doClip) throw CommandException("CLIP is not supported yet")
    if (msg.outputType == OutputType.JSON) {
        return RespValue.string("{\"ok\":true,\"result\":$result,\"elapsed\":\"${elapsed(start)}\"}")
    }
    return RespValue.integer(if (result) 1 else 0)
}
