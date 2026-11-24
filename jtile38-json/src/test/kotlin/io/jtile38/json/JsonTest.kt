package io.jtile38.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonTest {
    private val doc =
        """
        {
          "name": {"first": "Tom", "last": "Anderson"},
          "age":37,
          "children": ["Sara","Alex","Jack"],
          "fav.movie": "Deer Hunter",
          "friends": [
            {"first": "James", "last": "Murphy"},
            {"first": "Roger", "last": "Craig"}
          ]
        }
        """.trimIndent()

    @Test
    fun getPaths() {
        assertEquals("Anderson", Gjson.get(doc, "name.last").string())
        assertEquals(37L, Gjson.get(doc, "age").int())
        assertEquals("""["Sara","Alex","Jack"]""", Gjson.get(doc, "children").raw)
        assertEquals(3L, Gjson.get(doc, "children.#").int())
        assertEquals("Alex", Gjson.get(doc, "children.1").string())
        assertEquals("Jack", Gjson.get(doc, "child*.2").string())
        assertEquals("Sara", Gjson.get(doc, "c?ildren.0").string())
        assertEquals("Deer Hunter", Gjson.get(doc, "fav\\.movie").string())
        assertEquals("""["James","Roger"]""", Gjson.get(doc, "friends.#.first").raw)
        assertEquals("Murphy", Gjson.get(doc, "friends.0.last").string())
        assertFalse(Gjson.get(doc, "nope").exists())
        assertFalse(Gjson.get(doc, "children.5").exists())
        assertEquals(JsonType.JSON, Gjson.get(doc, "name").type)
        assertTrue(Gjson.get(doc, "name").isObject())
    }

    @Test
    fun valid() {
        assertTrue(Gjson.valid(doc))
        assertTrue(Gjson.valid("1"))
        assertTrue(Gjson.valid(" \"a\\u00e9\" "))
        assertTrue(Gjson.valid("[1,2,{\"a\":null}]"))
        assertFalse(Gjson.valid(""))
        assertFalse(Gjson.valid("{"))
        assertFalse(Gjson.valid("{\"a\":1,}"))
        assertFalse(Gjson.valid("[1,]"))
        assertFalse(Gjson.valid("01"))
        assertFalse(Gjson.valid("1."))
        assertFalse(Gjson.valid("tru"))
        assertFalse(Gjson.valid("{} x"))
        assertFalse(Gjson.valid("\"\\x\""))
    }

    @Test
    fun parseAndForEach() {
        val r = Gjson.parse("""{"a":1,"b":"x\"y","c":[1,2],"d":true,"e":null}""")
        val keys = ArrayList<String>()
        val raws = ArrayList<String>()
        r.forEach { k, v ->
            keys.add(k.str)
            raws.add(v.raw)
            true
        }
        assertEquals(listOf("a", "b", "c", "d", "e"), keys)
        assertEquals(listOf("1", "\"x\\\"y\"", "[1,2]", "true", "null"), raws)
        assertEquals("x\"y", r.get("b").str)
        val arr = Gjson.parse("[10, 20 ,30]").array()
        assertEquals(listOf(10.0, 20.0, 30.0), arr.map { it.num })
        val single = Gjson.parse("5")
        var count = 0
        single.forEach { k, v ->
            count++
            assertFalse(k.exists())
            assertEquals(5.0, v.num)
            true
        }
        assertEquals(1, count)
    }

    @Test
    fun resultConversions() {
        assertEquals("1.5", Gjson.parse("1.50").string())
        assertEquals("100", Gjson.parse("100").string())
        assertEquals("100", Gjson.parse("1e2").string())
        assertEquals(true, Gjson.parse("\"TRUE\"").bool())
        assertEquals(12L, Gjson.parse("\"12\"").int())
        assertEquals(2.5, Gjson.parse("\"2.5\"").float())
        // strings are byte strings: \u escapes unescape to UTF-8 bytes
        assertEquals(bytes("\u00e9\uD83D\uDE00"), Gjson.parse("\"\\u00e9\\ud83d\\ude00\"").str)
    }

    @Test
    fun ugly() {
        assertEquals("""{"a":[1,2],"b":"x y \" z"}""", Pretty.ugly("{ \"a\" : [ 1, 2 ],\n \"b\": \"x y \\\" z\" }"))
    }

    @Test
    fun floats() {
        assertEquals("1", JsonFloat.format(1.0))
        assertEquals("-0", JsonFloat.format(-0.0))
        assertEquals("0", JsonFloat.format(0.0))
        assertEquals("1000000000000000000000", JsonFloat.format(1e21))
        assertEquals("0.000001", JsonFloat.format(0.000001))
        assertEquals("33.0001", JsonFloat.format(33.0001))
        assertEquals("-112.0001", JsonFloat.format(-112.0001))
        assertEquals("0.1", JsonFloat.format(0.1))
        assertEquals("0.30000000000000004", JsonFloat.format(0.1 + 0.2))
        assertEquals("123456789012345680000", JsonFloat.format(1.2345678901234568e20))
        assertEquals("NaN", JsonFloat.format(Double.NaN))
        assertEquals("+Inf", JsonFloat.format(Double.POSITIVE_INFINITY))
        assertTrue(JsonFloat.format(Double.MIN_VALUE).endsWith("5"))
        assertEquals("1.7976931348623157e308".toDouble(), JsonFloat.format(Double.MAX_VALUE).toDouble())
    }

    @Test
    fun strings() {
        assertEquals(
            bytes("\"a\\\"b\\\\c\\n\\u0001\\u003c\\u003e\\u0026é\\u2028\\ufffd\""),
            JsonString.quote(bytes("a\"b\\c\n\u0001<>&é\u2028") + "\u00ff"),
        )
    }

    @Test
    fun match() {
        assertTrue(Match.match("hello", "*"))
        assertTrue(Match.match("hello", "h*o"))
        assertTrue(Match.match("hello", "h?llo"))
        assertFalse(Match.match("hello", "h?lo"))
        assertTrue(Match.match("truck12", "truck*"))
        assertFalse(Match.match("car1", "truck*"))
        assertTrue(Match.match("a*b", "a\\*b"))
        assertFalse(Match.match("axb", "a\\*b"))
        assertTrue(Match.match("abcabc", "*abc"))
        assertTrue(Match.matchNoCase("HeLLo", "hello"))
        assertTrue(Match.isPattern("ab*"))
        assertFalse(Match.isPattern("ab\\*"))
    }

    /** UTF-8 encodes [s] into a byte string (one char per byte). */
    private fun bytes(s: String): String = String(s.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
}
