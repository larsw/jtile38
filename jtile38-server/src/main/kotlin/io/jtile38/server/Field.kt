// Port of tile38 internal/field, Copyright Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.server

import io.jtile38.json.Gjson
import io.jtile38.json.JsonString
import io.jtile38.json.JsonType
import io.jtile38.json.Pretty
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Kind of a field value. The order matters for comparisons: Null < False < Number < String < True < JSON. */
enum class FieldKind { NULL, FALSE, NUMBER, STRING, TRUE, JSON }

/** A field value: a number, string, boolean, null or raw JSON. */
class FieldValue(
    val kind: FieldKind,
    val data: String,
    val num: Double,
) {
    /** True for the zero value (number 0) or the empty value; zero fields are deleted. */
    fun isZero(): Boolean = (kind == FieldKind.NUMBER && data == "0" && num == 0.0) || isEmpty()

    private fun isEmpty() = kind == FieldKind.NULL && data == "" && num == 0.0

    fun lessCase(
        b: FieldValue,
        caseSensitive: Boolean,
    ): Boolean {
        if (kind < b.kind) return true
        if (kind > b.kind) return false
        if (kind == FieldKind.NUMBER) return num < b.num
        if (kind == FieldKind.STRING) return if (caseSensitive) data < b.data else stringLessInsensitive(data, b.data)
        return data < b.data
    }

    fun less(b: FieldValue): Boolean = lessCase(b, false)

    fun equalsValue(b: FieldValue): Boolean = !less(b) && !b.less(this)

    /** JSON representation. */
    fun json(): String =
        when (kind) {
            FieldKind.NUMBER -> {
                when (data) {
                    "NaN" -> "\"NaN\""
                    "+Inf" -> "\"+Inf\""
                    "-Inf" -> "\"-Inf\""
                    else -> data
                }
            }

            FieldKind.STRING -> {
                JsonString.quote(data)
            }

            FieldKind.TRUE -> {
                "true"
            }

            FieldKind.FALSE -> {
                "false"
            }

            FieldKind.NULL -> {
                if (!isEmpty()) "null" else "0"
            }

            FieldKind.JSON -> {
                data
            }
        }

    override fun toString(): String = data

    companion object {
        @JvmField
        val ZERO = FieldValue(FieldKind.NUMBER, "0", 0.0)

        @JvmField
        val EMPTY = FieldValue(FieldKind.NULL, "", 0.0)

        private fun stringLessInsensitive(
            a: String,
            b: String,
        ): Boolean {
            var i = 0
            while (i < a.length && i < b.length) {
                var ca = a[i].code
                var cb = b[i].code
                if (ca in 'A'.code..'Z'.code) ca += 32
                if (cb in 'A'.code..'Z'.code) cb += 32
                if (ca < cb) return true
                if (ca > cb) return false
                i++
            }
            return a.length < b.length
        }

        private fun isAsciiSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000b' || c == '\u000c'

        /** Go `strings.TrimSpace` for byte strings (ASCII whitespace only). */
        internal fun trimSpace(s: String): String = s.trim { isAsciiSpace(it) }

        /** Port of `field.ValueOf`. */
        @JvmStatic
        fun of(input: String): FieldValue {
            var data = trimSpace(input)
            val num = GoCompat.parseFloat(data)
            if (num != null) {
                if (num == Double.POSITIVE_INFINITY) return FieldValue(FieldKind.NUMBER, "+Inf", num)
                if (num == Double.NEGATIVE_INFINITY) return FieldValue(FieldKind.NUMBER, "-Inf", num)
                if (num.isNaN()) return FieldValue(FieldKind.NUMBER, "NaN", num)
                if (Gjson.valid(data)) return FieldValue(FieldKind.NUMBER, data, num)
            } else if (Gjson.valid(data)) {
                val r = Gjson.parse(data)
                when (r.type) {
                    JsonType.NULL -> {
                        return FieldValue(FieldKind.NULL, "null", 0.0)
                    }

                    JsonType.JSON -> {
                        return FieldValue(FieldKind.JSON, Pretty.ugly(data), 0.0)
                    }

                    JsonType.TRUE -> {
                        return FieldValue(FieldKind.TRUE, "true", 0.0)
                    }

                    JsonType.FALSE -> {
                        return FieldValue(FieldKind.FALSE, "false", 0.0)
                    }

                    else -> {}
                }
                data = r.string()
            }
            if (data.length in 3..9) {
                when (data[0]) {
                    '-', '+', 'I', 'i', 'N', 'n' -> {
                        when (data.lowercase()) {
                            "nan" -> {
                                return FieldValue(FieldKind.NUMBER, "NaN", Double.NaN)
                            }

                            "inf", "+inf", "infinity", "+infinity" -> {
                                return FieldValue(FieldKind.NUMBER, "+Inf", Double.POSITIVE_INFINITY)
                            }

                            "-inf", "-infinity" -> {
                                return FieldValue(FieldKind.NUMBER, "-Inf", Double.NEGATIVE_INFINITY)
                            }
                        }
                    }
                }
            }
            return FieldValue(FieldKind.STRING, data, 0.0)
        }
    }
}

/** A named field. */
class Field(
    val name: String,
    val value: FieldValue,
) {
    companion object {
        @JvmField
        val ZERO = Field("", FieldValue.ZERO)

        fun make(
            name: String,
            data: String,
        ) = Field(FieldValue.trimSpace(name), FieldValue.of(data))
    }
}

/** An immutable list of fields sorted by name. */
class FieldList private constructor(
    private val entries: Array<Field>,
) {
    private fun bsearch(name: String): Pair<Int, Boolean> {
        var i = 0
        var j = entries.size
        while (i < j) {
            val h = i + (j - i) / 2
            if (name >= entries[h].name) i = h + 1 else j = h
        }
        if (i > 0 && entries[i - 1].name >= name) return (i - 1) to true
        return i to false
    }

    /** Returns a list with [field] set; zero values delete the field. */
    fun set(field: Field): FieldList {
        val (index, found) = bsearch(field.name)
        if (found) {
            return when {
                field.value.isZero() -> {
                    FieldList(entries.filterIndexed { i, _ -> i != index }.toTypedArray())
                }

                !entries[index].value.equalsValue(field.value) -> {
                    val copy = entries.copyOf()
                    copy[index] = Field(entries[index].name, field.value)
                    FieldList(copy)
                }

                else -> {
                    this
                }
            }
        }
        if (field.value.isZero()) return this
        SharedNames.store(field.name)
        val copy = ArrayList<Field>(entries.size + 1)
        copy.addAll(entries)
        copy.add(index, field)
        return FieldList(copy.toTypedArray())
    }

    fun get(name: String): Field {
        val (index, found) = bsearch(name)
        return if (found) entries[index] else Field.ZERO
    }

    fun forEach(iter: (Field) -> Unit) = entries.forEach(iter)

    fun size(): Int = entries.size

    /**
     * In-memory weight as reported by STATS and SERVER. Mirrors the size of Tile38's packed binary
     * field list: per entry a uvarint shared-name index, a kind byte and, for numbers, strings and
     * JSON, a uvarint length plus the data; the list is prefixed by a uvarint of its total size.
     */
    fun weight(): Int {
        if (entries.isEmpty()) return 0
        var size = 0
        for (f in entries) {
            size += uvarintLen(SharedNames.store(f.name).toLong()) + 1
            if (f.value.kind == FieldKind.NUMBER || f.value.kind == FieldKind.STRING || f.value.kind == FieldKind.JSON) {
                size += uvarintLen(
                    f.value.data.length
                        .toLong(),
                ) + f.value.data.length
            }
        }
        return size + uvarintLen(size.toLong())
    }

    companion object {
        @JvmField
        val EMPTY = FieldList(emptyArray())
    }
}

private fun uvarintLen(v: Long): Int {
    var n = 1
    var x = v ushr 7
    while (x != 0L) {
        n++
        x = x ushr 7
    }
    return n
}

/** Process-wide table of field names (Tile38 `internal/sstring`). */
internal object SharedNames {
    private val nums = ConcurrentHashMap<String, Int>()
    private val count = AtomicInteger()

    fun store(name: String): Int = nums.computeIfAbsent(name) { count.getAndIncrement() }
}
