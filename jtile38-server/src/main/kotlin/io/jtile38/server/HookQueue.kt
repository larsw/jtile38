// Port of the hook queue log of tile38 (internal/server/hooks.go, aof.go) and of the parts of
// github.com/tidwall/buntdb it relies on (append-only file format, expiration and auto-shrink),
// Copyright Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.server

import io.jtile38.json.Gjson
import io.jtile38.resp.CommandReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.TreeMap
import java.util.logging.Logger

/**
 * Queue of webhook messages waiting for delivery. Entries expire after 30 seconds, like upstream.
 *
 * When a [file] is given, the queue is persisted in the same format as Tile38's buntdb-backed
 * `queue.db`: an append-only log of RESP commands (`set key value ae <unix-seconds>`, `del key`)
 * with entries under `hook:log:<20-digit index>` and the last index under `hook:idx`. Undelivered
 * messages therefore survive a restart, and a `queue.db` written by Tile38 can be loaded.
 */
internal class HookQueue(
    private val file: File? = null,
    private val shrinkMinSize: Long = 32L * 1024 * 1024,
) {
    class Entry(
        val idx: Long,
        val hook: String,
        val message: String,
        val expiresAtMillis: Long,
    ) {
        val key: String get() = LOG_PREFIX + idxString(idx)
    }

    private val entries = TreeMap<Long, Entry>()
    private var qidx = 0L
    private var out: FileOutputStream? = null
    private var size = 0L
    private var lastShrinkSize = 0L
    private var dirty = false
    private var lastSync = 0L

    /** Loads the queue file (if any) and opens it for appending. */
    @Synchronized
    fun open(): HookQueue {
        val f = file ?: return this
        f.parentFile?.mkdirs()
        if (f.exists()) load(f)
        out = FileOutputStream(f, true)
        size = f.length()
        lastShrinkSize = size
        return this
    }

    @Synchronized
    fun close() {
        sync(force = true)
        out?.close()
        out = null
    }

    /** Queues messages; each message's `hook` member names the hook it belongs to. */
    @Synchronized
    fun add(messages: List<String>) {
        val exp = System.currentTimeMillis() + TTL_MILLIS
        val log = ByteArrayOutputStream()
        for (m in messages) {
            qidx++
            val e = Entry(qidx, Gjson.get(m, "hook").string(), m, exp)
            entries[qidx] = e
            writeSet(log, e)
        }
        writeRecord(log, "set", IDX_KEY, idxString(qidx))
        append(log)
    }

    /** Removes and returns the live entries for [hook], in queue order. */
    @Synchronized
    fun take(hook: String): List<Entry> {
        val log = ByteArrayOutputStream()
        purgeExpired(log)
        val taken = entries.values.filter { it.hook == hook }
        for (e in taken) {
            entries.remove(e.idx)
            writeRecord(log, "del", e.key)
        }
        append(log)
        return taken
    }

    /** Puts entries back after a failed delivery, keeping their original expiration. */
    @Synchronized
    fun reinsert(es: List<Entry>) {
        val now = System.currentTimeMillis()
        val log = ByteArrayOutputStream()
        for (e in es) {
            if (e.expiresAtMillis > now) {
                entries[e.idx] = e
                writeSet(log, e)
            }
        }
        append(log)
    }

    /** Number of pending messages (the `pending_events` stat). */
    @Synchronized
    fun size(): Int {
        val log = ByteArrayOutputStream()
        purgeExpired(log)
        append(log)
        return entries.size
    }

    /** Background maintenance: drops expired entries and syncs the file about once per second. */
    @Synchronized
    fun maintain() {
        val log = ByteArrayOutputStream()
        purgeExpired(log)
        append(log)
        sync(force = false)
    }

    private fun sync(force: Boolean) {
        val o = out ?: return
        val now = System.currentTimeMillis()
        if (dirty && (force || now - lastSync >= 1000)) {
            o.channel.force(false)
            dirty = false
            lastSync = now
        }
    }

    private fun purgeExpired(log: ByteArrayOutputStream) {
        val now = System.currentTimeMillis()
        val it = entries.values.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.expiresAtMillis <= now) {
                it.remove()
                writeRecord(log, "del", e.key)
            }
        }
    }

    private fun append(log: ByteArrayOutputStream) {
        val o = out ?: return
        if (log.size() == 0) return
        log.writeTo(o)
        size += log.size()
        dirty = true
        // buntdb auto-shrink: once the file is over 32 MB and has doubled since the last shrink
        if (size > shrinkMinSize && size > lastShrinkSize * 2) shrink()
    }

    /** Rewrites the file with only the live entries (buntdb `Shrink`). */
    private fun shrink() {
        val f = file ?: return
        val tmp = File(f.path + ".tmp")
        val buf = ByteArrayOutputStream()
        for (e in entries.values) writeSet(buf, e)
        writeRecord(buf, "set", IDX_KEY, idxString(qidx))
        FileOutputStream(tmp).use {
            buf.writeTo(it)
            it.channel.force(true)
        }
        out?.close()
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        out = FileOutputStream(f, true)
        size = f.length()
        lastShrinkSize = size
        dirty = false
    }

    private fun load(f: File) {
        val data = f.readBytes()
        val now = System.currentTimeMillis()
        var off = 0
        var good = 0
        while (off < data.size) {
            if (data[off] == 0.toByte()) {
                off++
                good = off
                continue
            }
            if (data[off] != '*'.code.toByte()) throw IllegalStateException("invalid queue file ${f.path} at offset $off")
            val cmd = CommandReader.readNextCommand(data, off, data.size - off) ?: break
            off += cmd.consumed
            good = off
            apply(cmd.args, now)
        }
        if (good < data.size) {
            LOG.warning("Truncating ${data.size - good} bytes of an incomplete record in ${f.path}")
            RandomAccessFile(f, "rw").use { it.setLength(good.toLong()) }
        }
        LOG.info("Hook queue loaded ${entries.size} pending messages")
    }

    private fun apply(
        args: List<String>,
        now: Long,
    ) {
        if (args.isEmpty()) return
        when (args[0].lowercase()) {
            "set" -> {
                if (args.size < 3 || args.size == 4 || args.size > 5) throw IllegalStateException("invalid queue record")
                val key = args[1]
                val value = args[2]
                var exat = Long.MAX_VALUE
                if (args.size == 5) {
                    val n = args[4].toLong()
                    exat =
                        when (args[3].lowercase()) {
                            "ae" -> n * 1000
                            "ex" -> now + n * 1000
                            else -> throw IllegalStateException("invalid queue record")
                        }
                }
                if (key == IDX_KEY) {
                    qidx = value.toLongOrNull() ?: 0
                } else if (key.startsWith(LOG_PREFIX)) {
                    val idx = key.substring(LOG_PREFIX.length).toLongOrNull() ?: return
                    if (exat > now) {
                        entries[idx] = Entry(idx, Gjson.get(value, "hook").string(), value, exat)
                    } else {
                        entries.remove(idx)
                    }
                }
            }

            "del" -> {
                val key = args.getOrNull(1) ?: return
                if (key.startsWith(LOG_PREFIX)) key.substring(LOG_PREFIX.length).toLongOrNull()?.let { entries.remove(it) }
            }

            "flushdb" -> {
                entries.clear()
                qidx = 0
            }

            else -> {
                throw IllegalStateException("invalid queue record '${args[0]}'")
            }
        }
    }

    private fun writeSet(
        log: ByteArrayOutputStream,
        e: Entry,
    ) = writeRecord(log, "set", e.key, e.message, "ae", (e.expiresAtMillis / 1000).toString())

    private fun writeRecord(
        log: ByteArrayOutputStream,
        vararg args: String,
    ) {
        log.writeBytes("*${args.size}\r\n".toByteArray())
        for (a in args) {
            log.writeBytes("$${a.length}\r\n".toByteArray())
            for (c in a) log.write(c.code)
            log.writeBytes("\r\n".toByteArray())
        }
    }

    companion object {
        const val LOG_PREFIX = "hook:log:"
        const val IDX_KEY = "hook:idx"
        private const val TTL_MILLIS = 30_000L
        private val LOG: Logger = Logger.getLogger("jtile38.queue")

        /** Go `uint64ToString`: zero-padded to 20 digits so keys sort numerically. */
        fun idxString(i: Long): String = i.toString().padStart(20, '0')
    }
}
