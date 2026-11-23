// Port of github.com/tidwall/geojson parsing (MIT License, Copyright (c) 2018 Joshua J Baker)
package io.jtile38.geojson

import io.jtile38.geojson.geometry.IndexOptions
import io.jtile38.geojson.geometry.Line
import io.jtile38.geojson.geometry.Point
import io.jtile38.geojson.geometry.Poly
import io.jtile38.geojson.geometry.Rect
import io.jtile38.json.Gjson
import io.jtile38.json.JsonResult
import io.jtile38.json.JsonType
import io.jtile38.json.Pretty

/** GeoJSON parsing entry point (Go: geojson.Parse). */
object GeoJson {
    /** Parses a GeoJSON object. Throws [GeoJsonException] with the Go error text on failure. */
    @JvmStatic
    @JvmOverloads
    fun parse(
        data: String,
        opts: ParseOptions = ParseOptions.DEFAULT,
    ): GeoObject {
        var i = 0
        while (true) {
            if (i >= data.length) throw GeoJsonException(Errors.DATA_INVALID)
            when (data[i]) {
                ' ', '\t', '\n', '\r' -> i++

                '{' -> return parseJson(if (i == 0) data else data.substring(i), opts)

                // well-known text and binary are not supported yet
                else -> throw GeoJsonException(Errors.DATA_INVALID)
            }
        }
    }

    /** Returns (z, true) if [obj] is a GeoJSON Point, otherwise (0, false). */
    @JvmStatic
    fun isPoint(obj: GeoObject): Pair<Double, Boolean> =
        when (obj) {
            is SimplePoint -> 0.0 to true
            is GeoPoint -> obj.z() to true
            else -> 0.0 to false
        }

    private class ParseKeys {
        var rCoordinates = JsonResult()
        var rGeometries = JsonResult()
        var rGeometry = JsonResult()
        var rFeatures = JsonResult()
        var members = ""
    }

    private fun toGeometryOpts(opts: ParseOptions): IndexOptions = IndexOptions(opts.indexGeometryKind, opts.indexGeometry)

    private fun err(msg: String): Nothing = throw GeoJsonException(msg)

    private fun parseJson(
        data: String,
        opts: ParseOptions,
    ): GeoObject {
        if (!Gjson.valid(data)) err(Errors.DATA_INVALID)
        val keys = ParseKeys()
        val fmembers = StringBuilder()
        var rType = JsonResult()
        Gjson.parse(data).forEach { key, value ->
            when (key.string()) {
                "type" -> {
                    rType = value
                }

                "coordinates" -> {
                    keys.rCoordinates = value
                }

                "geometries" -> {
                    keys.rGeometries = value
                }

                "geometry" -> {
                    keys.rGeometry = value
                }

                "features" -> {
                    keys.rFeatures = value
                }

                else -> {
                    fmembers.append(if (fmembers.isEmpty()) '{' else ',')
                    fmembers.append(Pretty.ugly(key.raw))
                    fmembers.append(':')
                    fmembers.append(Pretty.ugly(value.raw))
                }
            }
            true
        }
        if (fmembers.isNotEmpty()) {
            fmembers.append('}')
            keys.members = fmembers.toString()
        }
        if (!rType.exists()) err(Errors.TYPE_MISSING)
        if (rType.type != JsonType.STRING) err(Errors.TYPE_INVALID)
        return when (val t = rType.string()) {
            "Point" -> parseJsonPoint(keys, opts)
            "LineString" -> parseJsonLineString(keys, opts)
            "Polygon" -> parseJsonPolygon(keys, opts)
            "Feature" -> parseJsonFeature(keys, opts)
            "MultiPoint" -> parseJsonMultiPoint(keys, opts)
            "MultiLineString" -> parseJsonMultiLineString(keys, opts)
            "MultiPolygon" -> parseJsonMultiPolygon(keys, opts)
            "GeometryCollection" -> parseJsonGeometryCollection(keys, opts)
            "FeatureCollection" -> parseJsonFeatureCollection(keys, opts)
            else -> err(Errors.typeUnknown(t))
        }
    }

    private fun parseBBoxAndExtras(
        ex: Extra?,
        keys: ParseKeys,
    ): Extra? {
        if (keys.members.isEmpty()) return ex
        val e = ex ?: Extra()
        e.members = keys.members
        return e
    }

    // ---- Point ----

    private class PointCoords(
        val point: Point,
        val extra: Extra?,
    )

    private fun parseJsonPoint(
        keys: ParseKeys,
        opts: ParseOptions,
    ): GeoObject {
        val pc = parseJsonPointCoords(keys, null)
        val extra = parseBBoxAndExtras(pc.extra, keys)
        val o: GeoObject =
            if (extra == null && opts.allowSimplePoints) {
                SimplePoint(pc.point)
            } else {
                GeoPoint(pc.point, extra)
            }
        if (opts.requireValid && !o.valid()) err(Errors.COORDINATES_INVALID)
        return o
    }

    private fun parseJsonPointCoords(
        keys: ParseKeys,
        rcoords0: JsonResult?,
    ): PointCoords {
        var rcoords = rcoords0
        if (rcoords == null || !rcoords.exists()) {
            rcoords = keys.rCoordinates
            if (!rcoords.exists()) err(Errors.COORDINATES_MISSING)
            if (!rcoords.isArray()) err(Errors.COORDINATES_INVALID)
        }
        var error: String? = null
        var count = 0
        val nums = DoubleArray(4)
        rcoords.forEach { _, value ->
            if (count == 4) return@forEach false
            if (value.type != JsonType.NUMBER) {
                if (value.type == JsonType.NULL) {
                    // convert "null" to "NaN"
                    nums[count] = Double.NaN
                    count++
                    return@forEach true
                }
                error = Errors.COORDINATES_INVALID
                return@forEach false
            }
            nums[count] = value.float()
            count++
            true
        }
        error?.let { err(it) }
        if (count < 2) err(Errors.COORDINATES_INVALID)
        val point = Point(nums[0], nums[1])
        var ex: Extra? = null
        if (count > 2) {
            ex = Extra(dims = if (count > 3) 2 else 1)
            for (i in 2 until count) ex.values.add(nums[i])
        }
        return PointCoords(point, ex)
    }

    // ---- LineString ----

    private class LineCoords(
        val points: List<Point>,
        val extra: Extra?,
    )

    private fun parseJsonLineString(
        keys: ParseKeys,
        opts: ParseOptions,
    ): GeoObject {
        val lc = parseJsonLineStringCoords(keys, null)
        if (lc.points.size < 2) err(Errors.COORDINATES_INVALID)
        val line = Line(lc.points, toGeometryOpts(opts))
        val extra = parseBBoxAndExtras(lc.extra, keys)
        val g = LineString(line, extra)
        if (opts.requireValid && !g.valid()) err(Errors.DATA_INVALID)
        return g
    }

    private fun parseJsonLineStringCoords(
        keys: ParseKeys,
        rcoords0: JsonResult?,
    ): LineCoords {
        var rcoords = rcoords0
        if (rcoords == null || !rcoords.exists()) {
            rcoords = keys.rCoordinates
            if (!rcoords.exists()) err(Errors.COORDINATES_MISSING)
            if (!rcoords.isArray()) err(Errors.COORDINATES_INVALID)
        }
        var error: String? = null
        val coords = ArrayList<Point>()
        var ex: Extra? = null
        var dims = 0
        rcoords.forEach { _, value ->
            if (!value.isArray()) {
                error = Errors.COORDINATES_INVALID
                return@forEach false
            }
            var count = 0
            val nums = DoubleArray(4)
            value.forEach { _, v ->
                if (count == 4) return@forEach false
                if (v.type != JsonType.NUMBER) {
                    error = Errors.COORDINATES_INVALID
                    return@forEach false
                }
                nums[count] = v.float()
                count++
                true
            }
            if (error != null) return@forEach false
            if (count < 2) {
                error = Errors.COORDINATES_INVALID
                return@forEach false
            }
            coords.add(Point(nums[0], nums[1]))
            if (ex == null) {
                if (count > 2) {
                    if (coords.size > 1) {
                        error = Errors.COORDINATES_INVALID
                        return@forEach false
                    }
                    ex = Extra(dims = if (count > 3) 2 else 1)
                    dims = ex.dims
                }
            }
            ex?.let { e -> for (i in 0 until dims) e.values.add(nums[2 + i]) }
            true
        }
        error?.let { err(it) }
        return LineCoords(coords, ex)
    }

    // ---- Polygon ----

    private class PolyCoords(
        val rings: List<List<Point>>,
        val extra: Extra?,
    )

    private fun isLinearRing(p: List<Point>): Boolean = p.size >= 4 && p[0] == p[p.size - 1]

    private fun parseJsonPolygon(
        keys: ParseKeys,
        opts: ParseOptions,
    ): GeoObject {
        val pc = parseJsonPolygonCoords(keys, null)
        val coords = pc.rings
        if (coords.isEmpty()) err(Errors.COORDINATES_INVALID) // must be a linear ring
        for (p in coords) {
            if (!isLinearRing(p)) err(Errors.COORDINATES_INVALID) // must be a linear ring
        }
        val exterior = coords[0]
        val holes = if (coords.size > 1) coords.subList(1, coords.size) else null
        val extra = parseBBoxAndExtras(pc.extra, keys)
        val o: GeoObject =
            if (extra == null && opts.allowRects && holes == null && exterior.size == 5 &&
                exterior[0].x < exterior[1].x &&
                exterior[0].y == exterior[1].y &&
                exterior[1].x == exterior[2].x &&
                exterior[1].y < exterior[2].y &&
                exterior[2].x > exterior[3].x &&
                exterior[2].y == exterior[3].y &&
                exterior[3].x == exterior[4].x &&
                exterior[3].y > exterior[4].y
            ) {
                GeoRect(Rect(exterior[0], exterior[2]))
            } else {
                Polygon(Poly(exterior, holes, toGeometryOpts(opts)), extra)
            }
        if (opts.requireValid && !o.valid()) err(Errors.COORDINATES_INVALID)
        return o
    }

    private fun parseJsonPolygonCoords(
        keys: ParseKeys,
        rcoords0: JsonResult?,
    ): PolyCoords {
        var rcoords = rcoords0
        if (rcoords == null || !rcoords.exists()) {
            rcoords = keys.rCoordinates
            if (!rcoords.exists()) err(Errors.COORDINATES_MISSING)
            if (!rcoords.isArray()) err(Errors.COORDINATES_INVALID)
        }
        var error: String? = null
        val coords = ArrayList<ArrayList<Point>>()
        var ex: Extra? = null
        var dims = 0
        rcoords.forEach { _, value ->
            if (!value.isArray()) {
                error = Errors.COORDINATES_INVALID
                return@forEach false
            }
            val ring = ArrayList<Point>()
            coords.add(ring)
            value.forEach { _, pv ->
                var count = 0
                val nums = DoubleArray(4)
                pv.forEach { _, v ->
                    if (count == 4) return@forEach false
                    if (v.type != JsonType.NUMBER) {
                        error = Errors.COORDINATES_INVALID
                        return@forEach false
                    }
                    nums[count] = v.float()
                    count++
                    true
                }
                if (error != null) return@forEach false
                if (count < 2) {
                    error = Errors.COORDINATES_INVALID
                    return@forEach false
                }
                ring.add(Point(nums[0], nums[1]))
                if (ex == null) {
                    if (count > 2) {
                        if (coords.size > 1 || ring.size > 1) {
                            error = Errors.COORDINATES_INVALID
                            return@forEach false
                        }
                        ex = Extra(dims = if (count > 3) 2 else 1)
                        dims = ex.dims
                    }
                }
                ex?.let { e -> for (i in 0 until dims) e.values.add(nums[2 + i]) }
                true
            }
            error == null
        }
        error?.let { err(it) }
        return PolyCoords(coords, ex)
    }

    // ---- Multi types ----

    private fun parseJsonMultiPoint(
        keys: ParseKeys,
        opts: ParseOptions,
    ): GeoObject {
        val g = MultiPoint()
        if (!keys.rCoordinates.exists()) err(Errors.COORDINATES_MISSING)
        if (!keys.rCoordinates.isArray()) err(Errors.COORDINATES_INVALID)
        val children = ArrayList<GeoObject>()
        keys.rCoordinates.forEach { _, value ->
            val pc = parseJsonPointCoords(keys, value)
            children.add(GeoPoint(pc.point, pc.extra))
            true
        }
        g.childList = children
        g.extra = parseBBoxAndExtras(g.extra, keys)
        g.parseInitRectIndex(opts)
        return g
    }

    private fun parseJsonMultiLineString(
        keys: ParseKeys,
        opts: ParseOptions,
    ): GeoObject {
        val g = MultiLineString()
        if (!keys.rCoordinates.exists()) err(Errors.COORDINATES_MISSING)
        if (!keys.rCoordinates.isArray()) err(Errors.COORDINATES_INVALID)
        val children = ArrayList<GeoObject>()
        keys.rCoordinates.forEach { _, value ->
            val lc = parseJsonLineStringCoords(keys, value)
            if (lc.points.size < 2) err(Errors.COORDINATES_INVALID)
            val line = Line(lc.points, toGeometryOpts(opts))
            children.add(LineString(line, lc.extra))
            true
        }
        g.childList = children
        g.extra = parseBBoxAndExtras(g.extra, keys)
        if (opts.requireValid && !g.valid()) err(Errors.COORDINATES_INVALID)
        g.parseInitRectIndex(opts)
        return g
    }

    private fun parseJsonMultiPolygon(
        keys: ParseKeys,
        opts: ParseOptions,
    ): GeoObject {
        val g = MultiPolygon()
        if (!keys.rCoordinates.exists()) err(Errors.COORDINATES_MISSING)
        if (!keys.rCoordinates.isArray()) err(Errors.COORDINATES_INVALID)
        val children = ArrayList<GeoObject>()
        keys.rCoordinates.forEach { _, value ->
            val pc = parseJsonPolygonCoords(keys, value)
            val coords = pc.rings
            if (coords.isEmpty()) err(Errors.COORDINATES_INVALID) // must be a linear ring
            for (p in coords) {
                if (!isLinearRing(p)) err(Errors.COORDINATES_INVALID) // must be a linear ring
            }
            val exterior = coords[0]
            val holes = if (coords.size > 1) coords.subList(1, coords.size) else null
            val poly = Poly(exterior, holes, toGeometryOpts(opts))
            children.add(Polygon(poly, pc.extra))
            true
        }
        g.childList = children
        g.extra = parseBBoxAndExtras(g.extra, keys)
        if (opts.requireValid && !g.valid()) err(Errors.COORDINATES_INVALID)
        g.parseInitRectIndex(opts)
        return g
    }

    private fun parseJsonGeometryCollection(
        keys: ParseKeys,
        opts: ParseOptions,
    ): GeoObject {
        val g = GeometryCollection()
        if (!keys.rGeometries.exists()) err(Errors.GEOMETRIES_MISSING)
        if (!keys.rGeometries.isArray()) err(Errors.GEOMETRIES_INVALID)
        val children = ArrayList<GeoObject>()
        keys.rGeometries.forEach { _, value ->
            children.add(parse(value.raw, opts))
            true
        }
        g.childList = children
        g.extra = parseBBoxAndExtras(g.extra, keys)
        g.parseInitRectIndex(opts)
        return g
    }

    private fun parseJsonFeatureCollection(
        keys: ParseKeys,
        opts: ParseOptions,
    ): GeoObject {
        val g = FeatureCollection()
        if (!keys.rFeatures.exists()) err(Errors.FEATURES_MISSING)
        if (!keys.rFeatures.isArray()) err(Errors.FEATURES_INVALID)
        val children = ArrayList<GeoObject>()
        keys.rFeatures.forEach { _, value ->
            children.add(parse(value.raw, opts))
            true
        }
        g.childList = children
        g.extra = parseBBoxAndExtras(g.extra, keys)
        g.parseInitRectIndex(opts)
        return g
    }

    // ---- Feature ----

    private fun parseJsonFeature(
        keys: ParseKeys,
        opts: ParseOptions,
    ): GeoObject {
        if (!keys.rGeometry.exists()) err(Errors.GEOMETRY_MISSING)
        val base = parse(keys.rGeometry.raw, opts)
        val extra = parseBBoxAndExtras(null, keys)
        if (base is GeoPoint && extra != null) {
            val members = extra.members
            if (!opts.disableCircleType && Gjson.get(members, "properties.type").string() == "Circle") {
                var radius = Gjson.get(members, "properties.radius").float()
                when (Gjson.get(members, "properties.radius_units").string()) {
                    "", "m" -> {}

                    "km" -> {
                        radius *= 1000
                    }

                    else -> {
                        err(Errors.CIRCLE_RADIUS_UNITS_INVALID)
                    }
                }
                return Circle(base.base(), radius, 64)
            }
        }
        return Feature(base, extra)
    }
}
