package com.watchthisdrive.app.data

import kotlin.math.*

/**
 * Conservative, course-agnostic reconstruction for numbered gaps.
 *
 * Preferred evidence is a tee and green carrying the missing hole number.
 * When OSM omits those refs (common for tee/green polygons), an unnumbered
 * fallback is allowed only when unmatched tee clusters and greens form a
 * one-to-one set of fairway-supported connections equal to the missing run.
 * Ambiguous marker pairings are deliberately left unresolved.
 */
object CourseHoleInference {
    private const val MIN_HOLE_LENGTH_M = 60.0
    private const val MAX_HOLE_LENGTH_M = 550.0
    private const val MAX_TEE_CLUSTER_M = 45.0
    private const val MARKER_MATCH_LIMIT_M = 60.0
    private const val MIN_FAIRWAY_SUPPORT = 0.35
    private const val PAIR_AMBIGUITY_MARGIN = 20.0

    private data class TeeCluster(val point: GeoPoint, val features: List<CourseFeature>)
    private data class Candidate(val tee: TeeCluster, val green: CourseFeature, val greenPoint: GeoPoint, val score: Double)
    private data class HoleAnchors(val tee: GeoPoint, val green: GeoPoint)

    fun addInferredHoles(course: CourseGeometry): CourseGeometry {
        val usable = course.features.filter { it.hasUsableGeometry }
        val mappedHoles = usable.filter {
            it.origin == CourseFeatureOrigin.SOURCE && it.featureType == "hole" &&
                it.geometryType == CourseGeometryType.LINE && it.holeNumber?.toIntOrNull() in 1..18
        }
        val numbered = mappedHoles.mapNotNull { it.holeNumber?.toIntOrNull() }.toSet()
        val maximum = numbered.maxOrNull() ?: return course
        val expectedCount = when { maximum > 9 -> 18; maximum >= 9 -> 9; else -> return course }
        val missing = (1..expectedCount).filterNot(numbered::contains)
        if (missing.isEmpty()) return course

        val tees = usable.filter { it.origin == CourseFeatureOrigin.SOURCE && it.featureType == "tee" }
        val greens = usable.filter { it.origin == CourseFeatureOrigin.SOURCE && it.featureType == "green" }
        val fairways = usable.filter { it.origin == CourseFeatureOrigin.SOURCE && it.featureType == "fairway" }
        if (tees.isEmpty() || greens.isEmpty() || fairways.isEmpty()) return course

        val explicit = missing.mapNotNull { number ->
            val matchingTees = tees.filter { it.holeNumber?.toIntOrNull() == number }
            val matchingGreens = greens.filter { it.holeNumber?.toIntOrNull() == number }
            if (matchingTees.size != 1 || matchingGreens.size != 1) return@mapNotNull null
            val tee = representativePoint(matchingTees.single()) ?: return@mapNotNull null
            val green = representativePoint(matchingGreens.single()) ?: return@mapNotNull null
            if (!plausibleLine(tee, green, fairways)) return@mapNotNull null
            inferredFeature(number, tee, green, 0.65, "inferred_from_numbered_tee_green_and_fairway")
        }
        val inferred = if (explicit.size == missing.size) explicit else {
            inferFromUnnumberedMarkers(missing, mappedHoles, tees, greens, fairways)
        }
        if (inferred.isEmpty()) return course
        val retained = course.features.filterNot { it.origin == CourseFeatureOrigin.INFERRED && it.featureType == "hole" }
        return course.copy(features = retained + inferred)
    }

    private fun inferFromUnnumberedMarkers(
        missing: List<Int>,
        mappedHoles: List<CourseFeature>,
        allTees: List<CourseFeature>,
        allGreens: List<CourseFeature>,
        fairways: List<CourseFeature>
    ): List<CourseFeature> {
        // Do not guess ordering across disjoint numbering gaps.
        if (missing.zipWithNext().any { (a, b) -> b != a + 1 }) return emptyList()
        val tees = allTees.filter { it.holeNumber.isNullOrBlank() }
        val greens = allGreens.filter { it.holeNumber.isNullOrBlank() }
        val clusters = clusterTees(tees)
        if (clusters.isEmpty()) return emptyList()
        val greenPoints = greens.mapNotNull { feature -> representativePoint(feature)?.let { feature to it } }
        if (greenPoints.isEmpty()) return emptyList()

        val usedTeeClusters = mutableSetOf<TeeCluster>()
        val usedGreens = mutableSetOf<CourseFeature>()
        mappedHoles.forEach { hole ->
            val anchors = orientMappedHole(hole, clusters, greenPoints) ?: return@forEach
            clusters.filter { meters(it.point, anchors.tee) <= MARKER_MATCH_LIMIT_M }.forEach(usedTeeClusters::add)
            greenPoints.filter { meters(it.second, anchors.green) <= MARKER_MATCH_LIMIT_M }.forEach { usedGreens.add(it.first) }
        }
        val availableTees = clusters.filterNot { it in usedTeeClusters }
        val availableGreens = greenPoints.filterNot { it.first in usedGreens }
        if (availableTees.isEmpty() || availableGreens.isEmpty()) return emptyList()

        val candidates = mutableListOf<Candidate>()
        availableTees.forEach { tee ->
            availableGreens.forEach greenLoop@{ (greenFeature, greenPoint) ->
                val length = meters(tee.point, greenPoint)
                if (length !in MIN_HOLE_LENGTH_M..MAX_HOLE_LENGTH_M) return@greenLoop
                val support = fairwaySupport(tee.point, greenPoint, fairways)
                if (support < MIN_FAIRWAY_SUPPORT) return@greenLoop
                val score = length + (1.0 - support) * 250.0
                candidates.add(Candidate(tee, greenFeature, greenPoint, score))
            }
        }
        if (candidates.isEmpty()) return emptyList()

        // Keep only mutual, unambiguous best matches. This prevents a tee or green
        // from being reused and avoids arbitrary straight-line connections.
        val byTee = candidates.groupBy { it.tee }
        val byGreen = candidates.groupBy { it.green.elementType to it.green.id }
        val mutual = candidates.filter { candidate ->
            val teeRanked = byTee[candidate.tee].orEmpty().sortedBy { it.score }
            val greenRanked = byGreen[candidate.green.elementType to candidate.green.id].orEmpty().sortedBy { it.score }
            teeRanked.firstOrNull() == candidate && greenRanked.firstOrNull() == candidate &&
                (teeRanked.getOrNull(1)?.score?.minus(candidate.score) ?: Double.POSITIVE_INFINITY) >= PAIR_AMBIGUITY_MARGIN &&
                (greenRanked.getOrNull(1)?.score?.minus(candidate.score) ?: Double.POSITIVE_INFINITY) >= PAIR_AMBIGUITY_MARGIN
        }
        if (mutual.size != missing.size) return emptyList()

        val ordered = orderCandidates(missing, mutual, mappedHoles, clusters, greenPoints) ?: return emptyList()
        return missing.zip(ordered).map { (number, candidate) ->
            inferredFeature(number, candidate.tee.point, candidate.greenPoint, 0.50,
                "inferred_from_unmatched_tee_green_and_fairway_layout")
        }
    }

    private fun orderCandidates(
        missing: List<Int>,
        candidates: List<Candidate>,
        mappedHoles: List<CourseFeature>,
        clusters: List<TeeCluster>,
        greens: List<Pair<CourseFeature, GeoPoint>>
    ): List<Candidate>? {
        if (candidates.size == 1) return candidates
        val previous = mappedHoles.firstOrNull { it.holeNumber?.toIntOrNull() == missing.first() - 1 }
            ?.let { orientMappedHole(it, clusters, greens)?.green }
        val next = mappedHoles.firstOrNull { it.holeNumber?.toIntOrNull() == missing.last() + 1 }
            ?.let { orientMappedHole(it, clusters, greens)?.tee }
        if (previous == null && next == null) return null
        if (candidates.size > 6) return null

        fun permutations(items: List<Candidate>): List<List<Candidate>> {
            if (items.size <= 1) return listOf(items)
            return items.flatMap { item -> permutations(items - item).map { listOf(item) + it } }
        }
        val ranked = permutations(candidates).map { order ->
            var cost = previous?.let { meters(it, order.first().tee.point) } ?: 0.0
            order.zipWithNext().forEach { (a, b) -> cost += meters(a.greenPoint, b.tee.point) }
            cost += next?.let { meters(order.last().greenPoint, it) } ?: 0.0
            order to cost
        }.sortedBy { it.second }
        if (ranked.isEmpty()) return null
        if (ranked.getOrNull(1)?.second?.minus(ranked.first().second)?.let { it < PAIR_AMBIGUITY_MARGIN } == true) return null
        return ranked.first().first
    }

    private fun orientMappedHole(
        hole: CourseFeature,
        tees: List<TeeCluster>,
        greens: List<Pair<CourseFeature, GeoPoint>>
    ): HoleAnchors? {
        val start = hole.points.firstOrNull() ?: return null
        val end = hole.points.lastOrNull() ?: return null
        val startTee = tees.minOfOrNull { meters(start, it.point) } ?: return null
        val endTee = tees.minOfOrNull { meters(end, it.point) } ?: return null
        val startGreen = greens.minOfOrNull { meters(start, it.second) } ?: return null
        val endGreen = greens.minOfOrNull { meters(end, it.second) } ?: return null
        val forward = startTee + endGreen
        val reverse = endTee + startGreen
        if (abs(forward - reverse) < 12.0) return null
        return if (forward < reverse) HoleAnchors(start, end) else HoleAnchors(end, start)
    }

    private fun clusterTees(tees: List<CourseFeature>): List<TeeCluster> {
        val clusters = mutableListOf<MutableList<Pair<CourseFeature, GeoPoint>>>()
        tees.mapNotNull { feature -> representativePoint(feature)?.let { feature to it } }.forEach { item ->
            val cluster = clusters.firstOrNull { group ->
                val center = GeoPoint(group.map { it.second.latitude }.average(), group.map { it.second.longitude }.average())
                meters(center, item.second) <= MAX_TEE_CLUSTER_M
            }
            if (cluster == null) clusters.add(mutableListOf(item)) else cluster.add(item)
        }
        return clusters.map { group ->
            TeeCluster(
                GeoPoint(group.map { it.second.latitude }.average(), group.map { it.second.longitude }.average()),
                group.map { it.first }
            )
        }
    }

    private fun plausibleLine(tee: GeoPoint, green: GeoPoint, fairways: List<CourseFeature>): Boolean =
        meters(tee, green) in MIN_HOLE_LENGTH_M..MAX_HOLE_LENGTH_M &&
            fairwaySupport(tee, green, fairways) >= MIN_FAIRWAY_SUPPORT

    private fun fairwaySupport(tee: GeoPoint, green: GeoPoint, fairways: List<CourseFeature>): Double {
        val samples = (0..20).map { step ->
            val t = step / 20.0
            GeoPoint(
                tee.latitude + (green.latitude - tee.latitude) * t,
                tee.longitude + (green.longitude - tee.longitude) * t
            )
        }
        return fairways.maxOfOrNull { feature ->
            val polygons = when (feature.geometryType) {
                CourseGeometryType.POLYGON -> listOf(feature.rings)
                CourseGeometryType.MULTI_POLYGON -> feature.polygons
                else -> emptyList()
            }
            if (polygons.isEmpty()) 0.0 else samples.count { point ->
                polygons.any { polygon -> pointInPolygon(point, polygon.firstOrNull().orEmpty()) }
            } / samples.size.toDouble()
        } ?: 0.0
    }

    private fun inferredFeature(number: Int, tee: GeoPoint, green: GeoPoint, confidence: Double, status: String) =
        CourseFeature(
            id = -9_000_000L - number, elementType = "generated", featureType = "hole",
            holeNumber = number.toString(), name = null, points = listOf(tee, green),
            geometryType = CourseGeometryType.LINE, geometryStatus = status,
            origin = CourseFeatureOrigin.INFERRED, confidence = confidence
        )

    private fun representativePoint(feature: CourseFeature): GeoPoint? = when (feature.geometryType) {
        CourseGeometryType.POINT -> feature.points.firstOrNull()
        CourseGeometryType.POLYGON -> ringCenter(feature.rings.firstOrNull())
        CourseGeometryType.MULTI_POLYGON -> ringCenter(feature.polygons.maxByOrNull { it.firstOrNull()?.size ?: 0 }?.firstOrNull())
        else -> null
    }

    private fun ringCenter(ring: List<GeoPoint>?): GeoPoint? {
        val points = ring?.dropLast(1)?.takeIf { it.isNotEmpty() } ?: return null
        return GeoPoint(points.map { it.latitude }.average(), points.map { it.longitude }.average())
    }

    private fun meters(a: GeoPoint, b: GeoPoint): Double {
        val lat = Math.toRadians((a.latitude + b.latitude) / 2.0)
        return hypot(Math.toRadians(b.longitude-a.longitude)*6371008.8*cos(lat), Math.toRadians(b.latitude-a.latitude)*6371008.8)
    }

    private fun pointInPolygon(point:GeoPoint,ring:List<GeoPoint>):Boolean {
        var inside=false
        for(i in 0 until ring.lastIndex) {
            val a=ring[i]; val b=ring[i+1]
            if((a.latitude>point.latitude)!=(b.latitude>point.latitude)) {
                val x=(b.longitude-a.longitude)*(point.latitude-a.latitude)/(b.latitude-a.latitude)+a.longitude
                if(point.longitude<x) inside=!inside
            }
        }
        return inside
    }
}
