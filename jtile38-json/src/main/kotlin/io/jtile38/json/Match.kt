// Port of github.com/tidwall/match (MIT License, Copyright (c) 2016 Josh Baker)
package io.jtile38.json

/**
 * Simple glob matching: `*` matches any sequence of characters, `?` matches exactly one
 * character, and `\` escapes the next pattern character. Matching works on Unicode code
 * points.
 */
object Match {
    fun match(
        str: String,
        pattern: String,
    ): Boolean = match0(str, pattern, false)

    fun matchNoCase(
        str: String,
        pattern: String,
    ): Boolean = match0(str, pattern, true)

    /** Returns true if [pattern] contains an unescaped `*` or `?`. */
    fun isPattern(pattern: String): Boolean {
        var i = 0
        while (i < pattern.length) {
            when (pattern[i]) {
                '\\' -> i++
                '*', '?' -> return true
            }
            i++
        }
        return false
    }

    private fun match0(
        str: String,
        pattern: String,
        nocase: Boolean,
    ): Boolean {
        if (pattern == "*") return true
        val s = str.codePoints().toArray()
        val p = pattern.codePoints().toArray()
        return matchAt(s, 0, p, 0, nocase)
    }

    private fun lower(c: Int): Int = if (c in 'A'.code..'Z'.code) c + 32 else c

    private fun matchAt(
        s: IntArray,
        si0: Int,
        p: IntArray,
        pi0: Int,
        nocase: Boolean,
    ): Boolean {
        var si = si0
        var pi = pi0
        // iterative backtracking for the last seen star
        var starP = -1
        var starS = -1
        while (true) {
            if (pi < p.size) {
                val pc = p[pi]
                when (pc) {
                    '*'.code -> {
                        while (pi < p.size && p[pi] == '*'.code) pi++
                        if (pi == p.size) return true
                        starP = pi
                        starS = si
                        continue
                    }

                    '?'.code -> {
                        if (si < s.size) {
                            si++
                            pi++
                            continue
                        }
                    }

                    else -> {
                        var c = pc
                        var adv = 1
                        if (c == '\\'.code && pi + 1 < p.size) {
                            c = p[pi + 1]
                            adv = 2
                        }
                        if (si < s.size) {
                            val sc = s[si]
                            val eq = if (nocase) lower(sc) == lower(c) else sc == c
                            if (eq) {
                                si++
                                pi += adv
                                continue
                            }
                        }
                    }
                }
            } else if (si == s.size) {
                return true
            }
            // mismatch: backtrack
            if (starP >= 0 && starS < s.size) {
                starS++
                si = starS
                pi = starP
                continue
            }
            return false
        }
    }
}
