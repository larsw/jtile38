package io.jtile38.server

import redis.clients.jedis.Jedis
import redis.clients.jedis.commands.ProtocolCommand
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AOFSHRINK rewrites the AOF to the minimal commands that recreate the data (issue #8). */
class AofShrinkTest {
    private fun cmd(name: String) = ProtocolCommand { name.toByteArray() }

    private fun Jedis.run(vararg args: String): Any? = sendCommand(cmd(args[0]), *args.drop(1).toTypedArray())

    private fun aofCommands(file: File): List<List<String>> {
        val data = file.readBytes()
        val out = ArrayList<List<String>>()
        var off = 0
        while (off < data.size) {
            val c =
                io.jtile38.resp.CommandReader
                    .readNextCommand(data, off, data.size - off)!!
            off += c.consumed
            out.add(c.args)
        }
        return out
    }

    private fun waitForShrink(s: Server) {
        val deadline = System.nanoTime() + 10_000_000_000L
        do Thread.sleep(20) while (s.shrinking && System.nanoTime() < deadline)
    }

    @Test
    fun shrinksAndReloads() {
        val dir = Files.createTempDirectory("jtile38-shrink").toString()
        val aof = File(dir, "appendonly.aof")
        Server(ServerOptions(port = 0, dir = dir)).start().use { s ->
            Jedis("127.0.0.1", s.port).use { j ->
                for (i in 1..50) j.run("SET", "fleet", "t1", "FIELD", "speed", "$i", "POINT", "33", "-115")
                j.run("SET", "fleet", "t2", "FIELD", "speed", "0", "FIELD", "name", "bob", "POINT", "34", "-116")
                j.run("SET", "strs", "s1", "STRING", "hello")
                j.run("SET", "gone", "x", "POINT", "1", "1")
                j.run("DEL", "gone", "x")
                j.run("SETCHAN", "c1", "META", "b", "2", "META", "a", "1", "NEARBY", "fleet", "FENCE", "POINT", "33", "-115", "1000")
                assertEquals("OK", String(j.run("AOFSHRINK") as ByteArray))
                waitForShrink(s)
                // the AOF still receives writes after the swap
                j.run("SET", "fleet", "t3", "POINT", "35", "-117")
            }
        }
        assertEquals(
            listOf(
                listOf("set", "fleet", "t1", "field", "speed", "50", "object", """{"type":"Point","coordinates":[-115,33]}"""),
                // zero-valued fields are left out
                listOf("set", "fleet", "t2", "field", "name", "\"bob\"", "object", """{"type":"Point","coordinates":[-116,34]}"""),
                listOf("set", "strs", "s1", "string", "hello"),
                listOf("setchan", "c1", "meta", "a", "1", "meta", "b", "2", "NEARBY", "fleet", "FENCE", "POINT", "33", "-115", "1000"),
                listOf("SET", "fleet", "t3", "POINT", "35", "-117"),
            ),
            aofCommands(aof),
        )
        assertTrue(!File(dir, "appendonly.aof-shrink").exists() && !File(dir, "appendonly.aof-bak").exists())
        Server(ServerOptions(port = 0, dir = dir)).start().use { s ->
            Jedis("127.0.0.1", s.port).use { j ->
                assertEquals("50", String(j.run("FGET", "fleet", "t1", "speed") as ByteArray))
                assertEquals("bob", String(j.run("FGET", "fleet", "t2", "name") as ByteArray))
                assertEquals("hello", String(j.run("GET", "strs", "s1") as ByteArray))
                assertEquals(3L, (j.run("SCAN", "fleet", "COUNT") as Long))
                assertEquals(1, (j.run("CHANS", "*") as List<*>).size)
            }
        }
    }

    @Test
    fun devCommandsNeedDevMode() {
        val dir = Files.createTempDirectory("jtile38-dev").toString()
        Server(ServerOptions(port = 0, dir = dir)).start().use { s ->
            Jedis("127.0.0.1", s.port).use { j ->
                val e = runCatching { j.run("MASSINSERT", "1", "10") }.exceptionOrNull()
                assertEquals("ERR unknown command 'MASSINSERT'", e?.message)
            }
        }
        Server(ServerOptions(port = 0, dir = dir, devMode = true)).start().use { s ->
            Jedis("127.0.0.1", s.port).use { j ->
                assertEquals("OK", String(j.run("MASSINSERT", "2", "10") as ByteArray))
                // every 8th object is a string, some have a TTL
                assertEquals(10L, j.run("SCAN", "mi:1", "COUNT"))
                assertEquals("OK", String(j.run("SLEEP", "0.01") as ByteArray))
            }
        }
    }
}
