package io.jtile38.server

import redis.clients.jedis.Jedis
import redis.clients.jedis.commands.ProtocolCommand
import java.io.File
import java.io.InputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** MONITOR (#39), WebSocket connections (#13) and legacy AOF migration (#41). */
class MonitorWebSocketMigrateTest {
    private fun cmd(name: String) = ProtocolCommand { name.toByteArray() }

    private fun Jedis.run(vararg args: String): Any? = sendCommand(cmd(args[0]), *args.drop(1).toTypedArray())

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun resp(vararg args: String): ByteArray =
        ("*${args.size}\r\n" + args.joinToString("") { "$" + it.length + "\r\n" + it + "\r\n" }).toByteArray(Charsets.ISO_8859_1)

    @Test
    fun monitor() {
        val dir = Files.createTempDirectory("jtile38-mon").toString()
        Server(ServerOptions(port = 0, dir = dir)).start().use { s ->
            Socket("127.0.0.1", s.port).use { m ->
                m.soTimeout = 5000
                m.getOutputStream().write(resp("MONITOR"))
                assertEquals("+OK", readLine(m.getInputStream()))
                Jedis("127.0.0.1", s.port).use { j ->
                    j.run("SET", "fleet", "t1", "POINT", "33", "-112")
                    j.run("PING")
                    j.run("CONFIG", "GET", "requirepass")
                    j.run("SET", "k", "a\"b\né", "STRING", "v")
                }
                val l1 = readLine(m.getInputStream())
                assertTrue(Regex("""^\+\d+\.\d{6} \[0 127\.0\.0\.1:\d+] "SET" "fleet" "t1" "POINT" "33" "-112"$""").matches(l1), l1)
                // PING and CONFIG are not sent; the args are quoted like Go's strconv.Quote
                // the é arrives as its two UTF-8 bytes (read here one char per byte) and stays unescaped
                val utf8E = String("é".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
                val l2 = readLine(m.getInputStream())
                assertTrue(l2.endsWith(""""SET" "k" "a\"b\n$utf8E" "STRING" "v""""), l2)
                m.getOutputStream().write(resp("QUIT"))
                assertEquals("+OK", readLine(m.getInputStream()))
            }
        }
    }

    private fun readFrame(input: InputStream): String {
        assertEquals(0x81, input.read())
        var n = input.read()
        if (n == 126) n = (input.read() shl 8) or input.read()
        return String(input.readNBytes(n), Charsets.ISO_8859_1)
    }

    private fun upgrade(
        port: Int,
        path: String,
    ): Socket {
        val sock = Socket("127.0.0.1", port)
        sock.soTimeout = 5000
        sock.getOutputStream().write(
            (
                "GET /$path HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n"
            ).toByteArray(),
        )
        val input = sock.getInputStream()
        assertEquals("HTTP/1.1 101 Switching Protocols", readLine(input))
        val headers = generateSequence { readLine(input).takeIf { it.isNotEmpty() } }.toList()
        assertTrue("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=" in headers, headers.toString())
        return sock
    }

    @Test
    fun webSocket() {
        val dir = Files.createTempDirectory("jtile38-ws").toString()
        Server(ServerOptions(port = 0, dir = dir)).start().use { s ->
            Jedis("127.0.0.1", s.port).use { j -> j.run("SET", "fleet", "t1", "POINT", "33", "-112") }
            upgrade(s.port, "get+fleet+t1").use { ws ->
                val reply = readFrame(ws.getInputStream())
                assertTrue(reply.startsWith("""{"ok":true,"object":{"type":"Point","coordinates":[-112,33]},"elapsed":"""), reply)
                assertEquals(-1, ws.getInputStream().read()) // closed after the reply
            }
            upgrade(s.port, "nearby+fleet+fence+point+33+-112+1000").use { ws ->
                assertEquals("\$23\r\n{\"ok\":true,\"live\":true}\r\n", readFrame(ws.getInputStream()))
                Jedis("127.0.0.1", s.port).use { j -> j.run("SET", "fleet", "t2", "POINT", "33", "-112") }
                assertTrue(readFrame(ws.getInputStream()).contains("\"detect\":\"enter\""))
            }
        }
    }

    private fun legacy(vararg lines: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (l in lines) {
            val b = l.toByteArray(Charsets.ISO_8859_1)
            val sz =
                ByteBuffer
                    .allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(b.size)
                    .array()
            out.write(sz)
            out.write(b)
            out.write(sz)
            out.write(0)
        }
        return out.toByteArray()
    }

    @Test
    fun migratesLegacyAof() {
        val data = legacy("set fleet t1 point 33 -112", """set fleet t2 object {"type":"Point", "coordinates":[1, 2]}""")
        val dir = Files.createTempDirectory("jtile38-migrate").toFile()
        File(dir, "aof").writeBytes(data)
        Server(ServerOptions(port = 0, dir = dir.path)).start().use { s ->
            Jedis("127.0.0.1", s.port).use { j ->
                assertEquals("""{"type":"Point","coordinates":[1,2]}""", String(j.run("GET", "fleet", "t2") as ByteArray))
            }
        }
        assertTrue(File(dir, "appendonly.aof").exists())
        for ((bytes, err) in listOf(data.copyOf(data.size - 1) to "unexpected EOF", data.copyOfRange(1, data.size) to "unexpected EOF")) {
            val d = Files.createTempDirectory("jtile38-migrate").toFile()
            File(d, "aof").writeBytes(bytes)
            assertEquals(err, assertFailsWith<Exception> { Server(ServerOptions(port = 0, dir = d.path)).start().close() }.message)
        }
        val corrupt = data.copyOf()
        corrupt[4 + "set fleet t1 point 33 -112".length + 4] = 1
        val d = Files.createTempDirectory("jtile38-migrate").toFile()
        File(d, "aof").writeBytes(corrupt)
        assertEquals(
            "corrupted aof file",
            assertFailsWith<Exception> { Server(ServerOptions(port = 0, dir = d.path)).start().close() }.message,
        )
    }
}
