// Copyright jtile38 authors, MIT License.

package io.jtile38.client

import io.jtile38.resp.RespReader
import io.jtile38.resp.RespValue
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** An error reply from the server, or a protocol failure. */
open class Tile38Exception(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** A single connection to a Tile38 server. Not thread-safe; see [Tile38Client] for a pooled client. */
class Tile38Connection
    @JvmOverloads
    constructor(
        val host: String = "127.0.0.1",
        val port: Int = 9851,
        connectTimeoutMillis: Int = 5_000,
    ) : Closeable {
        private val socket = Socket()
        private val reader: RespReader
        private val out: BufferedOutputStream

        init {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            socket.tcpNoDelay = true
            reader = RespReader(socket.getInputStream())
            out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        }

        /** True until [close] is called or an I/O error broke the connection. */
        var isOpen: Boolean = true
            private set

        /**
         * Sends a command as a RESP array and returns the reply. Arguments are encoded as UTF-8.
         * Error replies are returned as [RespValue.Error], not thrown.
         */
        fun execute(args: List<String>): RespValue =
            io {
                val buf = ByteArrayOutputStream()
                buf.write("*${args.size}\r\n".toByteArray())
                for (a in args) {
                    val b = a.toByteArray(Charsets.UTF_8)
                    buf.write("$${b.size}\r\n".toByteArray())
                    buf.write(b)
                    buf.write(CRLF)
                }
                buf.writeTo(out)
                out.flush()
                reader.readValue()
            }

        /** Vararg variant of [execute]. */
        fun execute(vararg args: String): RespValue = execute(args.toList())

        /**
         * Sends a plain inline (telnet-style) command line, as `tile38-cli` does, and returns the
         * raw reply bytes. Supports RESP replies and native JSON lines.
         */
        fun executeInline(line: String): ByteArray =
            io {
                out.write(line.toByteArray(Charsets.UTF_8))
                out.write(CRLF)
                out.flush()
                readRawReply()
            }

        /** Reads one complete raw reply (used for streaming/live responses). */
        fun readRawReply(): ByteArray = io { ByteArrayOutputStream().also { readRaw(it) }.toByteArray() }

        /** Copies everything the server sends to [sink] until the connection closes. */
        fun pipeTo(sink: java.io.OutputStream) {
            io { reader.stream().transferTo(sink) }
        }

        private fun readRaw(dst: ByteArrayOutputStream) {
            when (val c = reader.peek().toChar()) {
                ':', '+', '-', '{' -> {
                    readRawLine(dst)
                }

                '$' -> {
                    val line = readRawLine(dst)
                    val n = line.substring(1).trim().toLong()
                    if (n >= 0) {
                        repeat((n + 2).toInt()) { dst.write(reader.read()) }
                    }
                }

                '*' -> {
                    val line = readRawLine(dst)
                    val n = line.substring(1).trim().toLong()
                    repeat(n.toInt()) { readRaw(dst) }
                }

                else -> {
                    throw Tile38Exception("invalid response character '$c")
                }
            }
        }

        private fun readRawLine(dst: ByteArrayOutputStream): String {
            val line = reader.readLine()
            dst.write(line.toByteArray(Charsets.ISO_8859_1))
            dst.write(CRLF)
            return line
        }

        private inline fun <T> io(block: () -> T): T {
            if (!isOpen) throw Tile38Exception("connection is closed")
            try {
                return block()
            } catch (e: EOFException) {
                isOpen = false
                throw Tile38Exception("connection closed by server", e)
            } catch (e: IOException) {
                isOpen = false
                throw Tile38Exception(e.message ?: "I/O error", e)
            }
        }

        override fun close() {
            isOpen = false
            socket.close()
        }

        private companion object {
            val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
        }
    }

/** Decodes a byte string from the RESP layer into a Java string (UTF-8). */
internal fun RespValue.text(): String? =
    when (this) {
        is RespValue.BulkString -> value?.let { utf8(it) }
        is RespValue.SimpleString -> utf8(value)
        is RespValue.Integer -> value.toString()
        is RespValue.Error -> utf8(message)
        is RespValue.Array -> null
    }

internal fun utf8(byteString: String) = String(byteString.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
