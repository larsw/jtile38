package io.jtile38.server

import redis.clients.jedis.Jedis
import redis.clients.jedis.commands.ProtocolCommand
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Prometheus metrics (issue #32). */
class MetricsTest {
    private fun cmd(name: String) = ProtocolCommand { name.toByteArray() }

    @Test
    fun goFloatFormatting() {
        // strconv.FormatFloat(v, 'g', -1, 64), as written by the Go Prometheus client
        val cases =
            mapOf(
                22.0 to "22",
                6684880.0 to "6.68488e+06",
                3022728.0 to "3.022728e+06",
                8.2736e-05 to "8.2736e-05",
                0.00015115000000000001 to "0.00015115000000000001",
                1.795552454e9 to "1.795552454e+09",
                123456.0 to "123456",
                0.5 to "0.5",
                0.0001 to "0.0001",
                -2.5 to "-2.5",
                0.0 to "0",
                Double.NaN to "NaN",
                Double.POSITIVE_INFINITY to "+Inf",
            )
        for ((v, want) in cases) assertEquals(want, Metrics.goFloat(v), "$v")
    }

    @Test
    fun endpoint() {
        val dir = Files.createTempDirectory("jtile38-metrics").toString()
        Server(ServerOptions(port = 0, dir = dir, metricsAddr = "127.0.0.1:0")).start().use { s ->
            Jedis("127.0.0.1", s.port).use { j ->
                j.sendCommand(cmd("SET"), "m1", "1", "FIELD", "foo", "5.5", "POINT", "5", "5")
                j.sendCommand(cmd("SET"), "m2", "2", "POINT", "19", "19")
                j.sendCommand(cmd("SET"), "m2", "3", "STRING", "John")
            }
            val base = "http://127.0.0.1:${s.metrics.port}/"
            assertTrue(URI(base).toURL().readText().contains("<a href='/metrics'>"))
            val metrics = URI(base + "metrics").toURL().readText()
            for (want in listOf(
                "tile38_cmd_duration_seconds_count{cmd=\"set\"} 3",
                "tile38_collection_objects{col=\"m2\"} 2",
                "tile38_collection_points{col=\"m2\"} 1",
                "tile38_collection_strings{col=\"m2\"} 1",
                "tile38_replication_info{caught_up=\"\",caught_up_once=\"\",following=\"\",role=\"leader\"} 1",
                "# TYPE tile38_cmd_duration_seconds summary",
                "go_threads",
            )) {
                assertTrue(metrics.contains(want), want)
            }
        }
    }
}
