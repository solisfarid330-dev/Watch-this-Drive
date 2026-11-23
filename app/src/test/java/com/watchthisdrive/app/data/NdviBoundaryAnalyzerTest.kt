package com.watchthisdrive.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NdviBoundaryAnalyzerTest {
    private val bounds = NaipImageryRepository.Bounds(
        west = -97.001, south = 32.999, east = -96.999, north = 33.001
    )
    private val polygon = listOf(
        GeoPoint(33.0007, -97.0007),
        GeoPoint(33.0007, -96.9993),
        GeoPoint(32.9993, -96.9993),
        GeoPoint(32.9993, -97.0007)
    )

    @Test
    fun detectsVegetatedMappedPolygonAgainstLessVegetatedSurroundings() {
        val width = 120
        val height = 120
        val pixels = IntArray(width * height) { 0xff505050.toInt() }
        for (y in 0 until height) for (x in 0 until width) {
            val longitude = bounds.west + x.toDouble() / (width - 1) * (bounds.east - bounds.west)
            val latitude = bounds.north - y.toDouble() / (height - 1) * (bounds.north - bounds.south)
            if (longitude in -97.0007..-96.9993 && latitude in 32.9993..33.0007) {
                pixels[y * width + x] = 0xffd8d8d8.toInt()
            }
        }
        val result = NdviBoundaryAnalyzer.evaluate(pixels, width, height, bounds, polygon)
        assertNotNull(result)
        assertTrue(result!!.insideVegetationFraction > 0.85)
        assertTrue(result.outsideVegetationFraction < 0.2)
        assertTrue(result.vegetationContrast > 0.6)
        assertEquals("strong", result.label)
    }

    @Test
    fun reportsMixedEvidenceWhenWholeAreaIsEquallyVegetated() {
        val pixels = IntArray(120 * 120) { 0xffc8c8c8.toInt() }
        val result = NdviBoundaryAnalyzer.evaluate(pixels, 120, 120, bounds, polygon)
        assertNotNull(result)
        assertTrue(kotlin.math.abs(result!!.vegetationContrast) < 0.02)
        assertEquals("mixed", result.label)
    }

    @Test
    fun rejectsDegenerateGeometryAndInsufficientImageData() {
        assertNull(NdviBoundaryAnalyzer.evaluate(
            IntArray(10), 10, 10, bounds, polygon
        ))
        assertNull(NdviBoundaryAnalyzer.evaluate(
            IntArray(120 * 120), 120, 120, bounds, listOf(polygon.first())
        ))
    }
}
