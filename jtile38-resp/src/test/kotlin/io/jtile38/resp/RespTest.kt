package io.jtile38.resp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RespTest {
    private fun read(s: String) = s.toByteArray(Charsets.ISO_8859_1).let { CommandReader.readNextCommand(it, 0, it.size) }

    @Test
    fun redisMultibulk() {
        val c = read("*3\r\n\$3\r\nSET\r\n\$5\r\nfleet\r\n\$0\r\n\r\nrest")!!
        assertEquals(listOf("SET", "fleet", ""), c.args)
        assertEquals(CommandKind.REDIS, c.kind)
        assertEquals("*3\r\n\$3\r\nSET\r\n\$5\r\nfleet\r\n\$0\r\n\r\n".length, c.consumed)
    }

    @Test
    fun redisIncomplete() {
        assertNull(read("*2\r\n\$3\r\nGET\r\n\$5\r\nfle"))
        assertNull(read("*2\r\n\$3\r\nGET\r\n"))
        assertNull(read("*2\r"))
    }

    @Test
    fun redisErrors() {
        assertFailsWith<ProtocolException> { read("*x\r\n") }
        assertFailsWith<ProtocolException> { read("*1\r\n+GET\r\n") }
        assertFailsWith<ProtocolException> { read("*1\r\n\$3\r\nGETX\r\n") }
    }

    @Test
    fun telnet() {
        val c = read("set fleet truck1 point 33 -115\r\n")!!
        assertEquals(listOf("set", "fleet", "truck1", "point", "33", "-115"), c.args)
        assertEquals(CommandKind.TELNET, c.kind)
        assertEquals(listOf("set", "k", "a b", "it's\n"), read("set k \"a b\" 'it\\'s\\n'\n")!!.args)
        assertFailsWith<ProtocolException> { read("set k \"a b\n") }
        assertNull(read("ping"))
        assertEquals(emptyList(), read("\r\n")!!.args)
    }

    @Test
    fun native() {
        val line = "SET fleet truck1 OBJECT {\"type\":\"Point\",\"coordinates\":[1, 2]}"
        val c = read("$" + line.length + " " + line + "\r\n")!!
        assertEquals(listOf("SET", "fleet", "truck1", "OBJECT", "{\"type\":\"Point\",\"coordinates\":[1, 2]}"), c.args)
        assertEquals(CommandKind.TILE38, c.kind)
        assertEquals(listOf("set", "k", "id", "string", "hello world"), CommandReader.splitNativeLine("set k id string \"hello world\""))
    }

    @Test
    fun marshal() {
        val v =
            RespValue.array(
                listOf(RespValue.string("a"), RespValue.integer(5), RespValue.NULL, RespValue.OK, RespValue.error("ERR x\ny")),
            )
        assertEquals("*5\r\n\$1\r\na\r\n:5\r\n\$-1\r\n+OK\r\n-ERR x y\r\n", String(v.marshal(), Charsets.ISO_8859_1))
        assertEquals("[a 5  OK ERR x y]", v.toString())
    }

    @Test
    fun floats() {
        assertEquals("33.0001", RespValue.formatFloat(33.0001))
        assertEquals("-112", RespValue.formatFloat(-112.0))
        assertEquals("1000000000000000000000", RespValue.formatFloat(1e21))
        assertEquals("0.000001", RespValue.formatFloat(1e-6))
        assertEquals("0", RespValue.formatFloat(0.0))
    }
}

class RespReaderTest {
    @Test
    fun readValues() {
        val data = "+OK\r\n-ERR bad\r\n:42\r\n\$5\r\nhello\r\n\$-1\r\n*2\r\n\$1\r\na\r\n*1\r\n:1\r\n*-1\r\n"
        val rd = RespReader(data.toByteArray(Charsets.ISO_8859_1).inputStream())
        assertEquals(RespValue.SimpleString("OK"), rd.readValue())
        assertEquals(RespValue.Error("ERR bad"), rd.readValue())
        assertEquals(RespValue.Integer(42), rd.readValue())
        assertEquals(RespValue.BulkString("hello"), rd.readValue())
        assertEquals(RespValue.BulkString(null), rd.readValue())
        assertEquals("[a [1]]", rd.readValue().toString())
        assertEquals(RespValue.Array(null), rd.readValue())
        assertFailsWith<java.io.EOFException> { rd.readValue() }
    }
}
