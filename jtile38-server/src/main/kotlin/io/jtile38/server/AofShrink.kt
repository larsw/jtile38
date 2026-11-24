// Port of tile38 internal/server/aofshrink.go and dev.go, Copyright Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.server

import io.jtile38.resp.RespValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ThreadLocalRandom
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.concurrent.write
import kotlin.math.floor
import kotlin.system.exitProcess

private val aofLog = Logger.getLogger("io.jtile38.server.AofShrink")

private const val MAX_KEYS = 8
private const val MAX_IDS = 32
private const val MAX_CHUNK = 4 * 1024 * 1024

/** AOFSHRINK: rewrites the AOF in the background and replies immediately. */
internal fun Server.cmdAofShrink(msg: Message): RespValue {
    val start = System.nanoTime()
    Thread.ofVirtual().name("jtile38-aofshrink").start { aofShrink() }
    return if (msg.outputType == OutputType.JSON) okJson(start) else RespValue.OK
}

private fun ByteArrayOutputStream.appendCommand(values: List<String>) {
    RespValue.array(values.map { RespValue.string(it) }).writeTo(this)
}

/**
 * Rewrites the AOF as the minimal set of commands that recreate the current data and hooks.
 *
 * Like Tile38, the data is copied in small batches under the write lock (8 keys, 32 objects
 * at a time) so the server keeps serving clients. Writes made meanwhile are recorded in the shrink
 * log and appended at the end, then the new file replaces the live AOF.
 */
internal fun Server.aofShrink() {
    val startNanos = System.nanoTime()
    lock.write {
        if (aof == null || shrinking) return
        shrinking = true
        shrinkLog = ArrayList()
    }
    try {
        val live = aofFile!!
        val shrinkFile = File(live.path + "-shrink")
        FileOutputStream(shrinkFile).use { f ->
            val buf = ByteArrayOutputStream()
            var keys = ArrayList<String>()
            var nextKey = ""
            var keysDone = false
            while (true) {
                if (keys.isEmpty()) {
                    // load more keys
                    if (keysDone) break
                    keysDone = true
                    lock.write {
                        for (key in cols.tailMap(nextKey, true).keys) {
                            if (keys.size == MAX_KEYS) {
                                keysDone = false
                                nextKey = key
                                break
                            }
                            keys.add(key)
                        }
                    }
                    continue
                }
                var idsDone = false
                var nextId = ""
                while (true) {
                    if (idsDone) {
                        keys = ArrayList(keys.subList(1, keys.size))
                        break
                    }
                    // load more objects
                    idsDone = true
                    lock.write {
                        val col = cols[keys[0]] ?: return@write
                        val now = nowNanos() // used for expiration
                        var count = 0
                        col.scanGreaterOrEqual(nextId, false, null) { o ->
                            if (count == MAX_IDS) {
                                // we reached the max number of ids for one batch
                                nextId = o.id
                                idsDone = false
                                return@scanGreaterOrEqual false
                            }
                            buf.appendCommand(setCommand(keys[0], o, now))
                            count++
                            true
                        }
                    }
                    if (buf.size() > MAX_CHUNK) {
                        buf.writeTo(f)
                        buf.reset()
                    }
                }
            }

            // hooks, by name
            val names = lock.write { hookRegistry.hooks.keys.toList() }
            for (name in names) {
                lock.write {
                    val hook = hookRegistry.hooks[name] ?: return@write
                    val values = ArrayList<String>()
                    if (hook.channel) {
                        values.add("setchan")
                        values.add(name)
                    } else {
                        values.add("sethook")
                        values.add(name)
                        values.add(hook.endpoints.joinToString(","))
                    }
                    for (meta in hook.metas) {
                        values.add("meta")
                        values.add(meta.name)
                        values.add(meta.value)
                    }
                    if (hook.expires != 0L) {
                        val ex = (hook.expires - nowNanos()) / 1e9
                        values.add("ex")
                        // strconv.FormatFloat(ex, 'f', 1, 64)
                        values.add(BigDecimal(ex).setScale(1, RoundingMode.HALF_EVEN).toPlainString())
                    }
                    values.addAll(hook.command)
                    buf.appendCommand(values)
                }
            }
            if (buf.size() > 0) {
                buf.writeTo(f)
                buf.reset()
            }
            f.fd.sync()

            // finally grab any new data that may have been written since the shrink started and
            // swap out the files
            lock.write {
                flushAof()
                for (values in shrinkLog) buf.appendCommand(values)
                buf.writeTo(f)
                f.fd.sync()
                // anything below this point is unrecoverable: log and exit the process
                try {
                    aof?.close()
                    f.close()
                    val backup = File(live.path + "-bak")
                    if (!live.renameTo(backup)) error("rename ${live.path} to ${backup.path} failed")
                    if (!shrinkFile.renameTo(live)) error("rename ${shrinkFile.path} to ${live.path} failed")
                    aof = FileOutputStream(live, true)
                    aofSize.set(live.length())
                    backup.delete()
                } catch (e: Exception) {
                    aofLog.log(Level.SEVERE, "shrink fatal operation", e)
                    exitProcess(1)
                }
            }
        }
    } catch (e: Exception) {
        aofLog.log(Level.SEVERE, "aof shrink failed", e)
    } finally {
        lock.write {
            shrinking = false
            shrinkLog = ArrayList()
        }
        aofLog.info("aof shrink ended ${GoCompat.duration(System.nanoTime() - startNanos)}")
    }
}

/** The SET command that recreates [o] in [key]; [now] is the current Unix time in nanoseconds. */
private fun setCommand(
    key: String,
    o: Item,
    now: Long,
): List<String> {
    val values = arrayListOf("set", key, o.id)
    o.fields.forEach { f ->
        if (!f.value.isZero()) {
            values.add("field")
            values.add(f.name)
            values.add(f.value.json())
        }
    }
    if (o.expires != 0L) {
        var ttl = floor((o.expires - now).toDouble() / 1e9 * 10) / 10
        // always leave a little bit of ttl
        if (ttl < 0.1) ttl = 0.1
        values.add("ex")
        values.add(GoCompat.formatFloat(ttl))
    }
    if (o.isSpatial()) {
        values.add("object")
        values.add(o.geo.json())
    } else {
        values.add("string")
        values.add(o.toString())
    }
    return values
}

/** MASSINSERT num_keys num_points [minlat minlon maxlat maxlon] (developer mode). */
internal fun Server.cmdMassInsert(msg: Message): RespValue {
    val start = System.nanoTime()
    val vs = msg.args.drop(1)
    var minLat = -90.0
    var minLon = -180.0
    var maxLat = 90.0
    var maxLon = 180.0
    if (vs.size < 2 || vs[0] == "" || vs[1] == "") throw Errors.invalidNumberOfArguments()
    if (vs.size > 2) {
        if (vs.size < 6 || vs.subList(2, 6).any { it == "" }) throw Errors.invalidNumberOfArguments()

        fun float(s: String) = GoCompat.parseFloat(s) ?: throw CommandException("strconv.ParseFloat: parsing \"$s\": invalid syntax")
        minLat = float(vs[2])
        minLon = float(vs[3])
        maxLat = float(vs[4])
        maxLon = float(vs[5])
        if (vs.size != 6) throw Errors.invalidNumberOfArguments()
    }
    val cols = GoCompat.parseUint(vs[0]) ?: throw Errors.invalidArgument(vs[0])
    val objs = GoCompat.parseUint(vs[1]) ?: throw Errors.invalidArgument(vs[1])
    val fvals =
        doubleArrayOf(1.0, 0.0, -1.0, 14.0, 20.5, 120.0, -120.0, 20000.0, -20000.0, 214748300.0, -214748300.0, 2014748300.0, 123.12312301)
    val rnd = ThreadLocalRandom.current()
    var k = 0L
    for (i in 0 until cols) {
        val key = "mi:$i"
        for (j in 0 until objs) {
            val values = arrayListOf("set", key, j.toString())
            for ((n, fval) in fvals.withIndex()) {
                values.add("FIELD")
                values.add("fname:$n")
                values.add(GoCompat.formatFloat(fval))
            }
            if (rnd.nextInt(2) == 0) {
                values.add("EX")
                values.add((rnd.nextInt(25) + 5).toString())
            }
            if (j % 8 == 0L) {
                values.add("STRING")
                values.add("str$j")
            } else {
                val lat = rnd.nextDouble() * (maxLat - minLat) + minLat
                val lon = rnd.nextDouble() * (maxLon - minLon) + minLon
                values.add("POINT")
                values.add(GoCompat.formatFloat(lat))
                values.add(GoCompat.formatFloat(lon))
            }
            lock.write {
                executeWrite(Message(values, msg.connType, msg.outputType))
                if (++k % 1000 == 0L) flushAof()
            }
        }
    }
    lock.write { flushAof() }
    aofLog.info("massinsert: done $k objects")
    return if (msg.outputType == OutputType.JSON) okJson(start) else RespValue.OK
}

/** SLEEP seconds (developer mode). */
internal fun cmdSleep(msg: Message): RespValue {
    val start = System.nanoTime()
    if (msg.args.size != 2) throw Errors.invalidNumberOfArguments()
    val d = GoCompat.parseFloat(msg.args[1]) ?: 0.0
    Thread.sleep((d * 1000).toLong())
    return if (msg.outputType == OutputType.JSON) okJson(start) else RespValue.OK
}

/** SHUTDOWN (developer mode): exits the process like Tile38's log.Fatal. */
internal fun cmdShutdown(): Nothing {
    aofLog.severe("shutdown requested by developer")
    exitProcess(1)
}
