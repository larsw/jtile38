// Port of tile38 internal/server/follow.go, checksum.go, readonly.go and the AOF/AOFMD5 commands of
// aof.go, Copyright Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.server

import io.jtile38.resp.CommandReader
import io.jtile38.resp.RespReader
import io.jtile38.resp.RespValue
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.concurrent.read
import kotlin.concurrent.write

private val followLog = Logger.getLogger("io.jtile38.server.Follow")

/** Size of the AOF blocks compared by checksum when a follower (re)connects. */
private const val CHECKSUM_SIZE = 512 * 1024

/** Thrown when the follow target changed, which ends the current follow loop. */
internal object NoLongerFollowing : IOException("no longer following") {
    private fun readResolve(): Any = NoLongerFollowing
}

/** Go's io.EOF as a command error ("EOF"). */
internal class AofEof : IOException("EOF")

/** bit flags of [Server.fcupflags] */
internal const val BIT_CAUGHT_UP_ONCE = 1 // follower caught up at least once in the past
internal const val BIT_CAUGHT_UP = 2 // follower is fully caught up to leader

/** A RESP connection to another server (Go `RESPConn`). */
internal class RespConn private constructor(
    private val sock: Socket,
) : Closeable {
    private val out = BufferedOutputStream(sock.getOutputStream())
    val reader = RespReader(sock.getInputStream())

    /** Sends a command and reads its reply. */
    fun doCmd(vararg args: Any): RespValue {
        out.write(RespValue.array(args.map { RespValue.string(it.toString()) }).marshal())
        out.flush()
        return reader.readValue()
    }

    /** Like Go's RESPConn.Close, says QUIT before closing. */
    override fun close() {
        runCatching {
            out.write(RespValue.array(listOf(RespValue.string("quit"))).marshal())
            out.flush()
        }
        sock.close()
    }

    companion object {
        /** Connects to [host]:[port] like Go's net.DialTimeout, trying each address of the host. */
        fun dial(
            host: String,
            port: Int,
            timeoutMs: Int,
        ): RespConn {
            val addr = "$host:$port"
            val ips =
                try {
                    InetAddress.getAllByName(host)
                } catch (_: UnknownHostException) {
                    throw IOException("dial tcp: lookup $host: no such host")
                }
            var last: IOException? = null
            for (ip in ips) {
                val sock = Socket()
                try {
                    sock.connect(InetSocketAddress(ip, port), timeoutMs)
                    return RespConn(sock)
                } catch (e: ConnectException) {
                    sock.close()
                    last = IOException("dial tcp ${ipPort(ip, port)}: connect: connection refused")
                } catch (e: SocketTimeoutException) {
                    sock.close()
                    last = IOException("dial tcp $addr: i/o timeout")
                } catch (e: IOException) {
                    sock.close()
                    last = IOException("dial tcp ${ipPort(ip, port)}: ${e.message}")
                }
            }
            throw last ?: IOException("dial tcp $addr: no address")
        }

        private fun ipPort(
            ip: InetAddress,
            port: Int,
        ): String = if (ip is java.net.Inet6Address) "[${ip.hostAddress.substringBefore('%')}]:$port" else "${ip.hostAddress}:$port"
    }
}

/** The error of a RESP reply, as Go's `resp.Value.Error()` text, or null. */
private fun RespValue.errorText(): String? = (this as? RespValue.Error)?.message

// ---- follower ----------------------------------------------------------------

internal fun Server.setCaughtUp(caughtUp: Boolean) {
    val flags = if (caughtUp) BIT_CAUGHT_UP or BIT_CAUGHT_UP_ONCE else fcupflags.get() and BIT_CAUGHT_UP_ONCE
    fcupflags.set(flags)
}

internal fun Server.caughtUp(): Boolean = (fcupflags.get() and BIT_CAUGHT_UP) == BIT_CAUGHT_UP

internal fun Server.caughtUpOnce(): Boolean = (fcupflags.get() and BIT_CAUGHT_UP_ONCE) == BIT_CAUGHT_UP_ONCE

private fun followDoLeaderAuth(
    conn: RespConn,
    auth: String,
) {
    val v = conn.doCmd("auth", auth)
    v.errorText()?.let { throw IOException(it) }
    if (v.toString() != "OK") throw IOException("cannot follow: auth no ok")
}

private fun doServer(conn: RespConn): Map<String, String> {
    val v = conn.doCmd("server")
    v.errorText()?.let { throw IOException(it) }
    val arr = (v as? RespValue.Array)?.values ?: emptyList()
    val m = HashMap<String, String>()
    for (i in 0 until arr.size / 2) m[arr[i * 2].toString()] = arr[i * 2 + 1].toString()
    return m
}

/** Checks that [m] (the SERVER reply of the leader) describes a server we may follow. */
private fun Server.checkLeader(m: Map<String, String>) {
    if ((m["id"] ?: "") == "") throw IOException("cannot follow: invalid id")
    if (m["id"] == config.serverId) throw IOException("cannot follow self")
    if ((m["following"] ?: "") != "") throw IOException("cannot follow a follower")
}

/** FOLLOW host port | FOLLOW no one (also SLAVEOF). */
internal fun Server.cmdFollow(msg: Message): RespValue {
    val start = System.nanoTime()
    val vs = msg.args
    if (vs.size < 3 || vs[1] == "" || vs[2] == "") throw Errors.invalidNumberOfArguments()
    if (vs.size != 3) throw Errors.invalidNumberOfArguments()
    val host = vs[1].lowercase()
    val sport = vs[2].lowercase()
    val update: Boolean
    if (host == "no" && sport == "one") {
        update =
            lock.write {
                val u = config.followHost != "" || config.followPort != 0L
                config.followHost = ""
                config.followPort = 0
                u
            }
    } else {
        val port = GoCompat.parseUint(sport) ?: throw Errors.invalidArgument(sport)
        val (u, auth) = lock.read { (config.followHost != host || config.followPort != port) to config.leaderAuth }
        update = u
        if (update) {
            // the leader is contacted without holding the server lock
            try {
                RespConn.dial(host, port.toInt(), 2000).use { conn ->
                    if (auth != "") followDoLeaderAuth(conn, auth)
                    checkLeader(doServer(conn))
                }
            } catch (e: IOException) {
                val m = e.message ?: ""
                throw CommandException(if (m.startsWith("cannot follow")) m else "cannot follow: $m")
            }
        }
        lock.write {
            config.followHost = host
            config.followPort = port
        }
    }
    lock.write { config.write(false) }
    if (update) {
        val fc = followc.incrementAndGet()
        if (config.followHost != "") {
            followLog.info("following new host '$host' '$sport'.")
            startFollowing(config.followHost, config.followPort.toInt(), fc)
        } else {
            followLog.info("following no one")
        }
    }
    return if (msg.outputType == OutputType.JSON) okJson(start) else RespValue.OK
}

/** Starts the background follow loop (Go `go s.follow(...)`). */
internal fun Server.startFollowing(
    host: String,
    port: Int,
    fc: Long,
) {
    Thread.ofVirtual().name("jtile38-follow").start { follow(host, port, fc) }
}

private fun Server.follow(
    host: String,
    port: Int,
    fc: Long,
) {
    while (!isStopped()) {
        try {
            followStep(host, port, fc)
        } catch (_: NoLongerFollowing) {
            return
        } catch (_: java.io.EOFException) {
            // the leader closed the connection
        } catch (e: Exception) {
            if (isStopped()) return
            followLog.warning("follow: ${e.message ?: e.toString()}")
        }
        Thread.sleep(1000)
    }
}

private fun Server.followStep(
    host: String,
    port: Int,
    fc: Long,
) {
    if (followc.get() != fc) throw NoLongerFollowing
    val auth =
        lock.write {
            faofsz.set(0)
            setCaughtUp(false)
            config.leaderAuth
        }
    val addr = "$host:$port"
    // check if we are following self
    val conn =
        try {
            RespConn.dial(host, port, 2000)
        } catch (e: IOException) {
            throw IOException("cannot follow: ${e.message}")
        }
    conn.use {
        if (auth != "") {
            try {
                followDoLeaderAuth(conn, auth)
            } catch (e: IOException) {
                throw IOException("cannot follow: ${e.message}")
            }
        }
        val m =
            try {
                doServer(conn)
            } catch (e: IOException) {
                throw IOException("cannot follow: ${e.message}")
            }
        checkLeader(m)

        // verify checksum
        val pos = followCheckSome(host, port, fc, auth)

        // send the replication port to the leader
        val p = if (config.announcePort != 0L) config.announcePort else this.port.toLong()
        var v = conn.doCmd("replconf", "listening-port", p)
        v.errorText()?.let { throw IOException(it) }
        if (v.toString() != "OK") throw IOException("invalid response to replconf request")

        // send the replication ip to the leader
        val ip = config.announceIP
        if (ip != "") {
            v = conn.doCmd("replconf", "ip-address", ip)
            v.errorText()?.let { throw IOException(it) }
            if (v.toString() != "OK") throw IOException("invalid response to replconf request")
        }
        followLog.fine("follow: $addr :replconf")

        v = conn.doCmd("aof", pos)
        v.errorText()?.let { throw IOException(it) }
        if (v.toString() != "OK") throw IOException("invalid response to aof live request")
        followLog.fine("follow: $addr :read aof")

        val aofSize = m["aof_size"]?.toLongOrNull() ?: throw IOException("strconv.ParseInt: parsing \"${m["aof_size"]}\": invalid syntax")
        faofsz.set(aofSize)
        var caughtUp = pos >= aofSize
        if (caughtUp) {
            setCaughtUp(true)
            followLog.info("caught up")
        }
        while (true) {
            val cmd = conn.reader.readValue()
            val vals = (cmd as? RespValue.Array)?.values ?: throw IOException("invalid multibulk")
            val svals = vals.map { it.toString() }
            val aofsz = followHandleCommand(svals, fc)
            faofsz.set(aofsz)
            if (!caughtUp && aofsz >= aofSize) {
                caughtUp = true
                lock.write {
                    flushAof()
                    setCaughtUp(true)
                }
                followLog.info("caught up")
            }
        }
    }
}

/** Applies one command of the leader's AOF stream; returns the follower's AOF size. */
private fun Server.followHandleCommand(
    args: List<String>,
    fc: Long,
): Long =
    lock.write {
        if (followc.get() != fc) throw NoLongerFollowing
        val msg = Message(args, ConnType.RESP, OutputType.RESP)
        try {
            executeWrite(msg)
        } catch (e: CommandException) {
            // like loading the AOF, missing keys or ids are not fatal
            if (e.message != "key not found" && e.message != "id not found") throw IOException(e.message)
        }
        if (aofBuf.size() > 10240) flushAof()
        aofSize()
    }

// ---- checksums -----------------------------------------------------------------

/** MD5 of [size] bytes of the AOF at [pos] (Go `checksum`); throws [AofEof] when out of range. */
internal fun Server.checksum(
    pos: Long,
    size: Long,
): String {
    if (pos + size > aofSize()) throw AofEof()
    val file = aofFile ?: throw IOException("aof disabled")
    val md5 = MessageDigest.getInstance("MD5")
    RandomAccessFile(file, "r").use { f ->
        if (size == 0L) {
            // Go seeks to aofsz, which may be past the end of the flushed file
            if (pos >= aofSize()) throw AofEof()
        } else {
            f.seek(pos)
            val buf = ByteArray(64 * 1024)
            var left = size
            while (left > 0) {
                val n = f.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw AofEof()
                md5.update(buf, 0, n)
                left -= n
            }
        }
    }
    return md5.digest().joinToString("") { "%02x".format(it) }
}

private fun connAofMd5(
    conn: RespConn,
    pos: Long,
    size: Long,
): String {
    val v = conn.doCmd("aofmd5", pos, size)
    val err = v.errorText()
    if (err != null) {
        if (err == "ERR EOF" || err == "EOF") throw AofEof()
        throw IOException(err)
    }
    val sum = v.toString()
    if (sum.length != 32) throw IOException("checksum not ok")
    return sum
}

private fun Server.matchChecksums(
    conn: RespConn,
    pos: Long,
    size: Long,
): Boolean {
    val sum =
        try {
            checksum(pos, size)
        } catch (_: AofEof) {
            return false
        }
    val csum =
        try {
            connAofMd5(conn, pos, size)
        } catch (_: AofEof) {
            return false
        }
    return csum == sum
}

/**
 * Returns the end of the last complete command at or before [startPos] (Go
 * `getEndOfLastValuePositionInFile`, which reads backwards for a `*` that starts a multibulk).
 */
private fun endOfLastValuePosition(
    file: File,
    startPos: Long,
): Long {
    val data = RandomAccessFile(file, "r").use { f -> ByteArray(minOf(f.length(), startPos).toInt()).also { f.readFully(it) } }
    var pos = startPos.toInt()
    while (true) {
        if (pos <= 0) throw AofEof()
        pos--
        if (pos >= data.size) continue
        if (data[pos] == '*'.code.toByte()) {
            val cmd =
                try {
                    CommandReader.readNextCommand(data, pos, data.size - pos)
                } catch (_: Exception) {
                    null
                }
            if (cmd != null && cmd.args.isNotEmpty()) return pos + cmd.consumed.toLong()
        }
    }
}

/**
 * Finds the position up to which the local AOF matches the leader's (Go `followCheckSome`): a
 * binary search over 512 KB blocks compared with AOFMD5. Holds the server lock while it runs.
 */
private fun Server.followCheckSome(
    host: String,
    port: Int,
    fc: Long,
    auth: String,
): Long =
    lock.write {
        if (followc.get() != fc) throw NoLongerFollowing
        if (aofSize() < CHECKSUM_SIZE) return@write 0L
        flushAof()
        var pos = 0L
        RespConn.dial(host, port, 2000).use { conn ->
            if (auth != "") followDoLeaderAuth(conn, auth)
            var min = 0L
            var max = aofSize() - CHECKSUM_SIZE
            var limit = aofSize()
            if (matchChecksums(conn, min, CHECKSUM_SIZE.toLong())) {
                min += CHECKSUM_SIZE // bump up the min
                while (true) {
                    if (max < min || max + CHECKSUM_SIZE > limit) {
                        pos = min
                        break
                    }
                    if (matchChecksums(conn, max, CHECKSUM_SIZE.toLong())) {
                        min = max + CHECKSUM_SIZE
                    } else {
                        limit = max
                    }
                    max = (limit - min) / 2 - CHECKSUM_SIZE / 2 + min
                }
            }
        }
        val file = aofFile!!
        val fullpos = pos
        if (pos == 0L) {
            // the local AOF does not match the leader's at all: start over with an empty file
            aof?.close()
            aof = FileOutputStream(file, false)
            aofBuf.reset()
            // Go keeps the old size here, which makes the follower report "caught up" too early
            aofSize.set(0)
            return@write 0L
        }
        // we want to truncate at a command location: search for the nearest command
        pos = endOfLastValuePosition(file, fullpos)
        if (pos == fullpos) {
            followLog.fine("follow: aof fully intact")
            return@write pos
        }
        followLog.warning("truncating aof to $pos")
        try {
            aof?.close()
            RandomAccessFile(file, "rw").use { it.setLength(pos) }
            aof = FileOutputStream(file, true)
            // reset the entire system
            followLog.info("reloading aof commands")
            cols.clear()
            aofSize.set(0)
            reloadAof(file)
            if (aofSize() != pos) throw IOException("aof size mismatch during reload, possible data loss.")
        } catch (e: Exception) {
            followLog.log(Level.SEVERE, "could not reload aof, possible data loss", e)
            throw e
        }
        pos
    }

// ---- leader side ---------------------------------------------------------------

/** REPLCONF listening-port port | ip-address ip */
internal fun Server.cmdReplConf(msg: Message): RespValue {
    val start = System.nanoTime()
    val vs = msg.args
    if (vs.size < 2 || vs[1] == "") throw Errors.invalidNumberOfArguments()
    if (vs.size < 3 || vs[2] == "") throw Errors.invalidNumberOfArguments()
    val cmd = vs[1]
    val v = vs[2]
    val client = msg.client
    when (cmd) {
        "listening-port" -> {
            val port = v.toIntOrNull() ?: throw Errors.invalidArgument(v)
            if (client != null) {
                client.replPort = port
                return if (msg.outputType == OutputType.JSON) okJson(start) else RespValue.OK
            }
        }

        "ip-address" -> {
            if (client != null) {
                client.replAddr = v
                return if (msg.outputType == OutputType.JSON) okJson(start) else RespValue.OK
            }
        }
    }
    throw CommandException("cannot find follower")
}

/** AOFMD5 pos size */
internal fun Server.cmdAofMd5(msg: Message): RespValue {
    val start = System.nanoTime()
    val args = msg.args
    if (args.size != 3) throw Errors.invalidNumberOfArguments()
    val pos = args[1].toLongOrNull()?.takeIf { it >= 0 } ?: throw Errors.invalidArgument(args[1])
    val size = args[2].toLongOrNull()?.takeIf { it >= 0 } ?: throw Errors.invalidArgument(args[2])
    val sum =
        try {
            checksum(pos, size)
        } catch (e: IOException) {
            throw CommandException(e.message ?: "EOF")
        }
    if (msg.outputType == OutputType.JSON) {
        return RespValue.string("{\"ok\":true,\"md5\":\"$sum\",\"elapsed\":\"${elapsed(start)}\"}")
    }
    return RespValue.simple(sum)
}

/** AOF pos: streams the AOF from [pos] to a follower. */
internal fun Server.cmdAof(msg: Message): RespValue {
    val file = aofFile
    if (aof == null || file == null) throw CommandException("aof disabled")
    val args = msg.args
    if (args.size != 2) throw Errors.invalidNumberOfArguments()
    val pos = args[1].toLongOrNull()?.takeIf { it >= 0 } ?: throw Errors.invalidArgument(args[1])
    if (file.length() < pos) throw CommandException("pos is too big, must be less that the aof_size of leader")
    throw GoLive.Aof(pos)
}

/** A follower's live AOF stream; closing it ends the stream (on AOFSHRINK, for example). */
internal class AofStream(
    private val sock: Socket?,
    val file: RandomAccessFile,
) : Closeable {
    @Volatile var closed = false

    override fun close() {
        closed = true
        runCatching { file.close() }
        runCatching { sock?.close() }
    }
}

/** Streams the AOF to a follower from [pos] until either side closes (Go `liveAOF`). */
internal fun Server.goLiveAof(
    pos: Long,
    input: LiveReader,
    out: OutputStream,
    sock: Socket?,
) {
    val file = lock.read { aofFile } ?: return
    val f = RandomAccessFile(file, "r")
    val stream = AofStream(sock, f)
    lock.write { aofStreams.add(stream) }
    try {
        out.write("+OK\r\n".toByteArray())
        out.flush()
        f.seek(pos)
        // any incoming message ends the connection
        Thread.ofVirtual().start {
            try {
                input.readMessages()
            } catch (_: Exception) {
            } finally {
                stream.close()
                aofSignal()
            }
        }
        val buf = ByteArray(64 * 1024)
        while (true) {
            val gen = aofFlushes.get()
            val n = f.read(buf)
            if (n > 0) {
                out.write(buf, 0, n)
                out.flush()
            } else {
                // wait for the next flush of the AOF
                awaitAofFlush(gen)
                if (isStopped() || stream.closed) return
            }
        }
    } catch (_: IOException) {
        // the follower left, or the AOF was swapped by AOFSHRINK
    } finally {
        lock.write { aofStreams.remove(stream) }
        stream.close()
    }
}

// ---- READONLY ------------------------------------------------------------------

/** READONLY yes|no */
internal fun Server.cmdReadOnly(msg: Message): RespValue {
    val start = System.nanoTime()
    val args = msg.args
    if (args.size != 2) throw Errors.invalidNumberOfArguments()
    when (args[1]) {
        "yes", "no" -> {}

        else -> {
            throw Errors.invalidArgument(args[1])
        }
    }
    var updated = false
    if (args[1] == "yes") {
        if (!config.readOnly) {
            updated = true
            config.readOnly = true
            followLog.info("read only")
        }
    } else if (config.readOnly) {
        updated = true
        config.readOnly = false
        followLog.info("read write")
    }
    if (updated) config.write(false)
    return if (msg.outputType == OutputType.JSON) okJson(start) else RespValue.OK
}
