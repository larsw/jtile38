// Port of tile38 internal/server/live.go and pubsub.go, Copyright Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.server

import io.jtile38.json.Gjson
import io.jtile38.json.Match
import io.jtile38.resp.CommandKind
import io.jtile38.resp.CommandReader
import io.jtile38.resp.RespValue
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Thrown by a command to turn its connection into a live (streaming) connection. */
sealed class GoLive : Exception(null, null, false, false) {
    /** A live geofence (NEARBY/WITHIN/INTERSECTS ... FENCE). */
    class Fence(
        val spec: FenceSpec,
        val sw: ScanWriter,
    ) : GoLive()

    /** SUBSCRIBE or PSUBSCRIBE. */
    object Subscribe : GoLive()

    /** MONITOR. */
    object Monitor : GoLive()

    /** AOF pos: a follower reading the AOF. */
    class Aof(
        val pos: Long,
    ) : GoLive()
}

/** Reads commands from a live connection, starting with already buffered bytes. */
internal class LiveReader(
    private val input: InputStream,
    leftover: ByteArray,
) {
    private var buf = leftover.copyOf(maxOf(leftover.size, 4096))
    private var len = leftover.size

    /** Returns the next batch of commands, or null at end of stream. */
    fun readMessages(): List<Message>? {
        while (true) {
            val msgs = ArrayList<Message>()
            var off = 0
            while (off < len) {
                val cmd = CommandReader.readNextCommand(buf, off, len - off) ?: break
                off += cmd.consumed
                if (cmd.args.isEmpty()) continue
                msgs.add(
                    if (cmd.kind == CommandKind.TILE38) {
                        Message(cmd.args, ConnType.NATIVE, OutputType.JSON)
                    } else {
                        Message(cmd.args, ConnType.RESP, OutputType.RESP)
                    },
                )
            }
            System.arraycopy(buf, off, buf, 0, len - off)
            len -= off
            if (msgs.isNotEmpty()) return msgs
            if (len == buf.size) buf = buf.copyOf(buf.size * 2)
            val n = input.read(buf, len, buf.size - len)
            if (n < 0) return null
            len += n
        }
    }
}

/** Writes a live message like Go `writeLiveMessage`; WebSocket connections get a text frame. */
internal fun writeLiveMessage(
    out: OutputStream,
    message: String,
    wrapResp: Boolean,
    connType: ConnType,
) {
    if (message.isEmpty()) return
    val data =
        when (connType) {
            ConnType.WEBSOCKET -> webSocketFrame(message)
            ConnType.RESP -> (if (wrapResp) "$" + message.length + "\r\n" + message + "\r\n" else message).toByteArray(Charsets.ISO_8859_1)
            ConnType.NATIVE -> ("$" + message.length + " " + message + "\r\n").toByteArray(Charsets.ISO_8859_1)
            else -> return
        }
    synchronized(out) {
        out.write(data)
        out.flush()
    }
}

/** Encodes [data] (a byte string) as one unmasked WebSocket text frame (Go `WriteWebSocketMessage`). */
internal fun webSocketFrame(data: String): ByteArray {
    val payload = data.toByteArray(Charsets.ISO_8859_1)
    val n = payload.size
    val head =
        when {
            n <= 125 -> {
                byteArrayOf(129.toByte(), n.toByte())
            }

            n <= 0xFFFF -> {
                byteArrayOf(129.toByte(), 126, (n shr 8).toByte(), n.toByte())
            }

            else -> {
                ByteArray(10).also { h ->
                    h[0] = 129.toByte()
                    h[1] = 127
                    for (k in 0 until 8) h[9 - k] = (n.toLong() shr (8 * k)).toByte()
                }
            }
        }
    return head + payload
}

/**
 * A live geofence connection. Messages are computed by the writing thread while it holds the server
 * write lock, so each message reflects the data as of its write; the connection thread only sends.
 */
internal class LiveFence(
    val key: String,
    val spec: FenceSpec,
    val sw: ScanWriter,
) {
    val queue = LinkedBlockingQueue<Any>()
}

private object Quit

/** Runs a live geofence on the calling (virtual) thread until the client quits or disconnects. */
internal fun Server.goLiveFence(
    live: GoLive.Fence,
    msg: Message,
    input: LiveReader,
    out: OutputStream,
) {
    val lf = LiveFence(live.spec.key, live.spec, live.sw)
    lives.add(lf)
    try {
        Thread.ofVirtual().start {
            try {
                while (true) {
                    val vs = input.readMessages() ?: break
                    if (vs.any { it.command != "quit" }) log.warning("received a live command that was not QUIT")
                    break
                }
            } catch (_: Exception) {
            } finally {
                lf.queue.add(Quit)
            }
        }
        val hello = if (msg.outputType == OutputType.JSON) "\$23\r\n{\"ok\":true,\"live\":true}\r\n" else "+OK\r\n"
        writeLiveMessage(out, hello, false, msg.connType)
        while (true) {
            val m = lf.queue.take()
            if (m === Quit) return
            writeLiveMessage(out, m as String, true, msg.connType)
            statsMsgsSent.incrementAndGet()
        }
    } catch (_: java.io.IOException) {
    } finally {
        lives.remove(lf)
    }
}

/** Channel and pattern subscriptions (Go `pubsub`). */
internal class PubSub(
    private val onPublish: () -> Unit = {},
) {
    class SubMsg(
        val pattern: Boolean,
        val patternText: String,
        val channel: String,
        val message: String,
    )

    class Target {
        val queue = LinkedBlockingQueue<Any>()
    }

    private val lock = ReentrantReadWriteLock()
    private val channels = HashMap<String, MutableSet<Target>>()
    private val patterns = HashMap<String, MutableSet<Target>>()

    /** Publishes to subscribers and returns the number of deliveries. */
    fun publish(
        channel: String,
        vararg messages: String,
    ): Int {
        val msgs = ArrayList<Pair<Target, SubMsg>>()
        lock.read {
            channels[channel]?.forEach { t -> messages.forEach { msgs.add(t to SubMsg(false, "", channel, it)) } }
            for ((pattern, targets) in patterns) {
                if (Match.match(channel, pattern)) {
                    targets.forEach { t -> messages.forEach { msgs.add(t to SubMsg(true, pattern, channel, it)) } }
                }
            }
        }
        for ((t, m) in msgs) t.queue.add(m)
        onPublish()
        return msgs.size
    }

    fun register(
        pattern: Boolean,
        channel: String,
        target: Target,
    ) = lock.write { (if (pattern) patterns else channels).getOrPut(channel) { ConcurrentHashMap.newKeySet() }.add(target) }

    fun unregister(
        pattern: Boolean,
        channel: String,
        target: Target,
    ) = lock.write {
        val m = if (pattern) patterns else channels
        m[channel]?.let {
            it.remove(target)
            if (it.isEmpty()) m.remove(channel)
        }
    }
}

private const val ONLY_PUBSUB = "only (P)SUBSCRIBE / (P)UNSUBSCRIBE / PING / QUIT allowed in this context"

/** PUBLISH channel message */
internal fun Server.cmdPublish(msg: Message): RespValue {
    val start = System.nanoTime()
    if (msg.args.size != 3) throw Errors.invalidNumberOfArguments()
    val n = pubsub.publish(msg.args[1], msg.args[2])
    if (msg.outputType == OutputType.JSON) {
        return RespValue.string("{\"ok\":true,\"published\":$n,\"elapsed\":\"${elapsed(start)}\"}")
    }
    return RespValue.integer(n)
}

/** Runs a SUBSCRIBE/PSUBSCRIBE session on the calling thread (Go `liveSubscription`). */
internal fun Server.goLiveSubscription(
    first: Message,
    input: LiveReader,
    out: OutputStream,
) {
    val outputType = first.outputType
    val connType = first.connType
    var start = System.nanoTime()
    val json = outputType == OutputType.JSON

    fun write(data: String) = writeLiveMessage(out, data, json, connType)

    fun bulk(s: String) = "$" + s.length + "\r\n" + s + "\r\n"
    val subs = arrayOf(HashSet<String>(), HashSet<String>())
    val target = PubSub.Target()
    val writer =
        Thread.ofVirtual().start {
            try {
                while (true) {
                    val m = target.queue.take()
                    if (m !is PubSub.SubMsg) break
                    val data =
                        if (json) {
                            if (!Gjson.valid(m.message)) GoCompat.jsonString(m.message) else m.message
                        } else if (!m.pattern) {
                            "*3\r\n" + bulk("message") + bulk(m.channel) + bulk(m.message)
                        } else {
                            "*4\r\n" + bulk("pmessage") + bulk(m.patternText) + bulk(m.channel) + bulk(m.message)
                        }
                    write(data)
                    statsMsgsSent.incrementAndGet()
                }
            } catch (_: Exception) {
            }
        }
    try {
        var msgs: List<Message> = listOf(first)
        while (true) {
            for (m in msgs) {
                start = System.nanoTime()
                val (pattern, un) =
                    when (m.command) {
                        "quit" -> {
                            write(if (json) "{\"ok\":true,\"elapsed\":\"${elapsed(start)}\"}" else "+OK\r\n")
                            return
                        }

                        "ping" -> {
                            val arg = m.args.getOrNull(1)
                            write(
                                if (json) {
                                    "{\"ok\":true,\"ping\":${if (arg != null) {
                                        GoCompat.jsonString(
                                            arg,
                                        )
                                    } else {
                                        "\"pong\""
                                    }},\"elapsed\":\"${elapsed(start)}\"}"
                                } else {
                                    "*2\r\n" + bulk("PONG") + bulk(arg ?: "")
                                },
                            )
                            continue
                        }

                        "psubscribe" -> {
                            true to false
                        }

                        "punsubscribe" -> {
                            true to true
                        }

                        "subscribe" -> {
                            false to false
                        }

                        "unsubscribe" -> {
                            false to true
                        }

                        else -> {
                            write(
                                if (json) {
                                    "{\"ok\":false,\"err\":\"$ONLY_PUBSUB\",\"elapsed\":\"${elapsed(start)}\"}"
                                } else {
                                    "-ERR $ONLY_PUBSUB\r\n"
                                },
                            )
                            continue
                        }
                    }
                if (m.args.size < 2) {
                    write(
                        if (json) {
                            "{\"ok\":false,\"err\":\"invalid number of arguments\",\"elapsed\":\"${elapsed(start)}\"}"
                        } else {
                            "-ERR wrong number of arguments for '${m.command}' command\r\n"
                        },
                    )
                }
                val kind = if (pattern) 1 else 0
                for (channel in m.args.drop(1)) {
                    if (un) {
                        subs[kind].remove(channel)
                        pubsub.unregister(pattern, channel, target)
                    } else {
                        subs[kind].add(channel)
                        pubsub.register(pattern, channel, target)
                    }
                    val num = subs[0].size + subs[1].size
                    write(
                        if (json) {
                            "{\"ok\":true,\"command\":${GoCompat.jsonString(
                                m.command,
                            )},\"channel\":${GoCompat.jsonString(channel)},\"num\":$num,\"elapsed\":\"${elapsed(start)}\"}"
                        } else {
                            "*3\r\n" + bulk(m.command) + bulk(channel) + ":" + num + "\r\n"
                        },
                    )
                }
            }
            msgs = input.readMessages() ?: return
        }
    } catch (_: java.io.IOException) {
    } finally {
        for (c in subs[0]) pubsub.unregister(false, c, target)
        for (c in subs[1]) pubsub.unregister(true, c, target)
        target.queue.add(Unit)
        writer.join(1000)
    }
}
