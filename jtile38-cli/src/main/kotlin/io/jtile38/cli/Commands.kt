// Port of tile38 core/commands.go, Copyright Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.cli

import io.jtile38.json.Gjson
import io.jtile38.json.JsonResult

private const val CLEAR = "\u001b[0m"
private const val BRIGHT = "\u001b[1m"
private const val GRAY = "\u001b[90m"
private const val YELLOW = "\u001b[33m"

/** A command from the Tile38 command catalog (`commands.json`). */
class Command(
    val name: String,
    val summary: String,
    val group: String,
    val arguments: List<Argument>,
) {
    override fun toString(): String = name + arguments.joinToString("") { " $it" }

    /** Colored two-line description for the terminal. */
    fun termOutput(indent: String): String {
        val line = toString()
        val line1 =
            if (line.startsWith(name)) {
                BRIGHT + name + CLEAR + GRAY + line.substring(name.length) + CLEAR
            } else {
                BRIGHT + line.replaceFirst(" ", " $CLEAR$GRAY") + CLEAR
            }
        val line2 = YELLOW + "summary: " + CLEAR + summary
        return indent + line1 + "\n" + indent + line2 + "\n"
    }
}

class EnumArg(
    val name: String,
    val arguments: List<Argument>,
) {
    override fun toString(): String = name + arguments.joinToString("") { " $it" }
}

class Argument(
    val command: String,
    val names: List<String>,
    val optional: Boolean,
    val multiple: Boolean,
    val variadic: Boolean,
    val enum: List<String>,
    val enumArgs: List<EnumArg>,
) {
    override fun toString(): String {
        var s = ""
        if (command != "") s += " $command"
        if (enumArgs.isNotEmpty()) {
            s += " " +
                enumArgs.joinToString("|") {
                    val v = it.toString()
                    if (v.contains(" ")) "($v)" else v
                }
        } else if (enum.isNotEmpty()) {
            s += " " + enum.joinToString("|")
        } else {
            val subs = names.joinToString(" ").trim()
            s += " $subs"
            if (variadic) s += if (names.isEmpty()) " [$subs ...]" else " [${names.last()} ...]"
            if (multiple) s += " ..."
        }
        s = s.trim()
        if (optional) s = "[$s]"
        return s
    }
}

/** The command catalog bundled with the CLI. */
object Commands {
    val all: Map<String, Command> by lazy {
        val json =
            Commands::class.java
                .getResourceAsStream("commands.json")!!
                .readAllBytes()
                .toString(Charsets.UTF_8)
        val map = LinkedHashMap<String, Command>()
        Gjson.parse(json).forEach { k, v ->
            val name = k.string().uppercase()
            map[name] = Command(name, v.get("summary").string(), v.get("group").string(), args(v.get("arguments")))
            true
        }
        map
    }

    private fun strings(r: JsonResult): List<String> =
        when {
            r.isArray() -> r.array().map { it.string() }
            r.exists() -> listOf(r.string())
            else -> emptyList()
        }

    private fun args(r: JsonResult): List<Argument> =
        r.array().map { a ->
            Argument(
                a.get("command").string(),
                strings(a.get("name")),
                a.get("optional").bool(),
                a.get("multiple").bool(),
                a.get("variadic").bool(),
                strings(a.get("enum")),
                a.get("enumargs").array().map { EnumArg(it.get("name").string(), args(it.get("arguments"))) },
            )
        }
}
