// Port of tile38 internal/server/config.go, Copyright Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.server

import io.jtile38.json.Gjson
import io.jtile38.resp.RespValue
import java.io.File
import java.security.SecureRandom

/**
 * Server configuration stored as JSON in `<dir>/config`, with the same keys and file layout as
 * Tile38. Settable properties: requirepass, leaderauth, protected-mode, maxmemory, autogc,
 * keepalive, logconfig, replica-priority, replica_announce_ip and replica_announce_port.
 */
internal class Config private constructor(
    private val file: File?,
) {
    var followHost = ""
    var followPort = 0L
    var followId = ""
    var followPos = 0L
    var replicaPriority = -1L
    var serverId = ""
    var readOnly = false

    private var requirePassP = ""
    var requirePass = ""
        private set
    private var leaderAuthP = ""
    var leaderAuth = ""
        private set
    private var protectedModeP = ""
    var protectedMode = DEFAULT_PROTECTED_MODE
        private set
    private var maxMemoryP = ""
    var maxMemory = 0L
        private set
    private var autoGCP = ""
    var autoGC = 0L
        private set
    private var keepAliveP = ""
    var keepAlive = DEFAULT_KEEP_ALIVE
        private set
    private var logConfigP = ""
    var logConfig = ""
        private set
    private var announceIPP = ""
    var announceIP = ""
        private set
    private var announcePortP = ""
    var announcePort = 0L
        private set

    @Synchronized
    fun setProperty(
        name: String,
        value: String,
        fromLoad: Boolean,
    ) {
        var invalid = false
        when (name) {
            REQUIRE_PASS -> {
                requirePass = value
            }

            LEADER_AUTH -> {
                leaderAuth = value
            }

            AUTO_GC -> {
                autoGC =
                    if (value ==
                        ""
                    ) {
                        0
                    } else {
                        GoCompat.parseUint(value)
                            ?: throw CommandException("strconv.ParseUint: parsing \"$value\": invalid syntax")
                    }
            }

            MAX_MEMORY -> {
                maxMemory = parseMemSize(value) ?: throw invalidArg(value, name)
            }

            PROTECTED_MODE -> {
                when (value.lowercase()) {
                    "" -> if (fromLoad) protectedMode = DEFAULT_PROTECTED_MODE else invalid = true
                    "yes", "no" -> protectedMode = value.lowercase()
                    else -> invalid = true
                }
            }

            KEEP_ALIVE -> {
                if (value == "") {
                    keepAlive = DEFAULT_KEEP_ALIVE
                } else {
                    val v = GoCompat.parseUint(value)
                    if (v == null) invalid = true else keepAlive = v
                }
            }

            LOG_CONFIG -> {
                logConfig = value
            }

            REPLICA_PRIORITY -> {
                val v = GoCompat.parseInt(value)
                if (v == null || v < 0) invalid = true else replicaPriority = v
            }

            ANNOUNCE_IP -> {
                announceIP = value
            }

            ANNOUNCE_PORT -> {
                if (value == "") {
                    announcePort = 0
                } else {
                    val v = GoCompat.parseUint(value)
                    if (v == null) invalid = true else announcePort = v
                }
            }

            else -> {
                throw CommandException("Unsupported CONFIG parameter: $name")
            }
        }
        if (invalid) throw invalidArg(value, name)
    }

    @Synchronized
    fun getProperty(name: String): String =
        when (name) {
            AUTO_GC -> autoGC.toString()
            REQUIRE_PASS -> requirePass
            LEADER_AUTH -> leaderAuth
            PROTECTED_MODE -> protectedMode
            MAX_MEMORY -> formatMemSize(maxMemory)
            KEEP_ALIVE -> keepAlive.toString()
            LOG_CONFIG -> logConfig
            REPLICA_PRIORITY -> if (replicaPriority < 0) "" else replicaPriority.toString()
            ANNOUNCE_IP -> announceIP
            ANNOUNCE_PORT -> announcePort.toString()
            else -> ""
        }

    /** Properties whose name matches [pattern], sorted by name. */
    fun getProperties(pattern: String): Map<String, String> =
        VALID_PROPERTIES.filter { Glob.matches(pattern, it) }.associateWith { getProperty(it) }.toSortedMap()

    /** Writes the config file; with [writeProperties], the current property values are saved too (CONFIG REWRITE). */
    @Synchronized
    fun write(writeProperties: Boolean) {
        if (writeProperties) {
            requirePassP = requirePass
            leaderAuthP = leaderAuth
            protectedModeP = if (protectedMode == DEFAULT_PROTECTED_MODE) "" else protectedMode
            maxMemoryP = formatMemSize(maxMemory)
            autoGCP = if (autoGC == 0L) "" else autoGC.toString()
            keepAliveP = if (keepAlive == DEFAULT_KEEP_ALIVE) "" else keepAlive.toString()
            if (logConfig != "") logConfigP = logConfig
            if (announceIP != "") announceIPP = announceIP
            announcePortP = if (announcePort == 0L) "" else announcePort.toString()
        }
        val m = sortedMapOf<String, String>()
        if (followHost != "") m[FOLLOW_HOST] = GoCompat.jsonMarshalString(followHost)
        if (followPort != 0L) m[FOLLOW_PORT] = followPort.toString()
        if (followId != "") m[FOLLOW_ID] = GoCompat.jsonMarshalString(followId)
        if (followPos != 0L) m[FOLLOW_POS] = followPos.toString()
        if (replicaPriority >= 0) m[REPLICA_PRIORITY] = replicaPriority.toString()
        if (serverId != "") m[SERVER_ID] = GoCompat.jsonMarshalString(serverId)
        if (readOnly) m[READ_ONLY] = "true"
        if (requirePassP != "") m[REQUIRE_PASS] = GoCompat.jsonMarshalString(requirePassP)
        if (leaderAuthP != "") m[LEADER_AUTH] = GoCompat.jsonMarshalString(leaderAuthP)
        if (protectedModeP != "") m[PROTECTED_MODE] = GoCompat.jsonMarshalString(protectedModeP)
        if (maxMemoryP != "") m[MAX_MEMORY] = GoCompat.jsonMarshalString(maxMemoryP)
        if (autoGCP != "") m[AUTO_GC] = GoCompat.jsonMarshalString(autoGCP)
        if (keepAliveP != "") m[KEEP_ALIVE] = GoCompat.jsonMarshalString(keepAliveP)
        if (logConfigP != "" && Gjson.valid(logConfig) && Gjson.parse(logConfig).isObject() && Gjson.parse(logConfig).map().isNotEmpty()) {
            m[LOG_CONFIG] =
                io.jtile38.json.Pretty
                    .ugly(logConfig)
        }
        if (announceIPP != "") m[ANNOUNCE_IP] = GoCompat.jsonMarshalString(announceIPP)
        if (announcePortP != "") m[ANNOUNCE_PORT] = GoCompat.jsonMarshalString(announcePortP)
        val f = file ?: return
        // encoding/json MarshalIndent with a tab, as Tile38 writes it
        val body = if (m.isEmpty()) "{}" else m.entries.joinToString(",\n", "{\n", "\n}") { "\t\"${it.key}\": ${it.value}" }
        f.parentFile?.mkdirs()
        f.writeText(body + "\n", Charsets.ISO_8859_1)
    }

    companion object {
        const val DEFAULT_KEEP_ALIVE = 300L
        const val DEFAULT_PROTECTED_MODE = "yes"
        const val FOLLOW_HOST = "follow_host"
        const val FOLLOW_PORT = "follow_port"
        const val FOLLOW_ID = "follow_id"
        const val FOLLOW_POS = "follow_pos"
        const val REPLICA_PRIORITY = "replica-priority"
        const val SERVER_ID = "server_id"
        const val READ_ONLY = "read_only"
        const val REQUIRE_PASS = "requirepass"
        const val LEADER_AUTH = "leaderauth"
        const val PROTECTED_MODE = "protected-mode"
        const val MAX_MEMORY = "maxmemory"
        const val AUTO_GC = "autogc"
        const val KEEP_ALIVE = "keepalive"
        const val LOG_CONFIG = "logconfig"
        const val ANNOUNCE_IP = "replica_announce_ip"
        const val ANNOUNCE_PORT = "replica_announce_port"
        val VALID_PROPERTIES =
            listOf(
                REQUIRE_PASS,
                LEADER_AUTH,
                PROTECTED_MODE,
                MAX_MEMORY,
                AUTO_GC,
                KEEP_ALIVE,
                LOG_CONFIG,
                REPLICA_PRIORITY,
                ANNOUNCE_PORT,
                ANNOUNCE_IP,
            )

        private fun invalidArg(
            value: String,
            name: String,
        ) = CommandException("Invalid argument '$value' for CONFIG SET '$name'")

        /** Loads (or creates) the config; [file] null keeps it in memory only. */
        fun load(file: File?): Config {
            val json = if (file != null && file.exists()) file.readText(Charsets.ISO_8859_1) else ""
            val c = Config(file)

            fun str(k: String) = Gjson.get(json, k).string()
            c.followHost = str(FOLLOW_HOST)
            c.followPort = Gjson.get(json, FOLLOW_PORT).int()
            c.followId = str(FOLLOW_ID)
            c.followPos = Gjson.get(json, FOLLOW_POS).int()
            c.serverId = str(SERVER_ID)
            c.readOnly = Gjson.get(json, READ_ONLY).bool()
            c.requirePassP = str(REQUIRE_PASS)
            c.leaderAuthP = str(LEADER_AUTH)
            c.protectedModeP = str(PROTECTED_MODE)
            c.maxMemoryP = str(MAX_MEMORY)
            c.autoGCP = str(AUTO_GC)
            c.keepAliveP = str(KEEP_ALIVE)
            c.logConfig = str(LOG_CONFIG)
            c.announceIPP = str(ANNOUNCE_IP)
            c.announcePortP = str(ANNOUNCE_PORT)
            if (c.serverId == "") c.serverId = randomKey(16)
            c.replicaPriority = if (Gjson.get(json, REPLICA_PRIORITY).exists()) Gjson.get(json, REPLICA_PRIORITY).int() else -1
            c.setProperty(REQUIRE_PASS, c.requirePassP, true)
            c.setProperty(LEADER_AUTH, c.leaderAuthP, true)
            c.setProperty(PROTECTED_MODE, c.protectedModeP, true)
            c.setProperty(MAX_MEMORY, c.maxMemoryP, true)
            c.setProperty(AUTO_GC, c.autoGCP, true)
            c.setProperty(KEEP_ALIVE, c.keepAliveP, true)
            c.setProperty(LOG_CONFIG, c.logConfig, true)
            c.setProperty(ANNOUNCE_IP, c.announceIPP, true)
            c.setProperty(ANNOUNCE_PORT, c.announcePortP, true)
            c.write(false)
            return c
        }

        /** Go `randomKey(n)`: n random bytes, hex encoded. */
        fun randomKey(n: Int): String = ByteArray(n).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

        fun parseMemSize(s0: String): Long? {
            if (s0 == "") return 0
            val s = s0.lowercase()
            val (num, mul) =
                when {
                    s.endsWith("gb") -> s.dropLast(2) to 1024L * 1024 * 1024
                    s.endsWith("mb") -> s.dropLast(2) to 1024L * 1024
                    s.endsWith("kb") -> s.dropLast(2) to 1024L
                    else -> s to 1L
                }
            return (GoCompat.parseUint(num) ?: return null) * mul
        }

        fun formatMemSize(sz0: Long): String {
            var sz = sz0
            if (sz <= 0) return ""
            if (sz < 1024) return sz.toString()
            sz /= 1024
            if (sz < 1024) return "${sz}kb"
            sz /= 1024
            if (sz < 1024) return "${sz}mb"
            sz /= 1024
            return "${sz}gb"
        }
    }
}

/** CONFIG GET pattern | CONFIG SET name [value] | CONFIG REWRITE */
internal fun Server.cmdConfig(msg: Message): RespValue {
    val start = System.nanoTime()
    if (msg.args.size < 2) throw CommandException("unknown command '${msg.args[0]}'")
    val sub = msg.args[1].lowercase()
    val vs = msg.args.drop(2)
    when (sub) {
        "get" -> {
            if (vs.size != 1) throw Errors.invalidNumberOfArguments()
            val m = config.getProperties(vs[0])
            if (msg.outputType == OutputType.JSON) {
                val data =
                    m.entries.joinToString(",", "{", "}") {
                        GoCompat.jsonMarshalString(it.key) + ":" +
                            GoCompat.jsonMarshalString(it.value)
                    }
                return RespValue.string("{\"ok\":true,\"properties\":$data,\"elapsed\":\"${elapsed(start)}\"}")
            }
            return RespValue.array(m.entries.flatMap { listOf(RespValue.string(it.key), RespValue.string(it.value)) })
        }

        "set" -> {
            if (vs.isEmpty()) throw Errors.invalidNumberOfArguments()
            val name = vs[0]
            if (vs.size == 1 && name.lowercase() != Config.REQUIRE_PASS) throw Errors.invalidNumberOfArguments()
            if (vs.size > 2) throw Errors.invalidNumberOfArguments()
            config.setProperty(name, vs.getOrElse(1) { "" }, false)
            if (name == Config.MAX_MEMORY) checkOutOfMemory()
            return if (msg.outputType == OutputType.JSON) okJson(start) else RespValue.OK
        }

        "rewrite" -> {
            if (vs.isNotEmpty()) throw Errors.invalidNumberOfArguments()
            config.write(true)
            return if (msg.outputType == OutputType.JSON) okJson(start) else RespValue.OK
        }

        else -> {
            throw CommandException("unknown command '${msg.args[0]} ${msg.args[1]}'")
        }
    }
}
