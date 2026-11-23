// Port of tile38 cmd/tile38-cli, Copyright Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.cli

import io.jtile38.client.Tile38Connection
import io.jtile38.client.Tile38Exception
import io.jtile38.json.Gjson
import io.jtile38.resp.RespReader
import io.jtile38.resp.RespValue
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.reader.impl.completer.StringsCompleter
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.InfoCmp
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.PrintStream
import java.net.ConnectException
import java.nio.file.Path

const val VERSION = "0.1.0-SNAPSHOT"
private const val LIVE_JSON = """{"ok":true,"live":true}"""

/** Command line options (same flags and environment variables as tile38-cli). */
class CliOptions(
    var hostname: String = "127.0.0.1",
    var port: Int = 9851,
    var output: String = "json",
    var raw: Boolean = false,
    var noprompt: Boolean = false,
    var tty: Boolean = false,
    var stdin: Boolean = false,
    var oneCommand: String = "",
    var password: String = "",
)

/** The interactive or one-shot CLI. */
class Cli(
    private val opts: CliOptions,
    private val stdout: PrintStream = System.out,
    private val stderr: PrintStream = System.err,
    private val historyFile: Path = Path.of(System.getProperty("user.home"), ".jtile38-cli_history"),
) {
    private val addr = "${opts.hostname}:${opts.port}"
    private var conn: Tile38Connection? = null
    private var output = opts.output

    private val groups: Map<String, List<String>> by lazy {
        Commands.all.values
            .groupBy { it.group }
            .mapValues { (_, v) -> v.map { it.name }.sorted() }
    }

    private fun refused() = "Could not connect to Tile38 at $addr: Connection refused"

    /** Connects and switches the output type. Returns false if the connection failed. */
    private fun connDial(): Boolean {
        try {
            val c = Tile38Connection(opts.hostname, opts.port)
            conn = c
            c.executeInline("output $output")
            return true
        } catch (e: Exception) {
            conn = null
            val cause = e.cause ?: e
            if (e is ConnectException || cause is ConnectException || e is java.net.SocketException) {
                stderr.println(refused())
            } else {
                stderr.println(e.message)
            }
            if (opts.oneCommand != "") throw ExitException(1)
            return false
        }
    }

    class ExitException(
        val code: Int,
    ) : RuntimeException(null, null, false, false)

    /** Runs the CLI and returns the process exit code. */
    fun run(stdinStream: InputStream = System.`in`): Int =
        try {
            runInner(stdinStream)
            0
        } catch (e: ExitException) {
            e.code
        }

    private fun runInner(stdinStream: InputStream) {
        if (opts.oneCommand.isNotEmpty() && opts.oneCommand.lowercase().split(" ")[0] == "help") {
            showHelp(stdout, opts)
            return
        }
        connDial()
        if (conn != null && opts.password != "") conn!!.executeInline("auth ${opts.password}")

        var monitor = false
        var livemode = false
        var aof = false
        var reader: LineReader? = null
        var terminal: Terminal? = null
        var plain: java.io.BufferedReader? = null
        if (opts.oneCommand == "") {
            val interactive = stdinStream === System.`in` && System.console()?.isTerminal == true
            if (interactive) {
                terminal =
                    TerminalBuilder
                        .builder()
                        .system(true)
                        .dumb(true)
                        .build()
                val builder =
                    LineReaderBuilder
                        .builder()
                        .terminal(terminal)
                        .variable(LineReader.HISTORY_FILE, historyFile)
                if (!(opts.noprompt && opts.tty)) builder.completer(completer())
                reader = builder.build()
            } else {
                // Not a terminal (pipes, scripts, embedding): read plain lines like liner's fallback.
                plain = stdinStream.bufferedReader(Charsets.UTF_8)
            }
        }
        try {
            loop@ while (true) {
                var command: String
                if (opts.oneCommand == "") {
                    val prompt =
                        when {
                            opts.raw || opts.noprompt -> ""
                            conn == null -> "not connected> "
                            else -> "$addr> "
                        }
                    command =
                        if (reader != null) {
                            try {
                                reader.readLine(prompt)
                            } catch (_: UserInterruptException) {
                                return
                            } catch (_: EndOfFileException) {
                                throw ExitException(0)
                            }
                        } else {
                            stdout.print(prompt)
                            stdout.flush()
                            plain!!.readLine() ?: throw ExitException(0)
                        }
                } else {
                    command = opts.oneCommand
                }
                command = command.trim()
                val lower = command.lowercase()
                if (command == "") {
                    val c = conn
                    if (c != null) {
                        try {
                            c.executeInline("pInG")
                        } catch (e: Tile38Exception) {
                            stderr.println(refused())
                            c.close()
                            conn = null
                        }
                    }
                } else {
                    when {
                        lower == "exit" || lower == "quit" -> {
                            return
                        }

                        lower == "clear" -> {
                            terminal?.puts(InfoCmp.Capability.clear_screen)
                            terminal?.flush()
                            if (opts.oneCommand != "") return
                            continue@loop
                        }

                        lower.startsWith("help") -> {
                            help(command.substring(4).trim())
                            if (opts.oneCommand != "") return
                            continue@loop
                        }
                    }
                    aof = lower.startsWith("aof ")
                    var msg: ByteArray
                    while (true) {
                        if (conn == null && !connDial()) continue@loop
                        try {
                            msg = conn!!.executeInline(plainToCompat(command))
                            break
                        } catch (e: Tile38Exception) {
                            if (e.cause !is EOFException) stderr.println(e.message)
                            conn?.close()
                            conn = null
                        }
                    }
                    val text = String(msg, Charsets.ISO_8859_1)
                    when (lower) {
                        "output resp" -> {
                            if (text == "+OK\r\n") output = "resp"
                        }

                        "output json" -> {
                            if (jsonOK(convert2termjson(msg))) output = "json"
                        }

                        "monitor" -> {
                            monitor = true
                            livemode = true
                            output = "resp"
                        }
                    }
                    if (output == "resp" &&
                        (text.startsWith("*3\r\n\$10\r\npsubscribe\r\n") || text.startsWith("*3\r\n\$9\r\nsubscribe\r\n"))
                    ) {
                        livemode = true
                    }
                    if (!opts.raw) msg = if (output == "resp") convert2termresp(msg) else convert2termjson(msg)
                    val out = String(msg, Charsets.UTF_8)
                    if (!livemode && output == "json") {
                        val c = Gjson.get(out, "command").string()
                        if (c == "psubscribe" || c == "subscribe" || out == LIVE_JSON) livemode = true
                    }
                    var mustOutput = true
                    if (!monitor && opts.oneCommand == "" && output == "json" && !jsonOK(msg)) {
                        if (Gjson.valid(out)) {
                            stderr.println("(error) " + Gjson.get(out, "err").string())
                            mustOutput = false
                        }
                    } else if (livemode) {
                        stderr.println(out)
                        break@loop
                    }
                    if (mustOutput) stdout.println(out)
                }
                if (opts.oneCommand != "") return
            }
        } finally {
            reader?.history?.save()
            terminal?.close()
            if (livemode) streamLive(aof)
        }
    }

    /** Feeds live data (MONITOR, SUBSCRIBE, FENCE, AOF) to the screen until the connection closes. */
    private fun streamLive(aof: Boolean) {
        val c = conn ?: return
        try {
            if (aof) {
                c.pipeTo(stdout)
                stderr.println()
            } else {
                while (true) {
                    var msg = c.readRawReply()
                    if (!opts.raw) msg = if (output == "resp") convert2termresp(msg) else convert2termjson(msg)
                    stderr.println(String(msg, Charsets.UTF_8))
                }
            }
        } catch (e: Tile38Exception) {
            if (e.cause !is EOFException) stderr.println(e.message)
        }
    }

    private fun completer() =
        org.jline.reader.Completer { _, line, candidates ->
            val text = line.line().substring(0, line.cursor())
            val names = Commands.all.keys.sorted()
            if (text.lowercase().startsWith("help ")) {
                val nline = text.substring(5).trim()
                if (nline == "" || nline[0] == '@') {
                    groups.keys.sorted().forEach { candidates.add(org.jline.reader.Candidate("@$it")) }
                } else {
                    names.forEach { candidates.add(org.jline.reader.Candidate(it)) }
                }
            } else if (!text.contains(' ')) {
                StringsCompleter(names).complete(null, line, candidates)
            }
        }

    private fun help(arg: String) {
        val groupsLine = "Groups: " + groups.keys.joinToString(", ") { "@$it" } + "\n"
        if (arg == "") {
            stderr.println("jtile38-cli $VERSION")
            stderr.println("""Type:   "help @<group>" to get a list of commands in <group>""")
            stderr.println("""        "help <command>" for help on <command>""")
            if (!(opts.noprompt && opts.tty)) stderr.println("""        "help <tab>" to get a list of possible help topics""")
            stderr.println("""        "quit" to exit""")
            if (opts.noprompt && opts.tty) stderr.print(groupsLine)
            return
        }
        var found = false
        var showGroups = false
        if (arg.startsWith("@")) {
            for (name in groups[arg.substring(1)].orEmpty()) {
                stderr.println(Commands.all[name]!!.termOutput("  "))
                found = true
            }
            if (!found) showGroups = true
        } else {
            Commands.all[arg.uppercase()]?.let {
                stderr.println(it.termOutput("  "))
                found = true
            }
        }
        if (showGroups) {
            if (opts.noprompt && opts.tty) stderr.print(groupsLine)
        } else if (!found && opts.noprompt && opts.tty) {
            help("")
        }
    }

    companion object {
        fun showHelp(
            out: PrintStream,
            o: CliOptions = CliOptions(),
        ) {
            out.print(
                "jtile38-cli $VERSION\n\n" +
                    "Usage: jtile38-cli [OPTIONS] [cmd [arg [arg ...]]]\n" +
                    " --raw              Use raw formatting for replies\n" +
                    " --noprompt         Do not display a prompt\n" +
                    " --resp             Use RESP output formatting (default is JSON output)\n" +
                    " --json             Use JSON output formatting (default is JSON output)\n" +
                    " -h <hostname>      Server hostname (default: ${o.hostname})\n" +
                    " -p <port>          Server port (default: ${o.port})\n" +
                    " -x                 Read last argument from STDIN.\n\n",
            )
        }

        private fun jsonOK(msg: ByteArray) = Gjson.get(String(msg, Charsets.UTF_8), "ok").bool()

        /** Formats a raw RESP reply like redis-cli. */
        fun convert2termresp(msg: ByteArray): ByteArray {
            val rd = RespReader(ByteArrayInputStream(msg))
            val out = StringBuilder()
            while (true) {
                val v =
                    try {
                        rd.readValue()
                    } catch (_: Exception) {
                        break
                    }
                out.append(termRespValue(v, 0))
            }
            return out.toString().trim().toByteArray(Charsets.ISO_8859_1)
        }

        /** Strips the bulk-string header from a JSON reply. */
        fun convert2termjson(msg: ByteArray): ByteArray {
            if (msg.isNotEmpty() && msg[0] == '{'.code.toByte()) return msg
            val nl = msg.indexOf('\n'.code.toByte())
            return String(msg, nl + 1, msg.size - nl - 1, Charsets.ISO_8859_1).trim().toByteArray(Charsets.ISO_8859_1)
        }

        private fun termRespValue(
            v: RespValue,
            spaces: Int,
        ): String =
            when (v) {
                is RespValue.BulkString -> {
                    if (v.value == null) "(nil)" else "\"" + v.value + "\""
                }

                is RespValue.Integer -> {
                    "(integer) $v"
                }

                is RespValue.Error -> {
                    "(error) $v"
                }

                is RespValue.Array -> {
                    val arr = v.values.orEmpty()
                    if (arr.isEmpty()) {
                        "(empty list or set)"
                    } else {
                        val sb = StringBuilder()
                        val nspaces = spaces + arr.size.toString().length
                        arr.forEachIndexed { i, item ->
                            if (i > 0) sb.append(" ".repeat(spaces))
                            sb
                                .append(i + 1)
                                .append(") ")
                                .append(termRespValue(item, nspaces + 2).trim())
                                .append('\n')
                        }
                        sb.toString()
                    }
                }

                else -> {
                    v.toString()
                }
            }

        /** Converts a plain command line into a Tile38 inline command, quoting arguments that contain quotes. */
        fun plainToCompat(message: String): String {
            val args = ArrayList<String>()
            var i = 0
            while (i < message.length) {
                if (message[i] != ' ') {
                    if (message[i] == '"' || message[i] == '\'') {
                        val s = i
                        val cap = message[i]
                        while (i < message.length) {
                            if (message[i] == cap && i > s) {
                                if (message[i - 1] == '\\') {
                                    i++
                                    continue
                                }
                                if (i == message.length - 1 || message[i + 1] == ' ') {
                                    args.add(message.substring(s, i + 1))
                                    i++
                                    break
                                }
                            }
                            i++
                        }
                    } else {
                        val s = i
                        var quotes = false
                        while (i < message.length) {
                            if (message[i] == '"' || message[i] == '\'') quotes = true
                            if (i == message.length - 1 || message[i + 1] == ' ') {
                                var arg = message.substring(s, i + 1)
                                if (quotes) arg = goQuote(arg)
                                args.add(arg)
                                i++
                                break
                            }
                            i++
                        }
                    }
                }
                i++
            }
            return args.joinToString(" ")
        }

        /** Go `strconv.Quote` for the characters that matter on a command line. */
        private fun goQuote(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c < ' ') sb.append("\\x%02x".format(c.code)) else sb.append(c)
                }
            }
            return sb.append('"').toString()
        }
    }
}
