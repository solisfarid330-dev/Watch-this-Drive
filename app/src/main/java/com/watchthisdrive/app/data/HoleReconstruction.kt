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
    val mappedCenterline: CourseFeature? = null,
    val rough: List<CourseFeature> = emptyList(),
    val woodland: List<CourseFeature> = emptyList(),
    val paths: List<CourseFeature> = emptyList(),
    val trees: List<CourseFeature> = emptyList(),
    val par: Int? = null,
    val handicap: Int? = null
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

        val holeLines = geometry.features.filter { it.featureType.equals("hole", true) && it.points.size >= 2 }
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
                val line = selectCenterline(holeLines, tee.point, greenPoint, greenNumber ?: teeNumber, geometry.features.filter { it.featureType.equals("fairway", true) && it.points.size >= 3 })
                // A centerline whose two endpoints actually meet this tee/green
                // is strong evidence of the intended pairing.
                val routeBonus = if (line != null) -min(500.0,
                    endpointDistance(tee.point, greenPoint, line.points) * 4.0) else 0.0
                val numberPenalty = if (greenNumber != null && teeNumber == greenNumber) -10000.0
                    else 0.0
                pairings += Pairing(greenIndex, teeIndex, distance + numberPenalty + routeBonus)
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
        val hazards = geometry.features.filter {
            it.featureType.equals("bunker", true) ||
                it.featureType.equals("water", true) ||
                it.featureType.equals("water_hazard", true)
        }
        val landscape = geometry.features.filter {
            it.featureType.equals("rough", true) ||
                it.featureType.equals("woodland", true) ||
                it.featureType.equals("path", true) ||
                it.featureType.equals("tree", true)
        }

        return assignments.mapIndexed { fallbackIndex, pair ->
            val greenFeature = greens[pair.greenIndex]
            val tee = tees[pair.teeIndex]
            val greenPoint = center(greenFeature.points)
            val teePoint = tee.point
            val holeNumber = number(greenFeature.holeNumber) ?: tee.holeNumber ?: (fallbackIndex + 1)

            // OSM golf=hole centerlines can preserve doglegs even where
            // fairway polygons are absent or fragmented.
            val centerline = selectCenterline(holeLines, teePoint, greenPoint, holeNumber, fairways)
            val route = centerline?.points.orEmpty()
            val mapped = fairwaysForHole(fairways, teePoint, greenPoint, holeNumber, route)
            // Prefer metadata on the explicitly numbered hole centerline.
            // Fall back only to other explicitly numbered features for this
            // same hole; never borrow par/handicap from a nearby hole.
            val metadataSources = listOfNotNull(centerline) + geometry.features.filter {
                number(it.holeNumber) == holeNumber
            }
            val holePar = metadataSources.firstNotNullOfOrNull { it.par }
            val holeHandicap = metadataSources.firstNotNullOfOrNull { it.handicap }

            fun routeDistance(feature: CourseFeature): Double {
                val mappedDistance = mapped.minOfOrNull { fairway -> featureDistance(feature, fairway) }
                return when {
                    mappedDistance != null -> mappedDistance
                    route.size >= 2 -> featureDistanceToPolyline(feature, route)
                    else -> featureDistanceToSegment(feature, teePoint, greenPoint)
                }
            }
            val relevantHazards = hazards.filter { routeDistance(it) <= 95.0 }
            // Preserve useful surrounding course context for the renderer:
            // tree belts, rough polygons and cart/walking paths near this hole.
            val nearbyLandscape = landscape.filter { feature ->
                val threshold = if (feature.featureType.equals("path", true)) 65.0 else 115.0
                routeDistance(feature) <= threshold
            }

            ReconstructedHole(
                number = holeNumber,
                tee = teePoint,
                green = greenPoint,
                greenBoundary = greenFeature.points,
                // The estimate is deliberately absent when real fairway data exists.
                fairwayEstimate = if (mapped.isEmpty()) {
                    if (route.size >= 2) corridor(route) else corridor(listOf(teePoint, greenPoint))
                } else emptyList(),
                bunkers = relevantHazards.filter { it.featureType.equals("bunker", true) },
                water = relevantHazards.filter {
                    it.featureType.equals("water", true) ||
                        it.featureType.equals("water_hazard", true)
                },
                teeSource = tee.sources.first(),
                greenSource = greenFeature,
                lengthMeters = meters(teePoint, greenPoint),
                mappedFairways = mapped,
                mappedCenterline = centerline,
                rough = nearbyLandscape.filter { it.featureType.equals("rough", true) && it.points.size >= 3 },
                woodland = nearbyLandscape.filter { it.featureType.equals("woodland", true) && it.points.size >= 3 },
                paths = nearbyLandscape.filter { it.featureType.equals("path", true) && it.points.size >= 2 },
                trees = nearbyLandscape.filter { it.featureType.equals("tree", true) && it.points.isNotEmpty() },
                par = holePar,
                handicap = holeHandicap
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
            fairways.filter { featureDistanceToPolyline(it, centerline) <= 65.0 }
        } else emptyList()
        // When a centerline exists, it is the authoritative corridor. Do not
        // pull in fairways merely because they are close to a straight tee-green chord.
        val pool = (if (centerline.size >= 2) {
            if (numbered.isNotEmpty()) numbered.filter { it in routeScoped }
            else routeScoped
        } else if (numbered.isNotEmpty()) numbered else fairways).take(160)
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
                    if (next !in previous && featureDistance(pool[current], pool[next]) <= 45.0) {
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
            if (distance <= if (centerline.size >= 2) 65.0 else 90.0) feature to distance else null
        }.sortedBy { it.second }.map { it.first }
    }

    private fun selectCenterline(
        lines: List<CourseFeature>,
        tee: GeoPoint,
        green: GeoPoint,
        holeNumber: Int?,
        fairways: List<CourseFeature>
    ): CourseFeature? {
        val eligible = lines.filter { number(it.holeNumber) == null || holeNumber == null ||
            number(it.holeNumber) == holeNumber }
        return eligible.mapNotNull { line ->
            val endpoint = endpointDistance(tee, green, line.points)
            if (endpoint > 180.0) return@mapNotNull null

            // Do not let a neighboring hole's fairway validate this route.
            // Prefer explicitly numbered fairways; otherwise scope candidates
            // to the route or tee-green vicinity before measuring alignment.
            val numberedFairways = fairways.filter {
                holeNumber != null && number(it.holeNumber) == holeNumber
            }
            val nearbyFairways = if (numberedFairways.isNotEmpty()) numberedFairways else {
                fairways.filter { feature ->
                    featureDistanceToPolyline(feature, line.points) <= 200.0 ||
                        featureDistanceToSegment(feature, tee, green) <= 180.0
                }
            }
            val alignment = fairwayAlignmentMeters(line.points, nearbyFairways)
            if (nearbyFairways.isNotEmpty() && alignment != null && alignment > 58.0) {
                return@mapNotNull null
            }
            val explicitBonus = if (holeNumber != null && number(line.holeNumber) == holeNumber) -35.0 else 0.0
            val alignmentPenalty = alignment?.times(1.8) ?: 0.0
            line to (endpoint + alignmentPenalty + explicitBonus)
        }.minByOrNull { it.second }?.first
    }

    private fun fairwayAlignmentMeters(
        route: List<GeoPoint>,
        fairways: List<CourseFeature>
    ): Double? {
        if (route.size < 2 || fairways.isEmpty()) return null
        // Sample the complete route at a fixed ground interval. OSM centerlines
        // often contain only a few long segments, so checking vertices/midpoints
        // can miss a substantial lateral excursion between them.
        val samples = mutableListOf<GeoPoint>()
        route.zipWithNext().forEach { (a, b) ->
            val segmentLength = meters(a, b)
            val steps = ceil(segmentLength / 18.0).toInt().coerceAtLeast(1)
            for (step in 0 until steps) {
                val fraction = step.toDouble() / steps
                samples += GeoPoint(
                    a.latitude + (b.latitude - a.latitude) * fraction,
                    a.longitude + (b.longitude - a.longitude) * fraction
                )
            }
        }
        samples += route.last()
        val distances = samples.map { point ->
            fairways.minOf { feature ->
                if (isClosedPolygon(feature.points) && pointInPolygon(point, feature.points)) 0.0
                else feature.points.zipWithNext().minOfOrNull { (a, b) ->
                    distanceToSegment(point, a, b)
                } ?: Double.POSITIVE_INFINITY
            }
        }.filter { it.isFinite() }.sorted()
        if (distances.isEmpty()) return null
        // A good average can hide a long section that runs beside the fairway.
        // Reject sustained departures as well as a poor overall fit.
        val upperTail = distances[((distances.size - 1) * 0.85).roundToInt()]
        return max(distances.average(), upperTail * 0.72)
    }

    private fun endpointDistance(tee: GeoPoint, green: GeoPoint, line: List<GeoPoint>): Double {
        if (line.size < 2) return Double.POSITIVE_INFINITY
        val a = meters(tee, line.first()) + meters(green, line.last())
        val b = meters(tee, line.last()) + meters(green, line.first())
        return min(a, b)
    }

    private fun featureDistanceToPolyline(feature: CourseFeature, line: List<GeoPoint>): Double {
        if (feature.points.isEmpty() || line.isEmpty()) return Double.POSITIVE_INFINITY
        val polygonEdges = if (isClosedPolygon(feature.points)) feature.points.zipWithNext() else emptyList()
        // A route passing through a mapped fairway polygon can be far from all
        // polygon vertices. Test route vertices against polygon edges and both
        // sets of segments against each other so the real fairway is retained.
        val vertexDistance = feature.points.minOfOrNull { point ->
            line.zipWithNext().minOfOrNull { (a, b) -> distanceToSegment(point, a, b) }
                ?: meters(point, line.first())
        } ?: Double.POSITIVE_INFINITY
        val routeVertexDistance = if (polygonEdges.isNotEmpty()) line.minOfOrNull { point ->
            polygonEdges.minOfOrNull { (a, b) -> distanceToSegment(point, a, b) } ?: Double.POSITIVE_INFINITY
        } ?: Double.POSITIVE_INFINITY else Double.POSITIVE_INFINITY
        val crossesInterior = if (polygonEdges.isNotEmpty()) line.zipWithNext().any { (a, b) ->
            pointInPolygon(a, feature.points) || pointInPolygon(b, feature.points) ||
                polygonEdges.any { (c, d) -> segmentsIntersect(a, b, c, d) }
        } else false
        return if (crossesInterior) 0.0 else min(vertexDistance, routeVertexDistance)
    }

    private fun segmentsIntersect(a: GeoPoint, b: GeoPoint, c: GeoPoint, d: GeoPoint): Boolean {
        fun orient(p: GeoPoint, q: GeoPoint, r: GeoPoint): Double =
            (q.longitude - p.longitude) * (r.latitude - p.latitude) -
                (q.latitude - p.latitude) * (r.longitude - p.longitude)
        fun onSegment(p: GeoPoint, q: GeoPoint, r: GeoPoint): Boolean =
            q.longitude >= min(p.longitude, r.longitude) - 1e-12 &&
                q.longitude <= max(p.longitude, r.longitude) + 1e-12 &&
                q.latitude >= min(p.latitude, r.latitude) - 1e-12 &&
                q.latitude <= max(p.latitude, r.latitude) + 1e-12
        val o1 = orient(a, b, c); val o2 = orient(a, b, d)
        val o3 = orient(c, d, a); val o4 = orient(c, d, b)
        val epsilon = 1e-12
        if (((o1 > epsilon && o2 < -epsilon) || (o1 < -epsilon && o2 > epsilon)) &&
            ((o3 > epsilon && o4 < -epsilon) || (o3 < -epsilon && o4 > epsilon))) return true
        return (abs(o1) <= epsilon && onSegment(a, c, b)) ||
            (abs(o2) <= epsilon && onSegment(a, d, b)) ||
            (abs(o3) <= epsilon && onSegment(c, a, d)) ||
            (abs(o4) <= epsilon && onSegment(c, b, d))
    }
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

    /** Builds an estimated fairway envelope along every mapped route vertex,
     * preserving doglegs instead of inventing a straight tee-to-green strip. */
    private fun corridor(route: List<GeoPoint>): List<GeoPoint> {
        if (route.size < 2) return emptyList()
        val left = mutableListOf<GeoPoint>()
        val right = mutableListOf<GeoPoint>()
        val width = 25.0
        for (i in route.indices) {
            val previous = route[(i - 1).coerceAtLeast(0)]
            val next = route[(i + 1).coerceAtMost(route.lastIndex)]
            val midLat = Math.toRadians(route[i].latitude)
            val dx = (next.longitude - previous.longitude) * 111320.0 * cos(midLat)
            val dy = (next.latitude - previous.latitude) * 111320.0
            val length = hypot(dx, dy).coerceAtLeast(0.01)
            val ox = -dy / length * width
            val oy = dx / length * width
            val latOffset = oy / 111320.0
            val lonOffset = ox / (111320.0 * cos(midLat).coerceAtLeast(0.01))
            left += GeoPoint(route[i].latitude + latOffset, route[i].longitude + lonOffset)
            right += GeoPoint(route[i].latitude - latOffset, route[i].longitude - lonOffset)
        }
        return left + right.asReversed() + left.first()
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
        if (first.points.isEmpty() || second.points.isEmpty()) return Double.POSITIVE_INFINITY
        val firstPolygon = isClosedPolygon(first.points)
        val secondPolygon = isClosedPolygon(second.points)
        if (firstPolygon && second.points.any { pointInPolygon(it, first.points) }) return 0.0
        if (secondPolygon && first.points.any { pointInPolygon(it, second.points) }) return 0.0

        val firstEdges = first.points.zipWithNext()
        val secondEdges = second.points.zipWithNext()
        if (firstEdges.any { (a, b) -> secondEdges.any { (c, d) -> segmentsIntersect(a, b, c, d) } }) {
            return 0.0
        }
        val firstToSecond = first.points.minOfOrNull { point ->
            secondEdges.minOfOrNull { (a, b) -> distanceToSegment(point, a, b) }
                ?: meters(point, second.points.first())
        } ?: Double.POSITIVE_INFINITY
        val secondToFirst = second.points.minOfOrNull { point ->
            firstEdges.minOfOrNull { (a, b) -> distanceToSegment(point, a, b) }
                ?: meters(point, first.points.first())
        } ?: Double.POSITIVE_INFINITY
        return min(firstToSecond, secondToFirst)
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
