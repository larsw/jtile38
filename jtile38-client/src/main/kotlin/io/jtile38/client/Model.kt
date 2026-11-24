// Copyright jtile38 authors, MIT License.

package io.jtile38.client

/** A geographic point. [z] is an optional elevation or other third coordinate. */
data class Point
    @JvmOverloads
    constructor(
        val lat: Double,
        val lon: Double,
        val z: Double? = null,
    )

/** A bounding box from south-west ([minLat], [minLon]) to north-east ([maxLat], [maxLon]). */
data class Bounds(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
)

/** An object returned by GET. [json] is the GeoJSON (or the raw value for STRING objects). */
data class GetResult(
    val json: String,
    val fields: Map<String, String>,
)

/**
 * One hit of a search. Depending on the requested output, one of [json], [point], [bounds]
 * or [hash] is set. [fields] holds raw field values; [distance] is in meters when requested.
 */
data class SearchHit(
    val id: String,
    val json: String? = null,
    val point: Point? = null,
    val bounds: Bounds? = null,
    val hash: String? = null,
    val fields: Map<String, String> = emptyMap(),
    val distance: Double? = null,
)

/** A page of search results. [cursor] is 0 when there are no more results. */
data class SearchPage(
    val cursor: Long,
    val hits: List<SearchHit>,
)

/** Per-collection statistics from STATS. */
data class CollectionStats(
    val inMemorySize: Long,
    val numObjects: Long,
    val numPoints: Long,
    val numStrings: Long,
)

/** How [Tile38Client.Commands.jset] stores its value. */
enum class JsonValueMode {
    /** Numbers, `true`, `false` and `null` as JSON values, anything else as a string. */
    AUTO,

    /** The value is raw JSON. */
    RAW,

    /** The value is always a string. */
    STR,
}
