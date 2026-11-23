// jtile38-cli entry point (port of tile38-cli argument handling). Copyright jtile38 authors, MIT License.

package io.jtile38.cli

import io.jtile38.json.Gjson
import kotlin.system.exitProcess

/** Parses arguments like tile38-cli; returns null when the CLI should exit immediately. */
fun parseArgs(
    argv: Array<String>,
    env: Map<String, String> = System.getenv(),
    readStdin: () -> String = { System.`in`.readAllBytes().toString(Charsets.UTF_8) },
): CliOptions? {
    val o = CliOptions()
    o.hostname = env["TILE38_HOSTNAME"] ?: o.hostname
    o.output = env["TILE38_OUTPUT"] ?: o.output
    env["TILE38_PORT"]?.toIntOrNull()?.let { o.port = it }
    o.password = env["TILE38_PASSWORD"] ?: ""
    val args = argv.toMutableList()

    fun badArg(arg: String): CliOptions? {
        System.err.println("Unrecognized option or bad number of args for: '$arg'")
        return null
    }

    fun readArg(): String? = if (args.isEmpty()) null else args.removeAt(0)
    while (args.isNotEmpty()) {
        val arg = readArg()!!
        if (arg == "--help" || arg == "-?") {
            Cli.showHelp(System.out, o)
            return null
        }
        if (!arg.startsWith("-")) {
            args.add(0, arg)
            break
        }
        when (arg) {
            "--raw" -> {
                o.raw = true
            }

            "--tty" -> {
                o.tty = true
            }

            "--noprompt" -> {
                o.noprompt = true
            }

            "--resp" -> {
                o.output = "resp"
            }

            "--json" -> {
                o.output = "json"
            }

            "-x" -> {
                o.stdin = true
            }

            "-h" -> {
                o.hostname = readArg() ?: run {
                    Cli.showHelp(System.out, o)
                    return null
                }
            }

            "-p" -> {
                val p = readArg()?.toIntOrNull()
                if (p == null || p !in 0..65535) return badArg(arg)
                o.port = p
            }

            else -> {
                return badArg(arg)
            }
        }
    }
    o.oneCommand = args.joinToString(" ")
    if (o.stdin) {
        val data = readStdin()
        if (!Gjson.valid(data)) {
            System.err.println("Invalid STDIN: Not JSON")
            return null
        }
        val arg = data.replace("\r", "").replace("\n", "").replace("'", "\\'")
        o.oneCommand += " '$arg'"
    }
    return o
}

fun main(argv: Array<String>) {
    val opts = parseArgs(argv) ?: return
    exitProcess(Cli(opts).run())
}
