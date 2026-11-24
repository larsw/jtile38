package io.jtile38.server

import redis.clients.jedis.Jedis
import redis.clients.jedis.commands.ProtocolCommand
import redis.clients.jedis.exceptions.JedisDataException
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Leader/follower replication (#28) and READONLY (#29). */
class ReplicationTest {
    private fun cmd(name: String) = ProtocolCommand { name.toByteArray() }

    private fun Jedis.run(vararg args: Any): Any? = sendCommand(cmd(args[0].toString()), *args.drop(1).map { it.toString() }.toTypedArray())

    private fun fmt(r: Any?): String =
        when (r) {
            is ByteArray -> String(r, Charsets.ISO_8859_1)
            is List<*> -> r.joinToString(" ", "[", "]") { fmt(it) }
            else -> r.toString()
        }

    private fun Jedis.str(vararg args: Any): String = fmt(run(*args))

    private fun Jedis.err(vararg args: Any): String = assertFailsWith<JedisDataException> { run(*args) }.message!!

    private fun waitFor(
        what: String,
        cond: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!cond()) {
            if (System.nanoTime() > deadline) error("timed out waiting for $what")
            Thread.sleep(50)
        }
    }

    private fun server(): Server = Server(ServerOptions(port = 0, dir = Files.createTempDirectory("jtile38-repl").toString())).start()

    @Test
    fun followerReplicatesTheLeader() {
        server().use { leader ->
            server().use { follower ->
                Jedis("127.0.0.1", leader.port).use { l ->
                    Jedis("127.0.0.1", follower.port).use { f ->
                        for (i in 1..20) l.run("SET", "fleet", "t$i", "FIELD", "speed", i, "POINT", 33, -112)
                        l.run("CONFIG", "SET", "requirepass", "secret")
                        l.run("AUTH", "secret")
                        f.run("SET", "own", "x", "POINT", 1, 1)
                        assertEquals("OK", f.str("CONFIG", "SET", "leaderauth", "secret"))
                        assertEquals("OK", f.str("FOLLOW", "127.0.0.1", leader.port))
                        // like Tile38, reads (SERVER included) are refused until the follower caught up once
                        assertEquals("ERR catching up to leader", f.err("GET", "fleet", "t1"))
                        waitFor("catch up") { runCatching { f.str("SERVER") }.getOrDefault("").contains("caught_up true") }
                        assertEquals("20", f.str("FGET", "fleet", "t20", "speed"))
                        l.run("SET", "fleet", "late", "POINT", 1, 2)
                        waitFor("live replication") {
                            runCatching { f.str("GET", "fleet", "late") }.getOrNull() ==
                                """{"type":"Point","coordinates":[2,1]}"""
                        }
                        assertEquals("ERR not the leader", f.err("SET", "fleet", "x", "POINT", 1, 1))
                        assertTrue(f.str("SERVER").contains("following 127.0.0.1:${leader.port}"))
                        assertEquals("[slave 127.0.0.1 ${leader.port} connected ${follower.faofsz.get()}]", f.str("ROLE"))
                        assertEquals("[master ${leader.aofSize()} [[127.0.0.1 ${follower.port} ${leader.aofSize()}]]]", l.str("ROLE"))
                        assertTrue(
                            l
                                .str(
                                    "INFO",
                                    "replication",
                                ).contains("slave0:ip=127.0.0.1,port=${follower.port},state=online\r\nconnected_slaves:1"),
                        )
                        // the follower's AOF holds the leader's commands
                        assertEquals("OK", f.str("FOLLOW", "no", "one"))
                        assertEquals("OK", f.str("SET", "fleet", "x", "POINT", 1, 1))
                    }
                }
            }
        }
    }

    @Test
    fun followErrors() {
        server().use { s ->
            Jedis("127.0.0.1", s.port).use { j ->
                assertEquals("ERR cannot follow self", j.err("FOLLOW", "127.0.0.1", s.port))
                assertEquals("ERR invalid argument 'b'", j.err("FOLLOW", "a", "b"))
                assertEquals("ERR wrong number of arguments for 'follow' command", j.err("FOLLOW", "a"))
                assertTrue(
                    j.err("FOLLOW", "127.0.0.1", 1).startsWith("ERR cannot follow: dial tcp 127.0.0.1:1: connect: connection refused"),
                )
                assertEquals("ERR cannot find follower", j.err("REPLCONF", "foo", "bar"))
                assertEquals("ERR invalid argument 'x'", j.err("REPLCONF", "listening-port", "x"))
            }
        }
    }

    @Test
    fun readOnly() {
        server().use { s ->
            Jedis("127.0.0.1", s.port).use { j ->
                assertEquals("OK", j.str("READONLY", "yes"))
                assertEquals("ERR read only", j.err("SET", "a", "b", "POINT", 1, 1))
                assertEquals("ERR invalid argument 'maybe'", j.err("READONLY", "maybe"))
                assertEquals("ERR wrong number of arguments for 'readonly' command", j.err("READONLY"))
                assertTrue(j.str("SERVER").contains("read_only true"))
                assertEquals("OK", j.str("READONLY", "no"))
                assertEquals("OK", j.str("SET", "a", "b", "POINT", 1, 1))
            }
        }
    }

    @Test
    fun aofMd5() {
        server().use { s ->
            Jedis("127.0.0.1", s.port).use { j ->
                for (i in 1..100) j.run("SET", "fleet", "t$i", "POINT", 33, -112)
                s.lock.write { s.flushAof() }
                val aof = File(s.options.dir, "appendonly.aof").readBytes()

                fun md5(
                    pos: Int,
                    size: Int,
                ) = MessageDigest.getInstance("MD5").digest(aof.copyOfRange(pos, pos + size)).joinToString("") { "%02x".format(it) }
                assertEquals(md5(0, 0), j.str("AOFMD5", 0, 0))
                assertEquals(md5(10, 500), j.str("AOFMD5", 10, 500))
                assertEquals("EOF", j.err("AOFMD5", 0, aof.size + 1))
                assertEquals("ERR invalid argument '-1'", j.err("AOFMD5", -1, 0))
                assertEquals("ERR pos is too big, must be less that the aof_size of leader", j.err("AOF", aof.size + 1))
            }
        }
    }

    private fun <T> java.util.concurrent.locks.ReentrantReadWriteLock.write(f: () -> T): T {
        writeLock().lock()
        try {
            return f()
        } finally {
            writeLock().unlock()
        }
    }
}
