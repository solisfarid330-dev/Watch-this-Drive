package com.watchthisdrive.app.data

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Measures whether NAIP NDVI pixels support an existing mapped polygon.
 *
 * This is deliberately an evidence scorer, not a semantic classifier: high
 * vegetation does not prove that a polygon is a fairway, and low vegetation
 * does not authorize moving its boundary. Geometry remains untouched.
 */
object NdviBoundaryAnalyzer {
    data class Result(
        val insideSamples: Int,
        val outsideSamples: Int,
        val insideVegetationFraction: Double,
        val outsideVegetationFraction: Double,
        val vegetationContrast: Double,
        val boundarySamples: Int,
        val boundaryVegetationContrast: Double,
        val boundarySupportFraction: Double,
        val supportScore: Double,
        val label: String
    )

    /**
     * pixels contains grayscale U8 NDVI values in ARGB form (R=G=B).
     * The export maps NDVI [-1,1] to [0,255].
     */
    fun evaluate(
        pixels: IntArray,
        width: Int,
        height: Int,
        bounds: NaipImageryRepository.Bounds,
        polygon: List<GeoPoint>,
        gridSize: Int = 28
    ): Result? {
        if (width < 2 || height < 2 || pixels.size < width * height ||
            polygon.size < 3 || gridSize < 8 ||
            !bounds.west.isFinite() || !bounds.east.isFinite() ||
            !bounds.south.isFinite() || !bounds.north.isFinite() ||
            bounds.east <= bounds.west || bounds.north <= bounds.south
        ) return null

        val midLat = Math.toRadians((bounds.north + bounds.south) / 2.0)
        val metersPerLon = 111_320.0 * cos(midLat).coerceAtLeast(0.1)
        val metersPerLat = 111_320.0
        val padLon = 18.0 / metersPerLon
        val padLat = 18.0 / metersPerLat
        val minLon = max(bounds.west, (polygon.minOf { it.longitude } - padLon))
        val maxLon = min(bounds.east, (polygon.maxOf { it.longitude } + padLon))
        val minLat = max(bounds.south, (polygon.minOf { it.latitude } - padLat))
        val maxLat = min(bounds.north, (polygon.maxOf { it.latitude } + padLat))
        if (maxLon <= minLon || maxLat <= minLat) return null

        var insideCount = 0
        var outsideCount = 0
        var insideVegetated = 0
        var outsideVegetated = 0
        // NDVI 0.25 corresponds to U8 ~159. The threshold is intentionally
        // modest: managed turf may be less vigorous than tree canopy.
        val vegetationThreshold = 159

        for (row in 0 until gridSize) {
            val latitude = maxLat - (row + 0.5) / gridSize * (maxLat - minLat)
            val py = (((bounds.north - latitude) / (bounds.north - bounds.south)) *
                (height - 1)).toInt().coerceIn(0, height - 1)
            for (column in 0 until gridSize) {
                val longitude = minLon + (column + 0.5) / gridSize * (maxLon - minLon)
                val px = (((longitude - bounds.west) / (bounds.east - bounds.west)) *
                    (width - 1)).toInt().coerceIn(0, width - 1)
                val inside = contains(polygon, longitude, latitude)
                val gray = (pixels[py * width + px] shr 16) and 0xff
                if (inside) {
                    insideCount++
                    if (gray >= vegetationThreshold) insideVegetated++
                } else {
                    outsideCount++
                    if (gray >= vegetationThreshold) outsideVegetated++
                }
            }
        }
        if (insideCount < 12 || outsideCount < 12) return null
        val insideFraction = insideVegetated.toDouble() / insideCount
        val outsideFraction = outsideVegetated.toDouble() / outsideCount
        val contrast = (insideFraction - outsideFraction).coerceIn(-1.0, 1.0)

        // Measure paired pixels immediately on either side of polygon edges.
        // This is more spatially specific than comparing the whole polygon
        // bounding box, whose corners may contain unrelated trees or rough.
        val edgeEvidence = evaluateBoundary(
            pixels, width, height, bounds, polygon, metersPerLon, metersPerLat
        )
        // Support rewards vegetation within the mapped feature and penalizes
        // equally green surroundings. It is a review signal, not a truth score.
        val boundarySupport = edgeEvidence?.positiveFraction ?: 0.5
        val boundaryContrast = edgeEvidence?.meanContrast ?: 0.0
        val score = (0.45 * insideFraction +
            0.25 * ((contrast + 1.0) / 2.0) +
            0.30 * boundarySupport).coerceIn(0.0, 1.0)
        val label = when {
            score >= 0.72 && contrast >= 0.12 &&
                (edgeEvidence == null || boundaryContrast >= 0.08) -> "strong"
            score >= 0.45 -> "mixed"
            else -> "weak"
        }
        return Result(
            insideSamples = insideCount,
            outsideSamples = outsideCount,
            insideVegetationFraction = insideFraction,
            outsideVegetationFraction = outsideFraction,
            vegetationContrast = contrast,
            boundarySamples = edgeEvidence?.samples ?: 0,
            boundaryVegetationContrast = boundaryContrast,
            boundarySupportFraction = boundarySupport,
            supportScore = score,
            label = label
        )
    }


    private data class EdgeEvidence(
        val samples: Int,
        val meanContrast: Double,
        val positiveFraction: Double
    )

    private fun evaluateBoundary(
        pixels: IntArray,
        width: Int,
        height: Int,
        bounds: NaipImageryRepository.Bounds,
        polygon: List<GeoPoint>,
        metersPerLon: Double,
        metersPerLat: Double
    ): EdgeEvidence? {
        // Signed area determines which side of each edge is interior.
        val signedArea = polygon.indices.sumOf { i ->
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            a.longitude * b.latitude - b.longitude * a.latitude
        }
        if (kotlin.math.abs(signedArea) < 1e-14) return null
        val ccw = signedArea > 0.0
        var samples = 0
        var contrastSum = 0.0
        var positive = 0
        val offsetMeters = 3.0
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            val dx = (b.longitude - a.longitude) * metersPerLon
            val dy = (b.latitude - a.latitude) * metersPerLat
            val length = kotlin.math.hypot(dx, dy)
            if (length < 2.0) continue
            val count = (length / 8.0).toInt().coerceIn(1, 24)
            // For CCW rings, the left normal points inward.
            val nx = if (ccw) -dy / length else dy / length
            val ny = if (ccw) dx / length else -dx / length
            for (step in 0 until count) {
                val t = (step + 0.5) / count
                val lon = a.longitude + (b.longitude - a.longitude) * t
                val lat = a.latitude + (b.latitude - a.latitude) * t
                val offsetLon = nx * offsetMeters / metersPerLon
                val offsetLat = ny * offsetMeters / metersPerLat
                val inside = sampleNdvi(pixels, width, height, bounds,
                    lon + offsetLon, lat + offsetLat) ?: continue
                val outside = sampleNdvi(pixels, width, height, bounds,
                    lon - offsetLon, lat - offsetLat) ?: continue
                val difference = inside - outside
                contrastSum += difference
                if (difference >= 0.08) positive++
                samples++
            }
        }
        if (samples < 4) return null
        return EdgeEvidence(samples, contrastSum / samples, positive.toDouble() / samples)
    }

    private fun sampleNdvi(
        pixels: IntArray,
        width: Int,
        height: Int,
        bounds: NaipImageryRepository.Bounds,
        longitude: Double,
        latitude: Double
    ): Double? {
        if (longitude !in bounds.west..bounds.east || latitude !in bounds.south..bounds.north) return null
        val x = (((longitude - bounds.west) / (bounds.east - bounds.west)) * (width - 1))
            .toInt().coerceIn(0, width - 1)
        val y = (((bounds.north - latitude) / (bounds.north - bounds.south)) * (height - 1))
            .toInt().coerceIn(0, height - 1)
        val gray = (pixels[y * width + x] shr 16) and 0xff
        return gray / 127.5 - 1.0
    }

    private fun contains(polygon: List<GeoPoint>, longitude: Double, latitude: Double): Boolean {
        var inside = false
        var previous = polygon.last()
        for (current in polygon) {
            val crosses = (current.latitude > latitude) != (previous.latitude > latitude) &&
                longitude < (previous.longitude - current.longitude) *
                (latitude - current.latitude) /
                ((previous.latitude - current.latitude).let { if (it == 0.0) 1e-12 else it }) +
                current.longitude
            if (crosses) inside = !inside
            previous = current
        }
        return inside
    }
}
