// Port of github.com/mmcloughlin/geohash, Copyright (c) 2015 Michael McLoughlin, MIT License,
// and tile38 internal/bing (Bing Maps tile system helpers).
// Copyright jtile38 authors, MIT License.

package io.jtile38.server

import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/** Geohash encoding/decoding compatible with mmcloughlin/geohash. */
object Geohash {
    private const val ALPHABET = "0123456789bcdefghjkmnpqrstuvwxyz"
    private val decodeTable = IntArray(256) { 0xff }.also { t -> ALPHABET.forEachIndexed { i, c -> t[c.code] = i } }
    private const val EXP232 = 4294967296.0

    class Box(
        val minLat: Double,
        val maxLat: Double,
        val minLng: Double,
        val maxLng: Double,
    ) {
        fun center() = (minLat + maxLat) / 2.0 to (minLng + maxLng) / 2.0

        /** Rounded (lat, lng) at the precision of the box. */
        fun round(): Pair<Double, Double> {
            var x = maxDecimalPower(maxLat - minLat)
            val lat = ceil(minLat / x) * x
            x = maxDecimalPower(maxLng - minLng)
            val lng = ceil(minLng / x) * x
            return lat to lng
        }

        private fun maxDecimalPower(r: Double): Double = 10.0.pow(floor(log10(r)).toInt())
    }

    fun encode(
        lat: Double,
        lng: Double,
        chars: Int,
    ): String {
        val bits = 5 * chars
        val hash = encodeInt(lat, lng) ushr (64 - bits)
        val b = CharArray(12)
        var x = hash
        for (i in 0 until 12) {
            b[11 - i] = ALPHABET[(x and 0x1f).toInt()]
            x = x ushr 5
        }
        return String(b).substring(12 - chars)
    }

    fun boundingBox(hash: String): Box {
        val bits = 5 * hash.length
        var x = 0L
        for (c in hash) x = (x shl 5) or (decodeTable[c.code and 0xff].toLong())
        val full = if (bits >= 64) x else x shl (64 - bits)
        val lat = decodeRange(squash(full), 90.0)
        val lng = decodeRange(squash(full ushr 1), 180.0)
        val latBits = bits / 2
        val lngBits = bits - latBits
        val latErr = Math.scalb(180.0, -latBits)
        val lngErr = Math.scalb(360.0, -lngBits)
        return Box(lat, lat + latErr, lng, lng + lngErr)
    }

    /** Decodes to the rounded (lat, lng) like `geohash.Decode`. */
    fun decode(hash: String): Pair<Double, Double> = boundingBox(hash).round()

    private fun encodeInt(
        lat: Double,
        lng: Double,
    ): Long = interleave(encodeRange(lat, 90.0), encodeRange(lng, 180.0))

    private fun encodeRange(
        x: Double,
        r: Double,
    ): Long {
        val p = (x + r) / (2 * r)
        return (p * EXP232).toLong() and 0xFFFFFFFFL
    }

    private fun decodeRange(
        x: Long,
        r: Double,
    ): Double = 2 * r * (x.toDouble() / EXP232) - r

    private fun spread(x: Long): Long {
        var v = x
        v = (v or (v shl 16)) and 0x0000ffff0000ffffL
        v = (v or (v shl 8)) and 0x00ff00ff00ff00ffL
        v = (v or (v shl 4)) and 0x0f0f0f0f0f0f0f0fL
        v = (v or (v shl 2)) and 0x3333333333333333L
        v = (v or (v shl 1)) and 0x5555555555555555L
        return v
    }

    private fun interleave(
        x: Long,
        y: Long,
    ): Long = spread(x) or (spread(y) shl 1)

    private fun squash(xIn: Long): Long {
        var x = xIn and 0x5555555555555555L
        x = (x or (x ushr 1)) and 0x3333333333333333L
        x = (x or (x ushr 2)) and 0x0f0f0f0f0f0f0f0fL
        x = (x or (x ushr 4)) and 0x00ff00ff00ff00ffL
        x = (x or (x ushr 8)) and 0x0000ffff0000ffffL
        x = (x or (x ushr 16)) and 0x00000000ffffffffL
        return x
    }
}

/** Bing Maps tile system helpers (tile38 internal/bing). */
object Bing {
    const val MIN_LATITUDE = -85.05112878
    const val MAX_LATITUDE = 85.05112878
    const val MIN_LONGITUDE = -180.0
    const val MAX_LONGITUDE = 180.0
    private const val TILE_SIZE = 256L

    private fun clip(
        n: Double,
        min: Double,
        max: Double,
    ) = if (n < min) {
        min
    } else if (n > max) {
        max
    } else {
        n
    }

    private fun mapSize(level: Int): Long = TILE_SIZE shl level

    fun latLongToPixelXY(
        latitude: Double,
        longitude: Double,
        level: Int,
    ): Pair<Long, Long> {
        val lat = clip(latitude, MIN_LATITUDE, MAX_LATITUDE)
        val lon = clip(longitude, MIN_LONGITUDE, MAX_LONGITUDE)
        val x = (lon + 180) / 360
        val sinLat = sin(lat * Math.PI / 180)
        val y = 0.5 - ln((1 + sinLat) / (1 - sinLat)) / (4 * Math.PI)
        val size = mapSize(level).toDouble()
        return clip(x * size + 0.5, 0.0, size - 1).toLong() to clip(y * size + 0.5, 0.0, size - 1).toLong()
    }

    /** Returns (latitude, longitude). */
    fun pixelXYToLatLong(
        pixelX: Long,
        pixelY: Long,
        level: Int,
    ): Pair<Double, Double> {
        val size = mapSize(level).toDouble()
        val x = (clip(pixelX.toDouble(), 0.0, size - 1) / size) - 0.5
        val y = 0.5 - (clip(pixelY.toDouble(), 0.0, size - 1) / size)
        return (90 - 360 * atan(exp(-y * 2 * Math.PI)) / Math.PI) to 360 * x
    }

    /** Returns [minLat, minLon, maxLat, maxLon]. */
    fun tileXYToBounds(
        tileX: Long,
        tileY: Long,
        level: Int,
    ): DoubleArray {
        val size = 1L shl level
        val (maxLat0, minLon0) = pixelXYToLatLong(tileX shl 8, tileY shl 8, level)
        val (minLat0, maxLon0) = pixelXYToLatLong((tileX + 1) shl 8, (tileY + 1) shl 8, level)
        var minLat = minLat0
        var maxLat = maxLat0
        var minLon = minLon0
        var maxLon = maxLon0
        if (size == 0L || tileX % size == 0L) minLon = MIN_LONGITUDE
        if (size == 0L || tileX % size == size - 1) maxLon = MAX_LONGITUDE
        if (tileY <= 0) maxLat = MAX_LATITUDE
        if (tileY >= size - 1) minLat = MIN_LATITUDE
        return doubleArrayOf(minLat, minLon, maxLat, maxLon)
    }

    /** Returns [minLat, minLon, maxLat, maxLon] or null for an invalid quadkey. */
    fun quadKeyToBounds(quadKey: String): DoubleArray? {
        if (quadKey.any { it !in '0'..'3' }) return null
        val level = quadKey.length
        var tileX = 0L
        var tileY = 0L
        for (i in level downTo 1) {
            val mask = 1L shl (i - 1)
            when (quadKey[level - i]) {
                '1' -> {
                    tileX = tileX or mask
                }

                '2' -> {
                    tileY = tileY or mask
                }

                '3' -> {
                    tileX = tileX or mask
                    tileY = tileY or mask
                }
            }
        }
        return tileXYToBounds(tileX, tileY, level)
    }
}
