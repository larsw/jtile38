// Port of parts of github.com/tidwall/pretty (MIT License, Copyright (c) 2017 Josh Baker)
package io.jtile38.json

/** JSON formatting helpers. */
object Pretty {
    /** Removes insignificant whitespace from [json] (pretty.Ugly). */
    fun ugly(json: String): String {
        val dst = StringBuilder(json.length)
        var i = 0
        val n = json.length
        while (i < n) {
            val c = json[i]
            if (c > ' ') {
                dst.append(c)
                if (c == '"') {
                    i++
                    while (i < n) {
                        val d = json[i]
                        dst.append(d)
                        if (d == '"') {
                            var j = i - 1
                            while (json[j] == '\\') j--
                            if ((j - i) % 2 != 0) break
                        }
                        i++
                    }
                }
            }
            i++
        }
        return dst.toString()
    }
}
