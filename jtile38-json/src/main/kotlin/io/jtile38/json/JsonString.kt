// Port of gjson.AppendJSONString (MIT License, Copyright (c) 2016 Josh Baker)
package io.jtile38.json

/** JSON string encoding compatible with gjson.AppendJSONString (HTML escaping enabled). */
object JsonString {
    private const val HEX = "0123456789abcdef"

    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        append(sb, s)
        return sb.toString()
    }

    fun append(
        sb: StringBuilder,
        s: String,
    ) {
        sb.append('"')
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                c < ' ' -> {
                    sb.append('\\')
                    when (c) {
                        '\b' -> {
                            sb.append('b')
                        }

                        '\u000c' -> {
                            sb.append('f')
                        }

                        '\n' -> {
                            sb.append('n')
                        }

                        '\r' -> {
                            sb.append('r')
                        }

                        '\t' -> {
                            sb.append('t')
                        }

                        else -> {
                            sb.append('u')
                            hex16(sb, c.code)
                        }
                    }
                }

                c == '>' || c == '<' || c == '&' -> {
                    sb.append('\\').append('u')
                    hex16(sb, c.code)
                }

                c == '\\' -> {
                    sb.append('\\').append('\\')
                }

                c == '"' -> {
                    sb.append('\\').append('"')
                }

                c.code > 127 -> {
                    if (Character.isHighSurrogate(c)) {
                        if (i + 1 < n && Character.isLowSurrogate(s[i + 1])) {
                            sb.append(c).append(s[i + 1])
                            i++
                        } else {
                            sb.append("\\ufffd")
                        }
                    } else if (Character.isLowSurrogate(c)) {
                        sb.append("\\ufffd")
                    } else if (c == ' ' || c == ' ') {
                        sb.append("\\u202").append(HEX[c.code and 0xF])
                    } else {
                        sb.append(c)
                    }
                }

                else -> {
                    sb.append(c)
                }
            }
            i++
        }
        sb.append('"')
    }

    private fun hex16(
        sb: StringBuilder,
        x: Int,
    ) {
        sb
            .append(HEX[(x shr 12) and 0xF])
            .append(HEX[(x shr 8) and 0xF])
            .append(HEX[(x shr 4) and 0xF])
            .append(HEX[x and 0xF])
    }
}
