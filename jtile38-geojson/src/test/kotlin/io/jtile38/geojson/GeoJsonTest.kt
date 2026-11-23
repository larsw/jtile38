@file:Suppress("ktlint:standard:function-naming")

package io.jtile38.geojson

import io.jtile38.geojson.geometry.IndexKind
import io.jtile38.geojson.geometry.Line
import io.jtile38.geojson.geometry.P
import io.jtile38.geojson.geometry.Point
import io.jtile38.geojson.geometry.Poly
import io.jtile38.geojson.geometry.R
import io.jtile38.geojson.geometry.expect
import io.jtile38.json.Gjson
import io.jtile38.json.JsonFloat
import io.jtile38.json.JsonResult
import io.jtile38.json.JsonString
import io.jtile38.json.JsonType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GeoJsonTest {
    private fun PO(
        x: Number,
        y: Number,
    ) = GeoPoint.of(P(x, y))

    private fun MPO(points: List<Point>) = MultiPoint(points)

    private fun RO(
        minX: Number,
        minY: Number,
        maxX: Number,
        maxY: Number,
    ) = GeoRect(R(minX, minY, maxX, maxY))

    private fun LO(points: List<Point>) = LineString(Line(points))

    private fun L(points: List<Point>) = Line(points)

    private fun MLO(lines: List<Line>) = MultiLineString(lines)

    private fun PPO(
        exterior: List<Point>,
        holes: List<List<Point>>? = null,
    ) = Polygon(Poly.of(exterior, holes))

    private fun canon(r: JsonResult): String =
        when {
            r.isObject() -> {
                r
                    .map()
                    .entries
                    .sortedBy { it.key }
                    .joinToString(",", "{", "}") { JsonString.quote(it.key) + ":" + canon(it.value) }
            }

            r.isArray() -> {
                r.array().joinToString(",", "[", "]") { canon(it) }
            }

            r.type == JsonType.NUMBER -> {
                JsonFloat.format(r.num)
            }

            r.type == JsonType.STRING -> {
                JsonString.quote(r.str)
            }

            else -> {
                r.raw
            }
        }

    private fun cleanJson(s: String): String {
        assertTrue(Gjson.valid(s), "invalid json: $s")
        return canon(Gjson.parse(s))
    }

    /** Parses [data] and checks that its JSON equals [expected] (or [data] itself when null). */
    private fun expectJson(
        data: String,
        expected: String? = null,
        opts: ParseOptions = ParseOptions.DEFAULT,
    ): GeoObject {
        val obj = GeoJson.parse(data, opts)
        assertEquals(cleanJson(expected ?: data), cleanJson(obj.json()))
        return obj
    }

    private fun expectError(
        data: String,
        message: String,
        opts: ParseOptions = ParseOptions.DEFAULT,
    ) {
        val e = assertFailsWith<GeoJsonException> { GeoJson.parse(data, opts) }
        assertEquals(message, e.message)
    }

    private val requireValid = ParseOptions(requireValid = true)

    @Test
    fun validJsonNumbers() {
        val p = GeoPoint.of(Point(Double.NaN, Double.POSITIVE_INFINITY))
        val expected = """{"type":"Point","coordinates":[null,null]}"""
        assertEquals(expected, p.json())
        assertEquals(expected, GeoJson.parse(expected).json())
    }

    @Test
    fun parseErrors() {
        expectError("", "invalid data")
        expectError("[1,2]", "invalid data")
        expectError("{", "invalid data")
        expectError("""{"coordinates":[1,2]}""", "missing type")
        expectError("""{"type":1}""", "invalid type")
        expectError("""{"type":"Foo"}""", "type 'Foo' is unknown")
        assertIs<GeoPoint>(GeoJson.parse("""  {"type":"Point","coordinates":[1,2]}"""))
    }

    @Test
    fun pointParse() {
        val p = expectJson("""{"type":"Point","coordinates":[1,2,3]}""")
        expect(p.center() == P(1, 2))
        assertEquals(3.0, GeoJson.isPoint(p).first)
        expectJson("""{"type":"Point","coordinates":[1,null]}""", """{"type":"Point","coordinates":[1,null]}""")
        expectError("""{"type":"Point","coordinates":[1,"hello"]}""", "invalid coordinates")
        expectJson("""{"type":"Point","coordinates":[1,2],"bbox":null}""")
        expectError("""{"type":"Point"}""", "missing coordinates")
        expectError("""{"type":"Point","coordinates":null}""", "invalid coordinates")
        expectJson("""{"type":"Point","coordinates":[1,2,3,4,5]}""", """{"type":"Point","coordinates":[1,2,3,4]}""")
        expectError("""{"type":"Point","coordinates":[1]}""", "invalid coordinates")
        expectJson(
            """{"type":"Point","coordinates":[1,2,3],"bbox":[1,2,3,4]}""",
            """{"type":"Point","coordinates":[1,2,3],"bbox":[1,2,3,4]}""",
        )
        val json = """{"type":"Point","coordinates":[190,90]}"""
        expectJson(json)
        expectError(json, "invalid coordinates", requireValid)
    }

    @Test
    fun pointVarious() {
        val g: GeoObject = PO(10, 20)
        assertEquals("""{"type":"Point","coordinates":[10,20]}""", g.json())
        assertEquals(g.json(), g.toString())
        expect(g.rect() == R(10, 20, 10, 20))
        expect(g.center() == P(10, 20))
        expect(!g.empty())
        expect(PO(0, 20).valid())
        expect(!PO(10, 91).valid())
        expect(!PO(181, 20).valid())
        expect(PO(180, 90).valid())
        assertEquals("""{"type":"Point","coordinates":[1,2,3]}""", GeoPoint.ofZ(P(1, 2), 3.0).json())
    }

    @Test
    fun exactMemberOrder() {
        // members are written back in their original order, minified
        val obj = GeoJson.parse("""{ "id" : "a b", "type":"Point", "coordinates":[1.5,2], "properties": { "x" : [1, 2] } }""")
        assertEquals("""{"type":"Point","coordinates":[1.5,2],"id":"a b","properties":{"x":[1,2]}}""", obj.json())
        assertEquals("""{"id":"a b","properties":{"x":[1,2]}}""", obj.members())
    }

    @Test
    fun simplePoints() {
        val opts = ParseOptions(allowSimplePoints = true)
        val p = expectJson("""{"type":"Point","coordinates":[1,2,3]}""", opts = opts)
        assertIs<GeoPoint>(p)
        expectError("""{"type":"Point","coordinates":[1,"hello"]}""", "invalid coordinates", opts)
        expectJson("""{"type":"Point","coordinates":[1,2],"bbox":null}""", opts = opts)
        assertIs<SimplePoint>(GeoJson.parse("""{"type":"Point","coordinates":[1,2]}""", opts))
        assertIs<GeoPoint>(GeoJson.parse("""{"type":"Point","coordinates":[1,2]}"""))
        val json = """{"type":"Point","coordinates":[190,90]}"""
        expect(!(GeoJson.parse(json, opts) as SimplePoint).empty())
        expectError(json, "invalid coordinates", ParseOptions(requireValid = true, allowSimplePoints = true))
        assertEquals("""{"type":"Point","coordinates":[10,20]}""", SimplePoint(P(10, 20)).json())
    }

    @Test
    fun lineStringParse() {
        expectError("""{"type":"LineString","coordinates":[[1,2,3]]}""", "invalid coordinates")
        expectError("""{"type":"LineString","coordinates":[[1,null]]}""", "invalid coordinates")
        expectError("""{"type":"LineString","coordinates":[[1,2]],"bbox":null}""", "invalid coordinates")
        expectError("""{"type":"LineString"}""", "missing coordinates")
        expectError("""{"type":"LineString","coordinates":null}""", "invalid coordinates")
        expectError("""{"type":"LineString","coordinates":[[null]]}""", "invalid coordinates")
        expectError("""{"type":"LineString","coordinates":[null]}""", "invalid coordinates")
        expectError("""{"type":"LineString","coordinates":[[1,2,3,4,5]]}""", "invalid coordinates")
        expectError("""{"type":"LineString","coordinates":[[1]]}""", "invalid coordinates")
        val g = expectJson("""{"type":"LineString","coordinates":[[3,4],[1,2]]}""")
        expect(g.rect() == R(1, 2, 3, 4))
        expect(g.center() == P(2, 3))
        assertEquals("""{"type":"LineString","coordinates":[[3,4],[1,2]]}""", g.json())
        expectJson("""{"type":"LineString","coordinates":[[3,4],[1,2]],"bbox":null}""")
        expectJson("""{"type":"LineString","coordinates":[[3,4],[1,2]],"bbox":[1,2,3,4]}""")
        val json = """{"type":"LineString","coordinates":[[1,2],[-12,-190]]}"""
        expectJson(json)
        expectError(json, "invalid data", requireValid)
        expect(!expectJson("""{"type":"LineString","coordinates":[[3,4],[1,2],[0, 190]]}""").valid())
    }

    @Test
    fun polygonParse() {
        expectJson("""{"type":"Polygon","coordinates":[[[0,0],[10,0],[10,10],[0,10],[0,0]]]}""")
        val g = expectJson("""{"type":"Polygon","coordinates":[[[0,0],[10,0],[10,10],[0,10],[0,0]],[[2,2],[8,2],[8,8],[2,8],[2,2]]]}""")
        expect(g.center() == P(5, 5))
        expectError("""{"type":"Polygon","coordinates":[[1,null]]}""", "invalid coordinates")
        expectError("""{"type":"Polygon","coordinates":[]}""", "invalid coordinates")
        expectError("""{"type":"Polygon","coordinates":[[[0,0],[10,0],[5,10],[0,0]],[[1,1]]]}""", "invalid coordinates")
        expectJson("""{"type":"Polygon","coordinates":[[[0,0],[10,0],[5,10],[0,0]]],"bbox":null}""")
        expectError("""{"type":"Polygon"}""", "missing coordinates")
        expectError("""{"type":"Polygon","coordinates":null}""", "invalid coordinates")
        expectError("""{"type":"Polygon","coordinates":[null]}""", "invalid coordinates")
        expectError("""{"type":"Polygon","coordinates":[[null]]}""", "invalid coordinates")
        expectJson(
            """{"type":"Polygon","coordinates":[[[0,0,0,0,0],[10,0],[5,10],[0,0]]]}""",
            """{"type":"Polygon","coordinates":[[[0,0,0,0],[10,0,0,0],[5,10,0,0],[0,0,0,0]]]}""",
        )
        expectJson(
            """{"type":"Polygon","coordinates":[[[0,0,0],[10,0,4,5],[5,10],[0,0]]]}""",
            """{"type":"Polygon","coordinates":[[[0,0,0],[10,0,4],[5,10,0],[0,0,0]]]}""",
        )
        val json = """{"type":"Polygon","coordinates":[[[0,0],[190,0],[10,10],[0,10],[0,0]],[[2,2],[8,2],[8,8],[2,8],[2,2]]]}"""
        expectJson(json)
        expectError(json, "invalid coordinates", requireValid)
        assertEquals("""{"type":"Polygon","coordinates":[]}""", Polygon(null).json())
        expectError(
            """{"type":"Polygon","coordinates":[[[0,0],[10,0],[0,10],[0,0]],[[0,0,0],[0,10,0],[10,0,0],[0,0,0]]]}""",
            "invalid coordinates",
        )
        expectError("""{"type":"Polygon","coordinates":[[[0,0],[10,0,1],[0,10],[0,0]]]}""", "invalid coordinates")
        expectError("""{"type":"LineString","coordinates":[[0,0],[10,0,1],[0,10],[0,0]]}""", "invalid coordinates")
    }

    @Test
    fun issue664() {
        val p1 =
            GeoJson.parse(
                """{"type":"Polygon","coordinates":[[
            [-0.104364362074306,51.515197601239528],[-0.100183436878063,51.511898267797733],
            [-0.095787000073766,51.509618100991439],[-0.097554195259807,51.505459855954911],
            [-0.106390171190011,51.504842793711688],[-0.115829579622766,51.507418302507524],
            [-0.115527863371491,51.511737318590235],[-0.108157366376052,51.513722319074922],
            [-0.104364362074306,51.515197601239528]
            ],[
            [-0.108114264054441,51.51141541846934],[-0.10289888313954,51.511066690771599],
            [-0.102036836707325,51.507954848516555],[-0.108674594235381,51.507874367018005],
            [-0.108114264054441,51.51141541846934]
            ]]}""",
            )
        val p2 =
            GeoJson.parse(
                """{"type":"Polygon","coordinates":[[
            [-0.104364362074306,51.515197601239528],[-0.108157366376052,51.513722319074922],
            [-0.115527863371491,51.511737318590235],[-0.115829579622766,51.507418302507524],
            [-0.106390171190011,51.504842793711688],[-0.097554195259807,51.505459855954911],
            [-0.095787000073766,51.509618100991439],[-0.100183436878063,51.511898267797733],
            [-0.104364362074306,51.515197601239528]
            ],[
            [-0.108114264054441,51.51141541846934],[-0.10289888313954,51.511066690771599],
            [-0.102036836707325,51.507954848516555],[-0.108674594235381,51.507874367018005],
            [-0.108114264054441,51.51141541846934]
            ]]}""",
            )
        val lines =
            listOf(
                """{"type": "LineString","coordinates": [[-0.11203657532102,51.509805883746516],[-0.098071423119136,51.509618100991439]]}""",
                """{"type": "LineString","coordinates": [[-0.106778092084508,51.510235098565985],[-0.104148850466252,51.510208272758227]]}""",
                """{"type": "LineString","coordinates": [[-0.099235185802626,51.512327462904388],[-0.099235185802626,51.510798436879945]]}""",
                """{"type": "LineString","coordinates": [[-0.111741852049867,51.510945273705836],[-0.10999491495498,51.512082479942833]]}""",
            )
        val expected = listOf(true, false, true, true)
        for (p in listOf(p1, p2)) {
            for ((i, line) in lines.withIndex()) {
                val l = GeoJson.parse(line)
                assertEquals(expected[i], p.intersects(l), "line $i")
                assertEquals(expected[i], l.intersects(p), "line $i")
            }
        }
    }

    @Test
    fun rect() {
        val rect = RO(10, 20, 30, 40)
        expect(!rect.empty())
        assertEquals("""{"type":"Polygon","coordinates":[[[10,20],[30,20],[30,40],[10,40],[10,20]]]}""", rect.json())
        expect(rect.rect() == R(10, 20, 30, 40))
        expect(rect.center() == P(20, 30))
        var g: GeoObject? = null
        rect.forEach {
            expect(g == null)
            g = it
            true
        }
        expect(g === rect)
        expect(rect.numPoints() == 2)

        val json = rect.json()
        val r2 = GeoJson.parse(json, ParseOptions(allowRects = true))
        assertIs<GeoRect>(r2)
        expect(r2.base() == rect.base())
        val poly = GeoJson.parse(json)
        assertIs<Polygon>(poly)
        assertEquals(json, poly.json())

        val invalid = """{"type":"Polygon","coordinates":[[[10,200],[30,200],[30,40],[10,40],[10,200]]]}"""
        expectJson(invalid)
        expectError(invalid, "invalid coordinates", requireValid)
    }

    @Test
    fun multiPoint() {
        val p = expectJson("""{"type":"MultiPoint","coordinates":[[1,2,3]]}""")
        expect(p.center() == P(1, 2))
        expectError("""{"type":"MultiPoint","coordinates":[1,null]}""", "invalid coordinates")
        expectJson("""{"type":"MultiPoint","coordinates":[[1,2]],"bbox":null}""")
        expectError("""{"type":"MultiPoint"}""", "missing coordinates")
        expectError("""{"type":"MultiPoint","coordinates":null}""", "invalid coordinates")
        expectJson("""{"type":"MultiPoint","coordinates":[[1,2,3],[4,5,6]],"bbox":[1,2,3,4]}""")
    }

    @Test
    fun multiLineString() {
        expectError("""{"type":"MultiLineString","coordinates":[[[1,2,3]]]}""", "invalid coordinates")
        expectError("""{"type":"MultiLineString","coordinates":[[[1,2]]],"bbox":null}""", "invalid coordinates")
        expectError("""{"type":"MultiLineString"}""", "missing coordinates")
        expectError("""{"type":"MultiLineString","coordinates":null}""", "invalid coordinates")
        expectError("""{"type":"MultiLineString","coordinates":[1,null]}""", "invalid coordinates")
        val invalid = """{"type":"MultiLineString","coordinates":[[[10,10],[120,190]],[[50,50],[100,100]]]}"""
        expectJson(invalid)
        expectError(invalid, "invalid coordinates", requireValid)
        val p = expectJson("""{"type":"MultiLineString","coordinates":[[[10,10],[20,20]],[[50,50],[100,100]]]}""")
        expect(p.intersects(PO(15, 15)))
        expect(p.contains(PO(15, 15)))
        expect(p.contains(PO(70, 70)))
        expect(!p.contains(PO(40, 40)))
    }

    @Test
    fun multiPolygon() {
        val json = """{"type":"MultiPolygon","coordinates":[
            [[[0,0],[10,0],[10,10],[0,10],[0,0]],[[2,2],[8,2],[8,8],[2,8],[2,2]]],
            [[[0,0],[10,0],[10,10],[0,10],[0,0]],[[2,2],[8,2],[8,8],[2,8],[2,2]]]
        ]}"""
        val p = expectJson(json)
        expect(p.center() == P(5, 5))
        expectJson(json, opts = requireValid)
        expectError("""{"type":"MultiPolygon","coordinates":[[[[0,0],[10,0],[5,10],[0,0]],[[1,1]]]],"bbox":null}""", "invalid coordinates")
        expectError("""{"type":"MultiPolygon"}""", "missing coordinates")
        expectError("""{"type":"MultiPolygon","coordinates":null}""", "invalid coordinates")
        expectError("""{"type":"MultiPolygon","coordinates":[1,null]}""", "invalid coordinates")

        val q =
            expectJson(
                """{"type":"MultiPolygon","coordinates":[[[[10,10],[20,20],[30,10],[10,10]]],[[[100,100],[200,200],[300,100],[100,100]]]]}""",
            )
        expect(q.intersects(PO(15, 15)))
        expect(q.contains(PO(15, 15)))
        expect(q.contains(PO(150, 150)))
        expect(!q.contains(PO(40, 40)))
        expect(q.within(RO(-100, -100, 1000, 1000)))
    }

    @Test
    fun issue369() {
        val poly14 =
            expectJson(
                """{"type":"Polygon","coordinates":[[[-122.44154334068298,37.73179457567642],[-122.43935465812682,37.73179457567642],[-122.43935465812682,37.7343740514423],[-122.44154334068298,37.7343740514423],[-122.44154334068298,37.73179457567642]],[[-122.44104981422423,37.73286371140448],[-122.44104981422423,37.73424677678513],[-122.43990182876587,37.73424677678513],[-122.43990182876587,37.73286371140448],[-122.44104981422423,37.73286371140448]],[[-122.44109272956847,37.731870943026074],[-122.43976235389708,37.731870943026074],[-122.43976235389708,37.7326855231885],[-122.44109272956847,37.7326855231885],[-122.44109272956847,37.731870943026074]]]}""",
            )
        val query =
            expectJson(
                """{"type":"MultiPolygon","coordinates":[[[[-122.4408378,37.7341129],[-122.4408378,37.733],[-122.44,37.733],[-122.44,37.7341129],[-122.4408378,37.7341129]]],[[[-122.44091033935547,37.731981251280985],[-122.43994474411011,37.731981251280985],[-122.43994474411011,37.73254976045042],[-122.44091033935547,37.73254976045042],[-122.44091033935547,37.731981251280985]]]]}""",
            )
        expect(!query.intersects(poly14))
        expect(!poly14.intersects(query))
    }

    @Test
    fun feature() {
        val p = expectJson("""{"type":"Feature","geometry":{"type":"Point","coordinates":[1,2,3]},"properties":{}}""")
        expect(p.center() == P(1, 2))
        expectError("""{"type":"Feature"}""", "missing geometry")
        expectError("""{"type":"Feature","geometry":null}""", "invalid data")
        expectJson("""{"type":"Feature","geometry":{"type":"Point","coordinates":[1,2,3]},"bbox":null,"properties":{}}""")
        expectJson("""{"type":"Feature","geometry":{"type":"Point","coordinates":[1,2]},"id":[4,true],"properties":{}}""")
        expectJson("""{"type":"Feature","geometry":{"type":"Point","coordinates":[1,2]},"id":"15","properties":{"a":"b"}}""")
        expectJson("""{"type":"Feature","geometry":{"type":"Point","coordinates":[1,2,3]},"bbox":[1,2,3,4],"properties":{}}""")
        expectJson("""{"type":"Feature","geometry":{"type":"Point","coordinates":[1,2],"bbox":[1,2,3,4]},"id":[4,true],"properties":{}}""")
        assertEquals(
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[1,2,3]},"properties":{}}""",
            GeoJson.parse("""{"type":"Feature","geometry":{"type":"Point","coordinates":[1,2,3]},"properties":{}}""").json(),
        )
        var json = GeoJson.parse("""{"type":"Feature","geometry":{"type":"Point","coordinates":[1,2]}}""").json()
        assertEquals("""{"type":"Feature","geometry":{"type":"Point","coordinates":[1,2]},"properties":{}}""", json)
        json = GeoJson.parse("""{"type":"Feature","geometry":{"type":"Point","coordinates":[1,2]},"properties":true}""").json()
        assertEquals(JsonType.TRUE, Gjson.get(json, "properties").type)
        json = GeoJson.parse("""{"type":"Feature","geometry":{"type":"Point","coordinates":[1,2]},"id":{}}""").json()
        assertEquals("""{"type":"Feature","geometry":{"type":"Point","coordinates":[1,2]},"id":{},"properties":{}}""", json)

        val f = Feature(PO(1, 2), """ { "id" : 5, "feature": 1, "properties": {"a":1} } """)
        assertEquals("""{"type":"Feature","geometry":{"type":"Point","coordinates":[1,2]},"id":5,"properties":{"a":1}}""", f.json())
        assertEquals(
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[1,2]},"properties":{}}""",
            Feature(PO(1, 2), "{}").json(),
        )
    }

    @Test
    fun issue529() {
        val ls1 = GeoJson.parse("""{"type":"LineString","coordinates":[[0,0],[0,1]]}""")
        val ls2 = GeoJson.parse(""" {"type":"Feature","geometry":{"type":"LineString","coordinates":[[0,0],[0,1]]},"properties":{}}""")
        assertIs<Feature>(ls2)
        val circ = Circle(P(0, 0.5), 5000.0, 64)
        expect(ls1.intersects(circ))
        expect(circ.intersects(ls1))
        expect(ls2.intersects(circ))
        expect(circ.intersects(ls2))
    }

    @Test
    fun featureCollection() {
        val p = expectJson("""{"type":"FeatureCollection","features":[{"type":"Point","coordinates":[1,2,3]}]}""")
        expect(p.center() == P(1, 2))
        expectError("""{"type":"FeatureCollection"}""", "missing features")
        expectError("""{"type":"FeatureCollection","features":null}""", "invalid features")
        expectJson("""{"type":"FeatureCollection","features":[{"type":"Point","coordinates":[1,2,3]}],"bbox":null}""")
        expectError("""{"type":"FeatureCollection","features":[{"type":"Point"}]}""", "missing coordinates")
        val q = expectJson("""{"type":"FeatureCollection","features":[{"type":"Point","coordinates":[1,2]}]}""")
        expect(q.intersects(PO(1, 2)))
        expect(q.contains(PO(1, 2)))
        val invalid = """{"type":"FeatureCollection","features":[{"type":"Point","coordinates":[1,200]}]}"""
        expectJson(invalid)
        expectError(invalid, "invalid coordinates", requireValid)

        val json = """{"type":"FeatureCollection","features":[
            {"type":"Feature","id":"A","geometry":{"type":"Point","coordinates":[1,2]},"properties":{}},
            {"type":"Feature","id":"B","geometry":{"type":"Point","coordinates":[3,4]},"properties":{}},
            {"type":"Feature","id":"C","geometry":{"type":"Point","coordinates":[5,6]},"properties":{}},
            {"type":"Feature","id":"D","geometry":{"type":"Point","coordinates":[7,8]},"properties":{}}
        ]}"""
        val g = GeoJson.parse(json) as FeatureCollection
        val objsA = g.children()
        val objsB = ArrayList<GeoObject>()
        g.forEach {
            objsB.add(it)
            true
        }
        assertEquals(objsA.map { it.toString() }, objsB.map { it.toString() })
        assertEquals(4, objsA.size)
    }

    @Test
    fun geometryCollection() {
        val p = expectJson("""{"type":"GeometryCollection","geometries":[{"type":"Point","coordinates":[1,2,3]}]}""")
        expect(p.center() == P(1, 2))
        expectError("""{"type":"GeometryCollection"}""", "missing geometries")
        expectError("""{"type":"GeometryCollection","geometries":null}""", "invalid geometries")
        expectJson("""{"type":"GeometryCollection","geometries":[{"type":"Point","coordinates":[1,2,3]}],"bbox":null}""")
        expectError("""{"type":"GeometryCollection","geometries":[{"type":"Point"}]}""", "missing coordinates")
        val q = expectJson("""{"type":"GeometryCollection","geometries":[{"type":"Point","coordinates":[1,2]}]}""")
        expect(q.intersects(PO(1, 2)))
        expect(q.contains(PO(1, 2)))
        val invalid = """{"type":"GeometryCollection","geometries":[{"type":"Point","coordinates":[1,200]}]}"""
        expectJson(invalid)
        expectError(invalid, "invalid coordinates", requireValid)
    }

    @Test
    fun collectionIndexOptionsDoNotChangeResults() {
        val features =
            (0 until 100).joinToString(",") { i ->
                """{"type":"Feature","geometry":{"type":"Point","coordinates":[$i,${i % 10}]},"properties":{"n":$i}}"""
            }
        val json = """{"type":"FeatureCollection","features":[$features]}"""
        val indexed = GeoJson.parse(json, ParseOptions(indexChildren = 1, indexGeometry = 1)) as FeatureCollection
        val plain = GeoJson.parse(json, ParseOptions(indexChildren = 0, indexGeometry = 0)) as FeatureCollection
        expect(indexed.indexed())
        expect(!plain.indexed())
        val q = R(10, 2, 30, 6)
        val a = ArrayList<String>()
        indexed.search(q) {
            a.add(it.json())
            true
        }
        val b = ArrayList<String>()
        plain.search(q) {
            b.add(it.json())
            true
        }
        assertEquals(b.sorted(), a.sorted())
        expect(indexed.intersects(PO(12, 2)))
        expect(!indexed.intersects(PO(12, 3)))
    }

    @Test
    fun circleNew() {
        expectJson(
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[-112,33]},"properties":{"type":"Circle","radius":"5000"}}""",
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[-112,33]},"properties":{"type":"Circle","radius":5000,"radius_units":"m"}}""",
        )
        val g =
            GeoJson.parse(
                """{
            "type":"Feature",
            "geometry":{"type":"Point","coordinates":[-112.2693,33.5123]},
            "properties": {
              "type": "Circle",
              "radius": 1000
             }
          }""",
            )
        assertIs<Circle>(g)
        expect(g.contains(PO(-112.26, 33.51)))
        val km =
            GeoJson.parse(
                """{"type":"Feature","geometry":{"type":"Point","coordinates":[1,2]},"properties":{"type":"Circle","radius":2,"radius_units":"km"}}""",
            ) as Circle
        assertEquals(2000.0, km.meters())
        expectError(
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[1,2]},"properties":{"type":"Circle","radius":2,"radius_units":"mi"}}""",
            "invalid circle radius units",
        )
        assertIs<Feature>(
            GeoJson.parse(
                """{"type":"Feature","geometry":{"type":"Point","coordinates":[1,2]},"properties":{"type":"Circle","radius":2}}""",
                ParseOptions(disableCircleType = true),
            ),
        )
        val circle = Circle(P(-112, 33), 123456.654321, 64)
        assertEquals(
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[-112,33]},"properties":{"type":"Circle","radius":123456.654321,"radius_units":"m"}}""",
            circle.json(),
        )
    }

    @Test
    fun circleContains() {
        val g = Circle(P(-122.4412, 37.7335), 1000.0, 64)
        expect(g.contains(PO(-122.4412, 37.7335)))
        expect(g.contains(PO(-122.44121, 37.7335)))
        expect(g.contains(MPO(listOf(P(-122.4408378, 37.7341129), P(-122.4408378, 37.733)))))
        expect(g.contains(Circle(P(-122.44121, 37.7335), 500.0, 64)))
        expect(g.contains(LO(listOf(P(-122.4408378, 37.7341129), P(-122.4408378, 37.733)))))
        expect(
            g.contains(
                MLO(
                    listOf(
                        L(listOf(P(-122.4408378, 37.7341129), P(-122.4408378, 37.733))),
                        L(listOf(P(-122.44, 37.733), P(-122.44, 37.7341129))),
                    ),
                ),
            ),
        )
        expect(
            g.contains(
                PPO(
                    listOf(
                        P(-122.4408378, 37.7341129),
                        P(-122.4408378, 37.733),
                        P(-122.44, 37.733),
                        P(-122.44, 37.7341129),
                        P(-122.4408378, 37.7341129),
                    ),
                    listOf(),
                ),
            ),
        )
        expect(!g.contains(PO(-122.265, 37.826)))
        expect(!g.contains(Circle(P(-122.265, 37.826), 100.0, 64)))
        expect(!g.contains(LO(listOf(P(-122.265, 37.826), P(-122.210, 37.860)))))
        expect(!g.contains(MPO(listOf(P(-122.4408378, 37.7341129), P(-122.198181, 37.7490)))))
        expect(
            !g.contains(
                MLO(
                    listOf(
                        L(listOf(P(-122.265, 37.826), P(-122.265, 37.860))),
                        L(listOf(P(-122.44, 37.733), P(-122.44, 37.7341129))),
                    ),
                ),
            ),
        )
        expect(
            !g.contains(
                PPO(
                    listOf(P(-122.265, 37.826), P(-122.265, 37.860), P(-122.210, 37.860), P(-122.210, 37.826), P(-122.265, 37.826)),
                    listOf(),
                ),
            ),
        )
    }

    @Test
    fun circleIntersects() {
        val g = Circle(P(-122.4412, 37.7335), 1000.0, 64)
        expect(g.intersects(PO(-122.4412, 37.7335)))
        expect(g.intersects(PO(-122.44121, 37.7335)))
        expect(g.intersects(MPO(listOf(P(-122.4408378, 37.7341129), P(-122.4408378, 37.733)))))
        expect(g.intersects(Circle(P(-122.44121, 37.7335), 500.0, 64)))
        expect(g.intersects(LO(listOf(P(-122.4408378, 37.7341129), P(-122.4408378, 37.733)))))
        expect(g.intersects(LO(listOf(P(-122.4408378, 37.7341129), P(-122.265, 37.826)))))
        expect(
            g.intersects(
                MLO(
                    listOf(
                        L(listOf(P(-122.4408378, 37.7341129), P(-122.265, 37.826))),
                        L(listOf(P(-122.44, 37.733), P(-122.44, 37.7341129))),
                    ),
                ),
            ),
        )
        expect(
            g.intersects(
                PPO(
                    listOf(
                        P(-122.4408378, 37.7341129),
                        P(-122.265, 37.860),
                        P(-122.210, 37.826),
                        P(-122.44, 37.7341129),
                        P(-122.4408378, 37.7341129),
                    ),
                    listOf(),
                ),
            ),
        )
        expect(g.intersects(MPO(listOf(P(-122.4408378, 37.7341129), P(-122.198181, 37.7490)))))
        expect(
            g.intersects(
                MLO(
                    listOf(
                        L(listOf(P(-122.265, 37.826), P(-122.265, 37.860))),
                        L(listOf(P(-122.44, 37.733), P(-122.44, 37.7341129))),
                    ),
                ),
            ),
        )
        expect(!g.intersects(PO(-122.265, 37.826)))
        expect(!g.intersects(Circle(P(-122.265, 37.826), 100.0, 64)))
        expect(!g.intersects(LO(listOf(P(-122.265, 37.826), P(-122.210, 37.860)))))
    }

    @Test
    fun pointCircle() {
        val p = GeoPoint.of(Point(-0.8856761, 52.7563759))
        val c = Circle(Point(-0.8838196, 52.7563395), 200.0, 20)
        expect(p.within(c))
    }

    @Test
    fun indexedGeometryKinds() {
        // the same big polygon parsed with every index kind answers identically
        val pts =
            (0..200).map { i ->
                val a = Math.toRadians(i * 360.0 / 200)
                val r = if (i % 2 == 0) 10.0 else 6.0
                "[${JsonFloat.format(r * Math.cos(a))},${JsonFloat.format(r * Math.sin(a))}]"
            }
        val json = """{"type":"Polygon","coordinates":[[${pts.joinToString(",")},${pts[0]}]]}"""
        val objs = IndexKind.entries.map { GeoJson.parse(json, ParseOptions(indexGeometryKind = it, indexGeometry = 8)) }
        val rnd = java.util.Random(5)
        repeat(500) {
            val p = PO(rnd.nextDouble() * 24 - 12, rnd.nextDouble() * 24 - 12)
            val results = objs.map { it.contains(p) }
            assertTrue(results.all { it == results[0] })
        }
    }
}
