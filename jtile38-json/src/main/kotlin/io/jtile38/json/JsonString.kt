// Port of gjson.AppendJSONString (MIT License, Copyright (c) 2016 Josh Baker)
package io.jtile38.json

/**
 * JSON string encoding compatible with gjson.AppendJSONString (HTML escaping enabled). The
 * input is a byte string: bytes above 127 are decoded as UTF-8, invalid sequences become
 * `�` and U+2028/U+2029 are escaped.
 */
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
                    // read utf8 character
                    val d = JsonUtf8.decodeRune(s, i)
                    val r = JsonUtf8.runeOf(d)
                    val size = JsonUtf8.sizeOf(d)
                    if (r == JsonUtf8.RUNE_ERROR && size == 1) {
                        sb.append("\\ufffd")
                    } else if (r == 0x2028 || r == 0x2029) {
                        sb.append("\\u202").append(HEX[r and 0xF])
                    } else {
                        sb.append(s, i, i + size)
                    }
                    i += size - 1
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
