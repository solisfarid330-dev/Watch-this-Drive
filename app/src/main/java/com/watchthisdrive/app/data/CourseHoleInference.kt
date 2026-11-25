package com.watchthisdrive.app.data

import kotlin.math.*

/** Conservative missing-hole inference from explicitly numbered tees, greens and a connecting mapped fairway. */
object CourseHoleInference {
    private const val MIN_HOLE_LENGTH_M = 60.0
    private const val MAX_HOLE_LENGTH_M = 550.0
    private const val MAX_FAIRWAY_OFFSET_M = 45.0

    fun addInferredHoles(course: CourseGeometry): CourseGeometry {
        val usable = course.features.filter { it.hasUsableGeometry }
        val mappedHoles = usable.filter { it.origin == CourseFeatureOrigin.SOURCE && it.featureType == "hole" && it.geometryType == CourseGeometryType.LINE }
        val numbered = mappedHoles.mapNotNull { it.holeNumber?.toIntOrNull() }.filter { it in 1..18 }.toSet()
        val maximum = numbered.maxOrNull() ?: return course
        val expectedCount = when { maximum > 9 -> 18; maximum >= 9 -> 9; else -> return course }
        val missing = (1..expectedCount).filterNot(numbered::contains)
        if (missing.isEmpty()) return course

        val tees = usable.filter { it.origin == CourseFeatureOrigin.SOURCE && it.featureType == "tee" }
        val greens = usable.filter { it.origin == CourseFeatureOrigin.SOURCE && it.featureType == "green" }
        val fairways = usable.filter { it.origin == CourseFeatureOrigin.SOURCE && it.featureType == "fairway" }
        if (tees.isEmpty() || greens.isEmpty() || fairways.isEmpty()) return course

        val inferred = missing.mapNotNull { number ->
            val candidatesTee = tees.filter { it.holeNumber?.toIntOrNull() == number }
            val candidatesGreen = greens.filter { it.holeNumber?.toIntOrNull() == number }
            if (candidatesTee.size != 1 || candidatesGreen.size != 1) return@mapNotNull null
            val tee = representativePoint(candidatesTee.single()) ?: return@mapNotNull null
            val green = representativePoint(candidatesGreen.single()) ?: return@mapNotNull null
            if (meters(tee, green) !in MIN_HOLE_LENGTH_M..MAX_HOLE_LENGTH_M) return@mapNotNull null
            val line = listOf(tee, green)
            if (fairways.none { distanceToFeature(line, it) <= MAX_FAIRWAY_OFFSET_M }) return@mapNotNull null
            if (mappedHoles.any { distanceToLine(tee, it.points) < 35.0 || distanceToLine(green, it.points) < 35.0 }) return@mapNotNull null
            CourseFeature(
                id = -9_000_000L - number, elementType = "generated", featureType = "hole",
                holeNumber = number.toString(), name = null, points = line,
                geometryType = CourseGeometryType.LINE,
                geometryStatus = "inferred_from_numbered_tee_green_and_fairway",
                origin = CourseFeatureOrigin.INFERRED, confidence = 0.65
            )
        }
        if (inferred.isEmpty()) return course
        val retained = course.features.filterNot { it.origin == CourseFeatureOrigin.INFERRED && it.featureType == "hole" }
        return course.copy(features = retained + inferred)
    }

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

    private fun distanceToFeature(line: List<GeoPoint>, feature: CourseFeature): Double {
        if (feature.geometryType == CourseGeometryType.POLYGON || feature.geometryType == CourseGeometryType.MULTI_POLYGON) {
            val polygons = if (feature.geometryType == CourseGeometryType.POLYGON) listOf(feature.rings) else feature.polygons
            if (line.any { p -> polygons.any { pointInPolygon(p, it.firstOrNull().orEmpty()) } }) return 0.0
            return polygons.flatMap { it }.flatten().minOfOrNull { distanceToLine(it, line) } ?: Double.POSITIVE_INFINITY
        }
        return minOf(distanceToLine(line.first(), feature.points), distanceToLine(line.last(), feature.points))
    }

    private fun distanceToLine(point: GeoPoint, line: List<GeoPoint>): Double {
        if (line.size < 2) return Double.POSITIVE_INFINITY
        return line.zipWithNext().minOf { (a,b) ->
            val lat=Math.toRadians((point.latitude+a.latitude+b.latitude)/3.0)
            fun xy(p:GeoPoint)=Pair(Math.toRadians(p.longitude)*6371008.8*cos(lat),Math.toRadians(p.latitude)*6371008.8)
            val p=xy(point); val aa=xy(a); val bb=xy(b); val dx=bb.first-aa.first; val dy=bb.second-aa.second
            val d=dx*dx+dy*dy
            val t=if(d==0.0) 0.0 else (((p.first-aa.first)*dx+(p.second-aa.second)*dy)/d).coerceIn(0.0,1.0)
            hypot(p.first-aa.first-t*dx,p.second-aa.second-t*dy)
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
