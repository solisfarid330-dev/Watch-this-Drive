package com.watchthisdrive.app.data

import org.junit.Assert.*
import org.junit.Test

class CourseReconstructionEngineTest {
    private fun polygon(id: Long, type: String, ring: List<GeoPoint>) = CourseFeature(
        id, "way", type, "1", null, ring, CourseGeometryType.POLYGON, listOf(ring), geometryStatus = "source_geometry"
    )

    @Test fun estimatesPinAtGreenAreaCenterWhenNoMappedPinExists() {
        val green = polygon(2, "green", listOf(
            GeoPoint(0.001, 0.001), GeoPoint(0.001, 0.003), GeoPoint(0.003, 0.003),
            GeoPoint(0.003, 0.001), GeoPoint(0.001, 0.001)
        ))
        val hole = CourseFeature(1, "way", "hole", "1", null,
            listOf(GeoPoint(0.0, 0.002), GeoPoint(0.0032, 0.002)), CourseGeometryType.LINE)
        val result = CourseReconstructionEngine.reconstruct(CourseGeometry(10, "Test", listOf(hole, green)))
        val reconstructed = result.holes.single()
        assertEquals(PinSource.ESTIMATED_GREEN_CENTER, reconstructed.pinSource)
        assertNotNull(reconstructed.pin)
        assertEquals(0.002, reconstructed.pin!!.latitude, 1e-7)
        assertEquals(0.002, reconstructed.pin!!.longitude, 1e-7)
    }

    @Test fun mappedPinTakesPrecedenceOverEstimatedCenter() {
        val green = polygon(2, "green", listOf(
            GeoPoint(0.001, 0.001), GeoPoint(0.001, 0.003), GeoPoint(0.003, 0.003),
            GeoPoint(0.003, 0.001), GeoPoint(0.001, 0.001)
        ))
        val pin = CourseFeature(3, "node", "pin", "1", null, listOf(GeoPoint(0.0025, 0.0025)), CourseGeometryType.POINT)
        val hole = CourseFeature(1, "way", "hole", "1", null,
            listOf(GeoPoint(0.0, 0.002), GeoPoint(0.0025, 0.0025)), CourseGeometryType.LINE)
        val result = CourseReconstructionEngine.reconstruct(CourseGeometry(10, "Test", listOf(hole, green, pin)))
        assertEquals(PinSource.MAPPED, result.holes.single().pinSource)
        assertEquals(pin.points.single(), result.holes.single().pin)
    }

    @Test fun concaveGreenUsesInteriorPointIfAreaCentroidFallsOutside() {
        val ring = listOf(
            GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.004), GeoPoint(0.001, 0.004),
            GeoPoint(0.001, 0.001), GeoPoint(0.004, 0.001), GeoPoint(0.004, 0.0), GeoPoint(0.0, 0.0)
        )
        val green = polygon(2, "green", ring)
        val hole = CourseFeature(1, "way", "hole", "1", null,
            listOf(GeoPoint(-0.001, 0.002), GeoPoint(0.002, 0.0012)), CourseGeometryType.LINE)
        val result = CourseReconstructionEngine.reconstruct(CourseGeometry(10, "Test", listOf(hole, green)))
        val pin = result.holes.single().pin!!
        assertTrue(pin.latitude >= 0.0 && pin.latitude <= 0.004)
        assertTrue(pin.longitude >= 0.0 && pin.longitude <= 0.004)
    }
    @Test fun reconstructsSouthPadreHolesAndEstimatesPinsFromMappedGreens() {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/south-padre-course-overpass.json")
        val json = stream!!.bufferedReader().use { it.readText() }
        val geometry = CourseGeometryLoader.parseGeometry(
            json, GolfCourse(19071561L, "relation", "South Padre Island Golf Club", 0.0, 0.0, 0f)
        )
        val result = CourseReconstructionEngine.reconstruct(geometry)
        assertEquals(18, result.holes.size)
        assertTrue(result.holes.any { it.green != null })
        result.holes.filter { it.green != null }.forEach { hole ->
            assertNotNull("A mapped green should produce an estimated pin", hole.pin)
            assertEquals(PinSource.ESTIMATED_GREEN_CENTER, hole.pinSource)
        }
    }

    @Test fun usesMappedPinsToOrientAndReconstructCoursesWithoutMappedGreens() {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/cottonwood-creek-course-overpass.json")
        val json = stream!!.bufferedReader().use { it.readText() }
        val geometry = CourseGeometryLoader.parseGeometry(
            json, GolfCourse(0L, "relation", "Cottonwood Creek", 0.0, 0.0, 0f)
        )
        val result = CourseReconstructionEngine.reconstruct(geometry)
        assertEquals(9, result.holes.size)
        assertEquals(9, result.mappedPinCount)
        assertTrue(result.holes.all { it.pin != null && it.pinSource == PinSource.MAPPED })
    }

    @Test fun doesNotReuseOneTeeMarkerAcrossNearbyHoles() {
        val tee = CourseFeature(
            5, "node", "tee", "1", null,
            listOf(GeoPoint(0.0, 0.0)), CourseGeometryType.POINT
        )
        val firstHole = CourseFeature(
            1, "way", "hole", "1", null,
            listOf(GeoPoint(0.0, 0.0), GeoPoint(0.004, 0.002)), CourseGeometryType.LINE
        )
        val secondHole = CourseFeature(
            2, "way", "hole", "2", null,
            listOf(GeoPoint(0.0001, 0.0), GeoPoint(0.004, 0.003)), CourseGeometryType.LINE
        )
        val result = CourseReconstructionEngine.reconstruct(
            CourseGeometry(10, "Test", listOf(firstHole, secondHole, tee))
        )
        assertEquals(1, result.holes.count { it.tee != null })
        assertEquals(tee.id, result.holes.single { it.number == "1" }.tee?.id)
        assertNull(result.holes.single { it.number == "2" }.tee)
    }

    @Test fun preservesMappedParAndMarksEvidenceBasedHoleDirection() {
        val tee = CourseFeature(
            2, "node", "tee", "1", null,
            listOf(GeoPoint(0.0, 0.0)), CourseGeometryType.POINT
        )
        val green = polygon(3, "green", listOf(
            GeoPoint(0.0039, 0.0039), GeoPoint(0.0039, 0.0041),
            GeoPoint(0.0041, 0.0041), GeoPoint(0.0041, 0.0039),
            GeoPoint(0.0039, 0.0039)
        ))
        val hole = CourseFeature(
            id = 1, elementType = "way", featureType = "hole", holeNumber = "1",
            name = null,
            points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.004, 0.004)),
            geometryType = CourseGeometryType.LINE,
            par = 4
        )
        val reconstructed = CourseReconstructionEngine.reconstruct(
            CourseGeometry(10, "Test", listOf(hole, tee, green))
        ).holes.single()
        assertEquals(4, reconstructed.par)
        assertTrue(reconstructed.teeToGreenOriented)
    }

}
