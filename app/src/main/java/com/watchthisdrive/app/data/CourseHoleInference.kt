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
        val maximum = numbered.maxOrNull() ?: 0
        if (numbered.isEmpty() && course.expectedHoleCount == null) return course
        val expectedCount = course.expectedHoleCount?.takeIf { it == 9 || it == 18 }
            ?: when { maximum > 9 -> 18; maximum >= 9 -> 9; else -> return course }
        val missing = (1..expectedCount).filterNot(numbered::contains)
        if (missing.isEmpty()) return course

        val tees = usable.filter { it.origin == CourseFeatureOrigin.SOURCE && it.featureType == "tee" }
        val greens = usable.filter { it.origin == CourseFeatureOrigin.SOURCE && it.featureType == "green" }
        val fairways = usable.filter { it.origin == CourseFeatureOrigin.SOURCE && it.featureType == "fairway" }
        if (tees.isEmpty() || greens.isEmpty()) return course

        val explicit = missing.mapNotNull { number ->
            val matchingTees = tees.filter { it.holeNumber?.toIntOrNull() == number }
            val matchingGreens = greens.filter { it.holeNumber?.toIntOrNull() == number }
            if (matchingTees.size != 1 || matchingGreens.size != 1) return@mapNotNull null
            val tee = representativePoint(matchingTees.single()) ?: return@mapNotNull null
            val green = representativePoint(matchingGreens.single()) ?: return@mapNotNull null
            if (!plausibleLine(tee, green, fairways)) return@mapNotNull null
            val hasFairwayEvidence = fairways.isNotEmpty()
            inferredFeature(
                number, tee, green,
                if (hasFairwayEvidence) 0.65 else 0.45,
                if (hasFairwayEvidence) "inferred_from_numbered_tee_green_and_fairway"
                else "inferred_from_numbered_tee_green_without_fairway"
            )
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
        // The sequence is only inferable as a run bounded by known holes.
        if (missing.size !in 1..2 || missing.zipWithNext().any { (a, b) -> b != a + 1 }) return emptyList()
        val tees = allTees.filter { it.holeNumber.isNullOrBlank() }
        val greens = allGreens.filter { it.holeNumber.isNullOrBlank() }
        val clusters = clusterTees(tees)
        if (clusters.isEmpty()) return emptyList()
        val greenPoints = greens.mapNotNull { feature -> representativePoint(feature)?.let { feature to it } }
        if (greenPoints.isEmpty()) return emptyList()

        val beforeHole = mappedHoles.firstOrNull { it.holeNumber?.toIntOrNull() == missing.first() - 1 }
        val afterHole = mappedHoles.firstOrNull { it.holeNumber?.toIntOrNull() == missing.last() + 1 }
        val before = beforeHole?.let { orientMappedHole(it, clusters, greenPoints)?.green }
        val after = afterHole?.let { orientMappedHole(it, clusters, greenPoints)?.tee }
        if (before == null && after == null) return emptyList()

        val usedTeeClusters = mutableSetOf<TeeCluster>()
        val usedGreens = mutableSetOf<CourseFeature>()
        mappedHoles.forEach { hole ->
            val anchors = orientMappedHole(hole, clusters, greenPoints) ?: return@forEach
            clusters.filter { meters(it.point, anchors.tee) <= MARKER_MATCH_LIMIT_M }.forEach(usedTeeClusters::add)
            greenPoints.filter { meters(it.second, anchors.green) <= MARKER_MATCH_LIMIT_M }.forEach { usedGreens.add(it.first) }
        }
        val availableTees = clusters.filterNot { it in usedTeeClusters }
        val availableGreens = greenPoints.filterNot { it.first in usedGreens }
        if (availableTees.size < missing.size || availableGreens.size < missing.size) return emptyList()

        val candidates = mutableListOf<Candidate>()
        availableTees.forEach { tee ->
            availableGreens.forEach greenLoop@{ (greenFeature, greenPoint) ->
                val length = meters(tee.point, greenPoint)
                if (length !in MIN_HOLE_LENGTH_M..MAX_HOLE_LENGTH_M) return@greenLoop
                val support = fairwaySupport(tee.point, greenPoint, fairways)
                val score = abs(length - 250.0) * 0.05 + (1.0 - support) * 80.0
                candidates.add(Candidate(tee, greenFeature, greenPoint, score))
            }
        }
        if (candidates.isEmpty()) return emptyList()

        data class Ordered(val candidates: List<Candidate>, val score: Double)
        val orderedOptions = mutableListOf<Ordered>()
        if (missing.size == 1) {
            candidates.forEach { candidate ->
                val firstLeg = before?.let { meters(it, candidate.tee.point) } ?: 0.0
                val lastLeg = after?.let { meters(candidate.greenPoint, it) } ?: 0.0
                if ((before != null && firstLeg > 350.0) || (after != null && lastLeg > 350.0)) return@forEach
                orderedOptions.add(Ordered(listOf(candidate), candidate.score + (firstLeg + lastLeg) * 0.8))
            }
        } else {
            candidates.forEach { first ->
                candidates.forEach secondLoop@{ second ->
                    if (first.tee == second.tee || first.green.elementType == second.green.elementType && first.green.id == second.green.id) return@secondLoop
                    val firstLeg = before?.let { meters(it, first.tee.point) } ?: 0.0
                    val middleLeg = meters(first.greenPoint, second.tee.point)
                    val lastLeg = after?.let { meters(second.greenPoint, it) } ?: 0.0
                    if ((before != null && firstLeg > 350.0) || middleLeg > 350.0 ||
                        (after != null && lastLeg > 350.0)) return@secondLoop
                    orderedOptions.add(
                        Ordered(listOf(first, second),
                            first.score + second.score + (firstLeg + middleLeg + lastLeg) * 0.8)
                    )
                }
            }
        }
        val ranked = orderedOptions.sortedBy { it.score }
        if (ranked.isEmpty()) return emptyList()
        if (ranked.getOrNull(1)?.score?.minus(ranked.first().score)?.let { it < PAIR_AMBIGUITY_MARGIN } == true) return emptyList()
        return missing.zip(ranked.first().candidates).map { (number, candidate) ->
            val supported = fairwaySupport(candidate.tee.point, candidate.greenPoint, fairways) >= MIN_FAIRWAY_SUPPORT
            inferredFeature(
                number, candidate.tee.point, candidate.greenPoint,
                if (supported) 0.50 else 0.35,
                if (supported) "inferred_from_unmatched_tee_green_fairway_and_hole_sequence"
                else "inferred_from_unmatched_tee_green_and_neighboring_hole_sequence"
            )
        }
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
            (fairways.isEmpty() || fairwaySupport(tee, green, fairways) >= MIN_FAIRWAY_SUPPORT)

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
                polygons.any { polygon ->
                    val outer = polygon.firstOrNull().orEmpty()
                    pointInPolygon(point, outer) || distanceToLine(point, outer) <= 35.0
                }
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


    private fun distanceToLine(point: GeoPoint, line: List<GeoPoint>): Double {
        if (line.size < 2) return Double.POSITIVE_INFINITY
        return line.zipWithNext().minOf { (a, b) ->
            val latitude = Math.toRadians((point.latitude + a.latitude + b.latitude) / 3.0)
            fun xy(p: GeoPoint) = Pair(
                Math.toRadians(p.longitude) * 6371008.8 * cos(latitude),
                Math.toRadians(p.latitude) * 6371008.8
            )
            val p = xy(point); val start = xy(a); val end = xy(b)
            val dx = end.first - start.first; val dy = end.second - start.second
            val denominator = dx * dx + dy * dy
            val t = if (denominator == 0.0) 0.0 else
                (((p.first - start.first) * dx + (p.second - start.second) * dy) / denominator).coerceIn(0.0, 1.0)
            hypot(p.first - start.first - t * dx, p.second - start.second - t * dy)
        }
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
