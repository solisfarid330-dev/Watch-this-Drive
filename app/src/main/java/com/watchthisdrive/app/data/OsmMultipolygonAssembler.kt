package com.watchthisdrive.app.data

/**
 * Reassembles OSM multipolygon member ways without relying on their input order
 * or direction. This is deliberately pure Kotlin so it can be unit-tested and
 * reused by the Android data layer without a GIS runtime.
 */
data class OsmWaySegment(
    val wayId: Long,
    val role: String,
    val points: List<GeoPoint>
)

data class CoursePolygon(
    val outer: List<GeoPoint>,
    val holes: List<List<GeoPoint>>
)

data class MultipolygonResult(
    val polygons: List<CoursePolygon>,
    val errors: List<String>
)

object OsmMultipolygonAssembler {
    private data class RingResult(val rings: List<List<GeoPoint>>, val errors: List<String>)

    fun assemble(segments: List<OsmWaySegment>): MultipolygonResult {
        val outer = assembleRole(segments, "outer")
        val inner = assembleRole(segments, "inner")
        val errors = outer.errors + inner.errors
        if (outer.rings.isEmpty()) {
            return MultipolygonResult(emptyList(), errors + "Multipolygon has no complete outer ring")
        }

        val holesByOuter = outer.rings.associateWith { mutableListOf<List<GeoPoint>>() }
        for (hole in inner.rings) {
            val sample = hole.firstOrNull()
            val owner = sample?.let { point ->
                outer.rings.filter { pointInRing(point, it) }.minByOrNull { abs(signedArea(it)) }
            }
            if (owner == null) {
                errors.plus("Inner ring is not contained by any outer ring")
            } else {
                holesByOuter.getValue(owner).add(hole)
            }
        }
        return MultipolygonResult(
            outer.rings.map { CoursePolygon(it, holesByOuter.getValue(it).toList()) },
            errors
        )
    }

    private fun assembleRole(segments: List<OsmWaySegment>, role: String): RingResult {
        val pending = segments.filter { it.role == role }.mapNotNull { segment ->
            if (segment.points.size < 2) null else segment.points.toMutableList()
        }.toMutableList()
        val rings = mutableListOf<List<GeoPoint>>()
        val errors = mutableListOf<String>()

        while (pending.isNotEmpty()) {
            val ring = pending.removeAt(0)
            var guard = 0
            while (ring.first() != ring.last() && guard++ <= segments.size + 1) {
                val start = ring.first()
                val end = ring.last()
                val index = pending.indexOfFirst { it.first() == end || it.last() == end ||
                    it.first() == start || it.last() == start }
                if (index < 0) break
                val next = pending.removeAt(index)
                when {
                    next.first() == end -> ring.addAll(next.drop(1))
                    next.last() == end -> ring.addAll(next.asReversed().drop(1))
                    next.last() == start -> ring.addAll(0, next.dropLast(1))
                    next.first() == start -> ring.addAll(0, next.asReversed().dropLast(1))
                }
            }
            if (ring.size >= 4 && ring.first() == ring.last()) {
                rings.add(normalizeWinding(ring, role == "outer"))
            } else {
                errors.add("Could not close $role ring from member segments")
            }
        }
        return RingResult(rings, errors)
    }

    private fun normalizeWinding(ring: List<GeoPoint>, counterClockwise: Boolean): List<GeoPoint> {
        val isCounterClockwise = signedArea(ring) > 0.0
        return if (isCounterClockwise == counterClockwise) ring else ring.asReversed()
    }

    private fun signedArea(ring: List<GeoPoint>): Double {
        var area = 0.0
        for (i in 0 until ring.lastIndex) {
            area += ring[i].longitude * ring[i + 1].latitude -
                ring[i + 1].longitude * ring[i].latitude
        }
        return area / 2.0
    }

    private fun pointInRing(point: GeoPoint, ring: List<GeoPoint>): Boolean {
        var inside = false
        for (i in 0 until ring.lastIndex) {
            val a = ring[i]
            val b = ring[i + 1]
            if ((a.latitude > point.latitude) != (b.latitude > point.latitude)) {
                val crossing = (b.longitude - a.longitude) *
                    (point.latitude - a.latitude) / (b.latitude - a.latitude) + a.longitude
                if (point.longitude < crossing) inside = !inside
            }
        }
        return inside
    }

    private fun abs(value: Double) = kotlin.math.abs(value)
}
