package com.watchthisdrive.app.data

import kotlin.math.*

/**
 * First-pass inferred hole geometry. Every generated corridor is an estimate,
 * not a surveyed fairway boundary. Source features are retained separately
 * so the UI can distinguish mapped hazards/greens from generated fairway.
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
    val lengthMeters: Double
)

object HoleReconstructor {
    private data class TeeCandidate(
        val source: CourseFeature,
        val point: GeoPoint,
        val holeNumber: Int?
    )

    /**
     * Builds an initial tee-to-green playing corridor for holes where a mapped
     * tee and green can be associated confidently enough by proximity.
     * It intentionally does not fabricate tees or greens when none exist.
     */
    fun reconstruct(geometry: CourseGeometry): List<ReconstructedHole> {
        val greens = geometry.features.filter {
            it.featureType.equals("green", true) && it.points.isNotEmpty()
        }
        val tees = clusterTees(geometry.features.filter {
            it.featureType.equals("tee", true) && it.points.isNotEmpty()
        })
        if (greens.isEmpty() || tees.isEmpty()) return emptyList()

        val pairs = mutableListOf<Triple<Int, Int, Double>>()
        greens.forEachIndexed { gi, green ->
            val greenCenter = center(green.points)
            tees.forEachIndexed { ti, tee ->
                val distance = meters(tee.point, greenCenter)
                if (distance in 35.0..650.0) {
                    val sameNumber = green.holeNumber?.toIntOrNull() != null &&
                        tee.holeNumber != null &&
                        green.holeNumber.toIntOrNull() == tee.holeNumber
                    val conflictingNumber = green.holeNumber?.toIntOrNull() != null &&
                        tee.holeNumber != null && !sameNumber
                    if (!conflictingNumber) {
                        pairs.add(Triple(gi, ti, distance - if (sameNumber) 1000.0 else 0.0))
                    }
                }
            }
        }

        // Greedy shortest links first; each green and tee cluster can be used once.
        val usedGreens = mutableSetOf<Int>()
        val usedTees = mutableSetOf<Int>()
        val assignments = pairs.sortedBy { it.third }.mapNotNull { pair ->
            val (gi, ti, _) = pair
            if (gi in usedGreens || ti in usedTees) null
            else {
                usedGreens.add(gi)
                usedTees.add(ti)
                gi to ti
            }
        }

        val ordered = assignments.map { (gi, ti) ->
            val greenFeature = greens[gi]
            val tee = tees[ti]
            val greenCenter = center(greenFeature.points)
            val teePoint = tee.point
            val length = meters(teePoint, greenCenter)
            val number = greenFeature.holeNumber?.toIntOrNull()
                ?: tee.holeNumber
                ?: (gi + 1)
            val fairway = corridor(teePoint, greenCenter, 24.0, 19.0)
            val hazards = geometry.features.filter { feature ->
                val kind = feature.featureType.lowercase()
                (kind == "bunker" || kind == "water" || kind == "water_hazard") &&
                    feature.points.any { point ->
                        distanceToSegmentMeters(point, teePoint, greenCenter) <= 95.0
                    }
            }
            ReconstructedHole(
                number = number,
                tee = teePoint,
                green = greenCenter,
                greenBoundary = greenFeature.points,
                fairwayEstimate = fairway,
                bunkers = hazards.filter { it.featureType.equals("bunker", true) },
                water = hazards.filter {
                    it.featureType.equals("water", true) ||
                        it.featureType.equals("water_hazard", true)
                },
                teeSource = tee.source,
                greenSource = greenFeature,
                lengthMeters = length
            )
        }
        return ordered.sortedBy { it.number }
    }

    private fun clusterTees(features: List<CourseFeature>): List<TeeCandidate> {
        val remaining = features.toMutableList()
        val clusters = mutableListOf<TeeCandidate>()
        while (remaining.isNotEmpty()) {
            val seed = remaining.removeAt(0)
            val points = seed.points.toMutableList()
            val members = mutableListOf(seed)
            var changed = true
            while (changed) {
                changed = false
                val nearby = remaining.filter { candidate ->
                    val sameHole = seed.holeNumber != null && candidate.holeNumber != null &&
                        seed.holeNumber != candidate.holeNumber
                    !sameHole && candidate.points.any { p ->
                        points.any { q -> meters(p, q) <= 22.0 }
                    }
                }
                if (nearby.isNotEmpty()) {
                    members.addAll(nearby)
                    nearby.forEach { points.addAll(it.points) }
                    remaining.removeAll(nearby.toSet())
                    changed = true
                }
            }
            clusters.add(
                TeeCandidate(
                    source = members.first(),
                    point = center(points),
                    holeNumber = members.mapNotNull { it.holeNumber?.toIntOrNull() }.distinct().singleOrNull()
                )
            )
        }
        return clusters
    }

    private fun center(points: List<GeoPoint>) = GeoPoint(
        points.map { it.latitude }.average(),
        points.map { it.longitude }.average()
    )

    /** Creates a tapered, approximate fairway corridor between tee and green. */
    private fun corridor(start: GeoPoint, end: GeoPoint, startHalfWidth: Double, endHalfWidth: Double): List<GeoPoint> {
        val latScale = 111_320.0
        val midLat = Math.toRadians((start.latitude + end.latitude) / 2.0)
        val dx = (end.longitude - start.longitude) * latScale * cos(midLat)
        val dy = (end.latitude - start.latitude) * latScale
        val length = hypot(dx, dy).coerceAtLeast(1.0)
        val px = -dy / length
        val py = dx / length
        fun offset(point: GeoPoint, width: Double, sign: Double): GeoPoint = GeoPoint(
            point.latitude + py * width * sign / latScale,
            point.longitude + px * width * sign / (latScale * cos(midLat).coerceAtLeast(0.01))
        )
        return listOf(
            offset(start, startHalfWidth, 1.0),
            offset(end, endHalfWidth, 1.0),
            offset(end, endHalfWidth, -1.0),
            offset(start, startHalfWidth, -1.0),
            offset(start, startHalfWidth, 1.0)
        )
    }

    private fun meters(a: GeoPoint, b: GeoPoint): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(a.latitude)
        val p2 = Math.toRadians(b.latitude)
        val dp = p2 - p1
        val dl = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private fun distanceToSegmentMeters(point: GeoPoint, start: GeoPoint, end: GeoPoint): Double {
        val latScale = 111_320.0
        val midLat = Math.toRadians((start.latitude + end.latitude + point.latitude) / 3.0)
        fun xy(p: GeoPoint) = Pair(
            p.longitude * latScale * cos(midLat),
            p.latitude * latScale
        )
        val a = xy(start); val b = xy(end); val p = xy(point)
        val dx = b.first - a.first; val dy = b.second - a.second
        val denominator = dx * dx + dy * dy
        val t = if (denominator == 0.0) 0.0 else
            (((p.first - a.first) * dx + (p.second - a.second) * dy) / denominator).coerceIn(0.0, 1.0)
        return hypot(p.first - (a.first + t * dx), p.second - (a.second + t * dy))
    }
}
