// Port of tile38 internal/server/metrics.go, Copyright Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.server

import com.sun.net.httpserver.HttpServer
import java.lang.management.ManagementFactory
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.read

/**
 * Prometheus metrics (Tile38's `--metrics-addr`). The `tile38_*` series match Tile38's. Tile38
 * also exports the Go client's runtime and process collectors; jtile38 exports the process
 * series and, so that dashboards and checks written for Tile38 keep working, `go_build_info`,
 * `go_goroutines` and `go_threads` filled with JVM values (live threads).
 */
internal class Metrics(
    private val server: Server,
) {
    /** Per-command durations: a summary over the last 10 minutes, like Go's SummaryVec defaults. */
    private class CmdSummary {
        val samples = ArrayDeque<LongArray>() // [time nanos, duration nanos]
        var count = 0L
        var sumNanos = 0L
    }

    private val durations = ConcurrentHashMap<String, CmdSummary>()
    private var http: HttpServer? = null

    fun observe(
        cmd: String,
        nanos: Long,
    ) {
        val s = durations.computeIfAbsent(cmd) { CmdSummary() }
        synchronized(s) {
            s.count++
            s.sumNanos += nanos
            val now = System.nanoTime()
            s.samples.addLast(longArrayOf(now, nanos))
            while (s.samples.size > MAX_SAMPLES || (s.samples.isNotEmpty() && now - s.samples.first()[0] > MAX_AGE_NANOS)) {
                s.samples.removeFirst()
            }
        }
    }

    /** Starts the metrics HTTP server on [addr] (`host:port` or `:port`). */
    fun start(addr: String) {
        val i = addr.lastIndexOf(':')
        val host = if (i > 0) addr.substring(0, i) else ""
        val port = addr.substring(i + 1).toIntOrNull() ?: throw IllegalArgumentException("invalid metrics address '$addr'")
        val sock = if (host == "") InetSocketAddress(port) else InetSocketAddress(host, port)
        val h = HttpServer.create(sock, 0)
        h.executor = Executors.newVirtualThreadPerTaskExecutor()
        h.createContext("/") { ex ->
            val metrics = ex.requestURI.path == "/metrics"
            val body = (if (metrics) render() else indexPage()).toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add(
                "Content-Type",
                if (metrics) "text/plain; version=0.0.4; charset=utf-8; escaping=underscores" else "text/html; charset=utf-8",
            )
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        h.start()
        http = h
    }

    /** The bound metrics port (useful when started with port 0), or 0 when not running. */
    val port: Int get() = http?.address?.port ?: 0

    fun stop() {
        http?.stop(0)
    }

    private fun indexPage(): String =
        "<html><head>\n<title>Tile38 $VERSION</title></head>\n<body><h1>Tile38 $VERSION</h1>\n" +
            "<p><a href='/metrics'>Metrics</a></p>\n</body></html>"

    /** One metric family in the text exposition format. */
    private class Family(
        val help: String,
        val type: String,
    ) {
        val lines = ArrayList<Pair<String, String>>() // sort key, line
    }

    private fun labels(vararg kv: Pair<String, String>): String =
        kv.sortedBy { it.first }.joinToString(",", "{", "}") { (k, v) -> k + "=\"" + escapeLabel(v) + "\"" }

    private fun escapeLabel(v: String): String = v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    /** Renders all metrics; families are sorted by name, like the Go client. */
    fun render(): String {
        val fams = TreeMap<String, Family>()

        fun add(
            name: String,
            help: String,
            type: String,
            value: Double,
            lbl: String = "",
            sortKey: String = lbl,
        ) {
            fams.getOrPut(name) { Family(help, type) }.lines.add(sortKey to "$name$lbl ${goFloat(value)}")
        }

        server.lock.read {
            val m = HashMap<String, Any>()
            m.putAll(server.basicStats())
            m.putAll(server.extStats())
            for ((key, d) in DESCRIPTIONS) {
                val v = toDouble(m[key]) ?: continue
                add(d.first, d.second, "gauge", v)
            }
            add("tile38_server_info", "Server info", "gauge", 1.0, labels("id" to server.serverId, "version" to VERSION))
            add("tile38_start_time_seconds", "", "gauge", server.startedEpochSeconds.toDouble())
            val repl = server.replicationLabels()
            add(
                "tile38_replication_info",
                "Replication info",
                "gauge",
                1.0,
                labels("role" to repl[0], "following" to repl[1], "caught_up" to repl[2], "caught_up_once" to repl[3]),
            )
            for ((key, col) in server.cols) {
                val l = labels("col" to key)
                add("tile38_collection_objects", "Total number of objects per collection", "gauge", col.count().toDouble(), l)
                add("tile38_collection_points", "Total number of points per collection", "gauge", col.pointCount().toDouble(), l)
                add("tile38_collection_strings", "Total number of strings per collection", "gauge", col.stringCount().toDouble(), l)
                add("tile38_collection_weight_bytes", "Total weight of collection in bytes", "gauge", col.totalWeight().toDouble(), l)
            }
        }

        // command durations
        val dfam = Family("", "summary")
        for ((cmd, s) in durations) {
            val l = escapeLabel(cmd)
            synchronized(s) {
                val sorted = s.samples.map { it[1] }.sorted()
                val sb = StringBuilder()
                for (q in QUANTILES) {
                    val v = if (sorted.isEmpty()) Double.NaN else sorted[maxOf(0, Math.ceil(q * sorted.size).toInt() - 1)] / 1e9
                    sb.append("tile38_cmd_duration_seconds{cmd=\"$l\",quantile=\"${goFloat(q)}\"} ${goFloat(v)}\n")
                }
                sb.append("tile38_cmd_duration_seconds_sum{cmd=\"$l\"} ${goFloat(s.sumNanos / 1e9)}\n")
                sb.append("tile38_cmd_duration_seconds_count{cmd=\"$l\"} ${s.count}")
                dfam.lines.add(cmd to sb.toString())
            }
        }
        if (dfam.lines.isNotEmpty()) fams["tile38_cmd_duration_seconds"] = dfam

        // runtime and process series
        val threads = ManagementFactory.getThreadMXBean().threadCount.toDouble()
        add(
            "go_build_info",
            "Build information about the main Go module.",
            "gauge",
            1.0,
            labels(
                "checksum" to "",
                "path" to "io.jtile38/jtile38-server",
                "version" to VERSION,
            ),
        )
        add("go_goroutines", "Number of goroutines that currently exist.", "gauge", threads)
        add("go_threads", "Number of OS threads created.", "gauge", threads)
        val os = ManagementFactory.getOperatingSystemMXBean()
        if (os is com.sun.management.OperatingSystemMXBean) {
            add("process_cpu_seconds_total", "Total user and system CPU time spent in seconds.", "counter", os.processCpuTime / 1e9)
            add("process_virtual_memory_bytes", "Virtual memory size in bytes.", "gauge", os.committedVirtualMemorySize.toDouble())
        }
        if (os is com.sun.management.UnixOperatingSystemMXBean) {
            add("process_open_fds", "Number of open file descriptors.", "gauge", os.openFileDescriptorCount.toDouble())
            add("process_max_fds", "Maximum number of open file descriptors.", "gauge", os.maxFileDescriptorCount.toDouble())
        }
        residentMemory()?.let { add("process_resident_memory_bytes", "Resident memory size in bytes.", "gauge", it) }
        ProcessHandle.current().info().startInstant().ifPresent {
            add("process_start_time_seconds", "Start time of the process since unix epoch in seconds.", "gauge", it.toEpochMilli() / 1000.0)
        }

        val out = StringBuilder()
        for ((name, f) in fams) {
            out
                .append("# HELP ")
                .append(name)
                .append(' ')
                .append(f.help)
                .append('\n')
            out
                .append("# TYPE ")
                .append(name)
                .append(' ')
                .append(f.type)
                .append('\n')
            for ((_, line) in f.lines.sortedBy { it.first }) out.append(line).append('\n')
        }
        return out.toString()
    }

    private fun residentMemory(): Double? =
        try {
            // Linux: the second field of /proc/self/statm is the resident set size in pages
            val pages =
                java.io
                    .File("/proc/self/statm")
                    .readText()
                    .trim()
                    .split(' ')[1]
                    .toLong()
            pages * 4096.0
        } catch (_: Exception) {
            null
        }

    companion object {
        private const val MAX_SAMPLES = 4096
        private const val MAX_AGE_NANOS = 10 * 60 * 1_000_000_000L
        private val QUANTILES = doubleArrayOf(0.5, 0.9, 0.95, 0.99)

        /** Stats map key to (metric name, help), Go `metricDescriptions`. */
        private val DESCRIPTIONS =
            mapOf(
                "num_collections" to ("tile38_collections" to "Total number of collections"),
                "pid" to ("tile38_pid" to ""),
                "aof_size" to ("tile38_aof_size_bytes" to ""),
                "num_hooks" to ("tile38_hooks" to ""),
                "in_memory_size" to ("tile38_in_memory_size_bytes" to ""),
                "heap_size" to ("tile38_heap_size_bytes" to ""),
                "heap_released" to ("tile38_memory_reap_released_bytes" to ""),
                "max_heap_size" to ("tile38_memory_max_heap_size_bytes" to ""),
                "avg_item_size" to ("tile38_avg_item_size_bytes" to ""),
                "pointer_size" to ("tile38_pointer_size_bytes" to ""),
                "cpus" to ("tile38_num_cpus" to ""),
                "tile38_connected_clients" to ("tile38_connected_clients" to ""),
                "tile38_total_connections_received" to ("tile38_connections_received_total" to ""),
                "tile38_total_messages_sent" to ("tile38_messages_sent_total" to ""),
                "tile38_expired_keys" to ("tile38_expired_keys_total" to ""),
            )

        /** Go `toFloat`: numbers only (booleans and strings are skipped). */
        private fun toDouble(v: Any?): Double? =
            when (v) {
                is Double -> v
                is Float -> v.toDouble()
                is Long -> v.toDouble()
                is Int -> v.toDouble()
                is Short -> v.toDouble()
                is Byte -> v.toDouble()
                else -> null
            }

        /** Go `strconv.FormatFloat(v, 'g', -1, 64)` as used by the Prometheus text format. */
        fun goFloat(v: Double): String {
            if (v.isNaN()) return "NaN"
            if (v.isInfinite()) return if (v > 0) "+Inf" else "-Inf"
            if (v == 0.0) return if (1.0 / v < 0) "-0" else "0"
            // shortest decimal digits and decimal point position
            val bd = BigDecimal(v.toString()).stripTrailingZeros()
            val neg = bd.signum() < 0
            val digits = bd.unscaledValue().abs().toString()
            val dp = digits.length - bd.scale() // position of the decimal point
            val exp = dp - 1
            val sb = StringBuilder()
            if (neg) sb.append('-')
            // with the shortest representation Go switches to %e from exponent 6 on
            if (exp < -4 || exp >= 6) {
                // %e with the shortest digits
                sb.append(digits[0])
                if (digits.length > 1) sb.append('.').append(digits, 1, digits.length)
                sb.append('e').append(if (exp < 0) '-' else '+')
                val e = kotlin.math.abs(exp)
                if (e < 10) sb.append('0')
                sb.append(e)
            } else if (dp <= 0) {
                sb.append("0.")
                repeat(-dp) { sb.append('0') }
                sb.append(digits)
            } else if (dp >= digits.length) {
                sb.append(digits)
                repeat(dp - digits.length) { sb.append('0') }
            } else {
                sb.append(digits, 0, dp).append('.').append(digits, dp, digits.length)
            }
            return sb.toString()
        }
    }
}
