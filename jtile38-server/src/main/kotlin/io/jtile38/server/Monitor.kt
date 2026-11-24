// Port of tile38 internal/server/monitor.go, Copyright Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.server

import io.jtile38.resp.RespValue
import java.io.OutputStream
import java.time.Instant

/** MONITOR: streams every command the server processes to this connection. */
internal fun cmdMonitor(msg: Message): RespValue {
    if (msg.args.size != 1) throw Errors.invalidNumberOfArguments()
    throw GoLive.Monitor
}

/** Runs a MONITOR session (Go `liveMonitor`): any input from the client ends it. */
internal fun Server.goLiveMonitor(
    input: LiveReader,
    out: OutputStream,
) {
    monitors.add(out)
    try {
        synchronized(out) {
            out.write("+OK\r\n".toByteArray())
            out.flush()
        }
        val msgs = input.readMessages() ?: return
        if (msgs.any { it.args.size == 1 && it.args[0].lowercase() == "quit" }) {
            synchronized(out) {
                out.write("+OK\r\n".toByteArray())
                out.flush()
            }
        }
    } catch (_: java.io.IOException) {
    } finally {
        monitors.remove(out)
    }
}

private val MONITOR_SKIPPED =
    setOf("config", "auth", "follow", "slaveof", "replconf", "aof", "aofmd5", "client", "monitor")

/**
 * Sends a processed command to MONITOR clients (Go `sendMonitor`). [err] is the command's error
 * message, if any; AOF replay and internal commands (no client) are not sent.
 */
internal fun Server.sendMonitor(
    err: String?,
    msg: Message,
) {
    if (monitors.isEmpty()) return
    val client = msg.client ?: return
    if (err != null && (err == Errors.INVALID_NUMBER_OF_ARGUMENTS || err.startsWith("unknown command "))) return
    // accept all commands except for these
    if (msg.command in MONITOR_SKIPPED) return
    val line = msg.args.joinToString(" ") { goQuote(it) }
    val now = Instant.now()
    val tstr = String.format(java.util.Locale.ROOT, "%.6f", (now.epochSecond * 1_000_000_000L + now.nano) / 1e9)
    val data = "+$tstr [0 ${client.remoteAddr}] $line\r\n".toByteArray(Charsets.ISO_8859_1)
    for (out in monitors) {
        try {
            synchronized(out) {
                out.write(data)
                out.flush()
            }
        } catch (_: java.io.IOException) {
            monitors.remove(out)
        }
    }
}

/** Go `strconv.Quote` over a byte string: the result is a byte string as well. */
internal fun goQuote(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    var i = 0
    while (i < s.length) {
        val (r, width) = Utf8.decodeRune(s, i)
        if (width == 1 && r == Utf8.RUNE_ERROR) {
            sb.append("\\x").append(HEX[s[i].code shr 4]).append(HEX[s[i].code and 0xF])
            i++
            continue
        }
        appendEscapedRune(sb, r, s, i, width)
        i += width
    }
    sb.append('"')
    return sb.toString()
}

private const val HEX = "0123456789abcdef"

private fun appendEscapedRune(
    sb: StringBuilder,
    r: Int,
    s: String,
    at: Int,
    width: Int,
) {
    if (r == '"'.code || r == '\\'.code) {
        sb.append('\\').append(r.toChar())
        return
    }
    if (isGoPrint(r)) {
        // printable: copy the UTF-8 bytes
        sb.append(s, at, at + width)
        return
    }
    when (r) {
        0x07 -> {
            sb.append("\\a")
        }

        0x08 -> {
            sb.append("\\b")
        }

        0x0C -> {
            sb.append("\\f")
        }

        0x0A -> {
            sb.append("\\n")
        }

        0x0D -> {
            sb.append("\\r")
        }

        0x09 -> {
            sb.append("\\t")
        }

        0x0B -> {
            sb.append("\\v")
        }

        else -> {
            when {
                r < ' '.code || r == 0x7f -> {
                    sb.append("\\x").append(HEX[r shr 4]).append(HEX[r and 0xF])
                }

                r < 0x10000 -> {
                    sb.append("\\u")
                    for (shift in 12 downTo 0 step 4) sb.append(HEX[(r shr shift) and 0xF])
                }

                else -> {
                    sb.append("\\U")
                    for (shift in 28 downTo 0 step 4) sb.append(HEX[(r shr shift) and 0xF])
                }
            }
        }
    }
}

/**
 * Go `unicode.IsPrint`: letters, marks, numbers, punctuation, symbols and the ASCII space. Java's
 * Unicode tables stand in for Go's, so very new characters may be classified differently.
 */
private fun isGoPrint(r: Int): Boolean {
    if (r < 0x80) return r in 0x20..0x7E
    return when (Character.getType(r).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.SURROGATE, Character.UNASSIGNED,
        Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
        -> false

        else -> true
    }
}
