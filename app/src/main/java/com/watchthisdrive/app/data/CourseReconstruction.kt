package com.watchthisdrive.app.data

import kotlin.math.*

enum class PinSource { MAPPED, ESTIMATED_GREEN_CENTER }

data class ReconstructedHole(
    val number: String,
    val centerline: List<GeoPoint>,
    val tee: CourseFeature?,
    val green: CourseFeature?,
    val pin: GeoPoint?,
    val pinSource: PinSource?,
    val fairways: List<CourseFeature>,
    val bunkers: List<CourseFeature>,
    val waterHazards: List<CourseFeature>,
    val associationWarnings: List<String> = emptyList(),
    val par: Int? = null,
    val teeToGreenOriented: Boolean = false
)

data class CourseReconstruction(val holes: List<ReconstructedHole>) {
    val estimatedPinCount: Int get() = holes.count { it.pinSource == PinSource.ESTIMATED_GREEN_CENTER }
    val mappedPinCount: Int get() = holes.count { it.pinSource == PinSource.MAPPED }
}

object CourseReconstructionEngine {
    private const val TEE_GREEN_ENDPOINT_LIMIT_M = 30.0
    private const val PIN_ENDPOINT_LIMIT_M = 20.0
    private const val DIRECTION_MARGIN_M = 10.0
    private const val MARKER_UNIQUENESS_MARGIN_M = 5.0
    private const val CORRIDOR_LIMIT_M = 45.0
    private const val UNIQUE_MARGIN_M = 8.0

    fun reconstruct(course: CourseGeometry): CourseReconstruction {
        val features = course.features.filter { it.hasUsableGeometry }
        val holeFeatures = features.filter { it.featureType == "hole" && it.geometryType == CourseGeometryType.LINE && it.points.size >= 2 }
            .sortedWith(compareBy<CourseFeature> { it.holeNumber?.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.id })
        if (holeFeatures.isEmpty()) return CourseReconstruction(emptyList())

        data class Draft(
            val feature: CourseFeature,
            val line: List<GeoPoint>,
            val tee: CourseFeature?,
            val greenCandidate: Pair<CourseFeature, Double>?,
            val pinCandidate: Pair<CourseFeature, Double>?,
            val teeToGreenOriented: Boolean,
            var green: CourseFeature? = null,
            var mappedPin: CourseFeature? = null,
            var pin: GeoPoint? = null,
            var pinSource: PinSource? = null,
            val warnings: MutableList<String> = mutableListOf()
        )

        // A tee marker should belong to one hole endpoint only. Greedily assign
        // the closest endpoint globally so adjacent holes cannot silently reuse it.
        data class TeeMatch(
            val holeIndex: Int,
            val isStart: Boolean,
            val feature: CourseFeature,
            val distance: Double
        )
        val teeFeatures = features.filter { it.featureType == "tee" }
        val teeMatches = mutableListOf<TeeMatch>()
        holeFeatures.forEachIndexed { index, hole ->
            listOf(true to hole.points.first(), false to hole.points.last()).forEach { (isStart, point) ->
                teeFeatures.forEach { tee ->
                    val distance = featureDistance(point, tee)
                    if (distance.isFinite() && distance <= TEE_GREEN_ENDPOINT_LIMIT_M) {
                        teeMatches.add(TeeMatch(index, isStart, tee, distance))
                    }
                }
            }
        }
        val teeAssociations = mutableMapOf<Pair<Int, Boolean>, Pair<CourseFeature, Double>>()
        val usedTeeMarkers = mutableSetOf<Pair<String, Long>>()
        teeMatches.sortedWith(compareBy<TeeMatch> { it.distance }.thenBy { it.holeIndex }).forEach { match ->
            val slot = match.holeIndex to match.isStart
            val markerKey = match.feature.elementType to match.feature.id
            if (slot !in teeAssociations && usedTeeMarkers.add(markerKey)) {
                teeAssociations[slot] = match.feature to match.distance
            }
        }

        val greens = features.filter { it.featureType == "green" }
        val pins = features.filter { it.featureType == "pin" }
        val drafts = holeFeatures.mapIndexed { index, hole ->
            var line = hole.points
            var lineReversed = false
            var teeToGreenOriented = false
            val start = line.first()
            val end = line.last()
            val startTee = teeAssociations[index to true]
            val endTee = teeAssociations[index to false]
            val startGreen = nearestFeature(start, greens)?.takeIf { it.second <= TEE_GREEN_ENDPOINT_LIMIT_M }
            val endGreen = nearestFeature(end, greens)?.takeIf { it.second <= TEE_GREEN_ENDPOINT_LIMIT_M }
            val startPin = nearestFeature(start, pins)?.takeIf { it.second <= PIN_ENDPOINT_LIMIT_M }
            val endPin = nearestFeature(end, pins)?.takeIf { it.second <= PIN_ENDPOINT_LIMIT_M }

            // Orient the centerline from tee toward green when endpoint evidence supports it.
            val forwardParts = listOfNotNull(startTee?.second, endGreen?.second)
            val reverseParts = listOfNotNull(endTee?.second, startGreen?.second)
            val forward = if (forwardParts.size == 2) forwardParts.sum() else Double.POSITIVE_INFINITY
            val reverse = if (reverseParts.size == 2) reverseParts.sum() else Double.POSITIVE_INFINITY
            if (reverse.isFinite() && !forward.isFinite()) {
                val forwardUsingReverseMarkers =
                    featureDistance(start, endTee!!.first) + featureDistance(end, startGreen!!.first)
                if (forwardUsingReverseMarkers - reverse >= DIRECTION_MARGIN_M) {
                    line = line.asReversed()
                    lineReversed = true
                    teeToGreenOriented = true
                }
            } else if (forward.isFinite() && !reverse.isFinite()) {
                val reverseUsingForwardMarkers =
                    featureDistance(end, startTee!!.first) + featureDistance(start, endGreen!!.first)
                if (reverseUsingForwardMarkers - forward >= DIRECTION_MARGIN_M) {
                    teeToGreenOriented = true
                }
            } else if (forward.isFinite() && reverse.isFinite() && abs(forward - reverse) >= DIRECTION_MARGIN_M) {
                if (reverse < forward) {
                    line = line.asReversed()
                    lineReversed = true
                }
                teeToGreenOriented = true
            } else if (!forward.isFinite() && !reverse.isFinite()) {
                // Single-end evidence must be clearly closer to one endpoint.
                when {
                    startPin != null && endPin == null &&
                        featureDistance(end, startPin.first) - startPin.second >= DIRECTION_MARGIN_M -> {
                        line = line.asReversed()
                        lineReversed = true
                        teeToGreenOriented = true
                    }
                    endPin != null && startPin == null &&
                        featureDistance(start, endPin.first) - endPin.second >= DIRECTION_MARGIN_M ->
                        teeToGreenOriented = true
                    startGreen != null && endGreen == null &&
                        featureDistance(end, startGreen.first) - startGreen.second >= DIRECTION_MARGIN_M -> {
                        line = line.asReversed()
                        lineReversed = true
                        teeToGreenOriented = true
                    }
                    endGreen != null && startGreen == null &&
                        featureDistance(start, endGreen.first) - endGreen.second >= DIRECTION_MARGIN_M ->
                        teeToGreenOriented = true
                    startTee != null && endTee == null &&
                        featureDistance(end, startTee.first) - startTee.second >= DIRECTION_MARGIN_M ->
                        teeToGreenOriented = true
                    endTee != null && startTee == null &&
                        featureDistance(start, endTee.first) - endTee.second >= DIRECTION_MARGIN_M -> {
                        line = line.asReversed()
                        lineReversed = true
                        teeToGreenOriented = true
                    }
                }
            }

            val tee = (if (lineReversed) endTee else startTee)?.first
            val teeEndpoint = line.first()
            val greenEndpoint = line.last()
            val greenCandidate = if (teeToGreenOriented) {
                nearestFeature(greenEndpoint, greens)?.takeIf {
                    it.second <= TEE_GREEN_ENDPOINT_LIMIT_M &&
                        featureDistance(teeEndpoint, it.first) - it.second >= DIRECTION_MARGIN_M
                }
            } else null
            val pinCandidate = if (teeToGreenOriented) {
                nearestFeature(greenEndpoint, pins)?.takeIf {
                    it.second <= PIN_ENDPOINT_LIMIT_M &&
                        featureDistance(teeEndpoint, it.first) - it.second >= DIRECTION_MARGIN_M
                }
            } else null

            Draft(hole, line, tee, greenCandidate, pinCandidate, teeToGreenOriented).also { draft ->
                if (tee == null) draft.warnings.add("No confident tee association")
            }
        }

        // A feature that is equally close to neighboring hole ends stays ambiguous.
        // This deliberately avoids claiming a shared green or pin for multiple holes.
        data class MarkerOwner(val draft: Draft, val feature: CourseFeature, val distance: Double)
        fun applyUniqueMarkerAssignments(
            candidate: (Draft) -> Pair<CourseFeature, Double>?,
            assign: (Draft, CourseFeature) -> Unit,
            label: String
        ) {
            val grouped = mutableMapOf<Pair<String, Long>, MutableList<MarkerOwner>>()
            drafts.forEach { draft ->
                candidate(draft)?.let { (feature, distance) ->
                    val key = feature.elementType to feature.id
                    grouped.getOrPut(key) { mutableListOf() }.add(MarkerOwner(draft, feature, distance))
                }
            }
            grouped.values.forEach { owners ->
                val sorted = owners.sortedBy { it.distance }
                if (sorted.size == 1) {
                    assign(sorted.first().draft, sorted.first().feature)
                } else {
                    val margin = sorted[1].distance - sorted[0].distance
                    if (margin >= MARKER_UNIQUENESS_MARGIN_M) {
                        assign(sorted.first().draft, sorted.first().feature)
                        sorted.drop(1).forEach {
                            it.draft.warnings.add("$label candidate is closer to another hole")
                        }
                    } else {
                        sorted.forEach {
                            it.draft.warnings.add("$label association ambiguous across nearby holes")
                        }
                    }
                }
            }
        }

        applyUniqueMarkerAssignments({ it.greenCandidate }, { draft, feature -> draft.green = feature }, "Green")
        applyUniqueMarkerAssignments({ it.pinCandidate }, { draft, feature -> draft.mappedPin = feature }, "Pin")
        drafts.forEach { draft ->
            draft.pin = draft.mappedPin?.let { representativePoint(it) }
                ?: draft.green?.let { polygonCenter(it) }
            draft.pinSource = when {
                draft.mappedPin != null && draft.pin != null -> PinSource.MAPPED
                draft.green != null && draft.pin != null -> PinSource.ESTIMATED_GREEN_CENTER
                else -> null
            }
            if (draft.green == null) draft.warnings.add("No confident green association")
            if (draft.pin == null) draft.warnings.add("No pin position available")
        }

        val corridorTypes = setOf("fairway", "bunker", "water_hazard")
        val assigned = drafts.map { mutableMapOf<String, MutableList<CourseFeature>>() }
        val unassigned = mutableSetOf<Pair<String, Long>>()
        features.filter { it.featureType in corridorTypes }.forEach { candidate ->
            val distances = drafts.mapIndexed { index, draft -> index to featureToLineDistance(candidate, draft.line) }
                .filter { it.second.isFinite() && it.second <= CORRIDOR_LIMIT_M }
                .sortedBy { it.second }
            if (distances.isEmpty()) return@forEach
            val best = distances[0]
            val second = distances.getOrNull(1)
            if (second != null && second.second - best.second < UNIQUE_MARGIN_M) {
                unassigned.add(candidate.elementType to candidate.id)
            } else {
                assigned[best.first].getOrPut(candidate.featureType) { mutableListOf() }.add(candidate)
            }
        }

        val holes = drafts.mapIndexed { index, draft ->
            ReconstructedHole(
                number = draft.feature.holeNumber ?: (index + 1).toString(),
                centerline = draft.line,
                tee = draft.tee,
                green = draft.green,
                pin = draft.pin,
                pinSource = draft.pinSource,
                fairways = assigned[index]["fairway"].orEmpty(),
                bunkers = assigned[index]["bunker"].orEmpty(),
                waterHazards = assigned[index]["water_hazard"].orEmpty(),
                associationWarnings = draft.warnings + if (unassigned.isNotEmpty()) listOf("${unassigned.size} nearby hazard/fairway features remain ambiguous course-wide") else emptyList(),
                par = draft.feature.par,
                teeToGreenOriented = draft.teeToGreenOriented
            )
        }
        return CourseReconstruction(holes)
    }

    private fun nearestFeature(point: GeoPoint, candidates: List<CourseFeature>): Pair<CourseFeature, Double>? =
        candidates.mapNotNull { feature ->
            val distance = featureDistance(point, feature)
            if (distance.isFinite()) feature to distance else null
        }.minByOrNull { it.second }

    private fun featureDistance(point: GeoPoint, feature: CourseFeature): Double {
        val points = featureCoordinates(feature)
        return when (feature.geometryType) {
            CourseGeometryType.POINT -> points.firstOrNull()?.let { meters(point, it) } ?: Double.POSITIVE_INFINITY
            CourseGeometryType.LINE -> lineDistance(point, points)
            CourseGeometryType.POLYGON, CourseGeometryType.MULTI_POLYGON -> {
                val polygons = if (feature.geometryType == CourseGeometryType.POLYGON) listOf(feature.rings) else feature.polygons
                if (polygons.any { pointInPolygon(point, it) }) 0.0
                else polygons.flatMap { it }.minOfOrNull { ring -> lineDistance(point, ring) } ?: Double.POSITIVE_INFINITY
            }
            CourseGeometryType.UNKNOWN -> Double.POSITIVE_INFINITY
        }
    }

    private fun featureToLineDistance(feature: CourseFeature, line: List<GeoPoint>): Double {
        if (line.size < 2) return Double.POSITIVE_INFINITY
        return when (feature.geometryType) {
            CourseGeometryType.POINT ->
                feature.points.firstOrNull()?.let { lineDistance(it, line) } ?: Double.POSITIVE_INFINITY
            CourseGeometryType.LINE -> {
                if (feature.points.size < 2) Double.POSITIVE_INFINITY
                else lineToLineDistance(feature.points, line)
            }
            CourseGeometryType.POLYGON -> polygonToLineDistance(feature.rings, line)
            CourseGeometryType.MULTI_POLYGON ->
                feature.polygons.minOfOrNull { polygonToLineDistance(it, line) } ?: Double.POSITIVE_INFINITY
            CourseGeometryType.UNKNOWN -> Double.POSITIVE_INFINITY
        }
    }

    private fun polygonToLineDistance(rings: List<List<GeoPoint>>, line: List<GeoPoint>): Double {
        if (rings.isEmpty()) return Double.POSITIVE_INFINITY
        if (line.any { pointInPolygon(it, rings) }) return 0.0
        var best = Double.POSITIVE_INFINITY
        for ((a, b) in line.zipWithNext()) {
            for (ring in rings) {
                for ((c, d) in ring.zipWithNext()) {
                    best = min(best, segmentSegmentMeters(a, b, c, d))
                    if (best == 0.0) return 0.0
                }
            }
        }
        return best
    }

    private fun lineToLineDistance(first: List<GeoPoint>, second: List<GeoPoint>): Double {
        var best = Double.POSITIVE_INFINITY
        for ((a, b) in first.zipWithNext()) {
            for ((c, d) in second.zipWithNext()) {
                best = min(best, segmentSegmentMeters(a, b, c, d))
                if (best == 0.0) return 0.0
            }
        }
        return best
    }

    // Segment-to-segment distance is required here: checking only vertices misses
    // a fairway or hazard polygon crossed between sparse hole-centerline vertices.
    private fun segmentSegmentMeters(a: GeoPoint, b: GeoPoint, c: GeoPoint, d: GeoPoint): Double {
        val latitude0 = Math.toRadians((a.latitude + b.latitude + c.latitude + d.latitude) / 4.0)
        fun project(point: GeoPoint): Pair<Double, Double> = Pair(
            Math.toRadians(point.longitude) * 6371008.8 * cos(latitude0),
            Math.toRadians(point.latitude) * 6371008.8
        )
        val pa = project(a)
        val pb = project(b)
        val pc = project(c)
        val pd = project(d)

        fun cross(p: Pair<Double, Double>, q: Pair<Double, Double>, r: Pair<Double, Double>): Double =
            (q.first - p.first) * (r.second - p.second) -
                (q.second - p.second) * (r.first - p.first)

        fun onSegment(p: Pair<Double, Double>, q: Pair<Double, Double>, r: Pair<Double, Double>): Boolean =
            abs(cross(p, q, r)) <= 1e-7 &&
                r.first >= min(p.first, q.first) - 1e-7 &&
                r.first <= max(p.first, q.first) + 1e-7 &&
                r.second >= min(p.second, q.second) - 1e-7 &&
                r.second <= max(p.second, q.second) + 1e-7

        val o1 = cross(pa, pb, pc)
        val o2 = cross(pa, pb, pd)
        val o3 = cross(pc, pd, pa)
        val o4 = cross(pc, pd, pb)
        val properIntersection =
            ((o1 > 1e-7 && o2 < -1e-7) || (o1 < -1e-7 && o2 > 1e-7)) &&
                ((o3 > 1e-7 && o4 < -1e-7) || (o3 < -1e-7 && o4 > 1e-7))
        if (properIntersection || onSegment(pa, pb, pc) || onSegment(pa, pb, pd) ||
            onSegment(pc, pd, pa) || onSegment(pc, pd, pb)) return 0.0

        fun pointSegmentDistance(
            point: Pair<Double, Double>,
            start: Pair<Double, Double>,
            end: Pair<Double, Double>
        ): Double {
            val dx = end.first - start.first
            val dy = end.second - start.second
            val denominator = dx * dx + dy * dy
            val t = if (denominator == 0.0) 0.0 else
                (((point.first - start.first) * dx + (point.second - start.second) * dy) / denominator)
                    .coerceIn(0.0, 1.0)
            return hypot(point.first - start.first - t * dx, point.second - start.second - t * dy)
        }

        return minOf(
            pointSegmentDistance(pa, pc, pd),
            pointSegmentDistance(pb, pc, pd),
            pointSegmentDistance(pc, pa, pb),
            pointSegmentDistance(pd, pa, pb)
        )
    }

    private fun featureCoordinates(feature: CourseFeature): List<GeoPoint> = when (feature.geometryType) {
        CourseGeometryType.POINT, CourseGeometryType.LINE -> feature.points
        CourseGeometryType.POLYGON -> feature.rings.flatten()
        CourseGeometryType.MULTI_POLYGON -> feature.polygons.flatten().flatten()
        CourseGeometryType.UNKNOWN -> emptyList()
    }

    private fun representativePoint(feature: CourseFeature): GeoPoint? = when (feature.geometryType) {
        CourseGeometryType.POINT, CourseGeometryType.LINE -> feature.points.firstOrNull()
        CourseGeometryType.POLYGON, CourseGeometryType.MULTI_POLYGON -> polygonCenter(feature)
        CourseGeometryType.UNKNOWN -> null
    }

    private fun polygonCenter(feature: CourseFeature): GeoPoint? {
        val polygons = if (feature.geometryType == CourseGeometryType.POLYGON) listOf(feature.rings) else feature.polygons
        // Select the largest net polygon component, not the average of disconnected green pieces.
        val primary = polygons.mapNotNull { rings ->
            polygonCentroid(rings)?.let { (center, area) -> Triple(rings, center, area) }
        }.maxByOrNull { it.third } ?: return null
        val (rings, centroid, _) = primary
        if (pointInPolygon(centroid, rings)) return centroid

        // A concave green can have its area centroid outside. Find a safe interior point.
        val outer = rings.firstOrNull() ?: return null
        val minLat = outer.minOf { it.latitude }; val maxLat = outer.maxOf { it.latitude }
        val minLon = outer.minOf { it.longitude }; val maxLon = outer.maxOf { it.longitude }
        var best: GeoPoint? = null
        var bestDistance = Double.POSITIVE_INFINITY
        for (y in 1..60) for (x in 1..60) {
            val p = GeoPoint(minLat + (maxLat - minLat) * y / 61.0, minLon + (maxLon - minLon) * x / 61.0)
            if (pointInPolygon(p, rings)) {
                val d = meters(p, centroid)
                if (d < bestDistance) { best = p; bestDistance = d }
            }
        }
        return best
    }

    // Area-weighted centroid for the outer ring minus interior rings (holes).
    private fun polygonCentroid(rings: List<List<GeoPoint>>): Pair<GeoPoint, Double>? {
        if (rings.isEmpty()) return null
        var totalArea = 0.0
        var weightedLon = 0.0
        var weightedLat = 0.0
        rings.forEachIndexed { index, ring ->
            val centroid = ringCentroid(ring) ?: return@forEachIndexed
            val area = abs(signedArea(ring))
            val signedWeight = if (index == 0) area else -area
            totalArea += signedWeight
            weightedLon += centroid.longitude * signedWeight
            weightedLat += centroid.latitude * signedWeight
        }
        if (totalArea <= 1e-14) return null
        return GeoPoint(weightedLat / totalArea, weightedLon / totalArea) to totalArea
    }

    private fun ringCentroid(ring: List<GeoPoint>): GeoPoint? {
        var twiceArea = 0.0; var cx = 0.0; var cy = 0.0
        for (i in 0 until ring.lastIndex) {
            val cross = ring[i].longitude * ring[i + 1].latitude - ring[i + 1].longitude * ring[i].latitude
            twiceArea += cross
            cx += (ring[i].longitude + ring[i + 1].longitude) * cross
            cy += (ring[i].latitude + ring[i + 1].latitude) * cross
        }
        if (abs(twiceArea) < 1e-14) return null
        return GeoPoint(cy / (3.0 * twiceArea), cx / (3.0 * twiceArea))
    }

    private fun signedArea(ring: List<GeoPoint>): Double {
        var sum = 0.0
        for (i in 0 until ring.lastIndex) sum += ring[i].longitude * ring[i + 1].latitude - ring[i + 1].longitude * ring[i].latitude
        return sum / 2.0
    }

    private fun pointInPolygon(point: GeoPoint, rings: List<List<GeoPoint>>): Boolean =
        rings.isNotEmpty() && pointInRing(point, rings.first()) && rings.drop(1).none { pointInRing(point, it) }

    private fun pointInRing(point: GeoPoint, ring: List<GeoPoint>): Boolean {
        var inside = false
        for (i in 0 until ring.lastIndex) {
            val a = ring[i]; val b = ring[i + 1]
            if ((a.latitude > point.latitude) != (b.latitude > point.latitude)) {
                val crossing = (b.longitude - a.longitude) * (point.latitude - a.latitude) / (b.latitude - a.latitude) + a.longitude
                if (point.longitude < crossing) inside = !inside
            }
        }
        return inside
    }

    private fun lineDistance(point: GeoPoint, line: List<GeoPoint>): Double {
        if (line.isEmpty()) return Double.POSITIVE_INFINITY
        if (line.size == 1) return meters(point, line.first())
        return line.zipWithNext().minOf { (a, b) -> pointSegmentMeters(point, a, b) }
    }

    private fun pointSegmentMeters(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val lat0 = Math.toRadians((p.latitude + a.latitude + b.latitude) / 3.0)
        fun xy(q: GeoPoint) = Pair(Math.toRadians(q.longitude) * 6371008.8 * cos(lat0), Math.toRadians(q.latitude) * 6371008.8)
        val pp = xy(p); val aa = xy(a); val bb = xy(b)
        val dx = bb.first - aa.first; val dy = bb.second - aa.second
        val denom = dx * dx + dy * dy
        val t = if (denom == 0.0) 0.0 else (((pp.first - aa.first) * dx + (pp.second - aa.second) * dy) / denom).coerceIn(0.0, 1.0)
        return hypot(pp.first - aa.first - t * dx, pp.second - aa.second - t * dy)
    }

    private fun meters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude); val lat2 = Math.toRadians(b.latitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * 6371008.8 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
