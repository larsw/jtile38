// Port of github.com/tidwall/resp (Reader), Copyright 2016 Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.resp

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/**
 * Reads RESP replies from a stream. Strings are byte strings (ISO-8859-1, one char per byte).
 * Not thread-safe.
 */
class RespReader(
    input: InputStream,
) {
    private val input = input as? BufferedInputStream ?: BufferedInputStream(input, 64 * 1024)

    /** Reads the next value; throws [EOFException] at end of stream and [ProtocolException] on bad input. */
    fun readValue(): RespValue {
        val type = read()
        return when (type.toChar()) {
            '+' -> {
                RespValue.SimpleString(readLine())
            }

            '-' -> {
                RespValue.Error(readLine())
            }

            ':' -> {
                RespValue.Integer(readLine().toLongOrNull() ?: throw ProtocolException("invalid integer"))
            }

            '$' -> {
                val n = readLine().toIntOrNull() ?: throw ProtocolException("invalid bulk length")
                if (n < 0) return RespValue.BulkString(null)
                val buf = ByteArray(n)
                var off = 0
                while (off < n) {
                    val r = input.read(buf, off, n - off)
                    if (r < 0) throw EOFException()
                    off += r
                }
                if (read() != '\r'.code || read() != '\n'.code) throw ProtocolException("invalid bulk line ending")
                RespValue.BulkString(String(buf, Charsets.ISO_8859_1))
            }

            '*' -> {
                val n = readLine().toIntOrNull() ?: throw ProtocolException("invalid multibulk length")
                if (n < 0) return RespValue.Array(null)
                RespValue.Array(List(n) { readValue() })
            }

            else -> {
                throw ProtocolException("invalid response character '${type.toChar()}'")
            }
        }
    }

    /** Reads one `\r\n` terminated line, without the terminator. */
    fun readLine(): String {
        val out = ByteArrayOutputStream(64)
        while (true) {
            val c = read()
            if (c == '\r'.code) {
                if (read() != '\n'.code) throw ProtocolException("invalid line ending")
                return out.toString(Charsets.ISO_8859_1)
            }
            out.write(c)
        }
    }

    /** Peeks at the next byte without consuming it. */
    fun peek(): Int {
        input.mark(1)
        val c = input.read()
        input.reset()
        if (c < 0) throw EOFException()
        return c
    }

    /** Reads one byte; throws [EOFException] at end of stream. */
    fun read(): Int {
        val c = input.read()
        if (c < 0) throw EOFException()
        return c
    }

    /** The underlying buffered stream, e.g. for streaming raw output. */
    fun stream(): InputStream = input
}
