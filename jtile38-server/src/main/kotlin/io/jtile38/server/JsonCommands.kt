// Port of tile38 internal/server/json.go (JGET, JSET, JDEL), Copyright Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.server

import io.jtile38.json.Gjson
import io.jtile38.json.JsonResult
import io.jtile38.json.Sjson
import io.jtile38.json.SjsonException
import io.jtile38.resp.RespValue

/** Returns true if [data] can be encoded as a JSON number value (Go isJSONNumber). */
internal fun isJsonNumber(data: String): Boolean {
    if (data == "") return false
    var i = 0
    // sign
    if (data[i] == '-') i++
    if (i == data.length) return false
    // int
    if (data[i] == '0') {
        i++
    } else {
        while (i < data.length && data[i] in '0'..'9') i++
    }
    // frac
    if (i == data.length) return true
    if (data[i] == '.') {
        i++
        if (i == data.length) return false
        if (data[i] !in '0'..'9') return false
        i++
        while (i < data.length && data[i] in '0'..'9') i++
    }
    // exp
    if (i == data.length) return true
    if (data[i] == 'e' || data[i] == 'E') {
        i++
        if (i == data.length) return false
        if (data[i] == '+' || data[i] == '-') i++
        if (i == data.length) return false
        if (data[i] !in '0'..'9') return false
        i++
        while (i < data.length && data[i] in '0'..'9') i++
    }
    return i == data.length
}

/** JGET key id [path [RAW]] */
internal fun Server.cmdJget(msg: Message): RespValue {
    val start = System.nanoTime()
    val args = msg.args
    if (args.size < 3 || args.size > 5) throw Errors.invalidNumberOfArguments()
    val key = args[1]
    val id = args[2]
    var doget = false
    var path = ""
    var raw = false
    if (args.size > 3) {
        doget = true
        path = args[3]
        if (args.size == 5) {
            if (args[4].lowercase() == "raw") raw = true else throw Errors.invalidArgument(args[4])
        }
    }
    val col = cols[key]
    if (col == null) {
        if (msg.outputType == OutputType.RESP) return RespValue.NULL
        throw Errors.keyNotFound()
    }
    val o = col.get(id)
    if (o == null) {
        if (msg.outputType == OutputType.RESP) return RespValue.NULL
        throw Errors.idNotFound()
    }
    val res: JsonResult = if (doget) Gjson.get(o.toString(), path) else Gjson.parse(o.toString())
    val v = if (raw) res.raw else res.string()
    if (msg.outputType == OutputType.JSON) {
        val buf = StringBuilder("{\"ok\":true")
        if (res.exists()) buf.append(",\"value\":").append(GoCompat.jsonString(v))
        buf.append(",\"elapsed\":\"").append(elapsed(start)).append("\"}")
        return RespValue.string(buf.toString())
    }
    if (!res.exists()) return RespValue.NULL
    return RespValue.string(v)
}

/**
 * Stores the edited JSON of [id]: a spatial object is replaced through `SET key id OBJECT json`
 * (so it is validated as GeoJSON), anything else is stored as a string object without an
 * expiration, keeping its fields.
 */
private fun Server.storeJson(
    msg: Message,
    key: String,
    id: String,
    json: String,
    geoobj: Boolean,
    fields: FieldList,
    col0: Collection?,
    start: Long,
    resp: RespValue,
): WriteResult {
    if (geoobj) {
        // SET key id OBJECT json
        val nmsg = Message(listOf("SET", key, id, "OBJECT", json), msg.connType, msg.outputType)
        return cmdSet(nmsg)
    }
    val col = col0 ?: Collection().also { cols[key] = it }
    val obj = Item(id, StringObject(json), 0, fields)
    val old = col.set(obj)
    // like Tile38, the details carry no command name for string objects
    val d = CommandDetails("", key, true, obj = obj, old = old)
    return WriteResult(if (msg.outputType == OutputType.JSON) okJson(start) else resp, d)
}

/** JSET key id path value [RAW|STR] */
internal fun Server.cmdJset(msg: Message): WriteResult {
    val start = System.nanoTime()
    val args = msg.args
    var raw = false
    var str = false
    when (args.size) {
        5 -> {}

        6 -> {
            when (args[5].lowercase()) {
                "raw" -> raw = true
                "str" -> str = true
                else -> throw Errors.invalidArgument(args[5])
            }
        }

        else -> {
            throw Errors.invalidNumberOfArguments()
        }
    }
    val key = args[1]
    val id = args[2]
    val path = args[3]
    val v = args[4]
    if (!str && !raw) {
        raw =
            when (v) {
                "true", "false", "null" -> true
                else -> isJsonNumber(v)
            }
    }
    val col = cols[key]
    var json = ""
    var geoobj = false
    var fields = FieldList.EMPTY
    val o = col?.get(id)
    if (o != null) {
        geoobj = o.isSpatial()
        json = o.toString()
        fields = o.fields
    }
    json =
        try {
            if (raw) Sjson.setRaw(json, path, v) else Sjson.set(json, path, v)
        } catch (e: SjsonException) {
            throw CommandException(e.message ?: "invalid path")
        }
    return storeJson(msg, key, id, json, geoobj, fields, col, start, RespValue.OK)
}

/**
 * JDEL key id path
 *
 * Unlike Tile38, which runs JDEL as a read command (so the change is neither written to the AOF
 * nor sent to hooks and is lost on restart), jtile38 treats JDEL as a write.
 */
internal fun Server.cmdJdel(msg: Message): WriteResult {
    val start = System.nanoTime()
    val args = msg.args
    if (args.size != 4) throw Errors.invalidNumberOfArguments()
    val key = args[1]
    val id = args[2]
    val path = args[3]
    val col = cols[key]
    if (col == null) {
        if (msg.outputType == OutputType.RESP) return WriteResult(RespValue.integer(0), CommandDetails())
        throw Errors.keyNotFound()
    }
    var json = ""
    var geoobj = false
    var fields = FieldList.EMPTY
    val o = col.get(id)
    if (o != null) {
        geoobj = o.isSpatial()
        json = o.toString()
        fields = o.fields
    }
    val njson =
        try {
            Sjson.delete(json, path)
        } catch (e: SjsonException) {
            throw CommandException(e.message ?: "invalid path")
        }
    if (njson == json) {
        if (msg.outputType == OutputType.JSON) throw Errors.pathNotFound()
        return WriteResult(RespValue.integer(0), CommandDetails())
    }
    return storeJson(msg, key, id, njson, geoobj, fields, col, start, RespValue.integer(1))
}
