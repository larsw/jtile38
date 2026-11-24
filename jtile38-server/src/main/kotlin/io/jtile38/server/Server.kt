// Port of tile38 internal/server/server.go and aof.go (core parts), Copyright Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.server

import io.jtile38.geojson.ParseOptions
import io.jtile38.resp.CommandKind
import io.jtile38.resp.CommandReader
import io.jtile38.resp.ProtocolException
import io.jtile38.resp.RespValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.concurrent.read
import kotlin.concurrent.write

const val VERSION = "0.1.0-SNAPSHOT"

/** Server options. */
class ServerOptions(
    val host: String = "127.0.0.1",
    val port: Int = 9851,
    val dir: String = "data",
    val appendOnly: Boolean = true,
    /** Overrides the default output type for all connections ("resp" or "json"). */
    val clientOutput: String? = null,
)

/** Current time in Unix nanoseconds. */
internal fun nowNanos(): Long {
    val now = java.time.Instant.now()
    return now.epochSecond * 1_000_000_000L + now.nano
}

/** Go `time.Since(start).String()` for a [System.nanoTime] start value. */
internal fun elapsed(start: Long): String = GoCompat.duration(System.nanoTime() - start)

internal fun okJson(start: Long): RespValue = RespValue.string("{\"ok\":true,\"elapsed\":\"${elapsed(start)}\"}")

/** A Tile38-compatible server speaking RESP, the Tile38 native protocol, telnet and HTTP on one port. */
class Server(
    val options: ServerOptions = ServerOptions(),
) : AutoCloseable {
    internal val log: Logger = Logger.getLogger("jtile38")

    /** Collections by key. Guarded by [lock]. */
    internal val cols = TreeMap<String, Collection>()
    internal val lock = ReentrantReadWriteLock()
    internal val parseOptions = ParseOptions.DEFAULT
    internal val config: Config = Config.load(File(options.dir, "config"))
    internal val serverId: String get() = config.serverId
    internal val startedNanos = System.nanoTime()
    internal val outOfMemory =
        java.util.concurrent.atomic
            .AtomicBoolean(false)
    internal val statsExpired = AtomicLong()

    // geofencing state; hooks and groups are guarded by [lock]
    internal val groups = Groups()
    internal val hookRegistry = HookRegistry()
    internal val hookQueue = HookQueue(if (options.appendOnly) File(options.dir, "queue.db") else null)
    internal val pubsub = PubSub()
    internal val endpointManager = EndpointManager { channel, message -> pubsub.publish(channel, message) }
    internal val lives: MutableSet<LiveFence> = ConcurrentHashMap.newKeySet()
    internal val statsMsgsSent = AtomicLong()

    private var aof: FileOutputStream? = null
    private val aofSize = AtomicLong()
    private val aofBuf = ByteArrayOutputStream()

    private val stopped = AtomicBoolean(false)
    private var listener: ServerSocket? = null
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    internal val clients = ConcurrentHashMap<Long, ClientConn>()
    private val clientIds = AtomicLong()
    internal val statsTotalConns = AtomicLong()
    internal val statsTotalCommands = AtomicLong()

    /** The bound port (useful when started with port 0). */
    val port: Int get() = listener?.localPort ?: options.port

    internal fun aofSize(): Long = aofSize.get()

    /** Loads the AOF, binds the listener and starts accepting connections in the background. */
    fun start(): Server {
        // like upstream, the hook queue is loaded before the AOF re-creates the hooks
        hookQueue.open()
        if (options.appendOnly) openAof()
        val ss = ServerSocket(options.port, 1024, InetAddress.getByName(options.host))
        listener = ss
        log.info("Ready to accept connections at ${options.host}:${ss.localPort}")
        Thread.ofVirtual().name("jtile38-accept").start { acceptLoop(ss) }
        Thread.ofVirtual().name("jtile38-expire").start { expireLoop() }
        return this
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        listener?.close()
        clients.values.forEach { runCatching { it.socket.close() } }
        executor.shutdownNow()
        lock.write {
            hookRegistry.clear()
            hookQueue.close()
            flushAof()
            aof?.close()
        }
    }

    // ---- AOF ---------------------------------------------------------------

    private fun openAof() {
        val dir = File(options.dir)
        dir.mkdirs()
        val file = File(dir, "appendonly.aof")
        if (file.exists()) loadAof(file)
        aof = FileOutputStream(file, true)
        aofSize.set(file.length())
    }

    private fun loadAof(file: File) {
        val data = file.readBytes()
        var off = 0
        var count = 0
        lock.write {
            while (off < data.size) {
                if (data[off] == 0.toByte()) {
                    off++
                    continue
                }
                val cmd = CommandReader.readNextCommand(data, off, data.size - off) ?: break
                off += cmd.consumed
                if (cmd.args.isEmpty()) continue
                val msg = Message(cmd.args, ConnType.RESP, OutputType.RESP)
                try {
                    executeWrite(msg, replay = true)
                } catch (e: CommandException) {
                    // like upstream, missing keys or ids are not fatal (e.g. FSET after a rewrite)
                    if (e.message != "key not found" && e.message != "id not found") throw e
                }
                count++
            }
        }
        if (off < data.size) {
            log.warning("Truncating ${data.size - off} bytes due to an incomplete command")
            RandomAccessFile(file, "rw").use { it.setLength(off.toLong()) }
        }
        log.info("AOF loaded $count commands")
    }

    private fun writeAof(
        args: List<String>,
        d: CommandDetails,
    ) {
        if (!d.updated || aof == null) return
        val v = RespValue.array(args.map { RespValue.string(it) })
        val n = aofBuf.size()
        v.writeTo(aofBuf)
        aofSize.addAndGet((aofBuf.size() - n).toLong())
    }

    private fun flushAof() {
        val f = aof ?: return
        if (aofBuf.size() > 0) {
            aofBuf.writeTo(f)
            aofBuf.reset()
        }
    }

    // ---- networking ------------------------------------------------------------

    private fun acceptLoop(ss: ServerSocket) {
        while (!stopped.get()) {
            val sock =
                try {
                    ss.accept()
                } catch (e: Exception) {
                    if (stopped.get()) return
                    log.log(Level.WARNING, "accept failed", e)
                    continue
                }
            statsTotalConns.incrementAndGet()
            val client = ClientConn(clientIds.incrementAndGet(), sock.remoteSocketAddress.toString().removePrefix("/"), sock)
            clients[client.id] = client
            executor.submit {
                try {
                    sock.use { handleConn(it, client) }
                } catch (_: java.io.IOException) {
                    // client went away
                } catch (e: Throwable) {
                    if (!stopped.get()) log.log(Level.WARNING, "connection ${client.remoteAddr} failed", e)
                } finally {
                    clients.remove(client.id)
                }
            }
        }
    }

    private fun handleConn(
        sock: Socket,
        client: ClientConn,
    ) {
        sock.tcpNoDelay = true
        val input: InputStream = sock.getInputStream()
        val output: OutputStream = sock.getOutputStream()
        var buf = ByteArray(64 * 1024)
        var len = 0
        val out = ByteArrayOutputStream()
        while (true) {
            if (len == buf.size) buf = buf.copyOf(buf.size * 2)
            val n = input.read(buf, len, buf.size - len)
            if (n < 0) return
            len += n
            var off = 0
            var close = false
            var protoErr: ProtocolException? = null
            var lastOutput = OutputType.RESP
            while (off < len) {
                val msg: Message
                try {
                    val http = readHttp(buf, off, len - off, out)
                    if (http != null) {
                        if (http.first == null) {
                            // incomplete HTTP request or CORS preflight
                            if (http.second == 0) break
                            off += http.second
                            close = true
                            break
                        }
                        msg = http.first!!
                        off += http.second
                    } else {
                        val cmd = CommandReader.readNextCommand(buf, off, len - off) ?: break
                        off += cmd.consumed
                        if (cmd.args.isEmpty()) continue
                        msg =
                            when (cmd.kind) {
                                CommandKind.TILE38 -> Message(cmd.args, ConnType.NATIVE, OutputType.JSON)
                                else -> Message(cmd.args, ConnType.RESP, OutputType.RESP)
                            }
                    }
                } catch (e: ProtocolException) {
                    protoErr = e
                    break
                }
                if (msg.connType != ConnType.HTTP) {
                    val forced = client.outputType ?: defaultOutputType()
                    if (forced != null) msg.outputType = forced
                }
                if (msg.command == "quit") {
                    if (msg.outputType == OutputType.RESP) out.writeBytes("+OK\r\n".toByteArray())
                    close = true
                    break
                }
                statsTotalCommands.incrementAndGet()
                msg.client = client
                client.last = System.nanoTime()
                try {
                    handleInputCommand(msg, out)
                } catch (live: GoLive) {
                    // detach: flush pending replies, then stream on this thread until the client leaves
                    out.writeTo(output)
                    out.reset()
                    output.flush()
                    val reader = LiveReader(input, buf.copyOfRange(off, len))
                    when (live) {
                        is GoLive.Fence -> goLiveFence(live, msg, reader, output)
                        is GoLive.Subscribe -> goLiveSubscription(msg, reader, output)
                    }
                    return
                }
                if (msg.connType != ConnType.HTTP) client.outputType = msg.outputType
                lastOutput = msg.outputType
                if (msg.connType == ConnType.HTTP) {
                    close = true
                    break
                }
            }
            if (off > 0) {
                System.arraycopy(buf, off, buf, 0, len - off)
                len -= off
            }
            if (out.size() > 0) {
                lock.write { flushAof() }
                out.writeTo(output)
                out.reset()
                output.flush()
            }
            if (protoErr != null) {
                val v =
                    if (lastOutput == OutputType.JSON) {
                        RespValue.string("{\"ok\":false,\"err\":${GoCompat.jsonString(protoErr.message!!)}}")
                    } else {
                        RespValue.error(protoErr.message!!)
                    }
                output.write(v.marshal())
                output.flush()
                return
            }
            if (close) return
        }
    }

    private fun defaultOutputType(): OutputType? =
        when (options.clientOutput) {
            "resp" -> OutputType.RESP
            "json" -> OutputType.JSON
            else -> null
        }

    /**
     * Detects and parses an HTTP request. Returns null if the data is not HTTP,
     * (null, 0) if more data is needed, (null, n) for a handled request without a
     * command (CORS preflight) and (message, n) otherwise.
     */
    private fun readHttp(
        buf: ByteArray,
        off: Int,
        len: Int,
        out: ByteArrayOutputStream,
    ): Pair<Message?, Int>? {
        val c = buf[off].toInt().toChar()
        if (c != 'G' && c != 'P' && c != 'O') return null
        var lineEnd = -1
        for (i in off + 1 until off + len) {
            if (buf[i] == '\n'.code.toByte() && buf[i - 1] == '\r'.code.toByte()) {
                lineEnd = i
                break
            }
        }
        if (lineEnd == -1) return null
        val line = String(buf, off, lineEnd + 1 - off, Charsets.ISO_8859_1)
        if (!(line.length > 11 && line.substring(line.length - 11, line.length - 5) == " HTTP/")) return null
        // read headers
        val text = String(buf, off, len, Charsets.ISO_8859_1)
        val headerEnd = text.indexOf("\r\n\r\n")
        if (headerEnd == -1) return null to 0
        val headers = text.substring(0, headerEnd).split("\r\n")
        val parts = headers[0].split(" ")
        if (parts.size != 3) throw ProtocolException("invalid HTTP request")
        val method = parts[0]
        var bodyStart = headerEnd + 4
        if (method == "OPTIONS") {
            out.writeBytes(
                (
                    "HTTP/1.1 204 No Content\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n" +
                        "Access-Control-Allow-Headers: *, Authorization\r\nAccess-Control-Allow-Methods: POST, GET, OPTIONS\r\n\r\n"
                ).toByteArray(),
            )
            return null to bodyStart
        }
        if (method != "GET" && method != "POST") throw ProtocolException("invalid HTTP request")
        val rawPath = parts[1]
        if (rawPath.isEmpty() || rawPath[0] != '/') throw ProtocolException("invalid HTTP request")
        var path =
            try {
                URLDecoder.decode(rawPath.substring(1), Charsets.ISO_8859_1)
            } catch (_: IllegalArgumentException) {
                throw ProtocolException("invalid HTTP request")
            }
        var auth = ""
        var contentLength = 0
        for (h in headers.drop(1)) {
            val i = h.indexOf(':')
            if (i < 0) continue
            val name = h.substring(0, i).trim().lowercase()
            val value = h.substring(i + 1).trim()
            when (name) {
                "authorization" -> auth = value
                "content-length" -> contentLength = value.toIntOrNull() ?: throw ProtocolException("invalid HTTP request")
                "upgrade" -> if (value.lowercase() == "websocket") throw ProtocolException("websockets are not supported yet")
            }
        }
        if (contentLength > 0) {
            if (len - bodyStart < contentLength) return null to 0
            path += text.substring(bodyStart, bodyStart + contentLength)
            bodyStart += contentLength
        }
        val args = CommandReader.splitNativeLine(path)
        if (args.isEmpty()) throw ProtocolException("invalid HTTP request")
        val msg = Message(args, ConnType.HTTP, OutputType.JSON)
        msg.auth = auth
        return msg to bodyStart
    }

    // ---- command execution -----------------------------------------------------

    private fun handleInputCommand(
        msg: Message,
        out: ByteArrayOutputStream,
    ) {
        val start = System.nanoTime()
        val cmd = msg.command

        fun writeOutput(res: String) {
            when (msg.connType) {
                ConnType.RESP -> {
                    if (msg.outputType == OutputType.JSON) {
                        out.writeLatin1("$" + res.length + "\r\n" + res + "\r\n")
                    } else {
                        out.writeLatin1(res)
                    }
                }

                ConnType.NATIVE -> {
                    out.writeLatin1("$" + res.length + " " + res + "\r\n")
                }

                ConnType.HTTP, ConnType.WEBSOCKET -> {
                    out.writeLatin1(
                        "HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Type: application/json; charset=utf-8\r\n" +
                            "Content-Length: ${res.length + 2}\r\nAccess-Control-Allow-Origin: *\r\n\r\n",
                    )
                    out.writeLatin1(res + "\r\n")
                }
            }
        }

        fun writeErr(errMsgIn: String) {
            if (msg.outputType == OutputType.JSON) {
                writeOutput("{\"ok\":false,\"err\":${GoCompat.jsonString(errMsgIn)},\"elapsed\":\"${elapsed(start)}\"}")
                return
            }
            if (errMsgIn == Errors.INVALID_NUMBER_OF_ARGUMENTS) {
                writeOutput("-ERR wrong number of arguments for '$cmd' command\r\n")
                return
            }
            val word = errMsgIn.split(" ")[0]
            val ucprefix = word.isNotEmpty() && word.all { it in 'A'..'Z' }
            val errMsg = if (ucprefix) errMsgIn else "ERR $errMsgIn"
            writeOutput(String(RespValue.error(errMsg).marshal(), Charsets.ISO_8859_1))
        }

        if (cmd == "ping" || cmd == "echo") {
            if (msg.outputType == OutputType.JSON) {
                val v = if (msg.args.size > 1) GoCompat.jsonString(msg.args[1]) else "\"pong\""
                writeOutput("{\"ok\":true,\"$cmd\":$v,\"elapsed\":\"${elapsed(start)}\"}")
            } else if (msg.args.size > 1) {
                writeOutput(String(RespValue.string(msg.args[1]).marshal(), Charsets.ISO_8859_1))
            } else {
                writeOutput("+PONG\r\n")
            }
            return
        }
        if (cmd == "hello") {
            val ot = msg.outputType
            if (msg.args.size > 1 && msg.args[1] >= "0" && msg.args[1] <= "9" && ot == OutputType.JSON &&
                msg.connType == ConnType.RESP
            ) {
                msg.outputType = OutputType.RESP
            }
            writeErr("unknown command '${msg.args[0]}'")
            msg.outputType = ot
            return
        }
        if (cmd == "timeout") {
            // TIMEOUT seconds <command ...>: run the command with a deadline
            val vs = msg.args
            if (vs.size < 3 || vs[1] == "") {
                writeErr(Errors.INVALID_NUMBER_OF_ARGUMENTS)
                return
            }
            val secs = GoCompat.parseFloat(vs[1])
            if (secs == null || secs < 0) {
                writeErr("invalid argument '${vs[1]}'")
                return
            }
            msg.args = vs.subList(2, vs.size)
            msg.deadline = Deadline(System.nanoTime() + (secs * 1e9).toLong())
        }
        try {
            val res = execute(msg)
            if (res is RespValue.Error) {
                writeErr(res.message)
                return
            }
            if (msg.outputType == OutputType.JSON) {
                writeOutput(res.toString())
            } else {
                writeOutput(String(res.marshal(), Charsets.ISO_8859_1))
            }
        } catch (e: CommandException) {
            writeErr(e.message ?: "")
        }
    }

    /** Runs a command under the appropriate lock and returns its response, honoring a TIMEOUT deadline. */
    internal fun execute(msg: Message): RespValue {
        val dl = msg.deadline ?: return executeCommand(msg)
        if (msg.command in WRITE_COMMANDS) throw CommandException("timeout not supported for '${msg.command}'")
        try {
            val res = executeCommand(msg)
            dl.check()
            return res
        } catch (_: DeadlineExceeded) {
            throw CommandException("timeout")
        }
    }

    private fun executeCommand(msg: Message): RespValue =
        when (msg.command) {
            in WRITE_COMMANDS -> {
                lock.write { executeWrite(msg).res }
            }

            "output" -> {
                cmdOutput(msg)
            }

            "publish" -> {
                cmdPublish(msg)
            }

            // system operations: no AOF, but the write lock
            "config", "client" -> {
                lock.write { if (msg.command == "config") cmdConfig(msg) else cmdClient(msg) }
            }

            "subscribe", "psubscribe" -> {
                if (msg.args.size < 2) throw Errors.invalidNumberOfArguments()
                throw GoLive.Subscribe
            }

            "flushall" -> {
                throw CommandException("unknown command '${msg.args[0]}'")
            }

            else -> {
                lock.read { executeRead(msg) }
            }
        }

    /**
     * Runs a write command; the caller must hold the write lock. Unless replaying the AOF, the
     * write is appended to the AOF and forwarded to hooks and live fences.
     */
    private fun executeWrite(
        msg: Message,
        replay: Boolean = false,
    ): WriteResult {
        val r =
            when (msg.command) {
                "sethook", "setchan" -> cmdSetHook(msg)
                "delhook", "delchan" -> cmdDelHook(msg)
                "pdelhook", "pdelchan" -> cmdPdelHook(msg)
                "set" -> cmdSet(msg)
                "fset" -> cmdFset(msg)
                "del" -> cmdDel(msg)
                "pdel" -> cmdPdel(msg)
                "drop" -> cmdDrop(msg)
                "flushdb" -> cmdFlushdb(msg)
                "rename", "renamenx" -> cmdRename(msg)
                "expire" -> cmdExpire(msg)
                "persist" -> cmdPersist(msg)
                "jset" -> cmdJset(msg)
                "jdel" -> cmdJdel(msg)
                else -> throw CommandException("unknown command '${msg.args[0]}'")
            }
        if (!replay && r.details.updated) {
            writeAof(msg.args, r.details)
            if (r.details.parent) r.details.children.forEach { notify(it) } else notify(r.details)
        }
        return r
    }

    /** Forwards a write to geofence hooks and live fences. Caller holds the write lock. */
    private fun notify(d: CommandDetails) {
        if (d.key == "") return
        if (hookRegistry.hooks.isNotEmpty()) queueHooks(d)
        for (lf in lives) {
            if (lf.key == d.key) Fences.match(this, "", lf.sw, lf.spec, emptyList(), d).forEach { lf.queue.add(it) }
        }
    }

    private fun executeRead(msg: Message): RespValue =
        when (msg.command) {
            "get" -> cmdGet(msg)
            "fget" -> cmdFget(msg)
            "jget" -> cmdJget(msg)
            "bounds" -> cmdBounds(msg)
            "type" -> cmdType(msg)
            "keys" -> cmdKeys(msg)
            "exists" -> cmdExists(msg)
            "fexists" -> cmdFexists(msg)
            "ttl" -> cmdTtl(msg)
            "stats" -> cmdStats(msg)
            "server" -> cmdServer(msg)
            "scan" -> cmdScan(msg)
            "search" -> cmdSearch(msg)
            "nearby" -> cmdNearby(msg)
            "within", "intersects" -> cmdWithinOrIntersects(msg.command, msg)
            "healthz" -> healthz(msg)
            "hooks", "chans" -> cmdHooks(msg)
            "info" -> cmdInfo(msg)
            "test" -> cmdTest(msg)
            "role" -> cmdRole(msg)
            else -> throw CommandException("unknown command '${msg.args[0]}'")
        }

    private fun healthz(msg: Message): RespValue {
        val start = System.nanoTime()
        if (msg.args.size != 1) throw Errors.invalidNumberOfArguments()
        return if (msg.outputType == OutputType.JSON) okJson(start) else RespValue.OK
    }

    /** OUTPUT (resp|json) */
    private fun cmdOutput(msg: Message): RespValue {
        val start = System.nanoTime()
        val args = msg.args
        return when (args.size) {
            // Upstream quirk kept for compatibility: "elapsed" is not quoted here.
            1 -> {
                if (msg.outputType == OutputType.JSON) {
                    RespValue.string("{\"ok\":true,\"output\":\"json\",\"elapsed\":${elapsed(start)}}")
                } else {
                    RespValue.string("resp")
                }
            }

            2 -> {
                msg.outputType =
                    when (args[1].lowercase()) {
                        "json" -> OutputType.JSON
                        "resp" -> OutputType.RESP
                        else -> throw Errors.invalidArgument(args[1])
                    }
                if (msg.outputType == OutputType.JSON) okJson(start) else RespValue.OK
            }

            else -> {
                throw Errors.invalidNumberOfArguments()
            }
        }
    }

    // ---- expiration ------------------------------------------------------------

    private var lastMemCheck = System.nanoTime()

    /** Updates [outOfMemory] from the used heap and the maxmemory setting (Go `checkOutOfMemory`). */
    internal fun checkOutOfMemory() {
        if (stopped.get()) return
        val max = config.maxMemory
        if (max == 0L) {
            outOfMemory.set(false)
            return
        }
        if (outOfMemory.get()) System.gc()
        val rt = Runtime.getRuntime()
        outOfMemory.set(rt.totalMemory() - rt.freeMemory() > max)
    }

    private fun expireLoop() {
        while (!stopped.get()) {
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return
            }
            lock.write {
                val now = nowNanos()
                val msgs = ArrayList<Message>()
                for ((key, col) in cols) {
                    col.scanExpires { o ->
                        if (now < o.expires) {
                            false
                        } else {
                            statsExpired.incrementAndGet()
                            msgs.add(Message(listOf("del", key, o.id), ConnType.RESP, OutputType.RESP))
                            true
                        }
                    }
                }
                for (h in hookRegistry.hookExpires) {
                    if (h.expires > now) break
                    msgs.add(Message(listOf(if (h.channel) "delchan" else "delhook", h.name), ConnType.RESP, OutputType.RESP))
                }
                for (m in msgs) executeWrite(m)
                if (msgs.isNotEmpty()) flushAof()
            }
            // outside the server lock: may fsync the queue file
            hookQueue.maintain()
            if (System.nanoTime() - lastMemCheck > 4_000_000_000L) {
                lastMemCheck = System.nanoTime()
                checkOutOfMemory()
            }
        }
    }

    companion object {
        private val WRITE_COMMANDS =
            setOf(
                "set",
                "fset",
                "jset",
                "jdel",
                "del",
                "pdel",
                "drop",
                "flushdb",
                "rename",
                "renamenx",
                "expire",
                "persist",
                "sethook",
                "setchan",
                "delhook",
                "delchan",
                "pdelhook",
                "pdelchan",
            )
    }
}

private fun ByteArrayOutputStream.writeLatin1(s: String) {
    for (c in s) write(c.code)
}
