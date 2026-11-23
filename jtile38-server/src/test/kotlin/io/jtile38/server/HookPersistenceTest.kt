package io.jtile38.server

import com.sun.net.httpserver.HttpServer
import io.jtile38.json.Gjson
import redis.clients.jedis.Jedis
import redis.clients.jedis.commands.ProtocolCommand
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Undelivered webhook messages survive a restart (issue #37). */
class HookPersistenceTest {
    private fun cmd(name: String) = ProtocolCommand { name.toByteArray() }

    private fun pending(j: Jedis): String {
        val stats = (j.sendCommand(cmd("SERVER")) as List<*>).map { String(it as ByteArray) }
        return stats[stats.indexOf("pending_events") + 1]
    }

    @Test
    fun pendingMessagesAreDeliveredAfterRestart() {
        val up = AtomicBoolean(false)
        val received = LinkedBlockingQueue<String>()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/hook") { ex ->
            val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
            if (up.get()) {
                received.add(body)
                ex.sendResponseHeaders(200, -1)
            } else {
                ex.sendResponseHeaders(503, -1)
            }
            ex.close()
        }
        http.start()
        try {
            val url = "http://127.0.0.1:${http.address.port}/hook"
            val dir = Files.createTempDirectory("jtile38-hookq").toString()
            Server(ServerOptions(port = 0, dir = dir)).start().use { s ->
                Jedis("127.0.0.1", s.port).use { j ->
                    j.sendCommand(cmd("SETHOOK"), "h1", url, "NEARBY", "fleet", "FENCE", "POINT", "33", "-115", "5000")
                    j.sendCommand(cmd("SET"), "fleet", "t1", "POINT", "33", "-115")
                    Thread.sleep(700) // at least one failed delivery attempt
                    assertEquals("2", pending(j))
                }
            }
            assertNull(received.poll(200, TimeUnit.MILLISECONDS))
            up.set(true)
            Server(ServerOptions(port = 0, dir = dir)).start().use { s ->
                val m1 = assertNotNull(received.poll(5, TimeUnit.SECONDS))
                val m2 = assertNotNull(received.poll(5, TimeUnit.SECONDS))
                assertEquals(listOf("enter", "inside"), listOf(m1, m2).map { Gjson.get(it, "detect").string() })
                assertEquals(listOf("h1", "h1"), listOf(m1, m2).map { Gjson.get(it, "hook").string() })
                Jedis("127.0.0.1", s.port).use { j ->
                    assertEquals("0", pending(j))
                    // new messages get new queue indexes and are delivered too
                    j.sendCommand(cmd("SET"), "fleet", "t2", "POINT", "33", "-115")
                }
                assertEquals("t2", Gjson.get(assertNotNull(received.poll(5, TimeUnit.SECONDS)), "id").string())
            }
        } finally {
            http.stop(0)
        }
    }
}
