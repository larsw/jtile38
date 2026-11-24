package io.jtile38.json

import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.fail

/**
 * Compares the gjson and sjson ports against results recorded from the Go libraries (see
 * src/test/go/gjson-gen). Every field must match byte for byte, including Index and Indexes.
 */
class GjsonDiffTest {
    private fun unhex(s: String): String {
        require(s.endsWith('.')) { "bad field $s" }
        val sb = StringBuilder((s.length - 1) / 2)
        var i = 0
        while (i < s.length - 1) {
            sb.append(Integer.parseInt(s, i, i + 2, 16).toChar())
            i += 2
        }
        return sb.toString()
    }

    private fun ints(a: IntArray?): String = if (a == null) "nil" else a.joinToString(",", "[", "]")

    private fun result(r: JsonResult): String =
        listOf(
            r.type.ordinal.toString(),
            r.raw,
            r.str,
            java.lang.Long.toHexString(r.num.toRawBits()),
            r.index.toString(),
            ints(r.indexes),
        ).joinToString("\t")

    private fun expected(f: List<String>): String = listOf(f[0], unhex(f[1]), unhex(f[2]), f[3], f[4], f[5]).joinToString("\t")

    private fun lines(): List<List<String>> {
        val input = javaClass.getResourceAsStream("/gjson-sjson-cases.txt.gz") ?: error("missing cases")
        return GZIPInputStream(input).bufferedReader(Charsets.ISO_8859_1).readLines().map { it.split('\t') }
    }

    @Test
    fun matchesGo() {
        val failures = ArrayList<String>()
        var n = 0
        for (f in lines()) {
            n++
            val desc: String
            val want: String
            val got: String =
                try {
                    when (f[0]) {
                        "parse" -> {
                            desc = "parse ${unhex(f[1])}"
                            want = expected(f.subList(2, 8))
                            result(Gjson.parse(unhex(f[1])))
                        }

                        "valid" -> {
                            desc = "valid ${unhex(f[1])}"
                            want = f[2]
                            Gjson.valid(unhex(f[1])).toString()
                        }

                        "get" -> {
                            desc = "get ${unhex(f[2])} in ${unhex(f[1]).take(80)}"
                            want = expected(f.subList(3, 9))
                            result(Gjson.get(unhex(f[1]), unhex(f[2])))
                        }

                        else -> {
                            val json = unhex(f[1])
                            val path = unhex(f[2])
                            val value = unhex(f[3])
                            desc = "${f[0]} $path=$value in $json"
                            want = unhex(f[4]) + " err=" + unhex(f[5])
                            try {
                                when (f[0]) {
                                    "set" -> Sjson.set(json, path, value)
                                    "setraw" -> Sjson.setRaw(json, path, value)
                                    else -> Sjson.delete(json, path)
                                } + " err="
                            } catch (e: SjsonException) {
                                // Go returns the original input on most errors, "" on some
                                (if (f[4] == ".") "" else json) + " err=" + e.message
                            }
                        }
                    }
                } catch (e: Exception) {
                    failures.add("${f[0]} ${f.drop(1).take(2).map { unhex(it).take(80) }}: threw $e")
                    continue
                }
            if (got != want) failures.add("$desc\n   want: $want\n    got: $got")
        }
        if (failures.isNotEmpty()) {
            fail("${failures.size} of $n cases differ from Go:\n" + failures.take(40).joinToString("\n"))
        }
    }
}
