// Port of tile38 internal/object and internal/collection/string.go, Copyright Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.server

import io.jtile38.geojson.EmptySpatial
import io.jtile38.geojson.GeoObject
import io.jtile38.geojson.Spatial
import io.jtile38.geojson.geometry.Point
import io.jtile38.geojson.geometry.Rect

/** An object stored in a collection (Go `object.Object`). */
class Item(
    val id: String,
    val geo: GeoObject,
    val expires: Long,
    val fields: FieldList,
) {
    fun rect(): Rect = geo.rect()

    fun isSpatial(): Boolean = geo is Spatial

    /** Go `Object.String()`: the raw string for strings, GeoJSON otherwise. */
    override fun toString(): String = geo.toString()

    fun weight(): Int {
        var w = id.length
        w += if (isSpatial()) geo.numPoints() * 16 else geo.toString().length
        w += fields.weight()
        return w
    }
}

/** A non-spatial string value stored with `SET key id STRING value`. */
class StringObject(
    val value: String,
) : GeoObject {
    override fun empty() = true

    override fun valid() = false

    override fun rect() = Rect(Point(0.0, 0.0), Point(0.0, 0.0))

    override fun center() = Point(0.0, 0.0)

    override fun contains(other: GeoObject) = false

    override fun within(other: GeoObject) = false

    override fun intersects(other: GeoObject) = false

    override fun appendJson(sb: StringBuilder) {
        sb.append(GoCompat.jsonMarshalString(value))
    }

    override fun json() = GoCompat.jsonMarshalString(value)

    override fun toString() = value

    override fun distance(other: GeoObject) = 0.0

    override fun numPoints() = 0

    override fun forEach(iter: (GeoObject) -> Boolean) = iter(this)

    override fun spatial(): Spatial = EmptySpatial

    override fun members() = ""
}
