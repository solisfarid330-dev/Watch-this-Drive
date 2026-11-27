package com.watchthisdrive.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseGeometryQueryTest {
    @Test
    fun recoveryQueryRedirectsCourseSelectorBeforeTerminatingStatement() {
        val course = GolfCourse(
            19071561L, "relation", "Validation course", 0.0, 0.0, 0f
        )

        val query = CourseGeometryLoader.buildRecoveryQuery(course)

        assertTrue(query.contains("relation(19071561) -> .course;"))
        assertTrue(query.contains(".course map_to_area -> .courseArea;"))
        assertTrue(query.contains("way(r.course);"))
        assertTrue(query.contains("nwr(around:2000,0.0,0.0)[\"golf\"];"))
        assertTrue(query.contains("nwr(around:2000,0.0,0.0)[\"golf:hole\"];"))
        assertTrue(query.contains("[out:json][timeout:25];"))
        assertFalse(query.contains("; -> .course;"))
    }

    @Test
    fun recoveryQueryCanBeRestrictedToHoleFeatures() {
        val course = GolfCourse(
            19071561L, "relation", "Validation course", 0.0, 0.0, 0f
        )

        val query = CourseGeometryLoader.buildRecoveryQuery(course, "hole")

        assertTrue(query.contains("nwr(area.courseArea)[\"golf\"=\"hole\"];"))
        assertTrue(query.contains("nwr(area.courseArea)[\"golf:hole\"];"))
        assertTrue(query.contains("nwr(around:2000,0.0,0.0)[\"golf\"=\"hole\"];"))
        assertTrue(query.contains("nwr(around:2000,0.0,0.0)[\"golf:hole\"];"))
        assertFalse(query.contains("[\"golf\"=\"null\"]"))
    }
    @Test
    fun recoveryResultsAreCombinedAcrossMirrorsAndDeduplicatedByOsmIdentity() {
        val course = GolfCourse(42L, "relation", "Validation course", 0.0, 0.0, 0f)
        val tee = CourseFeature(
            id = 10L,
            elementType = "node",
            featureType = "tee",
            holeNumber = "6",
            name = null,
            points = listOf(GeoPoint(0.0, 0.0)),
            geometryType = CourseGeometryType.POINT
        )
        val green = CourseFeature(
            id = 11L,
            elementType = "way",
            featureType = "green",
            holeNumber = "6",
            name = null,
            points = listOf(
                GeoPoint(0.001, 0.001),
                GeoPoint(0.001, 0.002),
                GeoPoint(0.002, 0.002),
                GeoPoint(0.002, 0.001),
                GeoPoint(0.001, 0.001)
            ),
            geometryType = CourseGeometryType.POLYGON,
            rings = listOf(
                listOf(
                    GeoPoint(0.001, 0.001),
                    GeoPoint(0.001, 0.002),
                    GeoPoint(0.002, 0.002),
                    GeoPoint(0.002, 0.001),
                    GeoPoint(0.001, 0.001)
                )
            )
        )

        val merged = CourseGeometryLoader.mergeGeometryResponses(
            course,
            listOf(
                CourseGeometry(course.id, course.name, listOf(tee)),
                CourseGeometry(course.id, course.name, listOf(tee, green))
            )
        )

        assertEquals(2, merged.features.size)
        assertEquals(setOf(10L, 11L), merged.features.map { it.id }.toSet())
    }

}
