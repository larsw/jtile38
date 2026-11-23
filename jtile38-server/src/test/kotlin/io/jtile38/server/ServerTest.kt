package io.jtile38.server

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import redis.clients.jedis.Jedis
import redis.clients.jedis.commands.ProtocolCommand
import redis.clients.jedis.exceptions.JedisDataException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * End-to-end tests over TCP using a plain Redis client (Jedis), like Tile38's own integration tests.
 *
 * Set `TILE38_EXTERNAL_PORT` to run the same assertions against an already running server, e.g. the
 * original Go Tile38, to verify that the expectations match upstream behavior.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServerTest {
    private val externalPort = System.getenv("TILE38_EXTERNAL_PORT")?.toInt()
    private var server: Server? = null
    private var port = 0
    private lateinit var jedis: Jedis

    @BeforeAll
    fun start() {
        if (externalPort != null) {
            port = externalPort
        } else {
            val dir = Files.createTempDirectory("jtile38-test")
            server = Server(ServerOptions(port = 0, dir = dir.toString())).start()
            port = server!!.port
        }
        jedis = Jedis("127.0.0.1", port)
    }

    @AfterAll
    fun stop() {
        jedis.close()
        server?.close()
    }

    @BeforeEach
    fun flush() {
        do_("FLUSHDB")
    }

    private fun cmd(name: String) = ProtocolCommand { name.toByteArray() }

    private fun raw(vararg args: Any): Any? = jedis.sendCommand(cmd(args[0].toString()), *args.drop(1).map { it.toString() }.toTypedArray())

    /** Formats a reply like Tile38's Go tests: strings, integers and `[a b]` arrays. */
    private fun fmt(v: Any?): String =
        when (v) {
            null -> "<nil>"
            is ByteArray -> String(v, Charsets.UTF_8)
            is List<*> -> v.joinToString(" ", "[", "]") { fmt(it) }
            else -> v.toString()
        }

    private fun do_(vararg args: Any): String = fmt(raw(*args))

    private fun expect(
        expected: String,
        vararg args: Any,
    ) = assertEquals(expected, do_(*args), args.joinToString(" "))

    @Test
    fun pingEcho() {
        expect("PONG", "PING")
        expect("hello", "ECHO", "hello")
    }

    @Test
    fun setGetPoint() {
        expect("OK", "SET", "fleet", "truck1", "POINT", 33.0001, -112.0001)
        expect("[33.0001 -112.0001]", "GET", "fleet", "truck1", "POINT")
        expect("{\"type\":\"Point\",\"coordinates\":[-112.0001,33.0001]}", "GET", "fleet", "truck1")
        expect("OK", "SET", "fleet", "truck2", "POINT", 33, -112, 10)
        expect("[33 -112 10]", "GET", "fleet", "truck2", "POINT")
        expect("9tb7er", "GET", "fleet", "truck1", "HASH", 6)
        expect("[[33.0001 -112.0001] [33.0001 -112.0001]]", "GET", "fleet", "truck1", "BOUNDS")
        expect("<nil>", "GET", "fleet", "nope")
        expect("<nil>", "GET", "nokey", "nope")
    }

    @Test
    fun fieldsAndWithFields() {
        expect("OK", "SET", "fleet", "truck1", "FIELD", "speed", 90, "POINT", 33, -112)
        expect("[{\"type\":\"Point\",\"coordinates\":[-112,33]} [speed 90]]", "GET", "fleet", "truck1", "WITHFIELDS")
        expect("1", "FSET", "fleet", "truck1", "speed", 100)
        expect("0", "FSET", "fleet", "truck1", "speed", 100)
        expect("100", "FGET", "fleet", "truck1", "speed")
        expect("1", "FEXISTS", "fleet", "truck1", "speed")
        expect("0", "FEXISTS", "fleet", "truck1", "weight")
    }

    @Test
    fun objectsBoundsStrings() {
        val poly = "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[10,0],[10,10],[0,10],[0,0]]]}"
        expect("OK", "SET", "areas", "a", "OBJECT", poly)
        expect(poly, "GET", "areas", "a")
        expect("OK", "SET", "areas", "b", "BOUNDS", 1, 2, 3, 4)
        expect("[[1 2] [3 4]]", "GET", "areas", "b", "BOUNDS")
        expect("[[0 0] [10 10]]", "BOUNDS", "areas")
        expect("OK", "SET", "strings", "s", "STRING", "hello world")
        expect("hello world", "GET", "strings", "s")
        expect("hash", "TYPE", "areas")
        expect("none", "TYPE", "nokey")
        expect("[areas strings]", "KEYS", "*")
        expect("[strings]", "KEYS", "str*")
    }

    @Test
    fun delDropRename() {
        expect("OK", "SET", "fleet", "truck1", "POINT", 33, -112)
        expect("OK", "SET", "fleet", "truck2", "POINT", 34, -113)
        expect("1", "DEL", "fleet", "truck1")
        expect("0", "DEL", "fleet", "truck1")
        expect("OK", "RENAME", "fleet", "fleet2")
        expect("[fleet2]", "KEYS", "*")
        expect("1", "DROP", "fleet2")
        expect("0", "DROP", "fleet2")
        expect("OK", "SET", "fleet", "a1", "POINT", 1, 1)
        expect("OK", "SET", "fleet", "a2", "POINT", 1, 1)
        expect("OK", "SET", "fleet", "b1", "POINT", 1, 1)
        expect("2", "PDEL", "fleet", "a*")
        expect("[0 [b1]]", "SCAN", "fleet", "IDS")
    }

    @Test
    fun nxXx() {
        expect("OK", "SET", "fleet", "truck1", "NX", "POINT", 33, -112)
        expect("<nil>", "SET", "fleet", "truck1", "NX", "POINT", 33, -112)
        expect("<nil>", "SET", "fleet", "truck2", "XX", "POINT", 33, -112)
        expect("OK", "SET", "fleet", "truck1", "XX", "POINT", 34, -112)
    }

    @Test
    fun expireTtlPersist() {
        expect("OK", "SET", "fleet", "truck1", "EX", 10, "POINT", 33, -112)
        val ttl = do_("TTL", "fleet", "truck1").toInt()
        assertTrue(ttl in 9..10, "ttl $ttl")
        expect("1", "PERSIST", "fleet", "truck1")
        expect("-1", "TTL", "fleet", "truck1")
        expect("1", "EXPIRE", "fleet", "truck1", 0.1)
        Thread.sleep(400)
        expect("<nil>", "GET", "fleet", "truck1")
        expect("-2", "TTL", "fleet", "truck1")
    }

    @Test
    fun scanAndSearch() {
        for (i in 1..5) expect("OK", "SET", "fleet", "truck$i", "FIELD", "speed", i * 10, "POINT", 33 + i, -112)
        expect("5", "SCAN", "fleet", "COUNT")
        expect("[0 [truck1 truck2 truck3 truck4 truck5]]", "SCAN", "fleet", "IDS")
        expect("[0 [truck5 truck4 truck3 truck2 truck1]]", "SCAN", "fleet", "DESC", "IDS")
        expect("[2 [truck1 truck2]]", "SCAN", "fleet", "LIMIT", 2, "IDS")
        expect("[4 [truck3 truck4]]", "SCAN", "fleet", "CURSOR", 2, "LIMIT", 2, "IDS")
        expect("[0 [truck2 truck3]]", "SCAN", "fleet", "WHERE", "speed", 20, 30, "IDS")
        expect("[0 [truck3]]", "SCAN", "fleet", "MATCH", "truck3", "IDS")
        expect("[0 [truck1 truck5]]", "SCAN", "fleet", "WHEREIN", "speed", 2, 10, 50, "IDS")
        expect("OK", "SET", "words", "1", "STRING", "banana")
        expect("OK", "SET", "words", "2", "STRING", "apple")
        expect("[0 [[2 apple] [1 banana]]]", "SEARCH", "words")
        expect("[0 [[1 banana]]]", "SEARCH", "words", "MATCH", "b*")
    }

    @Test
    fun nearbyWithinIntersects() {
        expect("OK", "SET", "fleet", "t1", "POINT", 33.0, -112.0)
        expect("OK", "SET", "fleet", "t2", "POINT", 33.1, -112.0)
        expect("OK", "SET", "fleet", "t3", "POINT", 35.0, -112.0)
        expect("[0 [t1 t2 t3]]", "NEARBY", "fleet", "IDS", "POINT", 33.0, -112.0)
        expect("[0 [t1 t2]]", "NEARBY", "fleet", "IDS", "POINT", 33.0, -112.0, 20000)
        expect("[0 [[t2 0]]]", "NEARBY", "fleet", "DISTANCE", "IDS", "POINT", 33.1, -112.0, 1)
        expect("[0 [t2 t1]]", "WITHIN", "fleet", "IDS", "BOUNDS", 32.9, -112.1, 33.2, -111.9)
        expect("[0 [t1]]", "WITHIN", "fleet", "IDS", "CIRCLE", 33.0, -112.0, 1000)
        expect(
            "[0 [t3]]",
            "INTERSECTS",
            "fleet",
            "IDS",
            "OBJECT",
            "{\"type\":\"Polygon\",\"coordinates\":[[[-113,34],[-111,34],[-111,36],[-113,36],[-113,34]]]}",
        )
        expect("2", "WITHIN", "fleet", "COUNT", "BOUNDS", 32.9, -112.1, 33.2, -111.9)
        expect("[0 [t3]]", "WITHIN", "fleet", "IDS", "GET", "fleet", "t3")
    }

    @Test
    fun errors() {
        val e = assertFailsWith<JedisDataException> { raw("GET", "fleet") }
        assertEquals("ERR wrong number of arguments for 'get' command", e.message)
        val e2 = assertFailsWith<JedisDataException> { raw("SET", "fleet", "x", "POINT", "a", "1") }
        assertEquals("ERR invalid argument 'a'", e2.message)
        val e3 = assertFailsWith<JedisDataException> { raw("NOPE") }
        assertEquals("ERR unknown command 'NOPE'", e3.message)
    }

    @Test
    fun jsonOutput() {
        expect("OK", "SET", "fleet", "truck1", "FIELD", "speed", 90, "POINT", 33, -112)
        val switched = do_("OUTPUT", "json")
        try {
            assertTrue(switched.startsWith("""{"ok":true,"elapsed":"""), switched)
            val get = do_("GET", "fleet", "truck1", "WITHFIELDS")
            assertTrue(
                get.matches(
                    Regex("""\{"ok":true,"object":\{"type":"Point","coordinates":\[-112,33]},"fields":\{"speed":90},"elapsed":"[^"]+"}"""),
                ),
                get,
            )
            val scan = do_("SCAN", "fleet", "POINTS")
            assertTrue(
                scan.startsWith(
                    """{"ok":true,"fields":["speed"],"points":[{"id":"truck1","point":{"lat":33,"lon":-112},"fields":[90]}],"count":1,"cursor":0,"elapsed":""",
                ),
                scan,
            )
            val err = do_("GET", "fleet", "nope")
            assertTrue(err.startsWith("""{"ok":false,"err":"id not found","elapsed":"""), err)
        } finally {
            expect("OK", "OUTPUT", "resp")
        }
    }

    @Test
    fun telnetAndNativeProtocols() {
        Socket("127.0.0.1", port).use { s ->
            val inp = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
            s.getOutputStream().write("SET fleet truck1 POINT 33 -112\r\n".toByteArray())
            assertEquals("+OK", inp.readLine())
        }
        // The output type is fixed by the first command on a connection, so use a fresh one.
        Socket("127.0.0.1", port).use { s ->
            val inp = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
            val line = "GET fleet truck1"
            s.getOutputStream().write("$${line.length} $line\r\n".toByteArray())
            val resp = inp.readLine()
            assertTrue(
                resp.matches(Regex("""\$\d+ \{"ok":true,"object":\{"type":"Point","coordinates":\[-112,33]},"elapsed":"[^"]+"}""")),
                resp,
            )
        }
    }

    @Test
    fun http() {
        expect("OK", "SET", "fleet", "truck1", "POINT", 33, -112)
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write("GET /get+fleet+truck1 HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val text = s.getInputStream().readAllBytes().toString(Charsets.ISO_8859_1)
            assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"), text)
            assertTrue(text.contains("{\"ok\":true,\"object\":{\"type\":\"Point\",\"coordinates\":[-112,33]}"), text)
        }
    }

    @Test
    fun aofReplay() {
        assumeTrue(externalPort == null, "needs an embedded server")
        val dir = Files.createTempDirectory("jtile38-aof")
        Server(ServerOptions(port = 0, dir = dir.toString())).start().use { s ->
            Jedis("127.0.0.1", s.port).use { j ->
                j.sendCommand(cmd("SET"), "fleet", "truck1", "FIELD", "speed", "5", "POINT", "33", "-112")
                j.sendCommand(cmd("SET"), "fleet", "truck2", "POINT", "34", "-113")
                j.sendCommand(cmd("DEL"), "fleet", "truck2")
                j.sendCommand(cmd("SETCHAN"), "c1", "NEARBY", "fleet", "FENCE", "POINT", "33", "-112", "1000")
                j.sendCommand(cmd("SETCHAN"), "c2", "NEARBY", "fleet", "FENCE", "POINT", "33", "-112", "1000")
                j.sendCommand(cmd("DELCHAN"), "c2")
            }
        }
        Server(ServerOptions(port = 0, dir = dir.toString())).start().use { s ->
            Jedis("127.0.0.1", s.port).use { j ->
                assertEquals(
                    "[0 [[truck1 {\"type\":\"Point\",\"coordinates\":[-112,33]} [speed 5]]]]",
                    fmt(j.sendCommand(cmd("SCAN"), "fleet")),
                )
                assertEquals(
                    "[[c1 fleet [local://c1] [NEARBY fleet FENCE POINT 33 -112 1000] []]]",
                    fmt(j.sendCommand(cmd("CHANS"), "*")),
                )
            }
        }
    }
}
