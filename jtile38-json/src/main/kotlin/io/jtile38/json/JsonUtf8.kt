// Helpers mirroring Go's unicode/utf8 package over byte strings
package io.jtile38.json

/**
 * UTF-8 helpers for byte strings (ISO-8859-1, one char per byte), mirroring the parts of Go's
 * unicode/utf8 package that gjson uses.
 */
internal object JsonUtf8 {
    const val RUNE_ERROR = 0xFFFD

    /** Appends the UTF-8 encoding of [r] as one char per byte (utf8.EncodeRune). */
    fun appendRune(
        sb: StringBuilder,
        r0: Int,
    ) {
        var r = r0
        if (r < 0 || r > 0x10FFFF || r in 0xD800..0xDFFF) r = RUNE_ERROR
        when {
            r < 0x80 -> {
                sb.append(r.toChar())
            }

            r < 0x800 -> {
                sb.append((0xC0 or (r shr 6)).toChar())
                sb.append((0x80 or (r and 0x3F)).toChar())
            }

            r < 0x10000 -> {
                sb.append((0xE0 or (r shr 12)).toChar())
                sb.append((0x80 or ((r shr 6) and 0x3F)).toChar())
                sb.append((0x80 or (r and 0x3F)).toChar())
            }

            else -> {
                sb.append((0xF0 or (r shr 18)).toChar())
                sb.append((0x80 or ((r shr 12) and 0x3F)).toChar())
                sb.append((0x80 or ((r shr 6) and 0x3F)).toChar())
                sb.append((0x80 or (r and 0x3F)).toChar())
            }
        }
    }

    /**
     * Decodes the rune starting at [i] (utf8.DecodeRuneInString). Returns the rune shifted left
     * by 3 bits or'ed with its byte size (1 to 4); invalid encodings decode to RUNE_ERROR with
     * size 1.
     */
    fun decodeRune(
        s: String,
        i: Int,
    ): Long {
        val n = s.length - i
        if (n < 1) return pack(RUNE_ERROR, 0)
        val b0 = s[i].code and 0xFF
        if (b0 < 0x80) return pack(b0, 1)

        fun cont(k: Int): Int = if (i + k < s.length) s[i + k].code and 0xFF else -1
        when {
            b0 in 0xC2..0xDF -> {
                val b1 = cont(1)
                if (b1 !in 0x80..0xBF) return pack(RUNE_ERROR, 1)
                return pack(((b0 and 0x1F) shl 6) or (b1 and 0x3F), 2)
            }

            b0 in 0xE0..0xEF -> {
                val b1 = cont(1)
                val lo = if (b0 == 0xE0) 0xA0 else 0x80
                val hi = if (b0 == 0xED) 0x9F else 0xBF
                if (b1 !in lo..hi) return pack(RUNE_ERROR, 1)
                val b2 = cont(2)
                if (b2 !in 0x80..0xBF) return pack(RUNE_ERROR, 1)
                return pack(((b0 and 0x0F) shl 12) or ((b1 and 0x3F) shl 6) or (b2 and 0x3F), 3)
            }

            b0 in 0xF0..0xF4 -> {
                val b1 = cont(1)
                val lo = if (b0 == 0xF0) 0x90 else 0x80
                val hi = if (b0 == 0xF4) 0x8F else 0xBF
                if (b1 !in lo..hi) return pack(RUNE_ERROR, 1)
                val b2 = cont(2)
                if (b2 !in 0x80..0xBF) return pack(RUNE_ERROR, 1)
                val b3 = cont(3)
                if (b3 !in 0x80..0xBF) return pack(RUNE_ERROR, 1)
                return pack(((b0 and 0x07) shl 18) or ((b1 and 0x3F) shl 12) or ((b2 and 0x3F) shl 6) or (b3 and 0x3F), 4)
            }

            else -> {
                return pack(RUNE_ERROR, 1)
            }
        }
    }

    private fun pack(
        r: Int,
        size: Int,
    ): Long = (r.toLong() shl 3) or size.toLong()

    fun runeOf(packed: Long): Int = (packed shr 3).toInt()

    fun sizeOf(packed: Long): Int = (packed and 7L).toInt()
}
