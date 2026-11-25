package com.watchthisdrive.app.data

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
}
