// Port of tile38 internal/glob (pattern matching derived from Go's path.Match,
// Copyright 2010 The Go Authors, BSD-style license) and Tile38's range limits.
// Copyright jtile38 authors, MIT License.

package io.jtile38.server

/** Glob pattern helpers used by KEYS, SCAN MATCH, PDEL, etc. */
class Glob private constructor(
    val pattern: String,
    val desc: Boolean,
    val limits: Array<String>,
    val isGlob: Boolean,
) {
    companion object {
        class BadPattern : Exception("syntax error in pattern")

        /** Returns true when [str] matches [pattern]; throws [BadPattern] for malformed patterns. */
        fun match(
            pattern: String,
            str: String,
        ): Boolean = wildcardMatch(pattern, str)

        /** Same as [match] but returns false for malformed patterns. */
        fun matches(
            pattern: String,
            str: String,
        ): Boolean =
            try {
                match(pattern, str)
            } catch (_: BadPattern) {
                false
            }

        fun isGlob(pattern: String): Boolean {
            for (c in pattern) {
                if (c == '[' || c == '*' || c == '?') {
                    return try {
                        match(pattern, "whatever")
                        true
                    } catch (_: BadPattern) {
                        false
                    }
                }
            }
            return false
        }

        /** Parses [pattern] into a key range that bounds all possible matches. */
        fun parse(
            pattern: String,
            desc: Boolean,
        ): Glob {
            if (pattern.startsWith("*")) return Glob(pattern, desc, arrayOf("", ""), true)
            if (pattern.isEmpty()) return Glob(pattern, desc, arrayOf("", ""), false)
            var n = 0
            var isGlob = false
            for (c in pattern) {
                if (c == '[' || c == '*' || c == '?') {
                    try {
                        match(pattern, "whatever")
                        isGlob = true
                    } catch (_: BadPattern) {
                    }
                    break
                }
                n++
            }
            if (n == 0) return Glob(pattern, desc, arrayOf(pattern, pattern), false)
            val a: String
            val b: String
            if (desc) {
                var aa = pattern.substring(0, n)
                var bb = aa
                if (bb[n - 1].code == 0x00) {
                    while (bb.isNotEmpty() && bb[bb.length - 1].code == 0x00) {
                        bb =
                            if (bb.length > 1) {
                                if (bb[bb.length - 2].code == 0x00) {
                                    bb.substring(0, bb.length - 1)
                                } else {
                                    bb.substring(0, bb.length - 2) + (bb[bb.length - 2].code - 1).toChar() + 0xFF.toChar()
                                }
                            } else {
                                ""
                            }
                    }
                } else {
                    bb = bb.substring(0, n - 1) + (bb[n - 1].code - 1).toChar()
                }
                aa =
                    if (aa[n - 1].code == 0xFF) {
                        aa + 0x00.toChar()
                    } else {
                        aa.substring(0, n - 1) + (aa[n - 1].code + 1).toChar()
                    }
                a = aa
                b = bb
            } else {
                a = pattern.substring(0, n)
                b = if (a[n - 1].code == 0xFF) a + 0x00.toChar() else a.substring(0, n - 1) + (a[n - 1].code + 1).toChar()
            }
            return Glob(pattern, desc, arrayOf(a, b), isGlob)
        }

        private fun wildcardMatch(
            patternIn: String,
            nameIn: String,
        ): Boolean {
            var pattern = patternIn
            var name = nameIn
            outer@ while (pattern.isNotEmpty()) {
                val (star, chunk, rest) = scanChunk(pattern)
                pattern = rest
                if (star && chunk.isEmpty()) return true
                val r = matchChunk(chunk, name)
                if (r.ok && (r.rest.isEmpty() || pattern.isNotEmpty())) {
                    name = r.rest
                    continue
                }
                if (r.err) throw BadPattern()
                if (star) {
                    for (i in name.indices) {
                        val r2 = matchChunk(chunk, name.substring(i + 1))
                        if (r2.ok) {
                            if (pattern.isEmpty() && r2.rest.isNotEmpty()) continue
                            name = r2.rest
                            continue@outer
                        }
                        if (r2.err) throw BadPattern()
                    }
                }
                return false
            }
            return name.isEmpty()
        }

        private fun scanChunk(patternIn: String): Triple<Boolean, String, String> {
            var pattern = patternIn
            var star = false
            while (pattern.isNotEmpty() && pattern[0] == '*') {
                pattern = pattern.substring(1)
                star = true
            }
            var inrange = false
            var i = 0
            scan@ while (i < pattern.length) {
                when (pattern[i]) {
                    '\\' -> if (i + 1 < pattern.length) i++
                    '[' -> inrange = true
                    ']' -> inrange = false
                    '*' -> if (!inrange) break@scan
                }
                i++
            }
            return Triple(star, pattern.substring(0, i), pattern.substring(i))
        }

        private class ChunkResult(
            val rest: String,
            val ok: Boolean,
            val err: Boolean,
        )

        private val NO = ChunkResult("", ok = false, err = false)
        private val ERR = ChunkResult("", ok = false, err = true)

        private fun matchChunk(
            chunkIn: String,
            sIn: String,
        ): ChunkResult {
            var chunk = chunkIn
            var s = sIn
            while (chunk.isNotEmpty()) {
                if (s.isEmpty()) return NO
                when (chunk[0]) {
                    '[' -> {
                        val (r, n) = Utf8.decodeRune(s, 0)
                        s = s.substring(n)
                        chunk = chunk.substring(1)
                        if (chunk.isEmpty()) return ERR
                        val negated = chunk[0] == '^'
                        if (negated) chunk = chunk.substring(1)
                        var match = false
                        var nrange = 0
                        while (true) {
                            if (chunk.isNotEmpty() && chunk[0] == ']' && nrange > 0) {
                                chunk = chunk.substring(1)
                                break
                            }
                            val lo = getEsc(chunk) ?: return ERR
                            chunk = lo.second
                            var hi = lo.first
                            if (chunk[0] == '-') {
                                val h = getEsc(chunk.substring(1)) ?: return ERR
                                hi = h.first
                                chunk = h.second
                            }
                            if (lo.first <= r && r <= hi) match = true
                            nrange++
                        }
                        if (match == negated) return NO
                    }

                    '?' -> {
                        val (_, n) = Utf8.decodeRune(s, 0)
                        s = s.substring(n)
                        chunk = chunk.substring(1)
                    }

                    else -> {
                        if (chunk[0] == '\\') {
                            chunk = chunk.substring(1)
                            if (chunk.isEmpty()) return ERR
                        }
                        if (chunk[0] != s[0]) return NO
                        s = s.substring(1)
                        chunk = chunk.substring(1)
                    }
                }
            }
            return ChunkResult(s, ok = true, err = false)
        }

        /** Returns (rune, remaining chunk) or null on a bad pattern. */
        private fun getEsc(chunkIn: String): Pair<Int, String>? {
            var chunk = chunkIn
            if (chunk.isEmpty() || chunk[0] == '-' || chunk[0] == ']') return null
            if (chunk[0] == '\\') {
                chunk = chunk.substring(1)
                if (chunk.isEmpty()) return null
            }
            val (r, n) = Utf8.decodeRune(chunk, 0)
            if (r == Utf8.RUNE_ERROR && n == 1) return null
            val nchunk = chunk.substring(n)
            if (nchunk.isEmpty()) return null
            return r to nchunk
        }
    }
}
