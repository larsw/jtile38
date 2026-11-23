// Port of tile38 internal/server/hooks.go and the hook queueing in aof.go, Copyright Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.server

import io.jtile38.json.Gjson
import io.jtile38.resp.RespValue
import io.jtile38.rtree.RTree
import java.util.TreeMap
import java.util.TreeSet
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Logger
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min

/** A webhook (SETHOOK) or channel (SETCHAN). */
class Hook(
    val name: String,
    val key: String,
    val endpoints: List<String>,
    val fence: FenceSpec,
    /** The search command that defines the fence, e.g. `NEARBY fleet FENCE POINT ...`. */
    val command: List<String>,
    val scanWriter: ScanWriter,
    val metas: List<FenceMeta>,
    val channel: Boolean,
    /** Expiration in Unix nanoseconds, or 0. */
    val expires: Long,
) {
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private var closed = false
    private var opened = false
    private var sig = 0

    fun sameAs(h: Hook): Boolean =
        key == h.key && name == h.name && endpoints == h.endpoints && expires == h.expires &&
            metas.map { it.name to it.value } == h.metas.map { it.name to it.value } && command == h.command

    /** Starts the delivery loop (webhooks only). */
    internal fun open(
        queue: HookQueue,
        endpoints: EndpointManager,
        sent: AtomicLong,
    ) {
        if (channel) return
        lock.withLock {
            if (opened) return
            opened = true
        }
        Thread.ofVirtual().name("jtile38-hook-$name").start { manager(queue, endpoints, sent) }
    }

    internal fun close() {
        if (channel) return
        lock.withLock {
            closed = true
            cond.signalAll()
        }
    }

    /** Wakes up the delivery loop because new messages may be queued. */
    internal fun signal() {
        if (channel) return
        lock.withLock {
            sig++
            cond.signalAll()
        }
    }

    private fun manager(
        queue: HookQueue,
        epm: EndpointManager,
        sent: AtomicLong,
    ) {
        while (true) {
            val s: Int
            lock.withLock {
                if (closed) return
                s = sig
            }
            if (!proc(queue, epm, sent)) {
                // a send failed; try again in a moment
                Thread.sleep(500)
                continue
            }
            lock.withLock {
                if (closed) return
                if (s == sig) cond.await()
            }
        }
    }

    /** Sends the queued messages; returns false if a send failed. */
    private fun proc(
        queue: HookQueue,
        epm: EndpointManager,
        sent: AtomicLong,
    ): Boolean {
        val entries = queue.take(name)
        for ((i, e) in entries.withIndex()) {
            var ok = false
            for (url in endpoints) {
                try {
                    epm.send(url, e.message)
                    ok = true
                    sent.incrementAndGet()
                    break
                } catch (ex: Exception) {
                    LOG.fine { "endpoint send error: ${e.idx}: $url: ${ex.message}" }
                }
            }
            if (!ok) {
                // reinsert this and all following entries
                queue.reinsert(entries.subList(i, entries.size))
                return false
            }
        }
        return true
    }

    private companion object {
        val LOG: Logger = Logger.getLogger("jtile38.hooks")
    }
}

/**
 * Queue of webhook messages waiting for delivery. Entries expire after 30 seconds, like
 * upstream's buntdb-backed queue. Unlike upstream, the queue is kept in memory only.
 */
internal class HookQueue {
    class Entry(
        val idx: Long,
        val hook: String,
        val message: String,
        val expiresAt: Long,
    )

    private val entries = TreeMap<Long, Entry>()
    private var qidx = 0L

    @Synchronized
    fun add(messages: List<String>) {
        val exp = System.nanoTime() + TTL_NANOS
        for (m in messages) {
            qidx++
            entries[qidx] = Entry(qidx, Gjson.get(m, "hook").string(), m, exp)
        }
    }

    /** Removes and returns the live entries for [hook], in queue order. */
    @Synchronized
    fun take(hook: String): List<Entry> {
        val now = System.nanoTime()
        entries.values.removeIf { it.expiresAt <= now }
        val out = entries.values.filter { it.hook == hook }
        out.forEach { entries.remove(it.idx) }
        return out
    }

    @Synchronized
    fun reinsert(es: List<Entry>) {
        val now = System.nanoTime()
        for (e in es) if (e.expiresAt > now) entries[e.idx] = e
    }

    /** Number of pending messages (the `pending_events` stat). */
    @Synchronized
    fun size(): Int {
        val now = System.nanoTime()
        entries.values.removeIf { it.expiresAt <= now }
        return entries.size
    }

    private companion object {
        const val TTL_NANOS = 30_000_000_000L
    }
}

/** Registered hooks and channels plus their spatial indexes. Guarded by the server lock. */
internal class HookRegistry {
    val hooks = TreeMap<String, Hook>()
    val hooksOut = TreeMap<String, Hook>()
    val hookTree = RTree<Hook>()
    val hookCross = RTree<Hook>()
    val hookExpires = TreeSet<Hook>(compareBy<Hook>({ it.expires }, { it.name }))

    fun add(h: Hook) {
        hooks[h.name] = h
        if (h.fence.detect == null || "outside" in h.fence.detect) hooksOut[h.name] = h
        h.fence.obj?.rect()?.let { r ->
            hookTree.insert(RTree.roundDown(r.min.x), RTree.roundDown(r.min.y), RTree.roundUp(r.max.x), RTree.roundUp(r.max.y), h)
            if (h.fence.detect?.contains("cross") == true) {
                hookCross.insert(RTree.roundDown(r.min.x), RTree.roundDown(r.min.y), RTree.roundUp(r.max.x), RTree.roundUp(r.max.y), h)
            }
        }
        if (h.expires != 0L) hookExpires.add(h)
    }

    fun remove(h: Hook) {
        hooks.remove(h.name)
        hooksOut.remove(h.name)
        if (h.expires != 0L) hookExpires.remove(h)
        h.fence.obj?.rect()?.let { r ->
            hookTree.delete(RTree.roundDown(r.min.x), RTree.roundDown(r.min.y), RTree.roundUp(r.max.x), RTree.roundUp(r.max.y), h)
            if (h.fence.detect?.contains("cross") == true) {
                hookCross.delete(RTree.roundDown(r.min.x), RTree.roundDown(r.min.y), RTree.roundUp(r.max.x), RTree.roundUp(r.max.y), h)
            }
        }
    }

    /** Hooks that may be affected by a write (Go `getQueueCandidates`), ordered by name. */
    fun candidates(d: CommandDetails): List<Hook> {
        val c = TreeMap<String, Hook>()
        for (h in hooksOut.values) if (h.key == d.key) c[h.name] = h
        val search: (Double, Double, Double, Double, RTree<Hook>) -> Unit = { minX, minY, maxX, maxY, tree ->
            tree.search(RTree.roundDown(minX), RTree.roundDown(minY), RTree.roundUp(maxX), RTree.roundUp(maxY)) { _, _, _, _, h ->
                if (h.key == d.key) c[h.name] = h
                true
            }
        }
        val old = d.old
        val obj = d.obj
        if (old != null && obj != null && hookCross.size() > 0) {
            val r1 = old.rect()
            val r2 = obj.rect()
            search(min(r1.min.x, r2.min.x), min(r1.min.y, r2.min.y), max(r1.max.x, r2.max.x), max(r1.max.y, r2.max.y), hookCross)
        }
        if (old != null) old.rect().let { search(it.min.x, it.min.y, it.max.x, it.max.y, hookTree) }
        if (obj != null) obj.rect().let { search(it.min.x, it.min.y, it.max.x, it.max.y, hookTree) }
        return c.values.toList()
    }

    /** Hooks or channels whose name matches [pattern], in name order. */
    fun byPattern(
        pattern: String,
        channel: Boolean,
    ): List<Hook> {
        val g = Glob.parse(pattern, false)
        val upper = g.limits[1]
        val out = ArrayList<Hook>()
        for (h in hooks.tailMap(g.limits[0], true).values) {
            if (upper != "" && h.name > upper) break
            if (h.channel == channel && Glob.matches(pattern, h.name)) out.add(h)
        }
        return out
    }

    fun clear() {
        hooks.values.forEach { it.close() }
        hooks.clear()
        hooksOut.clear()
        hookTree.clear()
        hookCross.clear()
        hookExpires.clear()
    }
}

/** SETHOOK name endpoint (META k v ...) (EX s) NEARBY|WITHIN|INTERSECTS key FENCE ... ; SETCHAN name ... */
internal fun Server.cmdSetHook(msg: Message): WriteResult {
    val start = System.nanoTime()
    val channel = msg.command == "setchan"
    val vs = msg.args
    var i = 1

    fun next(): String? = if (i < vs.size) vs[i++] else null
    val name = next()?.takeIf { it != "" } ?: throw Errors.invalidNumberOfArguments()
    val endpoints: List<String>
    if (channel) {
        endpoints = listOf("local://$name")
    } else {
        val urls = next()?.takeIf { it != "" } ?: throw Errors.invalidNumberOfArguments()
        endpoints = EndpointManager.splitUrls(urls)
        for (url in endpoints) {
            try {
                endpointManager.validate(url)
            } catch (e: EndpointException) {
                log.warning("sethook: ${e.message}")
                throw Errors.invalidArgument(url)
            }
        }
    }
    val metaMap = HashMap<String, String>()
    var expires = 0.0
    var expiresSet = false
    var cmdStart: Int
    var cmdlc: String
    val types: Set<String>
    while (true) {
        cmdStart = i
        val cmd = next()?.takeIf { it != "" } ?: throw Errors.invalidNumberOfArguments()
        cmdlc = cmd.lowercase()
        when (cmdlc) {
            "meta" -> {
                val k = next()?.takeIf { it != "" } ?: throw Errors.invalidNumberOfArguments()
                val v = next()?.takeIf { it != "" } ?: throw Errors.invalidNumberOfArguments()
                metaMap[k] = v
                continue
            }

            "ex" -> {
                val s = next()?.takeIf { it != "" } ?: throw Errors.invalidNumberOfArguments()
                expires = GoCompat.parseFloat(s) ?: throw Errors.invalidArgument(s)
                expiresSet = true
                continue
            }

            "nearby" -> {
                types = NEARBY_TYPES
            }

            "within", "intersects" -> {
                types = WITHIN_OR_INTERSECTS_TYPES
            }

            else -> {
                throw Errors.invalidArgument(cmd)
            }
        }
        break
    }
    val a = searchArgs(cmdlc, vs.subList(i, vs.size), types, fromFence = true)
    if (!a.fence) throw CommandException("missing FENCE argument")
    val commandArgs = vs.subList(cmdStart, vs.size).toList()
    val metas = metaMap.entries.map { FenceMeta(it.key, it.value) }.sortedBy { it.name }
    val cmsg = Message(commandArgs, msg.connType, msg.outputType)
    val hook =
        Hook(
            name,
            a.key,
            endpoints,
            a.fenceSpec(cmdlc),
            commandArgs,
            fenceScanWriter(cmsg, a),
            metas,
            channel,
            if (expiresSet) nowNanos() + (expires * 1e9).toLong() else 0L,
        )
    val prev = hookRegistry.hooks[name]
    if (prev != null) {
        if (prev.channel != channel) throw CommandException("hooks and channels cannot share the same name")
        if (prev.sameAs(hook)) {
            prev.signal()
            val res = if (msg.outputType == OutputType.JSON) okJson(start) else RespValue.integer(0)
            return WriteResult(res, CommandDetails())
        }
        prev.close()
        hookRegistry.remove(prev)
        groups.disconnectHook(name)
    }
    hookRegistry.add(hook)
    hook.open(hookQueue, endpointManager, statsMsgsSent)
    val res = if (msg.outputType == OutputType.JSON) okJson(start) else RespValue.integer(1)
    return WriteResult(res, CommandDetails(msg.command, updated = true))
}

/** Removes a hook or channel; returns true if it existed. */
internal fun Server.delHookOp(
    name: String,
    channel: Boolean,
): Boolean {
    val hook = hookRegistry.hooks[name]
    if (hook == null || hook.channel != channel) return false
    hook.close()
    hookRegistry.remove(hook)
    groups.disconnectHook(name)
    return true
}

/** DELHOOK name / DELCHAN name */
internal fun Server.cmdDelHook(msg: Message): WriteResult {
    val start = System.nanoTime()
    val channel = msg.command == "delchan"
    if (msg.args.size != 2 || msg.args[1] == "") throw Errors.invalidNumberOfArguments()
    val updated = delHookOp(msg.args[1], channel)
    val res = if (msg.outputType == OutputType.JSON) okJson(start) else RespValue.integer(if (updated) 1 else 0)
    return WriteResult(res, CommandDetails(msg.command, updated = updated))
}

/** PDELHOOK pattern / PDELCHAN pattern */
internal fun Server.cmdPdelHook(msg: Message): WriteResult {
    val start = System.nanoTime()
    val channel = msg.command == "pdelchan"
    if (msg.args.size != 2 || msg.args[1] == "") throw Errors.invalidNumberOfArguments()
    var count = 0
    for (h in hookRegistry.byPattern(msg.args[1], channel)) {
        delHookOp(h.name, channel)
        count++
    }
    val res = if (msg.outputType == OutputType.JSON) okJson(start) else RespValue.integer(count)
    return WriteResult(res, CommandDetails(msg.command, updated = count > 0))
}

/** HOOKS pattern / CHANS pattern */
internal fun Server.cmdHooks(msg: Message): RespValue {
    val start = System.nanoTime()
    val channel = msg.command == "chans"
    if (msg.args.size != 2 || msg.args[1] == "") throw Errors.invalidNumberOfArguments()
    val hooks = hookRegistry.byPattern(msg.args[1], channel)
    if (msg.outputType == OutputType.JSON) {
        val now = nowNanos()
        val sb = StringBuilder("{\"ok\":true,").append(if (channel) "\"chans\":[" else "\"hooks\":[")
        hooks.forEachIndexed { i, h ->
            val ttl = if (h.expires == 0L) -1 else max(((h.expires - now) / 1_000_000_000L).toInt(), 0)
            if (i > 0) sb.append(',')
            sb.append("{\"name\":").append(GoCompat.jsonString(h.name))
            sb.append(",\"key\":").append(GoCompat.jsonString(h.key))
            sb.append(",\"ttl\":").append(ttl)
            if (!channel) sb.append(",\"endpoints\":[").append(h.endpoints.joinToString(",") { GoCompat.jsonString(it) }).append(']')
            sb.append(",\"command\":[").append(h.command.joinToString(",") { GoCompat.jsonString(it) }).append(']')
            sb.append(",\"meta\":{")
            sb.append(h.metas.joinToString(",") { GoCompat.jsonString(it.name) + ":" + GoCompat.jsonString(it.value) })
            sb.append("}}")
        }
        sb.append("],\"elapsed\":\"").append(elapsed(start)).append("\"}")
        return RespValue.string(sb.toString())
    }
    return RespValue.array(
        hooks.map { h ->
            RespValue.array(
                listOf(
                    RespValue.string(h.name),
                    RespValue.string(h.key),
                    RespValue.array(h.endpoints.map { RespValue.string(it) }),
                    RespValue.array(h.command.map { RespValue.string(it) }),
                    RespValue.array(h.metas.flatMap { listOf(RespValue.string(it.name), RespValue.string(it.value)) }),
                ),
            )
        },
    )
}

/** Matches a write against all candidate hooks; publishes channel messages and queues webhook messages. */
internal fun Server.queueHooks(d: CommandDetails) {
    val cmsgs = ArrayList<String>()
    val wmsgs = ArrayList<String>()
    val whooks = ArrayList<Hook>()
    for (hook in hookRegistry.candidates(d)) {
        val msgs = Fences.match(this, hook.name, hook.scanWriter, hook.fence, hook.metas, d)
        if (msgs.isEmpty()) continue
        if (hook.channel) {
            cmsgs.addAll(msgs)
        } else {
            wmsgs.addAll(msgs)
            whooks.add(hook)
        }
    }
    if (cmsgs.isEmpty() && wmsgs.isEmpty()) return
    for (m in sortMsgs(cmsgs)) pubsub.publish(Gjson.get(m, "hook").string(), m)
    if (wmsgs.isNotEmpty()) {
        hookQueue.add(sortMsgs(wmsgs))
        whooks.forEach { it.signal() }
    }
}

/** Stable sort by detect weight, then hook name (Go `sortMsgs`). */
private fun sortMsgs(msgs: List<String>): List<String> {
    if (msgs.size < 2) return msgs

    fun code(detect: String) =
        when (detect) {
            "exit" -> 1
            "outside" -> 2
            "enter" -> 3
            "inside" -> 4
            else -> 0
        }
    return msgs.sortedWith(
        compareBy<String>({ code(Gjson.get(it, "detect").string()) }, { Gjson.get(it, "hook").string() }),
    )
}
