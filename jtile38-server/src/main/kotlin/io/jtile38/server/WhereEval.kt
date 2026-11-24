// Port of the whereeval parts of tile38 internal/server/token.go and scanner.go,
// Copyright Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.server

import io.jtile38.json.Gjson
import org.luaj.vm2.LuaClosure
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue

/** A WHEREEVAL or WHEREEVALSHA filter: a Lua script run for every candidate object (Go whereevalT). */
internal class WhereEval(
    private val lua: LuaScripts,
    private val state: LuaState,
    private val fn: LuaClosure,
    private val chunk: String,
) {
    private var closed = false

    /** Returns the interpreter to the pool (Go whereevalT.Close). */
    fun close() {
        if (closed) return
        closed = true
        state.setRaw("ARGV", LuaValue.NIL)
        lua.put(state)
    }

    /**
     * Runs the script for an object with the globals ID, FIELDS and PROPERTIES set. Lua errors other
     * than indexing a non-table fail the whole command.
     */
    fun match(
        fields: Map<String, FieldValue>,
        id: String,
        props: String,
    ): Boolean {
        val fieldsTbl = LuaTable()
        for ((name, v) in fields) {
            val lv =
                when (v.kind) {
                    FieldKind.NULL -> LuaValue.NIL
                    FieldKind.FALSE -> LuaValue.FALSE
                    FieldKind.TRUE -> LuaValue.TRUE
                    FieldKind.NUMBER -> LuaValue.valueOf(v.num)
                    else -> LuaScripts.bytes(v.data)
                }
            fieldsTbl.rawset(LuaScripts.bytes(name), lv)
        }
        val propsTbl = (if (props != "") LuaScripts.jsonDecode(props) else null) ?: LuaTable()
        state.setRaw("ID", LuaScripts.bytes(id))
        state.setRaw("FIELDS", fieldsTbl)
        state.setRaw("PROPERTIES", propsTbl)
        try {
            // state.line is not reset: LuaJ only reports a line when it differs from the last one
            val ret =
                try {
                    fn.call()
                } catch (e: LuaError) {
                    val msg = e.message ?: ""
                    if (msg.contains("attempt to index")) return false
                    throw CommandException(lua.runtimeError(state, e, chunk))
                } catch (e: StackOverflowError) {
                    throw CommandException(lua.runtimeError(state, LuaError("stack overflow"), chunk))
                }
            // make a bool out of the returned value
            return when (ret.type()) {
                LuaValue.TNIL -> {
                    false
                }

                LuaValue.TBOOLEAN -> {
                    ret.toboolean()
                }

                LuaValue.TNUMBER -> {
                    ret.todouble() != 0.0
                }

                LuaValue.TSTRING -> {
                    ret.checkstring().rawlen() != 0
                }

                LuaValue.TTABLE -> {
                    val t = ret.checktable()
                    t.length() != 0 || !t.next(LuaValue.NIL).arg1().isnil()
                }

                else -> {
                    throw CommandException("script returned value of type ${ret.typename()}")
                }
            }
        } finally {
            state.setRaw("ID", LuaValue.NIL)
            state.setRaw("FIELDS", LuaValue.NIL)
            state.setRaw("PROPERTIES", LuaValue.NIL)
        }
    }

    companion object {
        /**
         * Parses `WHEREEVAL script numargs arg...` or `WHEREEVALSHA sha numargs arg...` (the keyword
         * already consumed). [next] returns the next argument or null. The interpreter is added to
         * [out] as soon as it is taken, so the caller can release it even when parsing fails.
         */
        fun parse(
            lua: LuaScripts,
            isSha: Boolean,
            next: () -> String?,
            out: MutableList<WhereEval>,
        ) {
            val script = next()
            if (script == null || script == "") throw Errors.invalidNumberOfArguments()
            val nargsStr = next()
            if (nargsStr == null || nargsStr == "") throw Errors.invalidNumberOfArguments()
            val nargs = GoCompat.parseUint(nargsStr) ?: throw Errors.invalidArgument(nargsStr)
            val state = lua.get()
            var owned = true
            try {
                val argv = LuaTable()
                var i = 0L
                while (i < nargs) {
                    val arg = next()
                    if (arg == null || arg == "") throw Errors.invalidNumberOfArguments()
                    argv.rawset(argv.length() + 1, LuaScripts.bytes(arg))
                    i++
                }
                val sha = if (isSha) script else LuaScripts.sha1Hex(script)
                state.setRaw("ARGV", argv)
                val compiled = lua.getScript(sha)
                val proto =
                    when {
                        compiled != null -> compiled
                        isSha -> throw CommandException("sha not found")
                        else -> lua.compile(state, script, sha).also { lua.putScriptLru(sha, it) }
                    }
                out.add(WhereEval(lua, state, state.closure(proto), "f_$sha"))
                owned = false
            } finally {
                if (owned) {
                    state.setRaw("ARGV", LuaValue.NIL)
                    lua.put(state)
                }
            }
        }

        /** The FIELDS of an object as seen by WHEREEVAL scripts: z for spatial objects, then the fields. */
        fun fieldsOf(o: Item): Pair<Map<String, FieldValue>, String> {
            val names = LinkedHashMap<String, FieldValue>()
            var props = ""
            if (o.isSpatial()) {
                names["z"] = FieldValue.of(GoCompat.formatFloat(extractZCoordinate(o.geo)))
                props = Gjson.get(o.geo.members(), "properties").raw
            }
            o.fields.forEach { names[it.name] = it.value }
            return names to props
        }
    }
}
