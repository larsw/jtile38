package io.jtile38.a5

import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.fail

/**
 * Compares the A5 port against results recorded from a5-go (see src/test/go/a5-gen). Every
 * float is compared bit for bit.
 */
class A5DiffTest {
    private fun bits(d: Double): String = java.lang.Long.toHexString(d.toRawBits())

    private fun hex(id: Long): String = java.lang.Long.toUnsignedString(id, 16)

    private fun unbits(s: String): Double = Double.fromBits(java.lang.Long.parseUnsignedLong(s, 16))

    private fun lines(): List<List<String>> {
        val input = javaClass.getResourceAsStream("/a5-cases.txt.gz") ?: error("missing cases")
        return GZIPInputStream(input).bufferedReader().readLines().map { it.split('\t') }
    }

    private fun constant(name: String): Double =
        when (name) {
            "PI_OVER_180" -> PI_OVER_180
            "180_OVER_PI" -> D180_OVER_PI
            "TWO_PI_OVER_5" -> TWO_PI_OVER_5
            "PI_OVER_5" -> PI_OVER_5
            "PI_OVER_10" -> PI_OVER_10
            "PHI" -> PHI
            "DIHEDRAL_ANGLE" -> DIHEDRAL_ANGLE
            "INTERHEDRAL_ANGLE" -> INTERHEDRAL_ANGLE
            "FACE_EDGE_ANGLE" -> FACE_EDGE_ANGLE
            "R_CIRCUMSCRIBED" -> R_CIRCUMSCRIBED
            else -> error("unknown constant $name")
        }

    @Test
    fun matchesGo() {
        val failures = ArrayList<String>()
        var n = 0
        for (f in lines()) {
            n++
            val want: String
            val got: String =
                try {
                    when (f[0]) {
                        "const" -> {
                            want = f[2]
                            bits(constant(f[1]))
                        }

                        "cell" -> {
                            want = f[4]
                            hex(lonLatToCell(doubleArrayOf(unbits(f[1]), unbits(f[2])), f[3].toInt()))
                        }

                        "center" -> {
                            val id = java.lang.Long.parseUnsignedLong(f[1], 16)
                            want = f.subList(2, 5).joinToString("\t")
                            val ll = cellToLonLat(id)
                            "${getResolution(id)}\t${bits(ll[0])}\t${bits(ll[1])}"
                        }

                        "verts" -> {
                            val id = java.lang.Long.parseUnsignedLong(f[1], 16)
                            want = f.getOrElse(2) { "" }
                            cellVertices(id).joinToString(";") { c ->
                                val v = toLonLat(toSpherical(c))
                                listOf(c[0], c[1], c[2], v[0], v[1]).joinToString(",") { bits(it) }
                            }
                        }

                        "parent" -> {
                            val id = java.lang.Long.parseUnsignedLong(f[1], 16)
                            want = f[2] + "\t" + f[3]
                            try {
                                hex(cellToParent(id, getResolution(id) - 1)) + "\tfalse"
                            } catch (_: A5Exception) {
                                "0\ttrue"
                            }
                        }

                        "children" -> {
                            val id = java.lang.Long.parseUnsignedLong(f[1], 16)
                            want = f.getOrElse(2) { "" }
                            cellToChildren(id, getResolution(id) + 1).joinToString(",") { hex(it) }
                        }

                        "compact" -> {
                            val id = java.lang.Long.parseUnsignedLong(f[1], 16)
                            want = f.getOrElse(2) { "" }
                            compact(cellToChildren(id, getResolution(id) + 1)).joinToString(",") { hex(it) }
                        }

                        "panics" -> {
                            val id = java.lang.Long.parseUnsignedLong(f[1], 16)
                            want = "throws"
                            try {
                                cellToLonLat(id)
                                "returned"
                            } catch (_: RuntimeException) {
                                "throws"
                            }
                        }

                        else -> {
                            error("unknown line ${f[0]}")
                        }
                    }
                } catch (e: Exception) {
                    failures.add("${f.take(4)}: threw $e")
                    continue
                }
            if (got != want) failures.add("${f.take(4)}\n   want: $want\n    got: $got")
        }
        if (failures.isNotEmpty()) {
            fail("${failures.size} of $n cases differ from Go:\n" + failures.take(30).joinToString("\n"))
        }
    }
}
