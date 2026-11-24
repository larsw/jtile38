// Port of tile38 internal/server/fence.go, group.go and bson.go, Copyright Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.server

import io.jtile38.geojson.GeoObject
import io.jtile38.geojson.GeoRect
import io.jtile38.geojson.LineString
import io.jtile38.geojson.geo.Geo
import io.jtile38.geojson.geometry.Line
import io.jtile38.geojson.geometry.Point
import io.jtile38.geojson.geometry.Rect
import io.jtile38.json.Gjson
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor

/** ROAM options of a NEARBY fence. */
class RoamSpec(
    val key: String,
    val id: String,
    val pattern: Boolean,
    val meters: Double,
    val scan: String,
)

/** Geofence options parsed from a search command with FENCE. */
class FenceSpec(
    /** The search command: nearby, within or intersects. */
    var cmd: String,
    val key: String,
    val obj: GeoObject?,
    val globs: List<String>,
    val distance: Boolean,
    /** Enabled detections, or null for all. */
    val detect: Set<String>?,
    /** Accepted commands (COMMANDS option), or null for all. */
    val accept: Set<String>?,
    val nodwell: Boolean,
    val roam: RoamSpec?,
)

/** A meta key/value pair of a hook or channel. */
class FenceMeta(
    val name: String,
    val value: String,
)

private class RoamMatch(
    val id: String,
    val obj: GeoObject,
    var meters: Double,
)

/** Fence detection: turns a write into geofence notification messages. */
internal object Fences {
    /** Go `FenceMatch`: messages for [d], filtered by the COMMANDS option. */
    fun match(
        server: Server,
        hookName: String,
        sw: ScanWriter,
        fence: FenceSpec,
        metas: List<FenceMeta>,
        d: CommandDetails,
    ): List<String> {
        val msgs = fenceMatch(server, hookName, sw, fence, metas, d)
        val accept = fence.accept
        if (accept.isNullOrEmpty()) return msgs
        return msgs.filter { Gjson.get(it, "command").string() in accept }
    }

    private fun appendHookDetails(
        sb: StringBuilder,
        hookName: String,
        metas: List<FenceMeta>,
    ) {
        if (hookName.isNotEmpty()) sb.append(",\"hook\":").append(JsonStrings.append(hookName))
        if (metas.isNotEmpty()) {
            sb.append(",\"meta\":{")
            metas.forEachIndexed { i, m ->
                if (i > 0) sb.append(',')
                sb.append(JsonStrings.append(m.name)).append(':').append(JsonStrings.append(m.value))
            }
            sb.append('}')
        }
    }

    private fun hookJson(
        hookName: String,
        metas: List<FenceMeta>,
    ) = StringBuilder().also { appendHookDetails(it, hookName, metas) }.toString()

    private fun multiGlobMatch(
        globs: List<String>,
        s: String,
    ): Boolean {
        if (globs.isEmpty() || (globs.size == 1 && globs[0] == "*")) return true
        return globs.any { Glob.matches(it, s) }
    }

    private fun fenceMatch(
        server: Server,
        hookName: String,
        sw: ScanWriter,
        fence: FenceSpec,
        metas: List<FenceMeta>,
        d: CommandDetails,
    ): List<String> {
        if (d.command == "drop") {
            return listOf(
                "{\"command\":\"drop\"" + hookJson(hookName, metas) + ",\"key\":" + GoCompat.jsonString(d.key) +
                    ",\"time\":" + jsonTime(d.timestamp) + "}",
            )
        }
        val obj = d.obj ?: return emptyList()
        if (!multiGlobMatch(fence.globs, obj.id)) return emptyList()
        if (!obj.isSpatial()) return emptyList()
        if (d.command == "fset" && sw.nofields) return emptyList()
        if (d.command == "del") {
            return listOf(
                "{\"command\":\"del\"" + hookJson(hookName, metas) + ",\"key\":" + GoCompat.jsonString(d.key) +
                    ",\"id\":" + GoCompat.jsonString(obj.id) + ",\"time\":" + jsonTime(d.timestamp) + "}",
            )
        }
        var roamNearbys: List<RoamMatch> = emptyList()
        var roamFaraways: List<RoamMatch> = emptyList()
        var detect = "outside"
        if (fence.roam != null) {
            if (d.command == "set") {
                val (n, f) = fenceMatchRoam(server, fence, obj, d.old)
                roamNearbys = n
                roamFaraways = f
                if (n.isEmpty() && f.isEmpty()) return emptyList()
            }
            detect = "roam"
        } else {
            var nocross = false
            var match1 = fenceMatchObject(fence, d.old)
            if (match1) {
                match1 = sw.testObject(d.old!!)
                nocross = !match1
            }
            var match2 = fenceMatchObject(fence, obj)
            if (match2) {
                match2 = sw.testObject(obj)
                nocross = !match2
            }
            if (match1 && match2) {
                detect = "inside"
            } else if (match1 && !match2) {
                detect = "exit"
            } else if (!match1 && match2) {
                detect = if (d.command == "fset") "inside" else "enter"
            } else if (d.command != "fset") {
                // The object is spatially outside the fence, so the WHERE clauses were not
                // tested above. They must pass before trying cross detection.
                if (!sw.fieldMatchOrFalse(obj)) return emptyList()
                // The old and new objects may form a line that crosses the fence.
                val old = d.old
                if (!nocross && old != null) {
                    val ls = LineString(Line(listOf(old.geo.center(), obj.geo.center()), null))
                    val temp = fence.cmd == "within"
                    // a line crossing the area must be tested with "intersects"
                    if (temp) fence.cmd = "intersects"
                    if (fenceMatchObject(fence, Item("", ls, 0, FieldList.EMPTY))) detect = "cross"
                    if (temp) fence.cmd = "within"
                }
            }
        }
        val detects = fence.detect
        while (detects != null && detect !in detects) {
            detect =
                when (detect) {
                    "enter" -> "inside"
                    "exit", "cross" -> "outside"
                    else -> return emptyList()
                }
        }
        var distance = 0.0
        if (fence.distance && fence.obj != null) distance = obj.geo.distance(fence.obj)
        sw.fullFields = true
        sw.writeObject(ScanParams(obj, distance, fence.distance, noTest = true))
        var res = sw.takeOutput()
        if (res.isEmpty()) return emptyList()
        if (res[0] == ',') res = res.substring(1)
        if (sw.output == SearchOutput.IDS) res = "{\"id\":$res}"

        val groups = server.groups
        val group =
            when (detect) {
                "enter" -> {
                    groups.connect(hookName, d.key, obj.id)
                }

                "cross" -> {
                    groups.disconnect(hookName, d.key, obj.id)
                    groups.connect(hookName, d.key, obj.id)
                }

                else -> {
                    groups.get(hookName, d.key, obj.id) ?: groups.connect(hookName, d.key, obj.id)
                }
            }
        val msgs = ArrayList<String>()
        if (detects == null || detect in detects) {
            if (res.isNotEmpty() && res[0] == '{') {
                msgs.add(makeMsg(d.command, group, detect, hookName, metas, d.key, d.timestamp, res.substring(1)))
            } else {
                msgs.add(res)
            }
        }
        when (detect) {
            "enter" -> {
                if (detects == null || "inside" in detects) {
                    msgs.add(makeMsg(d.command, group, "inside", hookName, metas, d.key, d.timestamp, res.substring(1)))
                }
            }

            "exit", "cross" -> {
                if (detects == null || "outside" in detects) {
                    msgs.add(makeMsg(d.command, group, "outside", hookName, metas, d.key, d.timestamp, res.substring(1)))
                }
            }

            "roam" -> {
                if (msgs.isNotEmpty()) {
                    val nmsgs = ArrayList<String>()
                    for (m in msgs) {
                        val cmd = Gjson.get(m, "command")
                        if (cmd.exists() && cmd.string() != "set") nmsgs.add(m)
                    }
                    for (r in roamNearbys) nmsgs.add(extendRoamMessage(server, fence, "nearby", msgs[0], r))
                    for (r in roamFaraways) nmsgs.add(extendRoamMessage(server, fence, "faraway", msgs[0], r))
                    return nmsgs
                }
            }
        }
        return msgs
    }

    private fun extendRoamMessage(
        server: Server,
        fence: FenceSpec,
        kind: String,
        baseMsg: String,
        match: RoamMatch,
    ): String {
        val roam = fence.roam!!
        val sb = StringBuilder(baseMsg.substring(0, baseMsg.length - 1))
        sb
            .append(",\"")
            .append(kind)
            .append("\":{\"key\":")
            .append(JsonStrings.append(roam.key))
            .append(",\"id\":")
            .append(JsonStrings.append(match.id))
            .append(",\"object\":")
        match.obj.appendJson(sb)
        sb.append(",\"meters\":").append(GoCompat.formatFloat(floor(match.meters * 1000) / 1000))
        if (roam.scan != "") {
            sb.append(",\"scan\":[")
            val col = server.cols[roam.key]
            if (col != null) {
                val self = col.get(match.id)
                if (self != null) {
                    sb.append("{\"id\":").append(JsonStrings.append(match.id)).append(",\"self\":true,\"object\":")
                    self.geo.appendJson(sb)
                    sb.append('}')
                }
                val pattern = match.id + roam.scan
                val iter: (Item) -> Boolean = { o ->
                    if (o.id != match.id && Glob.matches(pattern, o.id)) {
                        sb.append(",{\"id\":").append(JsonStrings.append(o.id)).append(",\"object\":")
                        o.geo.appendJson(sb)
                        sb.append('}')
                    }
                    true
                }
                val g = Glob.parse(pattern, false)
                if (g.limits[0] == "" && g.limits[1] == "") {
                    col.scan(false, null, iter)
                } else {
                    col.scanRange(g.limits[0], g.limits[1], false, null, iter)
                }
            }
            sb.append(']')
        }
        sb.append("}}")
        return sb.toString()
    }

    private fun makeMsg(
        command: String,
        group: String,
        detect: String,
        hookName: String,
        metas: List<FenceMeta>,
        key: String,
        t: ZonedDateTime,
        tail: String,
    ): String {
        val sb = StringBuilder()
        sb.append("{\"command\":\"").append(command)
        sb.append("\",\"group\":\"").append(group)
        sb.append("\",\"detect\":\"").append(detect).append('"')
        appendHookDetails(sb, hookName, metas)
        sb.append(",\"key\":").append(JsonStrings.append(key))
        sb.append(",\"time\":").append(jsonTime(t))
        sb.append(',').append(tail)
        return sb.toString()
    }

    private fun fenceMatchObject(
        fence: FenceSpec,
        o: Item?,
    ): Boolean {
        if (o == null || fence.roam != null) return false
        val area = fence.obj ?: return false
        return when (fence.cmd) {
            // nearby is an intersects on a circle
            "nearby", "intersects" -> o.geo.intersects(area)

            "within" -> o.geo.within(area)

            else -> false
        }
    }

    private fun fenceMatchNearbys(
        server: Server,
        fence: FenceSpec,
        obj: Item?,
    ): MutableList<RoamMatch> {
        val nearbys = ArrayList<RoamMatch>()
        if (obj == null) return nearbys
        val roam = fence.roam!!
        val col = server.cols[roam.key] ?: return nearbys
        val center = obj.geo.center()
        val r = Geo.rectFromCenter(center.y, center.x, roam.meters)
        val rect = GeoRect(Rect(Point(r.minLon, r.minLat), Point(r.maxLon, r.maxLat)))
        col.intersects(rect, 0, null) { o ->
            if (o.id != obj.id) {
                // Upstream quirk kept for compatibility: this measures the distance from the
                // candidate to itself, so the radius check never excludes anything.
                val meters = o.geo.distance(o.geo)
                if (meters <= roam.meters) {
                    val idMatch = if (roam.pattern) Glob.matches(roam.id, o.id) else roam.id == o.id
                    if (idMatch) nearbys.add(RoamMatch(o.id, o.geo, obj.geo.distance(o.geo)))
                }
            }
            true
        }
        return nearbys
    }

    private fun fenceMatchRoam(
        server: Server,
        fence: FenceSpec,
        obj: Item,
        old: Item?,
    ): Pair<List<RoamMatch>, List<RoamMatch>> {
        val oldNearbys = fenceMatchNearbys(server, fence, old)
        val newNearbys = fenceMatchNearbys(server, fence, obj)
        var i = 0
        while (i < oldNearbys.size) {
            val j = newNearbys.indexOfFirst { it.id == oldNearbys[i].id }
            if (j >= 0) {
                // dwelling: remove from old-nearbys
                oldNearbys[i] = oldNearbys[oldNearbys.size - 1]
                oldNearbys.removeAt(oldNearbys.size - 1)
                i--
                if (fence.nodwell) {
                    // no dwelling allowed: remove from both lists
                    newNearbys[j] = newNearbys[newNearbys.size - 1]
                    newNearbys.removeAt(newNearbys.size - 1)
                }
            }
            i++
        }
        // faraway distances are measured to the new object
        for (f in oldNearbys) f.meters = f.obj.distance(obj.geo)
        val order = compareBy<RoamMatch>({ it.meters }, { it.id })
        return newNearbys.sortedWith(order) to oldNearbys.sortedWith(order)
    }

    /** Go `time.Time.AppendFormat(b, "2006-01-02T15:04:05.999999999Z07:00")`, quoted. */
    fun jsonTime(t: ZonedDateTime): String {
        val sb = StringBuilder("\"")
        sb.append(
            String.format(
                "%04d-%02d-%02dT%02d:%02d:%02d",
                t.year,
                t.monthValue,
                t.dayOfMonth,
                t.hour,
                t.minute,
                t.second,
            ),
        )
        if (t.nano != 0) sb.append('.').append(String.format("%09d", t.nano).trimEnd('0'))
        val off = t.offset
        if (off == ZoneOffset.UTC) {
            sb.append('Z')
        } else {
            val secs = off.totalSeconds
            val sign = if (secs < 0) '-' else '+'
            val abs = kotlin.math.abs(secs)
            sb.append(sign).append(String.format("%02d:%02d", abs / 3600, (abs / 60) % 60))
        }
        return sb.append('"').toString()
    }
}

/** Tile38 `appendJSONString` (same encoding as `jsonString`). */
internal object JsonStrings {
    fun append(s: String): String = GoCompat.jsonString(s)
}

/** Hook/object group ids (Go `group.go`): one id per (hook, key, object) association. */
internal class Groups {
    private data class K(
        val hook: String,
        val key: String,
        val id: String,
    )

    private val byHook = TreeMap<K, String>(compareBy<K>({ it.hook }, { it.key }, { it.id }))
    private val byObject = TreeMap<K, String>(compareBy<K>({ it.key }, { it.id }, { it.hook }))

    fun connect(
        hook: String,
        key: String,
        id: String,
    ): String {
        val g = BsonId.next()
        val k = K(hook, key, id)
        byHook[k] = g
        byObject[k] = g
        return g
    }

    fun disconnect(
        hook: String,
        key: String,
        id: String,
    ) {
        val k = K(hook, key, id)
        byHook.remove(k)
        byObject.remove(k)
    }

    fun get(
        hook: String,
        key: String,
        id: String,
    ): String? = byHook[K(hook, key, id)]

    /** Disconnects all hooks from an object. */
    fun disconnectObject(
        key: String,
        id: String,
    ) = removeAll(byObject.tailMap(K("", key, id)).keys.takeWhile { it.key == key && it.id == id })

    /** Disconnects all hooks from the objects of a collection. */
    fun disconnectCollection(key: String) = removeAll(byObject.tailMap(K("", key, "")).keys.takeWhile { it.key == key })

    /** Disconnects all objects from a hook. */
    fun disconnectHook(hook: String) = removeAll(byHook.tailMap(K(hook, "", "")).keys.takeWhile { it.hook == hook })

    fun size(): Int = byHook.size

    fun clear() {
        byHook.clear()
        byObject.clear()
    }

    private fun removeAll(keys: List<K>) {
        for (k in keys) {
            byHook.remove(k)
            byObject.remove(k)
        }
    }
}

/** Go `bsonID`: a 12-byte, hex-encoded, BSON-ObjectId-like identifier. */
internal object BsonId {
    private val machine: ByteArray =
        run {
            val rnd = ByteArray(3).also { SecureRandom().nextBytes(it) }
            val host = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.ifEmpty { null } ?: String(rnd, Charsets.ISO_8859_1)
            MessageDigest.getInstance("MD5").digest(host.toByteArray()).copyOf(3)
        }
    private val process = ProcessHandle.current().pid().toInt() and 0xFFFF
    private val counter = AtomicInteger(SecureRandom().nextInt())

    fun next(): String {
        val b = ByteArray(12)
        val secs = (System.currentTimeMillis() / 1000).toInt()
        b[0] = (secs ushr 24).toByte()
        b[1] = (secs ushr 16).toByte()
        b[2] = (secs ushr 8).toByte()
        b[3] = secs.toByte()
        System.arraycopy(machine, 0, b, 4, 3)
        val c = counter.incrementAndGet()
        b[8] = (c ushr 24).toByte()
        b[9] = (c ushr 16).toByte()
        b[10] = (c ushr 8).toByte()
        b[11] = c.toByte()
        b[7] = (process ushr 8).toByte()
        b[8] = process.toByte()
        return b.joinToString("") { "%02x".format(it) }
    }
}
