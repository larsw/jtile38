// Port of tile38 internal/server/stats.go (SERVER EXT, INFO, ROLE) and client.go (CLIENT),
// Copyright Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.server

import io.jtile38.resp.RespValue
import java.io.File
import java.lang.management.ManagementFactory
import java.net.Socket

/** A connected client (Go `Client`). */
internal class ClientConn(
    val id: Long,
    val remoteAddr: String,
    val socket: Socket,
) {
    @Volatile var name = ""

    @Volatile var outputType: OutputType? = null

    /** Whether the connection has authenticated (AUTH or an HTTP Authorization header). */
    @Volatile var authd = false
    val opened: Long = System.nanoTime()

    @Volatile var last: Long = System.nanoTime()
}

private fun jsonValue(v: Any): String =
    when (v) {
        is String -> GoCompat.jsonMarshalString(v)
        is Double -> GoCompat.formatFloat(v)
        is Float -> GoCompat.formatFloat(v.toDouble())
        else -> v.toString()
    }

private fun jsonObject(m: Map<String, Any>): String =
    m.entries.joinToString(",", "{", "}") { GoCompat.jsonMarshalString(it.key) + ":" + jsonValue(it.value) }

/** Go `tryParseType`: int, then float, then bool, else the string. */
private fun tryParseType(s: String): Any =
    GoCompat.parseInt(s) ?: GoCompat.parseFloat(s)?.takeIf { !it.isNaN() && !it.isInfinite() } ?: when (s) {
        "1", "t", "T", "TRUE", "true", "True" -> true
        "0", "f", "F", "FALSE", "false", "False" -> false
        else -> s
    }

/** Extended statistics for SERVER EXT (Go `extStats`), with JVM equivalents of the Go runtime values. */
internal fun Server.extStats(): Map<String, Any> {
    val rt = Runtime.getRuntime()
    val mem = ManagementFactory.getMemoryMXBean()
    val heap = mem.heapMemoryUsage
    val nonHeap = mem.nonHeapMemoryUsage
    val threads = ManagementFactory.getThreadMXBean()
    val gcs = ManagementFactory.getGarbageCollectorMXBeans()
    val used = heap.used
    val points = cols.values.sumOf { it.pointCount() }
    return sortedMapOf(
        "go_goroutines" to Thread.activeCount(),
        "go_threads" to threads.threadCount.toDouble(),
        "go_version" to "java" + System.getProperty("java.version"),
        "alloc_bytes" to used,
        "alloc_bytes_total" to used,
        "sys_cpus" to rt.availableProcessors(),
        "sys_bytes" to heap.committed + nonHeap.committed,
        "lookups_total" to 0,
        "mallocs_total" to 0,
        "frees_total" to 0,
        "heap_alloc_bytes" to used,
        "heap_sys_bytes" to heap.committed,
        "heap_idle_bytes" to heap.committed - used,
        "heap_inuse_bytes" to used,
        "heap_released_bytes" to 0,
        "heap_objects" to 0,
        "stack_inuse_bytes" to 0,
        "stack_sys_bytes" to 0,
        "mspan_inuse_bytes" to 0,
        "mspan_sys_bytes" to 0,
        "mcache_inuse_bytes" to 0,
        "mcache_sys_bytes" to 0,
        "buck_hash_sys_bytes" to 0,
        "gc_sys_bytes" to 0,
        "other_sys_bytes" to nonHeap.committed,
        "next_gc_bytes" to heap.committed,
        "last_gc_time_seconds" to 0.0,
        "gc_cpu_fraction" to
            gcs.sumOf { it.collectionTime.coerceAtLeast(0) }.toDouble() / 1000.0 / maxOf(uptimeSeconds(), 1e-9) / rt.availableProcessors(),
        "tile38_id" to serverId,
        "tile38_pid" to ProcessHandle.current().pid(),
        "tile38_version" to VERSION,
        "tile38_max_heap_size" to config.maxMemory,
        "tile38_type" to if (config.followHost == "") "leader" else "follower",
        "tile38_read_only" to config.readOnly,
        "tile38_pointer_size" to 8,
        "tile38_uptime_in_seconds" to uptimeSeconds(),
        "tile38_connected_clients" to clients.size,
        "tile38_cluster_enabled" to false,
        "tile38_aof_enabled" to options.appendOnly,
        "tile38_aof_rewrite_in_progress" to shrinking,
        "tile38_aof_last_rewrite_time_sec" to 0,
        "tile38_aof_current_rewrite_time_sec" to 0,
        "tile38_aof_size" to aofSize(),
        "tile38_http_transport" to true,
        "tile38_total_connections_received" to statsTotalConns.get(),
        "tile38_total_commands_processed" to statsTotalCommands.get(),
        "tile38_total_messages_sent" to statsMsgsSent.get(),
        "tile38_expired_keys" to statsExpired.get(),
        "tile38_connected_slaves" to 0,
        "tile38_num_points" to points,
        "tile38_num_objects" to cols.values.sumOf { it.count() },
        "tile38_num_strings" to cols.values.sumOf { it.stringCount() },
        "tile38_num_collections" to cols.size,
        "tile38_num_hooks" to hookRegistry.hooks.size,
        "tile38_num_hook_groups" to groups.size(),
        "tile38_num_object_groups" to groups.size(),
        "tile38_avg_point_size" to if (points != 0) used / points else 0,
        "tile38_in_memory_size" to cols.values.sumOf { it.totalWeight() },
    )
}

/** SERVER [EXT] */
internal fun Server.cmdServer(msg: Message): RespValue {
    val start = System.nanoTime()
    var ext = false
    for (a in msg.args.drop(1)) {
        if (a.lowercase() == "ext") ext = true else throw Errors.invalidArgument(a)
    }
    val m = if (ext) extStats() else basicStats()
    if (msg.outputType == OutputType.JSON) {
        return RespValue.string("{\"ok\":true,\"stats\":${jsonObject(m)},\"elapsed\":\"${elapsed(start)}\"}")
    }
    return RespValue.array(m.entries.flatMap { (k, v) -> listOf(RespValue.string(k), RespValue.string(goValueString(v))) })
}

/** Go `fmt.Sprintf("%v", v)` for the stat value types. */
internal fun goValueString(v: Any): String =
    when (v) {
        is Double -> if (v == Math.rint(v) && kotlin.math.abs(v) < 1e21) GoCompat.formatFloat(v) else goFloatV(v)
        else -> v.toString()
    }

/** Go `%v` for float64: shortest representation, exponent form for large/small values. */
private fun goFloatV(v: Double): String {
    val abs = kotlin.math.abs(v)
    if (abs < 1e-4 || abs >= 1e21) {
        val s =
            java.lang.Double
                .toString(v)
                .replace("E", "e")
        return if (s.contains("e-")) s else s.replace("e", "e+")
    }
    return GoCompat.formatFloat(v)
}

private fun Server.uptimeSeconds(): Double = (System.nanoTime() - startedNanos) / 1e9

/** Process CPU times (user, system) in seconds, from /proc on Linux. */
private fun cpuTimes(): Pair<Double, Double> {
    val stat = File("/proc/self/stat")
    if (stat.exists()) {
        val text = stat.readText()
        val fields = text.substring(text.lastIndexOf(')') + 2).split(" ")
        // fields[11] = utime, fields[12] = stime (clock ticks; assumes 100 Hz)
        val ticks = 100.0
        return fields[11].toLong() / ticks to fields[12].toLong() / ticks
    }
    val os = ManagementFactory.getOperatingSystemMXBean()
    val cpu = (os as? com.sun.management.OperatingSystemMXBean)?.processCpuTime ?: 0L
    return cpu / 1e9 to 0.0
}

/** INFO [section ...] */
internal fun Server.cmdInfo(msg: Message): RespValue {
    val start = System.nanoTime()
    val all = listOf("server", "clients", "memory", "persistence", "stats", "replication", "cpu", "cluster", "keyspace")
    val wanted = HashSet<String>()
    if (msg.args.size == 1) wanted.addAll(all)
    for (a in msg.args.drop(1)) {
        when (val s = a.lowercase()) {
            "all", "default" -> wanted.addAll(all)
            else -> if (s in all) wanted.add(s)
        }
    }
    val w = StringBuilder()
    val sects = all.filter { it in wanted }
    for ((i, section) in sects.withIndex()) {
        if (i > 0) w.append("\r\n")
        when (section) {
            "server" -> {
                w.append("# Server\r\n")
                w.append("tile38_version:$VERSION\r\n")
                w.append("redis_version:$VERSION\r\n")
                w.append("uptime_in_seconds:${uptimeSeconds().toLong()}\r\n")
            }

            "clients" -> {
                w.append("# Clients\r\nconnected_clients:${clients.size}\r\n")
            }

            "memory" -> {
                val rt = Runtime.getRuntime()
                w.append("# Memory\r\nused_memory:${rt.totalMemory() - rt.freeMemory()}\r\n")
            }

            "persistence" -> {
                w.append("# Persistence\r\n")
                w.append("aof_enabled:${if (options.appendOnly) 1 else 0}\r\n")
                w.append("aof_rewrite_in_progress:${if (shrinking) 1 else 0}\r\n")
                w.append("aof_last_rewrite_time_sec:0\r\n")
                w.append("aof_current_rewrite_time_sec:0\r\n")
            }

            "stats" -> {
                w.append("# Stats\r\n")
                w.append("total_connections_received:${statsTotalConns.get()}\r\n")
                w.append("total_commands_processed:${statsTotalCommands.get()}\r\n")
                w.append("total_messages_sent:${statsMsgsSent.get()}\r\n")
                w.append("expired_keys:${statsExpired.get()}\r\n")
            }

            "replication" -> {
                w.append("# Replication\r\nrole:master\r\nconnected_slaves:0\r\n")
            }

            "cpu" -> {
                val (user, sys) = cpuTimes()
                w.append("# CPU\r\n")
                w.append(String.format("used_cpu_sys:%.2f\r\n", sys))
                w.append(String.format("used_cpu_user:%.2f\r\n", user))
                w.append("used_cpu_sys_children:0.00\r\n")
                w.append("used_cpu_user_children:0.00\r\n")
            }

            "cluster" -> {
                w.append("# Cluster\r\ncluster_enabled:0\r\n")
            }
        }
    }
    if (msg.outputType == OutputType.JSON) {
        val m = sortedMapOf<String, Any>()
        for (kv0 in w.split("\r\n")) {
            val kv = kv0.trim()
            if (kv.startsWith("#")) continue
            val i = kv.indexOf(':')
            if (i >= 0) m[kv.substring(0, i)] = tryParseType(kv.substring(i + 1))
        }
        return RespValue.string("{\"ok\":true,\"info\":${jsonObject(m)},\"elapsed\":\"${elapsed(start)}\"}")
    }
    return RespValue.string(w.toString())
}

/** ROLE */
internal fun Server.cmdRole(msg: Message): RespValue {
    val start = System.nanoTime()
    val offset = aofSize()
    if (msg.outputType == OutputType.JSON) {
        return RespValue.string(
            "{\"ok\":true,\"role\":{\"role\":\"master\",\"offset\":$offset,\"slaves\":[]},\"elapsed\":${GoCompat.jsonString(
                elapsed(start),
            )}}",
        )
    }
    return RespValue.array(listOf(RespValue.string("master"), RespValue.integer(offset), RespValue.array(emptyList())))
}

/** CLIENT LIST | KILL | GETNAME | SETNAME */
internal fun Server.cmdClient(msg: Message): RespValue {
    val start = System.nanoTime()
    val args = msg.args
    val client = msg.client
    if (args.size == 1) throw Errors.invalidNumberOfArguments()

    fun ok() = if (msg.outputType == OutputType.JSON) okJson(start) else RespValue.OK
    when (args[1].lowercase()) {
        "list" -> {
            if (args.size != 2) throw Errors.invalidNumberOfArguments()
            val now = System.nanoTime()
            val list = clients.values.sortedBy { it.id }
            val buf = StringBuilder()
            for (c in list) {
                buf.append(
                    "id=${c.id} addr=${c.remoteAddr} name=${c.name} age=${(now - c.opened) / 1_000_000_000} idle=${(now - c.last) / 1_000_000_000}\n",
                )
            }
            if (msg.outputType == OutputType.JSON) {
                val maps =
                    buf.lines().mapNotNull { line ->
                        val m = sortedMapOf<String, Any>()
                        for (kv in line.trim().split(" ")) {
                            val i = kv.indexOf('=')
                            if (i >= 0) m[kv.substring(0, i)] = tryParseType(kv.substring(i + 1))
                        }
                        m.takeIf { it.isNotEmpty() }
                    }
                val data = if (maps.isEmpty()) "null" else maps.joinToString(",", "[", "]") { jsonObject(it) }
                return RespValue.string("{\"ok\":true,\"list\":$data,\"elapsed\":\"${elapsed(start)}\"}")
            }
            return RespValue.string(buf.toString())
        }

        "getname" -> {
            if (args.size != 2) throw Errors.invalidNumberOfArguments()
            val name = client?.name ?: ""
            if (msg.outputType == OutputType.JSON) {
                return RespValue.string("{\"ok\":true,\"name\":${GoCompat.jsonString(name)},\"elapsed\":\"${elapsed(start)}\"}")
            }
            return RespValue.string(name)
        }

        "setname" -> {
            if (args.size != 3) throw Errors.invalidNumberOfArguments()
            val name = args[2]
            if (name.any { it < '!' || it > '~' }) {
                throw CommandException("Client names cannot contain spaces, newlines or special characters.")
            }
            client?.name = name
            return ok()
        }

        "kill" -> {
            if (args.size < 3) throw Errors.invalidNumberOfArguments()
            var addr: String? = null
            var id: String? = null
            var i = 2
            while (i < args.size) {
                if (addr != null || id != null) throw Errors.invalidNumberOfArguments()
                val arg = args[i]
                if (arg.contains(':')) {
                    addr = arg
                } else {
                    when (arg.lowercase()) {
                        "addr" -> {
                            i++
                            if (i == args.size) throw Errors.invalidNumberOfArguments()
                            addr = args[i]
                        }

                        "id" -> {
                            i++
                            if (i == args.size) throw Errors.invalidNumberOfArguments()
                            id = args[i]
                        }

                        else -> {
                            throw CommandException("No such client")
                        }
                    }
                }
                i++
            }
            val closing = clients.values.filter { (id != null && it.id.toString() == id) || (addr != null && it.remoteAddr == addr) }
            if (closing.isEmpty()) throw CommandException("No such client")
            closing.forEach { runCatching { it.socket.close() } }
            return ok()
        }

        else -> {
            throw CommandException("Syntax error, try CLIENT (LIST | KILL | GETNAME | SETNAME)")
        }
    }
}
