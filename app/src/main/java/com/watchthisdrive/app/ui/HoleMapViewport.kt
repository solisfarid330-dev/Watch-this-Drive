package com.watchthisdrive.app.ui

import com.watchthisdrive.app.data.GeoPoint
import com.watchthisdrive.app.data.PinSource
import com.watchthisdrive.app.data.ReconstructedHole
import kotlin.math.*

/**
 * Camera framing for the active hole. Bearing is only applied when the reconstruction
 * has evidence for the green end; otherwise the map stays north-up rather than guessing.
 */
data class HoleMapViewport(
    val center: GeoPoint,
    val bearing: Double,
    val zoom: Double,
    val teeToGreenOriented: Boolean
) {
    companion object {
        fun from(hole: ReconstructedHole): HoleMapViewport? {
            val line = hole.centerline
            if (line.size < 2) return null

            val segmentLengths = line.zipWithNext { a, b -> distanceMeters(a, b) }
            val totalLength = segmentLengths.sum()
            if (!totalLength.isFinite() || totalLength <= 1.0) return null

            val center = pointAtDistance(line, segmentLengths, totalLength / 2.0)
            val hasGreenEndEvidence = hole.teeToGreenOriented
            val bearing = if (hasGreenEndEvidence) initialBearing(line.first(), line.last()) else 0.0
            // A typical golf hole should fill most, but not all, of a portrait map viewport.
            val zoom = (17.2 - log2(totalLength / 250.0)).coerceIn(14.0, 18.0)

            return HoleMapViewport(center, bearing, zoom, hasGreenEndEvidence)
        }

        private fun pointAtDistance(
            points: List<GeoPoint>,
            lengths: List<Double>,
            target: Double
        ): GeoPoint {
            var traversed = 0.0
            lengths.forEachIndexed { index, length ->
                if (traversed + length >= target && length > 0.0) {
                    val fraction = ((target - traversed) / length).coerceIn(0.0, 1.0)
                    val a = points[index]
                    val b = points[index + 1]
                    return GeoPoint(
                        a.latitude + (b.latitude - a.latitude) * fraction,
                        a.longitude + (b.longitude - a.longitude) * fraction
                    )
                }
                traversed += length
            }
            return points.last()
        }

        private fun initialBearing(from: GeoPoint, to: GeoPoint): Double {
            val lat1 = Math.toRadians(from.latitude)
            val lat2 = Math.toRadians(to.latitude)
            val deltaLon = Math.toRadians(to.longitude - from.longitude)
            val y = sin(deltaLon) * cos(lat2)
            val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
            return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        }

        private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
            val lat1 = Math.toRadians(a.latitude)
            val lat2 = Math.toRadians(b.latitude)
            val dLat = lat2 - lat1
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val h = sin(dLat / 2).pow(2) +
                cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
            return 2.0 * 6_371_008.8 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
    }
}
