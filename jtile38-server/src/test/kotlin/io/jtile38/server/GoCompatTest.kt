package io.jtile38.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GoCompatTest {
    @Test
    fun duration() {
        assertEquals("0s", GoCompat.duration(0))
        assertEquals("999ns", GoCompat.duration(999))
        assertEquals("1.5Âµs", GoCompat.duration(1_500))
        assertEquals("13.504ms", GoCompat.duration(13_504_000))
        assertEquals("1s", GoCompat.duration(1_000_000_000))
        assertEquals("1m30.25s", GoCompat.duration(90_250_000_000))
        assertEquals("2h0m0s", GoCompat.duration(7_200_000_000_000))
    }

    @Test
    fun parseFloat() {
        assertEquals(33.5, GoCompat.parseFloat("33.5"))
        assertEquals(-112.0, GoCompat.parseFloat("-112"))
        assertEquals(Double.POSITIVE_INFINITY, GoCompat.parseFloat("+Inf"))
        assertNull(GoCompat.parseFloat("1.0d"))
        assertNull(GoCompat.parseFloat(" 1"))
        assertNull(GoCompat.parseFloat("1e400"))
    }

    @Test
    fun jsonStrings() {
        assertEquals("\"abc\"", GoCompat.jsonString("abc"))
        assertEquals("\"a\\\"b\\n\"", GoCompat.jsonString("a\"b\n"))
        assertEquals("\"\\u003ca\\u003e\"", GoCompat.jsonMarshalString("<a>"))
        // UTF-8 "é" as a byte string passes through; an invalid byte becomes �.
        assertEquals("\"Ã©\\ufffd\"", GoCompat.jsonMarshalString("Ã©ÿ"))
    }

    @Test
    fun glob() {
        assertEquals(true, Glob.match("truck*", "truck1"))
        assertEquals(false, Glob.match("truck?", "truck12"))
        assertEquals(true, Glob.match("t[a-z]uck1", "truck1"))
        val g = Glob.parse("truck*", false)
        assertEquals(listOf("truck", "trucl"), g.limits.toList())
    }
}
