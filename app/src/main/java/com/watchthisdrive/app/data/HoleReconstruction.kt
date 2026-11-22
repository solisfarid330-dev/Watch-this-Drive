package com.watchthisdrive.app.data

import kotlin.math.*
import java.util.ArrayDeque

/**
 * A reconstructed hole keeps surveyed/mapped OSM geometry separate from any
 * generated fallback. Mapped fairway polygons are never replaced by a straight
 * tee-to-green corridor.
 */
data class ReconstructedHole(
    val number: Int,
    val tee: GeoPoint,
    val green: GeoPoint,
    val greenBoundary: List<GeoPoint>,
    val fairwayEstimate: List<GeoPoint>,
    val bunkers: List<CourseFeature>,
    val water: List<CourseFeature>,
    val teeSource: CourseFeature,
    val greenSource: CourseFeature,
    val lengthMeters: Double,
    val mappedFairways: List<CourseFeature> = emptyList(),
    val mappedCenterline: CourseFeature? = null
) {
    val usesMappedFairway: Boolean get() = mappedFairways.isNotEmpty()
}

object HoleReconstructor {
    private const val MIN_HOLE_METERS = 45.0
    private const val MAX_HOLE_METERS = 720.0

    private data class TeeCandidate(
        val sources: List<CourseFeature>,
        val point: GeoPoint,
        val holeNumber: Int?
    )

    private data class Pairing(
        val greenIndex: Int,
        val teeIndex: Int,
        val score: Double
    )

    /**
     * Associates tees and greens using explicit OSM hole numbers first, then
     * constrained spatial matching. It uses mapped fairways as the actual
     * playing shape and only generates a corridor when no mapped fairway fits.
     */
    fun reconstruct(geometry: CourseGeometry): List<ReconstructedHole> {
        val greens = geometry.features.filter {
            it.featureType.equals("green", true) && it.points.size >= 1
        }
        val tees = clusterTees(geometry.features.filter {
            it.featureType.equals("tee", true) && it.points.isNotEmpty()
        })
        if (greens.isEmpty() || tees.isEmpty()) return emptyList()

        val pairings = mutableListOf<Pairing>()
        greens.forEachIndexed { greenIndex, green ->
            val greenPoint = center(green.points)
            tees.forEachIndexed { teeIndex, tee ->
                val distance = meters(tee.point, greenPoint)
                if (distance !in MIN_HOLE_METERS..MAX_HOLE_METERS) return@forEachIndexed
                val greenNumber = number(green.holeNumber)
                val teeNumber = tee.holeNumber
                if (greenNumber != null && teeNumber != null && greenNumber != teeNumber) {
                    return@forEachIndexed
                }
                // Explicit matching is a strong preference, not a small tie-break.
                val numberPenalty = if (greenNumber != null && teeNumber == greenNumber) -10000.0
                    else if (greenNumber != null || teeNumber != null) 0.0 else 0.0
                pairings += Pairing(greenIndex, teeIndex, distance + numberPenalty)
            }
        }

        // Global one-to-one assignment prevents a nearby tee from being reused
        // for several greens. Explicit hole-number matches dominate distance.
        val usedGreens = mutableSetOf<Int>()
        val usedTees = mutableSetOf<Int>()
        val assignments = pairings.sortedBy { it.score }.mapNotNull { pair ->
            if (pair.greenIndex in usedGreens || pair.teeIndex in usedTees) null
            else {
                usedGreens += pair.greenIndex
                usedTees += pair.teeIndex
                pair
            }
        }

        val fairways = geometry.features.filter {
            it.featureType.equals("fairway", true) && it.points.size >= 3
        }
        val holeLines = geometry.features.filter { it.featureType.equals("hole", true) && it.points.size >= 2 }
        val hazards = geometry.features.filter {
            it.featureType.equals("bunker", true) ||
                it.featureType.equals("water", true) ||
                it.featureType.equals("water_hazard", true)
        }

        return assignments.mapIndexed { fallbackIndex, pair ->
            val greenFeature = greens[pair.greenIndex]
            val tee = tees[pair.teeIndex]
            val greenPoint = center(greenFeature.points)
            val teePoint = tee.point
            val holeNumber = number(greenFeature.holeNumber) ?: tee.holeNumber ?: (fallbackIndex + 1)

            // OSM golf=hole centerlines can preserve doglegs even where
            // fairway polygons are absent or fragmented.
            val centerline = selectCenterline(holeLines, teePoint, greenPoint, holeNumber)
            val route = centerline?.points.orEmpty()
            val mapped = fairwaysForHole(fairways, teePoint, greenPoint, holeNumber, route)

            val relevantHazards = hazards.filter { hazard ->
                val mappedDistance = mapped.minOfOrNull { fairway -> featureDistance(hazard, fairway) }
                when {
                    mapped.isNotEmpty() -> mappedDistance != null && mappedDistance <= 95.0
                    route.size >= 2 -> featureDistanceToPolyline(hazard, route) <= 90.0
                    else -> featureDistanceToSegment(hazard, teePoint, greenPoint) <= 90.0
                }
            }

            ReconstructedHole(
                number = holeNumber,
                tee = teePoint,
                green = greenPoint,
                greenBoundary = greenFeature.points,
                // The estimate is deliberately absent when real fairway data exists.
                fairwayEstimate = if (mapped.isEmpty()) corridor(teePoint, greenPoint) else emptyList(),
                bunkers = relevantHazards.filter { it.featureType.equals("bunker", true) },
                water = relevantHazards.filter {
                    it.featureType.equals("water", true) ||
                        it.featureType.equals("water_hazard", true)
                },
                teeSource = tee.sources.first(),
                greenSource = greenFeature,
                lengthMeters = meters(teePoint, greenPoint),
                mappedFairways = mapped,
                mappedCenterline = centerline
            )
        }.distinctBy { it.number }.sortedBy { it.number }
    }

    private fun fairwaysForHole(
        fairways: List<CourseFeature>,
        tee: GeoPoint,
        green: GeoPoint,
        holeNumber: Int,
        centerline: List<GeoPoint>
    ): List<CourseFeature> {
        if (fairways.isEmpty()) return emptyList()
        val numbered = fairways.filter { number(it.holeNumber) == holeNumber }
        val routeScoped = if (centerline.size >= 2) {
            fairways.filter { featureDistanceToPolyline(it, centerline) <= 140.0 }
        } else emptyList()
        val pool = (if (numbered.isNotEmpty()) numbered else if (routeScoped.isNotEmpty()) routeScoped else fairways).take(80)
        val starts = pool.indices.filter { featureDistanceToPoint(tee, pool[it]) <= 115.0 }
        val ends = pool.indices.filter { featureDistanceToPoint(green, pool[it]) <= 115.0 }
        if (starts.isNotEmpty() && ends.isNotEmpty()) {
            val target = ends.toSet()
            val queue = ArrayDeque<Int>()
            val previous = mutableMapOf<Int, Int?>()
            starts.forEach { queue.addLast(it); previous[it] = null }
            var finish: Int? = null
            while (queue.isNotEmpty() && finish == null) {
                val current = queue.removeFirst()
                if (current in target) { finish = current; break }
                for (next in pool.indices) {
                    if (next !in previous && featureDistance(pool[current], pool[next]) <= 85.0) {
                        previous[next] = current
                        queue.addLast(next)
                    }
                }
            }
            if (finish != null) {
                val route = mutableListOf<CourseFeature>()
                var cursor: Int? = finish
                while (cursor != null) {
                    route += pool[cursor]
                    cursor = previous[cursor]
                }
                return route.asReversed().distinctBy { it.elementType + ":" + it.id }
            }
        }
        // Disconnected or incomplete mapping: keep only nearby fairways,
        // never invent a connecting mapped segment.
        return pool.mapNotNull { feature ->
            val distance = if (centerline.size >= 2) featureDistanceToPolyline(feature, centerline)
                else featureDistanceToSegment(feature, tee, green)
            if (distance <= 150.0) feature to distance else null
        }.sortedBy { it.second }.take(3).map { it.first }
    }

    private fun selectCenterline(
        lines: List<CourseFeature>,
        tee: GeoPoint,
        green: GeoPoint,
        holeNumber: Int
    ): CourseFeature? {
        val eligible = lines.filter { number(it.holeNumber) == null || number(it.holeNumber) == holeNumber }
        return eligible.minByOrNull { line ->
            val explicitBonus = if (number(line.holeNumber) == holeNumber) -250.0 else 0.0
            featureDistanceToPoint(tee, line) + featureDistanceToPoint(green, line) + explicitBonus
        }?.takeIf { line ->
            featureDistanceToPoint(tee, line) + featureDistanceToPoint(green, line) <= 260.0
        }
    }

    private fun featureDistanceToPolyline(feature: CourseFeature, line: List<GeoPoint>): Double =
        feature.points.minOfOrNull { point ->
            line.zipWithNext().minOfOrNull { (a, b) -> distanceToSegment(point, a, b) }
                ?: meters(point, line.first())
        } ?: Double.POSITIVE_INFINITY
    private fun featureDistanceToPoint(point: GeoPoint, feature: CourseFeature): Double {
        if (isClosedPolygon(feature.points) && pointInPolygon(point, feature.points)) return 0.0
        return feature.points.zipWithNext().minOfOrNull { (a, b) -> distanceToSegment(point, a, b) }
            ?: feature.points.minOfOrNull { meters(point, it) } ?: Double.POSITIVE_INFINITY
    }
    private fun clusterTees(features: List<CourseFeature>): List<TeeCandidate> {
        val remaining = features.toMutableList()
        val result = mutableListOf<TeeCandidate>()
        while (remaining.isNotEmpty()) {
            val seed = remaining.removeAt(0)
            val seedNumber = number(seed.holeNumber)
            val members = mutableListOf(seed)
            val points = seed.points.toMutableList()
            var changed = true
            while (changed) {
                changed = false
                val nearby = remaining.filter { candidate ->
                    val candidateNumber = number(candidate.holeNumber)
                    val conflicting = seedNumber != null && candidateNumber != null &&
                        seedNumber != candidateNumber
                    val sameNumber = seedNumber != null && seedNumber == candidateNumber
                    !conflicting && candidate.points.any { p ->
                        points.any { q ->
                            meters(p, q) <= if (sameNumber) 70.0 else 24.0
                        }
                    }
                }
                if (nearby.isNotEmpty()) {
                    members.addAll(nearby)
                    nearby.forEach { points.addAll(it.points) }
                    remaining.removeAll(nearby.toSet())
                    changed = true
                }
            }
            result += TeeCandidate(
                sources = members,
                point = center(points),
                holeNumber = members.mapNotNull { number(it.holeNumber) }.distinct().singleOrNull()
            )
        }
        return result
    }

    private fun number(value: String?): Int? =
        value?.trim()?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }

    private fun center(points: List<GeoPoint>) = GeoPoint(
        points.map { it.latitude }.average(),
        points.map { it.longitude }.average()
    )

    private fun corridor(start: GeoPoint, end: GeoPoint): List<GeoPoint> {
        val latitudeScale = 111_320.0
        val midLatitude = Math.toRadians((start.latitude + end.latitude) / 2.0)
        val dx = (end.longitude - start.longitude) * latitudeScale * cos(midLatitude)
        val dy = (end.latitude - start.latitude) * latitudeScale
        val length = hypot(dx, dy).coerceAtLeast(1.0)
        val perpendicularX = -dy / length
        val perpendicularY = dx / length
        fun offset(point: GeoPoint, width: Double, sign: Double) = GeoPoint(
            point.latitude + perpendicularY * width * sign / latitudeScale,
            point.longitude + perpendicularX * width * sign /
                (latitudeScale * cos(midLatitude).coerceAtLeast(0.01))
        )
        return listOf(
            offset(start, 24.0, 1.0), offset(end, 19.0, 1.0),
            offset(end, 19.0, -1.0), offset(start, 24.0, -1.0),
            offset(start, 24.0, 1.0)
        )
    }

    private fun meters(a: GeoPoint, b: GeoPoint): Double {
        val radius = 6_371_000.0
        val p1 = Math.toRadians(a.latitude)
        val p2 = Math.toRadians(b.latitude)
        val dp = p2 - p1
        val dl = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * radius * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private fun featureDistanceToSegment(
        feature: CourseFeature,
        start: GeoPoint,
        end: GeoPoint
    ): Double = feature.points.minOfOrNull { distanceToSegment(it, start, end) }
        ?: Double.POSITIVE_INFINITY

    private fun featureDistance(first: CourseFeature, second: CourseFeature): Double {
        if (first.points.any { point -> isClosedPolygon(second.points) && pointInPolygon(point, second.points) }) {
            return 0.0
        }
        return first.points.minOfOrNull { a ->
            second.points.zipWithNext().minOfOrNull { (b, c) -> distanceToSegment(a, b, c) }
                ?: meters(a, center(second.points))
        } ?: Double.POSITIVE_INFINITY
    }

    private fun distanceToSegment(point: GeoPoint, start: GeoPoint, end: GeoPoint): Double {
        val latitudeScale = 111_320.0
        val middleLatitude = Math.toRadians((start.latitude + end.latitude + point.latitude) / 3.0)
        fun xy(p: GeoPoint) = p.longitude * latitudeScale * cos(middleLatitude) to
            p.latitude * latitudeScale
        val a = xy(start); val b = xy(end); val p = xy(point)
        val dx = b.first - a.first; val dy = b.second - a.second
        val denominator = dx * dx + dy * dy
        val t = if (denominator <= 1e-9) 0.0 else
            (((p.first - a.first) * dx + (p.second - a.second) * dy) / denominator).coerceIn(0.0, 1.0)
        return hypot(p.first - (a.first + t * dx), p.second - (a.second + t * dy))
    }

    private fun isClosedPolygon(points: List<GeoPoint>): Boolean =
        points.size >= 4 && meters(points.first(), points.last()) <= 5.0

    private fun pointInPolygon(point: GeoPoint, polygon: List<GeoPoint>): Boolean {
        var inside = false
        var previous = polygon.lastIndex
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[previous]
            if ((a.latitude > point.latitude) != (b.latitude > point.latitude) &&
                point.longitude < (b.longitude - a.longitude) *
                (point.latitude - a.latitude) /
                ((b.latitude - a.latitude).takeIf { abs(it) > 1e-12 } ?: 1e-12) + a.longitude
            ) inside = !inside
            previous = i
        }
        return inside
    }
}
