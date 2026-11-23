package io.jtile38.server

import com.sun.net.httpserver.HttpServer
import io.jtile38.json.Gjson
import io.jtile38.resp.RespReader
import io.jtile38.resp.RespValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Geofencing tests: live fences, channels/pub-sub, roaming and webhooks.
 * Set `TILE38_EXTERNAL_PORT` to run them against another server (e.g. the original Tile38).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeofenceTest {
    private val externalPort = System.getenv("TILE38_EXTERNAL_PORT")?.toInt()
    private var server: Server? = null
    private var port = 0

    @BeforeAll
    fun start() {
        port = externalPort ?: Server(ServerOptions(port = 0, dir = Files.createTempDirectory("jtile38-fence").toString()))
            .start()
            .also { server = it }
            .port
    }

    @AfterAll
    fun stop() {
        server?.close()
    }

    @BeforeEach
    fun flush() {
        Conn().use { it.cmd("FLUSHDB") }
    }

    /** A raw RESP connection. */
    private inner class Conn : AutoCloseable {
        val socket = Socket("127.0.0.1", port).also { it.soTimeout = 3000 }
        val reader = RespReader(socket.getInputStream())

        fun send(vararg args: Any) {
            val sb = StringBuilder("*${args.size}\r\n")
            for (a in args) {
                val s = a.toString()
                sb
                    .append("$")
                    .append(s.toByteArray().size)
                    .append("\r\n")
                    .append(s)
                    .append("\r\n")
            }
            socket.getOutputStream().write(sb.toString().toByteArray())
        }

        fun read(): RespValue = reader.readValue()

        fun cmd(vararg args: Any): RespValue {
            send(*args)
            return read()
        }

        /** Next live fence message (a JSON bulk string). */
        fun next(): String = (read() as RespValue.BulkString).value!!

        override fun close() = socket.close()
    }

    private fun assertMsg(
        json: String,
        vararg expect: Pair<String, String>,
    ) {
        for ((path, value) in expect) assertEquals(value, Gjson.get(json, path).string(), "$path in $json")
    }

    @Test
    fun liveFenceBasic() {
        Conn().use { live ->
            assertEquals(RespValue.OK, live.cmd("NEARBY", "mykey", "FENCE", "POINT", 33, -115, 5000))
            Conn().use { c ->
                assertEquals(RespValue.OK, c.cmd("SET", "mykey", "myid1", "FIELD", "speed", 5, "POINT", 33, -115))
                val enter = live.next()
                assertMsg(
                    enter,
                    "command" to "set",
                    "detect" to "enter",
                    "key" to "mykey",
                    "id" to "myid1",
                    "object.type" to "Point",
                    "object.coordinates" to "[-115,33]",
                    "fields.speed" to "5",
                )
                assertTrue(Gjson.get(enter, "time").exists() && Gjson.get(enter, "group").string().length == 24, enter)
                val inside = live.next()
                assertMsg(inside, "detect" to "inside", "id" to "myid1")
                assertEquals(Gjson.get(enter, "group").string(), Gjson.get(inside, "group").string())

                c.cmd("SET", "mykey", "myid1", "POINT", 34, -115)
                assertMsg(live.next(), "detect" to "exit", "object.coordinates" to "[-115,34]")
                assertMsg(live.next(), "detect" to "outside")
                c.cmd("SET", "mykey", "myid1", "POINT", 34.1, -115)
                assertMsg(live.next(), "detect" to "outside")

                c.cmd("SET", "mykey", "myid2", "POINT", 33, -115)
                assertMsg(live.next(), "detect" to "enter", "id" to "myid2")
                assertMsg(live.next(), "detect" to "inside", "id" to "myid2")
                c.cmd("DEL", "mykey", "myid2")
                val del = live.next()
                assertMsg(del, "command" to "del", "key" to "mykey", "id" to "myid2")
                assertTrue(!Gjson.get(del, "detect").exists(), del)

                c.cmd("SET", "mykey", "a1", "POINT", 33, -115)
                live.next()
                live.next()
                c.cmd("SET", "mykey", "a2", "POINT", 33, -115)
                live.next()
                live.next()
                assertEquals(RespValue.Integer(2), c.cmd("PDEL", "mykey", "a*"))
                assertMsg(live.next(), "command" to "del", "id" to "a1")
                assertMsg(live.next(), "command" to "del", "id" to "a2")
                c.cmd("DROP", "mykey")
                assertMsg(live.next(), "command" to "drop", "key" to "mykey")
            }
        }
    }

    @Test
    fun detectAndCommandsFilters() {
        Conn().use { live ->
            assertEquals(
                RespValue.OK,
                live.cmd("WITHIN", "fleet", "FENCE", "DETECT", "enter,exit,cross", "COMMANDS", "set", "BOUNDS", 0, 0, 10, 10),
            )
            Conn().use { c ->
                c.cmd("SET", "fleet", "t", "POINT", 5, -5)
                c.cmd("SET", "fleet", "t", "POINT", 5, 15) // crosses the box from west to east
                assertMsg(live.next(), "detect" to "cross", "id" to "t")
                c.cmd("SET", "fleet", "t", "POINT", 5, 5)
                assertMsg(live.next(), "detect" to "enter")
                c.cmd("SET", "fleet", "t", "POINT", 6, 6) // inside: filtered out
                c.cmd("SET", "fleet", "t", "POINT", 20, 20)
                assertMsg(live.next(), "detect" to "exit", "object.coordinates" to "[20,20]")
                c.cmd("DEL", "fleet", "t") // del is filtered by COMMANDS
                c.cmd("SET", "fleet", "u", "POINT", 1, 1)
                assertMsg(live.next(), "detect" to "enter", "id" to "u")
            }
        }
    }

    @Test
    fun channelsAndPubSub() {
        Conn().use { c ->
            assertEquals(
                RespValue.Integer(1),
                c.cmd(
                    "SETCHAN",
                    "mychan",
                    "META",
                    "b",
                    "2",
                    "META",
                    "a",
                    "1",
                    "NEARBY",
                    "mykey",
                    "FENCE",
                    "DETECT",
                    "enter",
                    "POINT",
                    33,
                    -115,
                    5000,
                ),
            )
            // same definition again: no change
            assertEquals(
                RespValue.Integer(0),
                c.cmd(
                    "SETCHAN",
                    "mychan",
                    "META",
                    "b",
                    "2",
                    "META",
                    "a",
                    "1",
                    "NEARBY",
                    "mykey",
                    "FENCE",
                    "DETECT",
                    "enter",
                    "POINT",
                    33,
                    -115,
                    5000,
                ),
            )
            assertEquals(
                "[[mychan mykey [local://mychan] [NEARBY mykey FENCE DETECT enter POINT 33 -115 5000] [a 1 b 2]]]",
                c.cmd("CHANS", "*").toString(),
            )
            Conn().use { sub ->
                assertEquals("[subscribe mychan 1]", sub.cmd("SUBSCRIBE", "mychan").toString())
                assertEquals("[psubscribe my* 2]", sub.cmd("PSUBSCRIBE", "my*").toString())
                c.cmd("SET", "mykey", "t1", "POINT", 33, -115)
                val m1 = (sub.read() as RespValue.Array).values!!
                val m2 = (sub.read() as RespValue.Array).values!!
                val byKind = listOf(m1, m2).associateBy { it[0].toString() }
                val msg = byKind["message"]!!
                assertEquals("mychan", msg[1].toString())
                assertMsg(msg[2].toString(), "hook" to "mychan", "detect" to "enter", "meta.a" to "1", "meta.b" to "2", "id" to "t1")
                val pmsg = byKind["pmessage"]!!
                assertEquals(listOf("my*", "mychan"), listOf(pmsg[1].toString(), pmsg[2].toString()))
                assertEquals(RespValue.Integer(2), c.cmd("PUBLISH", "mychan", "hello"))
                assertEquals(
                    setOf("hello"),
                    setOf(
                        (sub.read() as RespValue.Array).values!!.last().toString(),
                        (sub.read() as RespValue.Array).values!!.last().toString(),
                    ),
                )
                assertEquals("[unsubscribe mychan 1]", sub.cmd("UNSUBSCRIBE", "mychan").toString())
                assertEquals("[PONG x]", sub.cmd("PING", "x").toString())
                assertEquals(
                    "ERR only (P)SUBSCRIBE / (P)UNSUBSCRIBE / PING / QUIT allowed in this context",
                    sub.cmd("GET", "a", "b").toString(),
                )
            }
            assertEquals(RespValue.Integer(1), c.cmd("DELCHAN", "mychan"))
            assertEquals(RespValue.Integer(0), c.cmd("DELCHAN", "mychan"))
            assertEquals(RespValue.Integer(0), c.cmd("PUBLISH", "mychan", "nobody"))
        }
    }

    @Test
    fun roaming() {
        Conn().use { live ->
            assertEquals(RespValue.OK, live.cmd("NEARBY", "people", "FENCE", "ROAM", "people", "*", 5000))
            Conn().use { c ->
                c.cmd("SET", "people", "alice", "POINT", 33, -115)
                c.cmd("SET", "people", "bob", "POINT", 33.01, -115)
                val near = live.next()
                assertMsg(
                    near,
                    "command" to "set",
                    "detect" to "roam",
                    "id" to "bob",
                    "nearby.key" to "people",
                    "nearby.id" to "alice",
                    "nearby.meters" to "1111.949",
                )
                c.cmd("SET", "people", "bob", "POINT", 35, -115)
                assertMsg(live.next(), "detect" to "roam", "id" to "bob", "faraway.id" to "alice", "faraway.meters" to "222389.853")
            }
        }
    }

    @Test
    fun webhooks() {
        val received = LinkedBlockingQueue<String>()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/hook") { ex ->
            received.add(ex.requestBody.readAllBytes().toString(Charsets.UTF_8))
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        http.start()
        try {
            val url = "http://127.0.0.1:${http.address.port}/hook"
            Conn().use { c ->
                assertEquals(
                    RespValue.Integer(1),
                    c.cmd("SETHOOK", "myhook", url, "META", "m", "v", "NEARBY", "mykey", "FENCE", "POINT", 33, -115, 5000),
                )
                assertEquals(
                    "[[myhook mykey [$url] [NEARBY mykey FENCE POINT 33 -115 5000] [m v]]]",
                    c.cmd("HOOKS", "*").toString(),
                )
                c.cmd("SET", "mykey", "t1", "POINT", 33, -115)
                val m1 = assertNotNull(received.poll(5, TimeUnit.SECONDS))
                val m2 = assertNotNull(received.poll(5, TimeUnit.SECONDS))
                assertMsg(m1, "hook" to "myhook", "detect" to "enter", "id" to "t1", "meta.m" to "v")
                assertMsg(m2, "hook" to "myhook", "detect" to "inside")
                // a hook with the same name but a different definition replaces it
                assertEquals(
                    RespValue.Integer(1),
                    c.cmd("SETHOOK", "myhook", url, "WITHIN", "mykey", "FENCE", "DETECT", "exit", "BOUNDS", 32, -116, 34, -114),
                )
                c.cmd("SET", "mykey", "t1", "POINT", 40, -115)
                assertMsg(assertNotNull(received.poll(5, TimeUnit.SECONDS)), "detect" to "exit", "hook" to "myhook")
                assertEquals(RespValue.Integer(1), c.cmd("PDELHOOK", "my*"))
                assertEquals("[]", c.cmd("HOOKS", "*").toString())
                val err = c.cmd("SETHOOK", "bad", "nope://x", "NEARBY", "mykey", "FENCE", "POINT", 1, 1, 1)
                assertEquals("ERR invalid argument 'nope://x'", err.toString())
                // Upstream quirk: hooks are always parsed as fences, so FENCE may be omitted.
                assertEquals(RespValue.Integer(1), c.cmd("SETHOOK", "nofence", url, "NEARBY", "mykey", "POINT", 1, 1, 1))
            }
        } finally {
            http.stop(0)
        }
    }

    @Test
    fun hookExpiry() {
        Conn().use { c ->
            assertEquals(RespValue.Integer(1), c.cmd("SETCHAN", "tmp", "EX", "0.2", "NEARBY", "mykey", "FENCE", "POINT", 33, -115, 5000))
            Thread.sleep(800)
            assertEquals("[]", c.cmd("CHANS", "*").toString())
        }
    }
}
