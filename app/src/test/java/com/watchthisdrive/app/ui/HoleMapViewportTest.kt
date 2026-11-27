package com.watchthisdrive.app.ui

import com.watchthisdrive.app.data.GeoPoint
import com.watchthisdrive.app.data.PinSource
import com.watchthisdrive.app.data.ReconstructedHole
import org.junit.Assert.*
import org.junit.Test

class HoleMapViewportTest {
    private fun hole(
        line: List<GeoPoint>,
        green: Boolean = true,
        pinSource: PinSource? = null
    ) = ReconstructedHole(
        number = "1",
        centerline = line,
        tee = null,
        green = if (green) greenFeature else null,
        pin = null,
        pinSource = pinSource,
        teeToGreenOriented = green,
        fairways = emptyList(),
        bunkers = emptyList(),
        waterHazards = emptyList()
    )

    @Test fun northboundHoleKeepsNorthUpAndCentersAlongItsLength() {
        val result = HoleMapViewport.from(hole(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.004, 0.0))))!!
        assertEquals(0.0, result.bearing, 0.01)
        assertEquals(0.002, result.center.latitude, 1e-6)
        assertTrue(result.teeToGreenOriented)
    }

    @Test fun eastboundHoleRotatesSoGreenEndPointsTowardTop() {
        val result = HoleMapViewport.from(hole(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.004))))!!
        assertEquals(90.0, result.bearing, 0.1)
        assertEquals(0.002, result.center.longitude, 1e-6)
    }

    @Test fun missingGreenDirectionEvidenceDoesNotGuessBearing() {
        val result = HoleMapViewport.from(
            hole(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.004, 0.004)), green = false)
        )!!
        assertEquals(0.0, result.bearing, 0.01)
        assertFalse(result.teeToGreenOriented)
    }

    @Test fun invalidCenterlineHasNoViewport() {
        assertNull(HoleMapViewport.from(hole(listOf(GeoPoint(0.0, 0.0)))))
    }

    private companion object {
        val greenFeature = com.watchthisdrive.app.data.CourseFeature(
            99L, "way", "green", "1", null,
            listOf(GeoPoint(0.001, 0.001)), com.watchthisdrive.app.data.CourseGeometryType.POINT
        )
    }
}
