package io.jtile38.cli

import io.jtile38.server.Server
import io.jtile38.server.ServerOptions
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CliTest {
    private lateinit var server: Server
    private val historyFile = Files.createTempFile("jtile38-cli", ".history")

    @BeforeAll
    fun start() {
        server = Server(ServerOptions(port = 0, dir = Files.createTempDirectory("jtile38-cli").toString())).start()
    }

    @AfterAll
    fun stop() = server.close()

    private class Result(
        val code: Int,
        val out: String,
        val err: String,
    )

    private fun run(
        vararg argv: String,
        stdin: String = "",
    ): Result {
        val opts = parseArgs(arrayOf("-p", server.port.toString()) + argv, env = emptyMap())!!
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code =
            Cli(opts, PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8), historyFile)
                .run(stdin.byteInputStream())
        return Result(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    private fun stripElapsed(s: String) = s.replace(Regex(""","elapsed":"[^"]*""""), "")

    @Test
    fun oneCommandJson() {
        assertEquals("""{"ok":true}""" + "\n", stripElapsed(run("set", "fleet", "truck1", "point", "33", "-112").out))
        assertEquals(
            """{"ok":true,"object":{"type":"Point","coordinates":[-112,33]}}""" + "\n",
            stripElapsed(run("get", "fleet", "truck1").out),
        )
        // errors are printed to stdout in one-command mode, like tile38-cli
        assertEquals("""{"ok":false,"err":"id not found"}""" + "\n", stripElapsed(run("get", "fleet", "nope").out))
    }

    @Test
    fun oneCommandResp() {
        run("set", "fleet", "truck2", "field", "speed", "5", "point", "34", "-113")
        assertEquals("1) \"34\"\n2) \"-113\"\n", run("--resp", "get", "fleet", "truck2", "point").out)
        assertEquals("(nil)\n", run("--resp", "get", "fleet", "nope").out)
        assertEquals("(integer) 0\n", run("--resp", "del", "fleet", "nope").out)
        assertEquals("(error) ERR id not found\n", run("--resp", "fget", "fleet", "nope", "x").out)
        val scan = run("--resp", "scan", "fleet", "match", "truck2").out
        assertEquals(
            "1) (integer) 0\n2) 1) 1) \"truck2\"\n      2) \"{\"type\":\"Point\",\"coordinates\":[-113,34]}\"\n      3) 1) \"speed\"\n         2) \"5\"\n",
            scan,
        )
    }

    @Test
    fun stdinArgument() {
        val opts =
            parseArgs(
                arrayOf("-p", server.port.toString(), "-x", "set", "areas", "a", "object"),
                env = emptyMap(),
                readStdin = { "{\"type\":\"Point\",\n\"coordinates\":[1,2]}" },
            )!!
        val out = ByteArrayOutputStream()
        Cli(opts, PrintStream(out, true), PrintStream(ByteArrayOutputStream()), historyFile).run()
        assertEquals("""{"ok":true}""" + "\n", stripElapsed(out.toString()))
        assertEquals("""{"ok":true,"object":{"type":"Point","coordinates":[1,2]}}""" + "\n", stripElapsed(run("get", "areas", "a").out))
    }

    @Test
    fun interactive() {
        val r = run("--noprompt", stdin = "set fleet t9 point 1 2\nget fleet nope\n\noutput resp\nget fleet t9 point\nquit\n")
        assertEquals(0, r.code)
        val lines = stripElapsed(r.out).lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(listOf("{\"ok\":true}", "OK", "1) \"1\"", "2) \"2\""), lines, "out=${r.out} err=${r.err}")
        assertTrue(r.err.contains("(error) id not found"), r.err)
    }

    @Test
    fun connectionRefused() {
        val opts = parseArgs(arrayOf("-p", "1", "ping"), env = emptyMap())!!
        val err = ByteArrayOutputStream()
        val code = Cli(opts, PrintStream(ByteArrayOutputStream()), PrintStream(err, true), historyFile).run()
        assertEquals(1, code)
        assertEquals("Could not connect to Tile38 at 127.0.0.1:1: Connection refused\n", err.toString())
    }

    @Test
    fun plainToCompat() {
        assertEquals("set fleet truck1 point 33 -112", Cli.plainToCompat("  set fleet  truck1 point 33 -112 "))
        assertEquals("set k id object '{\"a\":1}'", Cli.plainToCompat("set k id object '{\"a\":1}'"))
        assertEquals("set k id string \"hello world\"", Cli.plainToCompat("set k id string \"hello world\""))
        assertEquals("set k id string \"it's\"", Cli.plainToCompat("set k id string it's"))
    }

    @Test
    fun helpCatalog() {
        val set = Commands.all["SET"]!!
        assertEquals("keys", set.group)
        assertTrue(
            set.toString().startsWith("SET key id [FIELD name value ...] [EX seconds] [NX|XX] (OBJECT geojson)|(POINT lat lon [z])"),
            set.toString(),
        )
        val r = run("--noprompt", stdin = "help nearby\nquit\n")
        assertTrue(r.err.contains("NEARBY"), r.err)
    }
}
