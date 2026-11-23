// Port of github.com/tidwall/redcon (ReadNextCommand), Copyright 2016 Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.resp

/** The wire format a command was received in. */
enum class CommandKind {
    /** RESP multibulk: `*2\r\n$3\r\nGET\r\n...` */
    REDIS,

    /** Tile38 native protocol: `$<len> <space separated args>\r\n` */
    TILE38,

    /** Plain inline text with optional quoting, e.g. from telnet. */
    TELNET,
}

/** Thrown for malformed input; the connection should be closed. */
class ProtocolException(
    msg: String,
) : Exception("Protocol error: $msg")

/** A complete command, its kind and the number of input bytes it consumed. */
class ParsedCommand(
    val args: List<String>,
    val kind: CommandKind,
    val consumed: Int,
)

/**
 * Incremental command reader. Arguments are returned as byte strings
 * (ISO-8859-1, one char per byte).
 */
object CommandReader {
    /**
     * Tries to read the next command from `buf[off until off+len]`.
     * Returns null if more data is needed. A complete command with zero args
     * is possible (e.g. `*0\r\n` or an empty line); callers should skip it.
     */
    @JvmStatic
    fun readNextCommand(
        buf: ByteArray,
        off: Int,
        len: Int,
    ): ParsedCommand? {
        if (len <= 0) return null
        return when (buf[off]) {
            '*'.code.toByte() -> readRedis(buf, off, len)
            '$'.code.toByte() -> readTile38(buf, off, len)
            else -> readTelnet(buf, off, len)
        }
    }

    private fun str(
        buf: ByteArray,
        from: Int,
        to: Int,
    ) = String(buf, from, to - from, Charsets.ISO_8859_1)

    private fun parseInt(
        buf: ByteArray,
        from: Int,
        to: Int,
    ): Int? {
        if (to - from == 1 && buf[from] in '0'.code.toByte()..'9'.code.toByte()) return buf[from] - '0'.code.toByte()
        var n = 0L
        var i = from
        var sign = false
        if (to > from && buf[from] == '-'.code.toByte()) {
            sign = true
            i++
        }
        if (i == to) return null
        while (i < to) {
            val c = buf[i]
            if (c < '0'.code.toByte() || c > '9'.code.toByte()) return null
            n = n * 10 + (c - '0'.code.toByte())
            if (n > Int.MAX_VALUE) return null
            i++
        }
        return if (sign) -n.toInt() else n.toInt()
    }

    private fun readRedis(
        buf: ByteArray,
        off: Int,
        len: Int,
    ): ParsedCommand? {
        val end = off + len
        var i = off + 1
        while (i < end) {
            if (buf[i] == '\n'.code.toByte()) {
                if (buf[i - 1] != '\r'.code.toByte()) throw ProtocolException("invalid multibulk length")
                val count = parseInt(buf, off + 1, i - 1)
                if (count == null || count < 0) throw ProtocolException("invalid multibulk length")
                i++
                if (count == 0) return ParsedCommand(emptyList(), CommandKind.REDIS, i - off)
                val args = ArrayList<String>(count)
                for (j in 0 until count) {
                    if (i == end) return null
                    if (buf[i] != '$'.code.toByte()) {
                        throw ProtocolException("expected '$', got '" + buf[i].toInt().toChar() + "'")
                    }
                    val s = i + 1
                    var found = false
                    while (i < end) {
                        if (buf[i] == '\n'.code.toByte()) {
                            if (buf[i - 1] != '\r'.code.toByte()) throw ProtocolException("invalid bulk length")
                            val n = parseInt(buf, s, i - 1)
                            if (n == null || n < 0) throw ProtocolException("invalid bulk length")
                            i++
                            if (end - i >= n + 2) {
                                if (buf[i + n] != '\r'.code.toByte() || buf[i + n + 1] != '\n'.code.toByte()) {
                                    throw ProtocolException("invalid bulk length")
                                }
                                args.add(str(buf, i, i + n))
                                i += n + 2
                                found = true
                            }
                            break
                        }
                        i++
                    }
                    if (!found) return null
                }
                return ParsedCommand(args, CommandKind.REDIS, i - off)
            }
            i++
        }
        return null
    }

    private fun readTile38(
        buf: ByteArray,
        off: Int,
        len: Int,
    ): ParsedCommand? {
        val end = off + len
        var i = off + 1
        while (i < end) {
            if (buf[i] == ' '.code.toByte()) {
                val n = parseInt(buf, off + 1, i)
                if (n == null || n < 0) throw ProtocolException("invalid message")
                i++
                if (end >= i + n + 2) {
                    if (buf[i + n] != '\r'.code.toByte() || buf[i + n + 1] != '\n'.code.toByte()) {
                        throw ProtocolException("invalid message")
                    }
                    val args = splitNativeLine(str(buf, i, i + n))
                    return ParsedCommand(args, CommandKind.TILE38, i + n + 2 - off)
                }
                return null
            }
            i++
        }
        return null
    }

    /**
     * Splits a Tile38 native-protocol line on spaces. A JSON object must be the
     * last element of the line, and a double-quoted value is allowed after
     * `SET ... STRING`.
     */
    @JvmStatic
    fun splitNativeLine(input: String): List<String> {
        val args = ArrayList<String>()
        var line = input
        while (line.isNotEmpty()) {
            if (line[0] == '{') {
                args.add(line)
                break
            }
            if (line[0] == '"' && line[line.length - 1] == '"') {
                if (args.isNotEmpty() && args[0].lowercase() == "set" && args[args.size - 1].lowercase() == "string") {
                    args.add(line.substring(1, line.length - 1))
                    break
                }
            }
            val sp = line.indexOf(' ')
            if (sp == -1) {
                args.add(line)
                break
            }
            val value = line.substring(0, sp)
            if (value.isNotEmpty()) args.add(value)
            line = line.substring(sp + 1)
        }
        return args
    }

    private fun readTelnet(
        buf: ByteArray,
        off: Int,
        len: Int,
    ): ParsedCommand? {
        val end = off + len
        for (i in off until end) {
            if (buf[i] != '\n'.code.toByte()) continue
            val lineEnd = if (i > off && buf[i - 1] == '\r'.code.toByte()) i - 1 else i
            val args = splitTelnetLine(str(buf, off, lineEnd))
            return ParsedCommand(args, CommandKind.TELNET, i + 1 - off)
        }
        return null
    }

    /** Splits an inline command, honoring single/double quotes and `\n`, `\r`, `\t` escapes. */
    @JvmStatic
    fun splitTelnetLine(input: String): List<String> {
        val args = ArrayList<String>()
        var line = input
        var quote = false
        var quotech = 0.toChar()
        var escape = false
        outer@ while (true) {
            val nline = StringBuilder(line.length)
            var i = 0
            while (i < line.length) {
                var c = line[i]
                if (!quote) {
                    if (c == ' ') {
                        if (nline.isNotEmpty()) args.add(nline.toString())
                        line = line.substring(i + 1)
                        continue@outer
                    }
                    if (c == '"' || c == '\'') {
                        if (i != 0) throw ProtocolException("unbalanced quotes in request")
                        quotech = c
                        quote = true
                        line = line.substring(i + 1)
                        continue@outer
                    }
                } else {
                    if (escape) {
                        escape = false
                        c =
                            when (c) {
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                else -> c
                            }
                    } else if (c == quotech) {
                        quote = false
                        quotech = 0.toChar()
                        args.add(nline.toString())
                        line = line.substring(i + 1)
                        if (line.isNotEmpty() && line[0] != ' ') throw ProtocolException("unbalanced quotes in request")
                        continue@outer
                    } else if (c == '\\') {
                        escape = true
                        i++
                        continue
                    }
                }
                nline.append(c)
                i++
            }
            if (quote) throw ProtocolException("unbalanced quotes in request")
            if (line.isNotEmpty()) args.add(line)
            break
        }
        return args
    }
}
