package com.watchthisdrive.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoleReconstructorTest {
    private fun feature(
        id: Long,
        kind: String,
        lat: Double,
        lon: Double,
        hole: String? = "1",
        points: List<GeoPoint> = listOf(GeoPoint(lat, lon))
    ) = CourseFeature(id, "way", kind, hole, null, points)

    @Test
    fun explicitHoleNumbersPairTheCorrectTeeAndGreen() {
        val geometry = CourseGeometry(
            courseId = 99,
            courseName = "Fixture",
            features = listOf(
                feature(1, "tee", 0.0, 0.0, "1"),
                feature(2, "green", 0.004, 0.0, "1"),
                feature(3, "tee", 0.0, 0.01, "2"),
                feature(4, "green", 0.004, 0.01, "2")
            )
        )

        val holes = HoleReconstructor.reconstruct(geometry)

        assertEquals(listOf(1, 2), holes.map { it.number })
        assertEquals(2, holes.size)
        assertTrue(holes.all { it.lengthMeters in 400.0..500.0 })
    }

    @Test
    fun mappedFairwayIsPreservedInsteadOfReplacingItWithCorridor() {
        val fairway = listOf(
            GeoPoint(0.0002, -0.0002),
            GeoPoint(0.0012, -0.0002),
            GeoPoint(0.0038, 0.0002),
            GeoPoint(0.0038, 0.0010),
            GeoPoint(0.0012, 0.0006),
            GeoPoint(0.0002, 0.0006),
            GeoPoint(0.0002, -0.0002)
        )
        val geometry = CourseGeometry(
            99, "Fixture", listOf(
                feature(1, "tee", 0.0, 0.0),
                feature(2, "green", 0.004, 0.0004),
                feature(3, "fairway", 0.0, 0.0, points = fairway)
            )
        )

        val hole = HoleReconstructor.reconstruct(geometry).single()

        assertTrue(hole.usesMappedFairway)
        assertEquals(fairway, hole.mappedFairways.single().points)
        assertTrue(hole.fairwayEstimate.isEmpty())
    }

    @Test
    fun missingFairwayUsesExplicitlyEstimatedCorridor() {
        val geometry = CourseGeometry(
            99, "Fixture", listOf(
                feature(1, "tee", 0.0, 0.0),
                feature(2, "green", 0.004, 0.0)
            )
        )

        val hole = HoleReconstructor.reconstruct(geometry).single()

        assertFalse(hole.usesMappedFairway)
        assertTrue(hole.fairwayEstimate.size >= 4)
    }

    @Test
    fun explicitHoleNumbersPreventCrossHoleTeeGreenPairing() {
        val geometry = CourseGeometry(
            99, "Fixture", listOf(
                feature(1, "tee", 0.0, 0.0, "1"),
                feature(2, "green", 0.004, 0.0, "2"),
                feature(3, "tee", 0.0, 0.01, "2"),
                feature(4, "green", 0.004, 0.01, "1")
            )
        )

        assertTrue(HoleReconstructor.reconstruct(geometry).isEmpty())
    }
}
