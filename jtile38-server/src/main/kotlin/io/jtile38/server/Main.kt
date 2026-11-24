// jtile38 server entry point. Copyright jtile38 authors, MIT License.

package io.jtile38.server

import kotlin.system.exitProcess

private fun usage(): Nothing {
    System.err.println(
        """
        Usage: jtile38-server [-h host] [-p port] [-d dir] [--appendonly yes|no] [-o resp|json] [--dev]

          -h host            listening host (default 127.0.0.1)
          -p port            listening port (default 9851)
          -d dir             data directory (default data)
          --appendonly yes|no  enable the append-only file (default yes)
          -o resp|json       default output type for all clients
          --dev              developer mode (enables MASSINSERT, SLEEP and SHUTDOWN)
        """.trimIndent(),
    )
    exitProcess(1)
}

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 9851
    var dir = "data"
    var appendOnly = true
    var output: String? = null
    var dev = false
    var i = 0
    while (i < args.size) {
        fun value(): String = args.getOrNull(++i) ?: usage()
        when (args[i]) {
            "-h", "--host" -> host = value()
            "-p", "--port" -> port = value().toIntOrNull() ?: usage()
            "-d", "--dir" -> dir = value()
            "--appendonly" -> appendOnly = value() == "yes"
            "-o", "--output" -> output = value()
            "--dev", "-dev" -> dev = true
            "--help" -> usage()
            else -> usage()
        }
        i++
    }
    val server =
        try {
            Server(ServerOptions(host, port, dir, appendOnly, output, dev)).start()
        } catch (e: Exception) {
            // the last "error: " line on stderr is the startup failure (used by test harnesses)
            System.err.println("error: " + (e.message ?: e.toString()))
            exitProcess(1)
        }
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    Thread.currentThread().join()
}
