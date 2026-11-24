package io.jtile38.server

import redis.clients.jedis.Jedis
import redis.clients.jedis.commands.ProtocolCommand
import redis.clients.jedis.exceptions.JedisDataException
import java.net.Socket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** AUTH and requirepass (issue #10). */
class AuthTest {
    private fun cmd(name: String) = ProtocolCommand { name.toByteArray() }

    private fun Jedis.run(vararg args: String): String =
        when (val r = sendCommand(cmd(args[0]), *args.drop(1).toTypedArray())) {
            is ByteArray -> String(r)
            else -> r.toString()
        }

    private fun Jedis.fails(vararg args: String): String = assertFailsWith<JedisDataException> { run(*args) }.message!!

    private fun http(
        port: Int,
        path: String,
        auth: String?,
    ): String =
        Socket("127.0.0.1", port).use { s ->
            val req = "GET /$path HTTP/1.1\r\nHost: x\r\n" + (if (auth != null) "Authorization: $auth\r\n" else "") + "\r\n"
            s.getOutputStream().write(req.toByteArray())
            s
                .getInputStream()
                .readAllBytes()
                .toString(Charsets.ISO_8859_1)
                .substringAfter("\r\n\r\n")
                .trim()
        }

    @Test
    fun requirePass() {
        val dir = Files.createTempDirectory("jtile38-auth").toString()
        Server(ServerOptions(port = 0, dir = dir)).start().use { s ->
            Jedis("127.0.0.1", s.port).use { j ->
                assertEquals("ERR invalid password", j.fails("AUTH", "x"))
                assertEquals("OK", j.run("CONFIG", "SET", "requirepass", "secret"))
                // the connection that set the password must authenticate too
                assertEquals("ERR authentication required", j.fails("GET", "k", "x"))
            }
            Jedis("127.0.0.1", s.port).use { j ->
                assertEquals("PONG", j.run("PING"))
                assertEquals("OK", j.run("HEALTHZ"))
                assertEquals("ERR authentication required", j.fails("SET", "k", "x", "POINT", "1", "1"))
                assertEquals("ERR authentication required", j.fails("TIMEOUT", "1", "GET", "k", "x"))
                assertEquals("ERR invalid password", j.fails("AUTH", "nope"))
                assertEquals("OK", j.run("AUTH", " secret "))
                assertEquals("OK", j.run("SET", "k", "x", "POINT", "1", "1"))
                // a failed AUTH does not log the connection out
                assertEquals("ERR invalid password", j.fails("AUTH", "bad"))
                assertEquals("OK", j.run("SET", "k", "y", "POINT", "2", "2"))
                // like Tile38, CONFIG SET only lasts until CONFIG REWRITE saves it
                assertEquals("OK", j.run("CONFIG", "REWRITE"))
            }
            assertTrue(http(s.port, "get+k+x", null).contains("\"err\":\"authentication required\""))
            assertTrue(http(s.port, "get+k+x", "nope").contains("\"err\":\"invalid password\""))
            assertTrue(http(s.port, "get+k+x", "secret").contains("\"object\":{\"type\":\"Point\",\"coordinates\":[1,1]}"))
        }
        // the rewritten config file keeps the password
        Server(ServerOptions(port = 0, dir = dir)).start().use { s ->
            Jedis("127.0.0.1", s.port).use { j ->
                assertEquals("ERR authentication required", j.fails("GET", "k", "x"))
                assertEquals("OK", j.run("AUTH", "secret"))
                assertEquals("OK", j.run("CONFIG", "SET", "requirepass", ""))
            }
            Jedis("127.0.0.1", s.port).use { j ->
                assertEquals("""{"type":"Point","coordinates":[1,1]}""", j.run("GET", "k", "x"))
            }
        }
    }
}
