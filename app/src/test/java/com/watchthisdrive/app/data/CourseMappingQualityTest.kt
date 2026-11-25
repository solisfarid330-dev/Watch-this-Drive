package com.watchthisdrive.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseMappingQualityTest {
    private fun hole(number: String) = CourseFeature(
        id = number.toLong(),
        elementType = "way",
        featureType = "hole",
        holeNumber = number,
        name = null,
        points = listOf(GeoPoint(0.0, number.toDouble() * 0.001), GeoPoint(0.01, number.toDouble() * 0.001)),
        geometryType = CourseGeometryType.LINE,
        geometryStatus = "source_geometry"
    )

    @Test fun identifiesGapsOnlyFromExplicitNumberedHoleEvidence() {
        val geometry = CourseGeometry(1, "Generic", listOf(hole("1"), hole("2"), hole("4"), hole("5"), hole("6"), hole("7"), hole("8"), hole("9")))
        val quality = CourseMappingQuality.assess(geometry, CourseReconstructionEngine.reconstruct(geometry))
        assertEquals(listOf(3), quality.missingNumberedHoles)
    }

    @Test fun doesNotInventExpectedHoleCountWhenSourceHasNoNumbering() {
        val features = (1..5).map { index ->
            hole(index.toString()).copy(holeNumber = null)
        }
        val geometry = CourseGeometry(1, "Generic", features)
        val quality = CourseMappingQuality.assess(geometry, CourseReconstructionEngine.reconstruct(geometry))
        assertTrue(quality.missingNumberedHoles.isEmpty())
    }
    @Test fun reportsInferredHoleCountAndExpectedNineHoleTarget() {
        val inferred = hole("3").copy(
            origin = CourseFeatureOrigin.INFERRED,
            geometryStatus = "inferred_from_numbered_tee_green_and_fairway",
            confidence = 0.65
        )
        val geometry = CourseGeometry(1, "Generic", listOf(hole("1"), hole("2"), hole("4"), hole("5"), hole("6"), hole("7"), hole("8"), hole("9"), inferred))
        val quality = CourseMappingQuality.assess(geometry, CourseReconstructionEngine.reconstruct(geometry))
        assertEquals(9, quality.expectedHoleCount)
        assertEquals(1, quality.inferredHoleCount)
        assertTrue(quality.unresolvedNumberedHoles.isEmpty())
    }

    @Test fun usesExpectedCourseMetadataWhenTheHighestMappedHoleIsEight() {
        val geometry = CourseGeometry(
            1, "Nine-hole course", (1..8).map { hole(it.toString()) },
            expectedHoleCount = 9
        )
        val quality = CourseMappingQuality.assess(geometry, CourseReconstructionEngine.reconstruct(geometry))
        assertEquals(9, quality.expectedHoleCount)
        assertEquals(listOf(9), quality.missingNumberedHoles)
    }

    @Test fun reportsTheEntireExpectedRangeWhenMetadataExistsButNoHoleNumbersAreMapped() {
        val geometry = CourseGeometry(
            1, "Nine-hole course",
            features = emptyList(),
            expectedHoleCount = 9
        )
        val quality = CourseMappingQuality.assess(geometry, CourseReconstructionEngine.reconstruct(geometry))
        assertEquals(9, quality.expectedHoleCount)
        assertEquals((1..9).toList(), quality.missingNumberedHoles)
        assertEquals((1..9).toList(), quality.unresolvedNumberedHoles)
    }

}
