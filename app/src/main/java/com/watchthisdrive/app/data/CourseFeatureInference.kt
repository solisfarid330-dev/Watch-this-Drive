package com.watchthisdrive.app.data

import kotlin.math.*

/**
 * Conservative, course-agnostic geometry synthesis.
 *
 * A hole centerline gives a usable playing corridor even when OSM has no
 * fairway polygon. The generated corridor is deliberately marked INFERRED
 * and low-confidence; it is a visualization aid, not surveyed turf geometry.
 */
object CourseFeatureInference {
    private const val HALF_WIDTH_M = 18.0
    private const val MIN_LINE_LENGTH_M = 45.0
    private const val SOURCE_COVERAGE_DISTANCE_M = 28.0

    fun addInferredFeatures(course: CourseGeometry): CourseGeometry {
        val source = course.features.filter { it.origin == CourseFeatureOrigin.SOURCE && it.hasUsableGeometry }
        val holes = source.filter {
            it.featureType == "hole" && it.geometryType == CourseGeometryType.LINE && it.points.size >= 2
        }
        val inferred = holes.mapNotNull { hole ->
            if (source.any { it.featureType == "fairway" && isNearLine(it, hole.points, SOURCE_COVERAGE_DISTANCE_M) }) {
                return@mapNotNull null
            }
            buildFairway(hole)
        }
        val retained = course.features.filterNot { it.origin == CourseFeatureOrigin.INFERRED && it.featureType == "fairway" }
        return course.copy(features = retained + inferred)
    }

    private fun buildFairway(hole: CourseFeature): CourseFeature? {
        val line = simplifyConsecutiveDuplicates(hole.points)
        if (line.size < 2) return null
        val origin = line.first()
        val latitude = Math.toRadians(line.map { it.latitude }.average())
        val metersPerLon = 111320.0 * cos(latitude).coerceAtLeast(0.1)
        fun xy(p: GeoPoint) = Pair((p.longitude - origin.longitude) * metersPerLon, (p.latitude - origin.latitude) * 110574.0)
        fun geo(x: Double, y: Double) = GeoPoint(origin.latitude + y / 110574.0, origin.longitude + x / metersPerLon)
        val points = line.map(::xy)
        val length = points.zipWithNext().sumOf { (a, b) -> hypot(b.first - a.first, b.second - a.second) }
        if (length < MIN_LINE_LENGTH_M) return null

        val left = mutableListOf<GeoPoint>()
        val right = mutableListOf<GeoPoint>()
        for (i in points.indices) {
            val before = points[(i - 1).coerceAtLeast(0)]
            val after = points[(i + 1).coerceAtMost(points.lastIndex)]
            val dx = after.first - before.first
            val dy = after.second - before.second
            val magnitude = hypot(dx, dy)
            if (magnitude < 0.01) return null
            val nx = -dy / magnitude
            val ny = dx / magnitude
            left.add(geo(points[i].first + nx * HALF_WIDTH_M, points[i].second + ny * HALF_WIDTH_M))
            right.add(geo(points[i].first - nx * HALF_WIDTH_M, points[i].second - ny * HALF_WIDTH_M))
        }
        val ring = left + right.asReversed()
        val closed = ring + ring.first()
        if (!isSimpleRing(closed)) return null
        return CourseFeature(
            id = -((hole.id % Long.MAX_VALUE).coerceAtLeast(1L)),
            elementType = "generated",
            featureType = "fairway",
            holeNumber = hole.holeNumber,
            name = null,
            points = closed,
            geometryType = CourseGeometryType.POLYGON,
            rings = listOf(closed),
            geometryStatus = "inferred_corridor_from_hole_centerline",
            origin = CourseFeatureOrigin.INFERRED,
            confidence = 0.35
        )
    }

    private fun simplifyConsecutiveDuplicates(points: List<GeoPoint>): List<GeoPoint> =
        points.fold(mutableListOf()) { result, point ->
            if (result.isEmpty() || result.last() != point) result.add(point)
            result
        }

    private fun isNearLine(feature: CourseFeature, line: List<GeoPoint>, limit: Double): Boolean {
        val candidates = when (feature.geometryType) {
            CourseGeometryType.POINT, CourseGeometryType.LINE -> feature.points
            CourseGeometryType.POLYGON -> feature.rings.flatten()
            CourseGeometryType.MULTI_POLYGON -> feature.polygons.flatten().flatten()
            CourseGeometryType.UNKNOWN -> emptyList()
        }
        if (candidates.any { point -> distanceToLine(point, line) <= limit }) return true
        if (feature.geometryType == CourseGeometryType.POLYGON || feature.geometryType == CourseGeometryType.MULTI_POLYGON) {
            val polygons = if (feature.geometryType == CourseGeometryType.POLYGON) listOf(feature.rings) else feature.polygons
            if (line.any { p -> polygons.any { polygon -> pointInRing(p, polygon.firstOrNull().orEmpty()) } }) return true
        }
        return false
    }

    private fun distanceToLine(point: GeoPoint, line: List<GeoPoint>): Double {
        val lat = Math.toRadians(point.latitude)
        fun xy(p: GeoPoint) = Pair(Math.toRadians(p.longitude) * 6371008.8 * cos(lat), Math.toRadians(p.latitude) * 6371008.8)
        val p = xy(point)
        return line.zipWithNext().minOfOrNull { (a, b) ->
            val aa = xy(a); val bb = xy(b)
            val dx = bb.first - aa.first; val dy = bb.second - aa.second
            val denom = dx * dx + dy * dy
            val t = if (denom == 0.0) 0.0 else
                (((p.first - aa.first) * dx + (p.second - aa.second) * dy) / denom).coerceIn(0.0, 1.0)
            hypot(p.first - aa.first - t * dx, p.second - aa.second - t * dy)
        } ?: Double.POSITIVE_INFINITY
    }

    private fun pointInRing(point: GeoPoint, ring: List<GeoPoint>): Boolean {
        var inside = false
        for (i in 0 until ring.lastIndex) {
            val a = ring[i]; val b = ring[i + 1]
            if ((a.latitude > point.latitude) != (b.latitude > point.latitude)) {
                val crossing = (b.longitude - a.longitude) * (point.latitude - a.latitude) /
                    (b.latitude - a.latitude) + a.longitude
                if (point.longitude < crossing) inside = !inside
            }
        }
        return inside
    }

    private fun isSimpleRing(ring: List<GeoPoint>): Boolean {
        if (ring.size < 5 || ring.first() != ring.last()) return false
        // Reject crossings between non-adjacent edges; do not draw a malformed buffer.
        val lat0 = Math.toRadians(ring.map { it.latitude }.average())
        fun xy(p: GeoPoint) = Pair(Math.toRadians(p.longitude) * 6371008.8 * cos(lat0), Math.toRadians(p.latitude) * 6371008.8)
        val p = ring.map(::xy)
        fun orient(a: Pair<Double, Double>, b: Pair<Double, Double>, c: Pair<Double, Double>) =
            (b.first - a.first) * (c.second - a.second) - (b.second - a.second) * (c.first - a.first)
        for (i in 0 until p.lastIndex) for (j in i + 1 until p.lastIndex) {
            if (j == i + 1 || (i == 0 && j == p.lastIndex - 1)) continue
            val a = p[i]; val b = p[i + 1]; val c = p[j]; val d = p[j + 1]
            val o1 = orient(a, b, c); val o2 = orient(a, b, d)
            val o3 = orient(c, d, a); val o4 = orient(c, d, b)
            if (((o1 > 1e-6 && o2 < -1e-6) || (o1 < -1e-6 && o2 > 1e-6)) &&
                ((o3 > 1e-6 && o4 < -1e-6) || (o3 < -1e-6 && o4 > 1e-6))) return false
        }
        return true
    }
}
