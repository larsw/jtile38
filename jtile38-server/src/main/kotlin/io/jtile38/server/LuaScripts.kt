// Port of the Lua support in tile38 internal/server/scripts.go, Copyright Joshua J Baker, MIT
// License, and of the json module of layeh.com/gopher-json (public domain).
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.server

import io.jtile38.geojson.geo.Geo
import io.jtile38.json.Gjson
import io.jtile38.json.JsonResult
import io.jtile38.json.JsonType
import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.LuaClosure
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaString
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Prototype
import org.luaj.vm2.Varargs
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.DebugLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JseMathLib
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10

/*
 * Lua engine notes. Tile38 runs scripts on gopher-lua (Lua 5.1); jtile38 uses LuaJ 3.0.1 (Lua 5.2
 * semantics, MIT licensed, the only maintained pure JVM Lua). The environment is made to look like
 * Tile38's: only tonumber/tostring from the base library (ported from Tile38, including its
 * tonumber quirks), the table/string/math libraries with gopher-lua's function sets (table.getn,
 * table.maxn, math.log10 and math.mod added; table.pack/unpack removed), os.clock/os.difftime, the
 * tile38 and json tables, and globals that cannot be created. Runtime errors are reported in
 * gopher-lua's format ("chunk:line: message" plus a stack traceback) with gopher-lua's wording for
 * comparison, arithmetic, concatenation, length and call errors.
 *
 * Known differences: syntax error messages only match gopher-lua for an unexpected end of
 * script; the traceback of errors inside nested Lua functions follows LuaJ; numbers converted to
 * strings by the .. operator or string functions use LuaJ's formatting (tostring() uses Go's);
 * json.decode error messages differ from Go's encoding/json.
 */

/** Thrown from Kotlin functions called by scripts; gopher-lua shows them as a "[G]" frame. */
internal class GoFunctionError(
    message: String,
    val frame: String,
) : LuaError(message)

/** One Lua interpreter (Go `*lua.LState`) with Tile38's restricted environment. */
internal class LuaState(
    val globals: Globals,
) {
    /** Last source line executed by the main chunk; used for errors LuaJ reports without a position. */
    var line = 0

    fun setRaw(
        name: String,
        value: LuaValue,
    ) {
        globals.rawset(name, value)
    }

    fun closure(p: Prototype): LuaClosure = LuaClosure(p, globals)
}

/** Lua interpreters and compiled scripts shared by WHEREEVAL (and, later, EVAL). */
internal class LuaScripts {
    private val saved = ArrayList<LuaState>()
    private var total = 0

    /** Scripts loaded with SCRIPT LOAD or EVAL (Go lScriptMap.scripts). */
    private val scripts = HashMap<String, Prototype>()

    /** Scripts compiled by WHEREEVAL, least recently used dropped first (Go tinylru, size 256). */
    private val lru =
        object : LinkedHashMap<String, Prototype>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Prototype>?) = size > LRU_SIZE
        }

    init {
        synchronized(this) { repeat(INITIAL_POOL_SIZE) { saved.add(newState()) } }
        total = INITIAL_POOL_SIZE
    }

    /** Takes an interpreter from the pool (Go lStatePool.Get). */
    @Synchronized
    fun get(): LuaState {
        if (saved.isEmpty()) {
            if (total >= MAX_POOL_SIZE) throw CommandException("no interpreters available")
            total++
            return newState()
        }
        return saved.removeAt(saved.size - 1)
    }

    /** Returns an interpreter to the pool (Go lStatePool.Put). */
    @Synchronized
    fun put(state: LuaState) {
        saved.add(state)
    }

    @Synchronized
    fun getScript(sha: String): Prototype? = scripts[sha] ?: lru[sha]

    @Synchronized
    fun putScript(
        sha: String,
        p: Prototype,
    ) {
        scripts[sha] = p
    }

    @Synchronized
    fun putScriptLru(
        sha: String,
        p: Prototype,
    ) {
        lru[sha] = p
    }

    @Synchronized
    fun flush() {
        scripts.clear()
        lru.clear()
    }

    /**
     * Compiles [script] (a byte string) with the chunk name `f_<sha>`. Syntax errors become a
     * CommandException with gopher-lua's message shape, newlines escaped (Go makeSafeErr).
     */
    fun compile(
        state: LuaState,
        script: String,
        sha: String,
    ): Prototype {
        val chunk = "f_$sha"
        try {
            return state.globals.compilePrototype(ByteArrayInputStream(script.toByteArray(Charsets.ISO_8859_1)), chunk)
        } catch (e: LuaError) {
            throw CommandException(makeSafeErr(syntaxError(chunk, e.message ?: "")))
        }
    }

    private fun syntaxError(
        chunk: String,
        luajMessage: String,
    ): String {
        // LuaJ: `f_<sha>:1: unexpected symbol 286 (...)` (286 is the end of the stream)
        val m = Regex("""^(?:\[string "[^"]*"\]|[^:]*):(\d+): (.*)$""", RegexOption.DOT_MATCHES_ALL).find(luajMessage)
        val line = m?.groupValues?.get(1) ?: "1"
        val msg = m?.groupValues?.get(2) ?: luajMessage
        if (msg.startsWith("unexpected symbol $TK_EOS ") || msg.contains("near '<eof>'") || msg.contains("near <eof>")) {
            return "$chunk at EOF:   syntax error\n"
        }
        return "$chunk line:$line:   $msg\n"
    }

    private fun newState(): LuaState {
        val g = Globals()
        LoadState.install(g)
        LuaC.install(g)
        val state = LuaState(g)
        // LuaJ's libraries register themselves in package.loaded; scripts get no package table
        val pkg = LuaTable()
        pkg.rawset("loaded", LuaTable())
        g.rawset("package", pkg)
        // debug library: positions and tracebacks for runtime errors, and a line hook; scripts
        // cannot reach it
        g.load(DebugLib())
        val debug = g.get("debug")
        g.rawset("debug", LuaValue.NIL)
        // base library subset
        g.rawset("_G", g)
        g.rawset("_VERSION", LuaValue.valueOf("Lua 5.1"))
        g.rawset("_GOPHER_LUA_VERSION", LuaValue.valueOf("GopherLua 0.1"))
        g.rawset("tonumber", fn { baseToNumber(it) })
        g.rawset("tostring", fn { baseToString(it) })
        // table, with gopher-lua's functions
        g.load(TableLib())
        val table = g.get("table")
        table.rawset("pack", LuaValue.NIL)
        table.rawset("unpack", LuaValue.NIL)
        table.rawset("getn", fn { LuaValue.valueOf(it.checktable(1).length()) })
        table.rawset("maxn", fn { tableMaxN(it.checktable(1)) })
        g.load(StringLib())
        g.load(JseMathLib())
        val math = g.get("math")
        math.rawset("log10", fn { LuaValue.valueOf(log10(it.checkdouble(1))) })
        math.rawset("mod", math.get("fmod"))
        // os: clock and difftime only
        val os = LuaTable()
        os.rawset("clock", fn { LuaValue.valueOf((System.nanoTime() - STARTED_AT) / 1e9) })
        os.rawset("difftime", fn { LuaValue.valueOf((it.checklong(1) - it.checklong(2)).toDouble()) })
        g.rawset("os", os)
        g.rawset("package", LuaValue.NIL)
        g.rawset("tile38", tile38Table())
        g.rawset("json", jsonTable())
        // prohibit creating new globals in this state
        val mt = LuaTable()
        mt.rawset(
            "__newindex",
            object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs =
                    throw GoFunctionError("attempt to create global variable '${args.arg(2).tojstring()}'", "function (anonymous)")
            },
        )
        g.setmetatable(mt)
        // track the current line of the main chunk
        debug.get("sethook").call(
            object : TwoArgFunction() {
                override fun call(
                    event: LuaValue,
                    line: LuaValue,
                ): LuaValue {
                    state.line = line.toint()
                    return LuaValue.NONE
                }
            },
            LuaValue.valueOf("l"),
        )
        return state
    }

    private fun tile38Table(): LuaTable {
        val t = LuaTable()
        // tile38.call and tile38.pcall run commands from EVAL scripts; elsewhere (WHEREEVAL) they
        // fail like Tile38's luaTile38Call without an EVAL_CMD
        t.rawset(
            "call",
            object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs =
                    throw GoFunctionError("ERR command not supported in scripts", "function 'call'")
            },
        )
        t.rawset(
            "pcall",
            fn {
                val tbl = LuaTable()
                tbl.rawset("err", bytes("command not supported in scripts"))
                tbl
            },
        )
        t.rawset(
            "error_reply",
            fn {
                val tbl = LuaTable()
                tbl.rawset("err", LuaString.valueOf(it.arg1().tojstringBytes()))
                tbl
            },
        )
        t.rawset(
            "status_reply",
            fn {
                val tbl = LuaTable()
                tbl.rawset("ok", LuaString.valueOf(it.arg1().tojstringBytes()))
                tbl
            },
        )
        t.rawset("sha1hex", fn { bytes(sha1Hex(luaToByteString(it.arg1()))) })
        t.rawset(
            "distance_to",
            fn {
                LuaValue.valueOf(
                    Geo.distanceTo(it.arg(1).todouble(), it.arg(2).todouble(), it.arg(3).todouble(), it.arg(4).todouble()),
                )
            },
        )
        return t
    }

    private fun jsonTable(): LuaTable {
        val t = LuaTable()
        t.rawset(
            "decode",
            object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val s = luaToByteString(args.checkstring(1))
                    val v = jsonDecode(s) ?: return LuaValue.varargsOf(LuaValue.NIL, bytes("invalid JSON"))
                    return v
                }
            },
        )
        t.rawset(
            "encode",
            object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val sb = StringBuilder()
                    return try {
                        jsonEncode(args.checkvalue(1), sb, HashSet())
                        bytes(sb.toString())
                    } catch (e: IllegalArgumentException) {
                        LuaValue.varargsOf(LuaValue.NIL, bytes(e.message ?: ""))
                    }
                }
            },
        )
        return t
    }

    /** Formats a runtime error like gopher-lua's ApiError: message, then the stack traceback. */
    fun runtimeError(
        state: LuaState,
        e: LuaError,
        chunk: String,
    ): String {
        val raw = e.message ?: ""
        val head: String
        val luajFrames: List<String>
        val nl = raw.indexOf("\nstack traceback:\n")
        if (nl >= 0) {
            head = raw.substring(0, nl)
            luajFrames = raw.substring(nl + "\nstack traceback:\n".length).split('\n').map { it.trim() }
        } else {
            head = raw
            luajFrames = emptyList()
        }
        var line = state.line
        var msg = head
        val pos = Regex("""^${Regex.escape(chunk)}:(\d+) (.*)$""", RegexOption.DOT_MATCHES_ALL).find(head)
        if (pos != null) {
            line = pos.groupValues[1].toInt()
            msg = pos.groupValues[2]
        }
        val frames = ArrayList<String>()
        if (e is GoFunctionError) frames.add("[G]: in ${e.frame}")
        val luaFrames = luajFrames.filter { it.startsWith("$chunk:") }
        if (luaFrames.isEmpty()) frames.add("$chunk:$line: in main chunk") else frames.addAll(luaFrames)
        frames.add("[G]: ?")
        return "$chunk:$line: ${gopherMessage(msg)}\nstack traceback:\n\t" + frames.joinToString("\n\t")
    }

    companion object {
        const val INITIAL_POOL_SIZE = 5
        const val MAX_POOL_SIZE = 1000
        private const val LRU_SIZE = 256

        /** LuaJ's token number for the end of the stream. */
        private const val TK_EOS = 286
        private val STARTED_AT = System.nanoTime()

        private fun fn(body: (Varargs) -> Varargs): VarArgFunction =
            object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs = body(args)
            }

        /** A Lua string holding the bytes of a byte string. */
        fun bytes(s: String): LuaString = LuaString.valueOf(s.toByteArray(Charsets.ISO_8859_1))

        /** The bytes of a Lua string as a byte string. */
        fun luaToByteString(v: LuaValue): String {
            val s = v.checkstring()
            val b = ByteArray(s.rawlen())
            s.copyInto(0, b, 0, b.size)
            return String(b, Charsets.ISO_8859_1)
        }

        private fun LuaValue.tojstringBytes(): ByteArray =
            if (isstring()) {
                ByteArray(checkstring().rawlen()).also {
                    checkstring().copyInto(0, it, 0, it.size)
                }
            } else {
                tojstring().toByteArray()
            }

        fun sha1Hex(s: String): String {
            val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.ISO_8859_1))
            return d.joinToString("") { "%02x".format(it) }
        }

        /** Go makeSafeErr: RESP errors cannot hold newlines. */
        fun makeSafeErr(s: String): String = s.replace("\n", "\\n")

        /** Rewrites LuaJ's runtime error wording into gopher-lua's. */
        fun gopherMessage(msg: String): String {
            Regex("""^attempt to compare __(?:lt|le) on (\w+) and (\w+)$""").matchEntire(msg)?.let {
                return "attempt to compare ${it.groupValues[1]} with ${it.groupValues[2]}"
            }
            // LuaJ names the operands of this form in reverse order
            Regex("""^attempt to compare (\w+) with (\w+)(?: expected, got \w+)?$""").matchEntire(msg)?.let {
                return "attempt to compare ${it.groupValues[2]} with ${it.groupValues[1]}"
            }
            Regex("""^attempt to perform arithmetic __(\w+) on (\w+) and (\w+)$""").matchEntire(msg)?.let {
                return "cannot perform ${it.groupValues[1]} operation between ${it.groupValues[2]} and ${it.groupValues[3]}"
            }
            if (msg.startsWith("attempt to perform arithmetic on ")) return "__unm undefined"
            Regex("""^attempt to concatenate (\w+) and (\w+)$""").matchEntire(msg)?.let {
                return "cannot perform concat operation between ${it.groupValues[1]} and ${it.groupValues[2]}"
            }
            if (msg.startsWith("attempt to get length of ")) return "__len undefined"
            if (msg.startsWith("attempt to call ")) return "attempt to call a non-function object"
            return msg
        }

        /** Tile38's tonumber(): Go ParseFloat when the text has a '.', otherwise ParseInt. */
        private fun baseToNumber(args: Varargs): Varargs {
            val noBase = args.isnil(2)
            var base = if (noBase) 10 else args.checkint(2)
            val v = args.checkvalue(1)
            if (v.type() == LuaValue.TNUMBER) return v
            if (v.type() != LuaValue.TSTRING) return LuaValue.NIL
            var str = luaToByteString(v).trim(' ', '\n', '\t')
            if (str.contains('.')) {
                val f = GoCompat.parseFloat(str) ?: return LuaValue.NIL
                return LuaValue.valueOf(f)
            }
            if (noBase && str.lowercase().startsWith("0x")) {
                base = 16
                str = str.substring(2)
            }
            val n = goParseInt(str, base) ?: return LuaValue.NIL
            return LuaValue.valueOf(n.toDouble())
        }

        /** Go strconv.ParseInt(s, base, 64) for bases 2 to 36 (0 picks the base from the prefix). */
        private fun goParseInt(
            s0: String,
            base0: Int,
        ): Long? {
            var s = s0
            var neg = false
            if (s.startsWith("+") || s.startsWith("-")) {
                neg = s[0] == '-'
                s = s.substring(1)
            }
            var base = base0
            if (base == 0) {
                base = 10
                val l = s.lowercase()
                when {
                    l.startsWith("0x") -> {
                        base = 16
                        s = s.substring(2)
                    }

                    l.startsWith("0b") -> {
                        base = 2
                        s = s.substring(2)
                    }

                    l.startsWith("0o") -> {
                        base = 8
                        s = s.substring(2)
                    }

                    l.startsWith("0") && l.length > 1 -> {
                        base = 8
                        s = s.substring(1)
                    }
                }
                s = s.replace("_", "")
            }
            if (base < 2 || base > 36 || s.isEmpty() || s[0] == '+' || s[0] == '-') return null
            return try {
                val v = java.math.BigInteger(s, base)
                (if (neg) v.negate() else v).longValueExact()
            } catch (_: NumberFormatException) {
                null
            } catch (_: ArithmeticException) {
                null
            }
        }

        /** Tile38's tostring(): gopher-lua's LValue.String(). */
        private fun baseToString(args: Varargs): Varargs {
            val v = args.checkvalue(1)
            val mt = v.getmetatable()
            if (mt != null && !mt.rawget("__tostring").isnil()) return mt.rawget("__tostring").call(v)
            return when (v.type()) {
                LuaValue.TNUMBER -> bytes(goNumberString(v.todouble()))
                LuaValue.TSTRING -> v
                LuaValue.TNIL -> LuaValue.valueOf("nil")
                LuaValue.TBOOLEAN -> LuaValue.valueOf(if (v.toboolean()) "true" else "false")
                else -> LuaValue.valueOf(v.typename() + ": 0x" + Integer.toHexString(System.identityHashCode(v)).padStart(10, '0'))
            }
        }

        private fun tableMaxN(t: LuaTable): LuaValue {
            var max = 0.0
            var k = LuaValue.NIL
            while (true) {
                val n = t.next(k)
                k = n.arg1()
                if (k.isnil()) break
                if (k.type() == LuaValue.TNUMBER && k.todouble() > max) max = k.todouble()
            }
            return LuaValue.valueOf(max)
        }

        /** gopher-lua LNumber.String(): integers as int64, other values like Go's %v. */
        fun goNumberString(d: Double): String {
            if (!d.isNaN() && !d.isInfinite() && d == floor(d) && abs(d) < 9.223372036854776E18) return d.toLong().toString()
            return goFormatG(d)
        }

        /** Go strconv.FormatFloat(d, 'g', -1, 64). */
        fun goFormatG(d: Double): String {
            if (d.isNaN()) return "NaN"
            if (d.isInfinite()) return if (d > 0) "+Inf" else "-Inf"
            if (d == 0.0) return if (1 / d < 0) "-0" else "0"
            val bd = BigDecimal(java.lang.Double.toString(abs(d))).stripTrailingZeros()
            val digits = bd.unscaledValue().toString()
            val exp = digits.length - 1 - bd.scale()
            val sign = if (d < 0) "-" else ""
            if (exp < -4 || exp >= 6) {
                val mant = if (digits.length > 1) digits[0] + "." + digits.substring(1) else digits
                val e = abs(exp).toString().padStart(2, '0')
                return sign + mant + "e" + (if (exp < 0) "-" else "+") + e
            }
            return sign + bd.toPlainString()
        }

        /** Go encoding/json float64 formatting. */
        private fun goJsonFloat(f: Double): String {
            require(!f.isNaN() && !f.isInfinite()) { "json: unsupported value: ${goFormatG(f)}" }
            val a = abs(f)
            if (a != 0.0 && (a < 1e-6 || a >= 1e21)) {
                val bd = BigDecimal(java.lang.Double.toString(a)).stripTrailingZeros()
                val digits = bd.unscaledValue().toString()
                val exp = digits.length - 1 - bd.scale()
                val mant = if (digits.length > 1) digits[0] + "." + digits.substring(1) else digits
                val e = abs(exp).toString().padStart(2, '0').let { if (it.length == 2 && it[0] == '0' && exp < 0) it.substring(1) else it }
                return (if (f < 0) "-" else "") + mant + "e" + (if (exp < 0) "-" else "+") + e
            }
            return GoCompat.formatFloat(f)
        }

        /** gopher-json Encode. Throws IllegalArgumentException with gopher-json's messages. */
        private fun jsonEncode(
            v: LuaValue,
            sb: StringBuilder,
            visited: MutableSet<LuaTable>,
        ) {
            when (v.type()) {
                LuaValue.TBOOLEAN -> {
                    sb.append(if (v.toboolean()) "true" else "false")
                }

                LuaValue.TNUMBER -> {
                    sb.append(goJsonFloat(v.todouble()))
                }

                LuaValue.TNIL -> {
                    sb.append("null")
                }

                LuaValue.TSTRING -> {
                    sb.append(GoCompat.jsonMarshalString(luaToByteString(v)))
                }

                LuaValue.TTABLE -> {
                    val t = v.checktable()
                    require(visited.add(t)) { "cannot encode recursively nested tables to JSON" }
                    val first = t.next(LuaValue.NIL)
                    val key = first.arg1()
                    when (key.type()) {
                        LuaValue.TNIL -> {
                            sb.append("[]")
                        }

                        LuaValue.TNUMBER -> {
                            sb.append('[')
                            var expected = 1.0
                            var k: LuaValue = key
                            var value = first.arg(2)
                            while (!k.isnil()) {
                                require(k.type() == LuaValue.TNUMBER) { "cannot encode mixed or invalid key types" }
                                require(k.todouble() == expected) { "cannot encode sparse array" }
                                if (expected > 1) sb.append(',')
                                jsonEncode(value, sb, visited)
                                expected++
                                val n = t.next(k)
                                k = n.arg1()
                                value = n.arg(2)
                            }
                            sb.append(']')
                        }

                        LuaValue.TSTRING -> {
                            val members = java.util.TreeMap<String, LuaValue>()
                            var k: LuaValue = key
                            var value = first.arg(2)
                            while (!k.isnil()) {
                                require(k.type() == LuaValue.TSTRING) { "cannot encode mixed or invalid key types" }
                                members[luaToByteString(k)] = value
                                val n = t.next(k)
                                k = n.arg1()
                                value = n.arg(2)
                            }
                            sb.append('{')
                            var i = 0
                            for ((name, mv) in members) {
                                if (i++ > 0) sb.append(',')
                                sb.append(GoCompat.jsonMarshalString(name)).append(':')
                                jsonEncode(mv, sb, visited)
                            }
                            sb.append('}')
                        }

                        else -> {
                            throw IllegalArgumentException("cannot encode mixed or invalid key types")
                        }
                    }
                }

                else -> {
                    throw IllegalArgumentException("cannot encode ${v.typename()} to JSON")
                }
            }
        }

        /** gopher-json Decode; null when [s] is not valid JSON. */
        fun jsonDecode(s: String): LuaValue? {
            if (!Gjson.valid(s)) return null
            return decodeValue(Gjson.parse(s))
        }

        private fun decodeValue(r: JsonResult): LuaValue =
            when (r.type) {
                JsonType.TRUE -> {
                    LuaValue.TRUE
                }

                JsonType.FALSE -> {
                    LuaValue.FALSE
                }

                JsonType.NUMBER -> {
                    LuaValue.valueOf(r.num)
                }

                JsonType.STRING -> {
                    bytes(r.str)
                }

                JsonType.NULL -> {
                    LuaValue.NIL
                }

                JsonType.JSON -> {
                    val t = LuaTable()
                    if (r.isArray()) {
                        // nil elements are skipped, like gopher-lua's LTable.Append
                        var n = 0
                        r.forEach { _, v ->
                            val lv = decodeValue(v)
                            if (!lv.isnil()) t.rawset(++n, lv)
                            true
                        }
                    } else {
                        r.forEach { k, v ->
                            t.rawset(bytes(k.str), decodeValue(v))
                            true
                        }
                    }
                    t
                }
            }
    }
}
