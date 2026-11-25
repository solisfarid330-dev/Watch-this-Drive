package com.watchthisdrive.app.data

import org.junit.Assert.*
import org.junit.Test

class CourseFeatureInferenceTest {
    private fun hole(id: Long = 42L, points: List<GeoPoint> = listOf(
        GeoPoint(0.0, 0.0),
        GeoPoint(0.0005, 0.0001),
        GeoPoint(0.001, 0.0002)
    )) = CourseFeature(
        id = id,
        elementType = "way",
        featureType = "hole",
        holeNumber = "1",
        name = null,
        points = points,
        geometryType = CourseGeometryType.LINE
    )

    @Test
    fun createsLowConfidenceFairwayCorridorWhenSourceFairwayIsMissing() {
        val original = CourseGeometry(7L, "Test course", listOf(hole()))
        val result = CourseFeatureInference.addInferredFeatures(original)
        val generated = result.features.single { it.origin == CourseFeatureOrigin.INFERRED }

        assertEquals("fairway", generated.featureType)
        assertEquals(CourseGeometryType.POLYGON, generated.geometryType)
        assertTrue(generated.hasUsableGeometry)
        assertEquals(0.35, generated.confidence!!, 0.0)
        assertEquals("inferred_corridor_from_hole_centerline", generated.geometryStatus)
        assertEquals(1, result.features.count { it.featureType == "hole" })
    }

    @Test
    fun doesNotGenerateDuplicateWhenSourceFairwayAlreadyFollowsHole() {
        val line = hole().points
        val mappedFairway = CourseFeature(
            id = 99L,
            elementType = "way",
            featureType = "fairway",
            holeNumber = "1",
            name = null,
            points = line,
            geometryType = CourseGeometryType.LINE
        )
        val result = CourseFeatureInference.addInferredFeatures(
            CourseGeometry(7L, "Test course", listOf(hole(), mappedFairway))
        )

        assertFalse(result.features.any { it.origin == CourseFeatureOrigin.INFERRED })
        assertEquals(1, result.features.count { it.featureType == "fairway" })
    }

    @Test
    fun ignoresCenterlinesThatAreTooShortToRepresentAPlayingCorridor() {
        val shortHole = hole(points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0001, 0.0)))
        val result = CourseFeatureInference.addInferredFeatures(
            CourseGeometry(7L, "Test course", listOf(shortHole))
        )

        assertFalse(result.features.any { it.origin == CourseFeatureOrigin.INFERRED })
    }

    @Test
    fun repeatedInferenceReplacesRatherThanDuplicatesGeneratedGeometry() {
        val once = CourseFeatureInference.addInferredFeatures(
            CourseGeometry(7L, "Test course", listOf(hole()))
        )
        val twice = CourseFeatureInference.addInferredFeatures(once)

        assertEquals(1, twice.features.count { it.origin == CourseFeatureOrigin.INFERRED })
    }
}
