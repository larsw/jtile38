// A5
// SPDX-License-Identifier: Apache-2.0
// Copyright (c) A5 contributors
//
// Kotlin port of github.com/a5geo/a5-go (serialization.go, compact.go).
// Port Copyright jtile38 authors, Apache License 2.0.

package io.jtile38.a5

/** An error returned by the A5 functions (a5-go returns these as Go errors). */
class A5Exception(
    message: String,
) : Exception(message)

const val FIRST_HILBERT_RESOLUTION = 2
const val MAX_RESOLUTION = 30

/** 64 - 6 bits for origin & segment. */
const val HILBERT_START_BIT = 58

/** First 6 bits 0, remaining 58 bits 1. */
const val REMOVAL_MASK = 0x03ffffffffffffffL

/** First 6 bits 1, remaining 58 bits 0. */
const val ORIGIN_SEGMENT_MASK = -0x400000000000000L // 0xfc00000000000000

/** The abstract cell containing the whole world (resolution -1). */
const val WORLD_CELL = 0L

/**
 * The resolution of a cell ID (an unsigned 64-bit value held in a Long); -1 for [WORLD_CELL].
 * Invalid IDs can give other negative values.
 */
fun getResolution(index: Long): Int {
    if (index == 0L) return -1
    val shifted = index ushr 1
    if (shifted == 0L) return -1
    // The resolution marker is the least significant 1-bit in shifted.
    // For non-Hilbert (res 0,1): resolution = 56 - tz
    // For Hilbert (res >= 2): resolution = (58 - tz) / 2
    val tz = java.lang.Long.numberOfTrailingZeros(shifted)
    if (tz >= 55) return 56 - tz
    return (58 - tz) / 2
}

/** Extracts the cell components from a cell ID. */
fun deserialize(index: Long): A5Cell {
    val resolution = getResolution(index)
    if (resolution == -1) return A5Cell(ORIGINS[0], 0, 0, -1)

    val top6Bits = (index ushr HILBERT_START_BIT).toInt()
    val origin: Origin
    val segment: Int
    if (resolution == 0) {
        origin = ORIGINS[top6Bits]
        segment = 0
    } else {
        val originId = top6Bits / 5
        origin = ORIGINS[originId]
        segment = (top6Bits + origin.firstQuintant) % 5
    }

    if (resolution < FIRST_HILBERT_RESOLUTION) return A5Cell(origin, segment, 0, resolution)

    // Extract Hilbert S value.
    val hilbertLevels = resolution - FIRST_HILBERT_RESOLUTION + 1
    val hilbertBits = 2L * hilbertLevels
    val shift = HILBERT_START_BIT - hilbertBits
    val s = goShr(index and REMOVAL_MASK, shift)
    return A5Cell(origin, segment, s, resolution)
}

/** Encodes cell components into a cell ID. */
fun serialize(cell: A5Cell): Long {
    if (cell.resolution > MAX_RESOLUTION) throw A5Exception("resolution exceeds maximum")
    if (cell.resolution == -1) return WORLD_CELL

    // Position of resolution marker (a Go uint: a negative value stands for a huge one).
    val r: Long =
        if (cell.resolution < FIRST_HILBERT_RESOLUTION) {
            (cell.resolution + 1).toLong()
        } else {
            val hilbertResolution = 1 + cell.resolution - FIRST_HILBERT_RESOLUTION
            (2 * hilbertResolution + 1).toLong()
        }

    // Top 6 bits: origin ID and segment.
    val segmentN = (cell.segment - cell.origin.firstQuintant + 5) % 5

    var index =
        if (cell.resolution == 0) {
            cell.origin.id.toLong() shl HILBERT_START_BIT
        } else {
            (5L * cell.origin.id + segmentN) shl HILBERT_START_BIT
        }

    if (cell.resolution >= FIRST_HILBERT_RESOLUTION) {
        val hilbertLevels = cell.resolution - FIRST_HILBERT_RESOLUTION + 1
        val hilbertBits = 2L * hilbertLevels
        if (java.lang.Long.compareUnsigned(cell.s, goShl(1L, hilbertBits)) >= 0) {
            throw A5Exception("S value too large for resolution")
        }
        val shift = HILBERT_START_BIT - hilbertBits
        index += goShl(cell.s, shift)
    }

    // Resolution marker.
    index = index or goShl(1L, HILBERT_START_BIT - r)
    return index
}

/** Expands a cell to its children at [childResolution0] (-1 means the next resolution). */
fun cellToChildren(
    index: Long,
    childResolution0: Int,
): List<Long> {
    val cell = deserialize(index)
    var childResolution = childResolution0
    if (childResolution < 0) childResolution = cell.resolution + 1

    if (childResolution < cell.resolution) throw A5Exception("target resolution must be >= current resolution")
    if (childResolution > MAX_RESOLUTION) throw A5Exception("target resolution exceeds maximum")
    if (childResolution == cell.resolution) return listOf(index)

    var origins = listOf(cell.origin)
    var segments = listOf(cell.segment)
    if (cell.resolution == -1) origins = ORIGINS
    if ((cell.resolution == -1 && childResolution > 0) || cell.resolution == 0) segments = listOf(0, 1, 2, 3, 4)

    var baseRes = cell.resolution
    if (baseRes < FIRST_HILBERT_RESOLUTION - 1) baseRes = FIRST_HILBERT_RESOLUTION - 1
    val resDiff = childResolution - baseRes

    var childrenCount = 1L
    for (i in 0 until resDiff) childrenCount *= 4
    val shiftedS = goShl(cell.s, 2L * resDiff)

    val result = ArrayList<Long>()
    for (o in origins) {
        for (seg in segments) {
            for (i in 0 until childrenCount) {
                val newS = shiftedS + i
                result.add(serialize(A5Cell(o, seg, newS, childResolution)))
            }
        }
    }
    return result
}

/** The parent cell at [parentResolution0] (below -1 means the previous resolution). */
fun cellToParent(
    index: Long,
    parentResolution0: Int,
): Long {
    val cell = deserialize(index)
    var parentResolution = parentResolution0
    if (parentResolution < -1) parentResolution = cell.resolution - 1

    if (parentResolution == -1) return WORLD_CELL
    if (parentResolution > cell.resolution) throw A5Exception("target resolution must be <= current resolution")
    if (parentResolution == cell.resolution) return index

    val resDiff = cell.resolution - parentResolution
    val shiftedS = goShr(cell.s, 2L * resDiff)
    return serialize(A5Cell(cell.origin, cell.segment, shiftedS, parentResolution))
}

/** The 12 resolution-0 cells. */
fun getRes0Cells(): List<Long> = cellToChildren(WORLD_CELL, 0)

/** Checks whether a cell is the first child of its parent. */
fun isFirstChild(
    index: Long,
    resolution0: Int,
): Boolean {
    var resolution = resolution0
    if (resolution < 0) resolution = getResolution(index)
    if (resolution < 2) {
        val top6Bits = (index ushr HILBERT_START_BIT).toInt()
        val childCount = if (resolution == 1) 5 else 12
        return top6Bits % childCount == 0
    }
    val sPosition = 2L * (MAX_RESOLUTION - resolution)
    val sMask = goShl(3L, sPosition)
    return (index and sMask) == 0L
}

/** The difference between two neighbouring sibling cells. */
fun getStride(resolution: Int): Long {
    if (resolution < 2) return 1L shl HILBERT_START_BIT
    val sPosition = 2L * (MAX_RESOLUTION - resolution)
    return goShl(1L, sPosition)
}

// ---- compact.go ----

/** The number of children when expanding from [parentResolution] to [childResolution]. */
fun getNumChildren(
    parentResolution: Int,
    childResolution: Int,
): Long {
    if (childResolution < parentResolution) return 0
    if (childResolution == parentResolution) return 1
    if (parentResolution >= FIRST_HILBERT_RESOLUTION) {
        var result = 1L
        for (i in 0 until childResolution - parentResolution) result *= 4
        return result
    }
    var parentCount = getNumCells(parentResolution)
    if (parentCount == 0L) parentCount = 1
    return getNumCells(childResolution) / parentCount
}

/** The total number of cells at a resolution. */
fun getNumCells(resolution: Int): Long =
    when {
        resolution < 0 -> {
            1
        }

        resolution == 0 -> {
            12
        }

        resolution == 1 -> {
            60
        }

        else -> {
            var result = 60L
            for (i in FIRST_HILBERT_RESOLUTION..resolution) result *= 4
            result
        }
    }

/** Expands cells to a target resolution. */
fun uncompact(
    cells: List<Long>,
    targetResolution: Int,
): List<Long> {
    val resolutions = IntArray(cells.size)
    for ((i, cell) in cells.withIndex()) {
        val res = getResolution(cell)
        if (targetResolution < res) throw A5Exception("cannot uncompact to lower resolution")
        resolutions[i] = res
    }
    val result = ArrayList<Long>()
    for ((i, cell) in cells.withIndex()) {
        val n = getNumChildren(resolutions[i], targetResolution)
        if (n == 1L) result.add(cell) else result.addAll(cellToChildren(cell, targetResolution))
    }
    return result
}

/** Merges complete sibling groups into parent cells. */
fun compact(cells: List<Long>): List<Long> {
    if (cells.isEmpty()) return emptyList()

    // Dedup and sort (unsigned).
    var current = LinkedHashSet(cells).sortedWith { a, b -> java.lang.Long.compareUnsigned(a, b) }

    var changed = true
    while (changed) {
        changed = false
        val result = ArrayList<Long>(current.size)
        var i = 0
        while (i < current.size) {
            val cell = current[i]
            val resolution = getResolution(cell)
            if (resolution < 0) {
                result.add(cell)
                i++
                continue
            }
            var expectedChildren = 4
            if (resolution < FIRST_HILBERT_RESOLUTION) expectedChildren = if (resolution == 0) 12 else 5

            if (i + expectedChildren <= current.size && isFirstChild(cell, resolution)) {
                val stride = getStride(resolution)
                var allPresent = true
                for (j in 1 until expectedChildren) {
                    val expected = cell + j.toLong() * stride
                    if (current[i + j] != expected) {
                        allPresent = false
                        break
                    }
                }
                if (allPresent) {
                    val parent =
                        try {
                            cellToParent(cell, resolution - 1)
                        } catch (_: A5Exception) {
                            null
                        }
                    if (parent != null) {
                        result.add(parent)
                        i += expectedChildren
                        changed = true
                        continue
                    }
                }
            }
            result.add(cell)
            i++
        }
        current = result
    }
    return current
}
