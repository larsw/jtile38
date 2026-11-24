package io.jtile38.client

import io.jtile38.server.Server
import io.jtile38.server.ServerOptions
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Set `TILE38_EXTERNAL_PORT` to run against an already running server (e.g. the original Tile38). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Tile38ClientTest {
    private val externalPort = System.getenv("TILE38_EXTERNAL_PORT")?.toInt()
    private var server: Server? = null
    private lateinit var t38: Tile38Client

    @BeforeAll
    fun start() {
        val port =
            externalPort ?: Server(ServerOptions(port = 0, dir = Files.createTempDirectory("jtile38-client").toString()))
                .start()
                .also { server = it }
                .port
        t38 = Tile38Client("127.0.0.1", port)
    }

    @AfterAll
    fun stop() {
        t38.close()
        server?.close()
    }

    @BeforeEach
    fun flush() = t38.flushDb()

    @Test
    fun json() {
        t38.jset("docs", "d1", "name.first", "Tom")
        t38.jset("docs", "d1", "age", 37)
        t38.jset("docs", "d1", "zip", "0123")
        t38.jset("docs", "d1", "tags", "[1,2]", JsonValueMode.RAW)
        t38.jset("docs", "d1", "flag", "true", JsonValueMode.STR)
        assertEquals("""{"name":{"first":"Tom"},"age":37,"zip":"0123","tags":[1,2],"flag":"true"}""", t38.jget("docs", "d1"))
        assertEquals("Tom", t38.jget("docs", "d1", "name.first"))
        assertEquals("\"Tom\"", t38.jget("docs", "d1", "name.first", raw = true))
        assertNull(t38.jget("docs", "d1", "nope"))
        assertTrue(t38.jdel("docs", "d1", "tags.0"))
        assertFalse(t38.jdel("docs", "d1", "tags.5"))
        val feature = """{"type":"Feature","geometry":{"type":"Point","coordinates":[-115,33]},"properties":{"a":1}}"""
        t38.execute("SET", "fleet", "f1", "OBJECT", feature)
        assertTrue(t38.jdel("fleet", "f1", "properties.a"))
        assertEquals("{}", t38.jget("fleet", "f1", "properties"))
    }

    @Test
    fun crud() {
        assertTrue(t38.ping())
        assertTrue(t38.set("fleet", "truck1").field("speed", 90).point(33.5123, -112.2693))
        assertEquals(Point(33.5123, -112.2693), t38.getPoint("fleet", "truck1"))
        assertEquals("""{"type":"Point","coordinates":[-112.2693,33.5123]}""", t38.get("fleet", "truck1")!!.json)
        assertEquals(mapOf("speed" to "90"), t38.get("fleet", "truck1", withFields = true)!!.fields)
        assertEquals(2, t38.fset("fleet", "truck1", mapOf("speed" to 100, "weight" to 2.5)))
        assertEquals("100", t38.fget("fleet", "truck1", "speed"))
        assertTrue(t38.fexists("fleet", "truck1", "weight"))
        assertTrue(t38.exists("fleet", "truck1"))
        assertNull(t38.get("fleet", "nope"))
        assertNull(t38.getPoint("nokey", "nope"))
        assertFalse(t38.set("fleet", "truck1").nx().point(1.0, 2.0))
        assertTrue(t38.set("fleet", "truck1").xx().point(33.0, -112.0, 10.0))
        assertEquals(Point(33.0, -112.0, 10.0), t38.getPoint("fleet", "truck1"))
        assertEquals(Bounds(33.0, -112.0, 33.0, -112.0), t38.getBounds("fleet", "truck1"))
        assertEquals("9tb7", t38.getHash("fleet", "truck1", 4))
        assertTrue(t38.set("words", "w1").string("hello wörld"))
        assertEquals("hello wörld", t38.get("words", "w1")!!.json)
        assertEquals(listOf("fleet", "words"), t38.keys())
        assertTrue(t38.del("fleet", "truck1"))
        assertFalse(t38.del("fleet", "truck1"))
        assertTrue(t38.drop("words"))
        assertEquals(emptyList(), t38.keys())
    }

    @Test
    fun expiry() {
        t38.set("fleet", "t").expire(10.0).point(1.0, 2.0)
        assertTrue(t38.ttl("fleet", "t") in 9..10)
        assertTrue(t38.persist("fleet", "t"))
        assertEquals(-1, t38.ttl("fleet", "t"))
        assertTrue(t38.expire("fleet", "t", 100.0))
        assertEquals(-2, t38.ttl("fleet", "nope"))
    }

    @Test
    fun collectionCommands() {
        t38.set("a", "1").bounds(1.0, 2.0, 3.0, 4.0)
        t38.set("a", "2").point(0.0, 0.0)
        assertEquals(Bounds(0.0, 0.0, 3.0, 4.0), t38.bounds("a"))
        val stats = t38.stats("a", "nokey")
        assertEquals(2, stats[0]!!.numObjects)
        assertNull(stats[1])
        t38.rename("a", "b")
        assertFalse(t38.renameNx("b", "b"))
        assertEquals(listOf("b"), t38.keys())
        assertEquals(1, t38.pdel("b", "1"))
        assertTrue(t38.server().containsKey("num_objects"))
    }

    @Test
    fun searches() {
        t38.set("fleet", "t1").field("speed", 10).point(33.0, -112.0)
        t38.set("fleet", "t2").field("speed", 20).point(33.1, -112.0)
        t38.set("fleet", "t3").point(35.0, -112.0)
        assertEquals(3, t38.scan("fleet").count())
        assertEquals(
            listOf("t1", "t2", "t3"),
            t38
                .scan("fleet")
                .asIds()
                .hits
                .map { it.id },
        )
        val page = t38.scan("fleet").limit(2).asIds()
        assertEquals(2, page.cursor)
        assertEquals(
            listOf("t3"),
            t38
                .scan("fleet")
                .cursor(page.cursor)
                .asIds()
                .hits
                .map { it.id },
        )
        assertEquals(
            listOf("t2"),
            t38
                .scan("fleet")
                .where("speed", 15, 30)
                .asIds()
                .hits
                .map { it.id },
        )

        val near =
            t38
                .nearby("fleet")
                .distance()
                .point(33.0, -112.0, 20_000.0)
                .asObjects()
                .hits
        assertEquals(listOf("t1", "t2"), near.map { it.id })
        assertEquals(0.0, near[0].distance)
        assertEquals(mapOf("speed" to "10"), near[0].fields)
        assertEquals("""{"type":"Point","coordinates":[-112,33]}""", near[0].json)

        val pts =
            t38
                .within("fleet")
                .bounds(32.9, -112.1, 33.2, -111.9)
                .asPoints()
                .hits
        assertEquals(setOf(Point(33.0, -112.0), Point(33.1, -112.0)), pts.map { it.point }.toSet())
        assertEquals(2, t38.within("fleet").circle(33.05, -112.0, 10_000.0).count())
        assertEquals(
            1,
            t38
                .intersects(
                    "fleet",
                ).geoJson("""{"type":"Polygon","coordinates":[[[-113,34],[-111,34],[-111,36],[-113,36],[-113,34]]]}""")
                .count(),
        )
        assertEquals(
            "9tb",
            t38
                .intersects("fleet")
                .get("fleet", "t1")
                .asHashes(3)
                .hits
                .single()
                .hash,
        )
        val b =
            t38
                .intersects("fleet")
                .point(35.0, -112.0)
                .asBounds()
                .hits
                .single()
        assertEquals(Bounds(35.0, -112.0, 35.0, -112.0), b.bounds)

        t38.set("words", "1").string("banana")
        t38.set("words", "2").string("apple")
        assertEquals(
            listOf("2", "1"),
            t38
                .search("words")
                .asIds()
                .hits
                .map { it.id },
        )
        assertEquals(
            listOf("banana"),
            t38
                .search("words")
                .match("b*")
                .asObjects()
                .hits
                .map { it.json },
        )
    }

    @Test
    fun errorsAreThrown() {
        val e = assertFailsWith<Tile38Exception> { t38.execute("SET", "fleet", "x", "POINT", "a", 1) }
        assertEquals("invalid argument 'a'", e.message)
        assertFailsWith<IllegalStateException> { t38.within("fleet").count() }
    }

    @Test
    fun concurrentUse() {
        val pool = Executors.newFixedThreadPool(8)
        repeat(200) { i -> pool.submit { t38.set("fleet", "t$i").point(30.0 + i * 0.01, -110.0) } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals(200, t38.scan("fleet").count())
    }
}
