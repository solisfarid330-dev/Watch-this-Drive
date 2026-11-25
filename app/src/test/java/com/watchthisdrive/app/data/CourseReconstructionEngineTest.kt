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
        assertEquals("All South Padre holes should resolve to their own mapped green", 18, result.holes.count { it.green != null })
        assertEquals("South Padre has no mapped pins, so every resolved green should yield an estimated pin",
            18, result.holes.count { it.pinSource == PinSource.ESTIMATED_GREEN_CENTER })
        result.holes.forEach { hole ->
            assertNotNull("A mapped green should produce an estimated pin", hole.pin)
            assertEquals(PinSource.ESTIMATED_GREEN_CENTER, hole.pinSource)
        }
    }

    @Test fun reconstructsWestwoodHolesWithUniqueTeeGreenAndMappedPinAssociations() {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/westwood-course-overpass.json")
        val json = stream!!.bufferedReader().use { it.readText() }
        val geometry = CourseGeometryLoader.parseGeometry(
            json, GolfCourse(125353929L, "relation", "Westwood Country Club", 0.0, 0.0, 0f)
        )
        val result = CourseReconstructionEngine.reconstruct(geometry)
        assertEquals(18, result.holes.size)
        assertEquals(18, result.holes.count { it.tee != null })
        assertEquals(18, result.holes.count { it.green != null })
        assertEquals(18, result.mappedPinCount)
        assertEquals(18, result.holes.mapNotNull { it.tee?.let { tee -> tee.elementType to tee.id } }.toSet().size)
        assertEquals(18, result.holes.mapNotNull { it.green?.let { green -> green.elementType to green.id } }.toSet().size)
        assertEquals(18, result.holes.mapNotNull { it.pinSource?.let { source -> source to it.number } }.size)
        assertTrue(result.holes.all { it.teeToGreenOriented && it.pin != null })
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

    @Test fun assignsCorridorPolygonWhenItCrossesBetweenCenterlineVertices() {
        val hole = CourseFeature(
            1, "way", "hole", "1", null,
            listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.004)), CourseGeometryType.LINE
        )
        val fairway = polygon(2, "fairway", listOf(
            GeoPoint(-0.001, 0.0015), GeoPoint(-0.001, 0.0025),
            GeoPoint(0.001, 0.0025), GeoPoint(0.001, 0.0015),
            GeoPoint(-0.001, 0.0015)
        ))
        val result = CourseReconstructionEngine.reconstruct(
            CourseGeometry(10, "Test", listOf(hole, fairway))
        )
        assertEquals(1, result.holes.single().fairways.size)
        assertEquals(fairway.id, result.holes.single().fairways.single().id)
    }

    @Test fun corridorAmbiguityWarnsOnlyTheCompetingHoles() {
        val firstHole = CourseFeature(
            1, "way", "hole", "1", null,
            listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.004)), CourseGeometryType.LINE
        )
        val secondHole = CourseFeature(
            2, "way", "hole", "2", null,
            listOf(GeoPoint(0.0001, 0.0), GeoPoint(0.0001, 0.004)), CourseGeometryType.LINE
        )
        val distantHole = CourseFeature(
            3, "way", "hole", "3", null,
            listOf(GeoPoint(0.002, 0.0), GeoPoint(0.002, 0.004)), CourseGeometryType.LINE
        )
        val fairway = polygon(4, "fairway", listOf(
            GeoPoint(0.00003, 0.001), GeoPoint(0.00003, 0.003),
            GeoPoint(0.00007, 0.003), GeoPoint(0.00007, 0.001),
            GeoPoint(0.00003, 0.001)
        ))
        val result = CourseReconstructionEngine.reconstruct(
            CourseGeometry(10, "Test", listOf(firstHole, secondHole, distantHole, fairway))
        )
        assertTrue(result.holes.all { it.fairways.isEmpty() })
        assertTrue(result.holes[0].associationWarnings.any { it.contains("fairway feature association ambiguous") })
        assertTrue(result.holes[1].associationWarnings.any { it.contains("fairway feature association ambiguous") })
        assertTrue(result.holes[2].associationWarnings.none { it.contains("fairway feature association ambiguous") })
    }

    @Test fun leavesGreenSharedByEquidistantHolesAmbiguous() {
        val green = polygon(20, "green", listOf(
            GeoPoint(0.001, 0.001), GeoPoint(0.001, 0.003),
            GeoPoint(0.002, 0.003), GeoPoint(0.002, 0.001),
            GeoPoint(0.001, 0.001)
        ))
        val firstHole = CourseFeature(
            1, "way", "hole", "1", null,
            listOf(GeoPoint(0.0, 0.002), GeoPoint(0.0015, 0.002)), CourseGeometryType.LINE
        )
        val secondHole = CourseFeature(
            2, "way", "hole", "2", null,
            listOf(GeoPoint(0.0, 0.0021), GeoPoint(0.0016, 0.0021)), CourseGeometryType.LINE
        )
        val result = CourseReconstructionEngine.reconstruct(
            CourseGeometry(10, "Test", listOf(firstHole, secondHole, green))
        )
        assertTrue(result.holes.all { it.green == null })
        assertTrue(result.holes.all { hole ->
            hole.associationWarnings.any { it.contains("Green association ambiguous") }
        })
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
