// A5
// SPDX-License-Identifier: Apache-2.0
// Copyright (c) A5 contributors
//
// Kotlin port of github.com/a5geo/a5-go (cell.go).
// Port Copyright jtile38 authors, Apache License 2.0.

package io.jtile38.a5

import io.jtile38.geojson.geo.GoMath
import kotlin.math.pow

private const val N_SAMPLES = 25

/** The ID of the cell containing [lonLat] ([lon, lat] in degrees) at [resolution] (-1 to 30). */
fun lonLatToCell(
    lonLat: DoubleArray,
    resolution: Int,
): Long {
    if (resolution == -1) return WORLD_CELL
    if (resolution < FIRST_HILBERT_RESOLUTION) return serialize(lonLatToEstimate(lonLat, resolution))

    // For Hilbert resolutions, sample nearby points to handle lattice approximation.
    val hilbertResolution = 1 + resolution - FIRST_HILBERT_RESOLUTION
    val samples = arrayOfNulls<DoubleArray>(N_SAMPLES + 1)
    samples[0] = lonLat
    val scale = 50 / 2.0.pow(hilbertResolution.toDouble())
    for (i in 0 until N_SAMPLES) {
        val r = i.toDouble() / N_SAMPLES.toDouble() * scale
        val fi = i.toDouble()
        samples[i + 1] = doubleArrayOf(GoMath.cos(fi) * r + lonLat[0], GoMath.sin(fi) * r + lonLat[1])
    }

    // Precompute the spherical coordinates for the original point once.
    val origSpherical = fromLonLat(lonLat)

    // Deduplicate estimates by serialized cell ID.
    val seenKeys = LongArray(N_SAMPLES + 1)
    var nSeen = 0
    val candidateCells = arrayOfNulls<A5Cell>(N_SAMPLES + 1)
    val candidateKeys = LongArray(N_SAMPLES + 1)
    val candidateDistances = DoubleArray(N_SAMPLES + 1)
    var nCandidates = 0

    for (si in 0 until N_SAMPLES + 1) {
        val est = lonLatToEstimate(samples[si]!!, resolution)
        val key =
            try {
                serialize(est)
            } catch (_: A5Exception) {
                continue
            }
        var dup = false
        for (j in 0 until nSeen) {
            if (seenKeys[j] == key) {
                dup = true
                break
            }
        }
        if (dup) continue
        seenKeys[nSeen++] = key

        val d = a5CellContainsPointSpherical(est, origSpherical)
        if (d > 0) return key
        candidateCells[nCandidates] = est
        candidateKeys[nCandidates] = key
        candidateDistances[nCandidates] = d
        nCandidates++
    }

    // Fallback: return closest candidate.
    if (nCandidates == 0) return serialize(lonLatToEstimate(lonLat, resolution))
    var best = 0
    for (i in 1 until nCandidates) {
        if (candidateDistances[i] > candidateDistances[best]) best = i
    }
    return serialize(candidateCells[best]!!)
}

/** An approximate cell for a LonLat coordinate. */
private fun lonLatToEstimate(
    lonLat: DoubleArray,
    resolution: Int,
): A5Cell {
    val spherical = fromLonLat(lonLat)
    val origin = findNearestOrigin(spherical)
    var dodecPoint = dodecForward(spherical, origin.id)
    val polar = toPolar(dodecPoint)
    val quintant = getQuintantPolar(polar)
    val (segment, orientation) = quintantToSegment(quintant, origin)

    if (resolution < FIRST_HILBERT_RESOLUTION) return A5Cell(origin, segment, 0, resolution)

    // Rotate into right quintant.
    if (quintant != 0) {
        val extraAngle = TWO_PI_OVER_5 * quintant.toDouble()
        val cos = GoMath.cos(-extraAngle)
        val sin = GoMath.sin(-extraAngle)
        val x = dodecPoint[0] * cos - dodecPoint[1] * sin
        val y = dodecPoint[0] * sin + dodecPoint[1] * cos
        dodecPoint = doubleArrayOf(x, y)
    }

    val hilbertResolution = 1 + resolution - FIRST_HILBERT_RESOLUTION
    val s = 2.0.pow(hilbertResolution.toDouble())
    dodecPoint = doubleArrayOf(dodecPoint[0] * s, dodecPoint[1] * s)

    val ij = faceToIJ(dodecPoint)
    val sVal = ijToS(ij, hilbertResolution, orientation)
    return A5Cell(origin, segment, sVal, resolution)
}

/** The pentagon shape of a cell. */
private fun getPentagon(cell: A5Cell): PentagonVertices {
    val (quintant, orientation) = segmentToQuintant(cell.segment, cell.origin)
    if (cell.resolution == FIRST_HILBERT_RESOLUTION - 1) return getQuintantVertices(quintant)
    if (cell.resolution == FIRST_HILBERT_RESOLUTION - 2) return getFaceVertices()
    val hilbertResolution = cell.resolution - FIRST_HILBERT_RESOLUTION + 1
    val anchor = sToAnchor(cell.s, hilbertResolution, orientation)
    return getPentagonVertices(hilbertResolution, quintant, anchor)
}

/**
 * The center of a cell as [lon, lat] in degrees. Like a5-go, invalid cell IDs can fail with a
 * runtime exception.
 */
fun cellToLonLat(cellId: Long): DoubleArray {
    if (cellId == WORLD_CELL) return doubleArrayOf(0.0, 0.0)
    val cell = deserialize(cellId)
    val pent = getPentagon(cell)
    val center = pent.center()
    val spherical = dodecInverse(center, cell.origin.id)
    return toLonLat(spherical)
}

/**
 * The unit-sphere vertex positions (Cartesian [x, y, z]) of a cell: 5 for resolution 0 and
 * resolutions >= 2, 3 for resolution 1, and none for [WORLD_CELL].
 */
fun cellVertices(cellId: Long): List<DoubleArray> {
    if (cellId == WORLD_CELL) return emptyList()
    val cell = deserialize(cellId)
    val pent = getPentagon(cell)
    return (0 until pent.n).map { i -> toCartesian(dodecInverse(pent.verts[i], cell.origin.id)) }
}

/** The 12 dodecahedron face center positions on the unit sphere. */
fun faceCenters(): List<DoubleArray> = ORIGINS.map { toCartesian(it.axis) }

/** Tests if a LonLat point is inside a cell's pentagon (positive inside, negative outside). */
fun a5CellContainsPoint(
    cell: A5Cell,
    point: DoubleArray,
): Double = a5CellContainsPointSpherical(cell, fromLonLat(point))

private fun a5CellContainsPointSpherical(
    cell: A5Cell,
    spherical: DoubleArray,
): Double {
    val pent = getPentagon(cell)
    val projected = dodecForward(spherical, cell.origin.id)
    return pent.containsPoint(projected)
}
