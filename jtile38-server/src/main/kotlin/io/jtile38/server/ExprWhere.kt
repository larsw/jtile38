// Port of tile38 internal/server/expr.go, Copyright Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.server

import io.jtile38.expr.Booler
import io.jtile38.expr.CallInfo
import io.jtile38.expr.Context
import io.jtile38.expr.Expr
import io.jtile38.expr.Extender
import io.jtile38.expr.Op
import io.jtile38.expr.OpInfo
import io.jtile38.expr.RefInfo
import io.jtile38.expr.Stringer
import io.jtile38.expr.Value
import io.jtile38.geojson.Circle
import io.jtile38.geojson.Feature
import io.jtile38.geojson.FeatureCollection
import io.jtile38.geojson.GeoPoint
import io.jtile38.geojson.GeoRect
import io.jtile38.geojson.GeometryCollection
import io.jtile38.geojson.LineString
import io.jtile38.geojson.MultiLineString
import io.jtile38.geojson.MultiPoint
import io.jtile38.geojson.MultiPolygon
import io.jtile38.geojson.Polygon
import io.jtile38.geojson.SimplePoint
import io.jtile38.json.Gjson
import io.jtile38.json.JsonResult
import io.jtile38.json.JsonType
import io.jtile38.json.Match
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** A stored object as an expression value (Go `expr.Object(*object.Object)`). */
private class ItemValue(
    val item: Item,
) : Stringer {
    override fun string(): String = item.toString()
}

/**
 * A JSON value as an expression value (Go `expr.Object(gjson.Result)`). Like gjson.Result it
 * converts with `Bool()` and `String()`; gjson's `Int()`/`Float()` do not match the conv interfaces.
 */
private class JsonValue(
    val result: JsonResult,
) : Booler,
    Stringer {
    override fun bool(): Boolean = result.bool()

    override fun string(): String = result.string()
}

/**
 * WHERE expressions (`WHERE "speed > 50 && properties.kind == 'truck'"`), evaluated per object
 * with [Expr]. Identifiers resolve to the object's GeoJSON members, then `id`, `type` and its
 * fields; missing identifiers are 0. String comparisons are case-insensitive.
 */
internal object ExprWhere {
    /** Strings in this server are byte strings; regexes and globs work on the decoded UTF-8 text. */
    private fun utf8(s: String): String = String(s.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)

    // gjson lookups, kept in one place.
    private fun jsonGet(
        json: String,
        path: String,
    ): JsonResult = Gjson.get(json, path)

    private fun jsonParse(json: String): JsonResult = Gjson.parse(json)

    private fun resultToValue(r: JsonResult): Value {
        if (!r.exists()) return Value.UNDEFINED
        return when (r.type) {
            JsonType.STRING -> Value.string(r.string())
            JsonType.FALSE -> Value.bool(false)
            JsonType.TRUE -> Value.bool(true)
            JsonType.NUMBER -> Value.number(r.float())
            JsonType.JSON -> Value.obj(JsonValue(r))
            else -> Value.NULL
        }
    }

    private fun typeForObject(o: Item): Value =
        when (o.geo) {
            is GeoPoint, is SimplePoint -> Value.string("Point")
            is LineString -> Value.string("LineString")
            is Polygon, is Circle, is GeoRect -> Value.string("Polygon")
            is MultiPoint -> Value.string("MultiPoint")
            is MultiLineString -> Value.string("MultiLineString")
            is MultiPolygon -> Value.string("MultiPolygon")
            is GeometryCollection -> Value.string("GeometryCollection")
            is Feature -> Value.string("Feature")
            is FeatureCollection -> Value.string("FeatureCollection")
            else -> Value.UNDEFINED
        }

    private fun objExpr(
        o: Item,
        info: RefInfo,
    ): Value {
        val r = jsonGet(o.geo.members(), info.ident)
        if (r.exists()) return resultToValue(r)
        when (info.ident) {
            "id" -> {
                return Value.string(o.id)
            }

            "type" -> {
                return typeForObject(o)
            }

            else -> {
                var found: Field? = null
                o.fields.forEach { f -> if (found == null && f.name == info.ident) found = f }
                val f = found
                if (f != null) return resultToValue(jsonParse(f.value.json()))
            }
        }
        return Value.number(0.0)
    }

    /**
     * Compiled regexes (Go: a tinylru cache of 256 entries). Go uses RE2 syntax; Java's syntax is
     * mostly a superset. Differences: Go's `(?P<name>re)` and the ASCII classes `[[:alpha:]]` are
     * translated; Java also accepts constructs that RE2 rejects (backreferences, lookaround), which
     * Tile38 treats as invalid patterns (no match).
     */
    private val regexCache =
        object : LinkedHashMap<String, Pattern>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pattern>?) = size > 256
        }

    private val posixClasses =
        mapOf(
            "alnum" to "\\p{Alnum}",
            "alpha" to "\\p{Alpha}",
            "ascii" to "\\p{ASCII}",
            "blank" to "\\p{Blank}",
            "cntrl" to "\\p{Cntrl}",
            "digit" to "\\p{Digit}",
            "graph" to "\\p{Graph}",
            "lower" to "\\p{Lower}",
            "print" to "\\p{Print}",
            "punct" to "\\p{Punct}",
            "space" to "\\s",
            "upper" to "\\p{Upper}",
            "word" to "\\w",
            "xdigit" to "\\p{XDigit}",
        )

    private val posixClass = Regex("\\[:(\\^?)([a-z]+):]")

    private fun toJavaRegex(pattern: String): String =
        posixClass
            .replace(pattern.replace("(?P<", "(?<")) { m ->
                val cls = posixClasses[m.groupValues[2]] ?: return@replace m.value
                if (m.groupValues[1].isEmpty()) {
                    cls
                } else if (cls.startsWith("\\p")) {
                    "\\P" + cls.substring(2)
                } else {
                    cls.uppercase()
                }
            }

    private fun compile(pattern: String): Pattern {
        synchronized(regexCache) { regexCache[pattern]?.let { return it } }
        val re =
            try {
                Pattern.compile(toJavaRegex(utf8(pattern)))
            } catch (e: PatternSyntaxException) {
                throw IllegalArgumentException("invalid regex pattern: ${e.description}")
            }
        synchronized(regexCache) { regexCache[pattern] = re }
        return re
    }

    private val extender =
        object : Extender {
            override fun ref(
                info: RefInfo,
                ctx: Context,
            ): Value {
                val o = ctx.userData as Item
                if (!info.chain) {
                    // root
                    if (info.ident == "this") return Value.obj(ItemValue(o))
                    return objExpr(o, info)
                }
                return when (val v = info.value.value()) {
                    is ItemValue -> {
                        objExpr(o, info)
                    }

                    is JsonValue -> {
                        resultToValue(v.result.get(info.ident))
                    }

                    // object methods
                    else -> {
                        if (info.ident == "match") Value.function("match") else Value.UNDEFINED
                    }
                }
            }

            override fun call(
                info: CallInfo,
                ctx: Context,
            ): Value {
                if (info.chain && info.ident == "match") {
                    return Value.bool(Match.matchNoCase(utf8(info.value.toString()), utf8(info.args.at(0).toString())))
                }
                return Value.UNDEFINED
            }

            override fun op(
                info: OpInfo,
                ctx: Context,
            ): Value {
                if (info.op == Op.REGEX) {
                    val re = compile(info.right.toString())
                    return Value.bool(re.matcher(utf8(info.left.toString())).find())
                }
                return Value.UNDEFINED
            }
        }

    /** Go `whereT.matchExpr`: evaluation errors count as no match. */
    fun match(
        expr: String,
        o: Item,
    ): Boolean =
        try {
            Expr.eval(expr, Context(userData = o, extender = extender, noCase = true)).bool()
        } catch (_: Exception) {
            false
        }
}
