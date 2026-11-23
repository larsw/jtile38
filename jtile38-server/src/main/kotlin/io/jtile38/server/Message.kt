// Port of tile38 internal/server message types, Copyright Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.server

/** How a message arrived. */
enum class ConnType { RESP, NATIVE, HTTP, WEBSOCKET }

/** How a response is serialized. */
enum class OutputType { RESP, JSON }

/** A client command. Arguments are byte strings. */
class Message(
    var args: List<String>,
    val connType: ConnType,
    var outputType: OutputType,
) {
    var auth: String = ""

    val command: String by lazy { args[0].lowercase() }
}

/** An error returned to the client (Go `error` values from command handlers). */
open class CommandException(
    message: String,
) : Exception(message, null, false, false)

object Errors {
    fun invalidNumberOfArguments() = CommandException("invalid number of arguments")

    fun keyNotFound() = CommandException("key not found")

    fun idNotFound() = CommandException("id not found")

    fun idAlreadyExists() = CommandException("id already exists")

    fun pathNotFound() = CommandException("path not found")

    fun invalidArgument(arg: String) = CommandException("invalid argument '$arg'")

    fun duplicateArgument(arg: String) = CommandException("duplicate argument '$arg'")

    const val INVALID_NUMBER_OF_ARGUMENTS = "invalid number of arguments"
}

/** Details about a write, used for the AOF, geofence hooks and live fences (Go `commandDetails`). */
class CommandDetails(
    val command: String = "",
    val key: String = "",
    val updated: Boolean = false,
    /** Target object (the deleted object for DEL). */
    val obj: Item? = null,
    /** Previous object, if any. */
    val old: Item? = null,
    /** New key for RENAME. */
    val newKey: String = "",
    val timestamp: java.time.ZonedDateTime = java.time.ZonedDateTime.now(),
    /** When true, only [children] are forwarded to hooks and fences (e.g. PDEL). */
    val parent: Boolean = false,
    val pattern: String = "",
    val children: List<CommandDetails> = emptyList(),
)
