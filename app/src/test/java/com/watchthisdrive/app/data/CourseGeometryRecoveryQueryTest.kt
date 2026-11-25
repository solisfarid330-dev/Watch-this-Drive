package com.watchthisdrive.app.data

import org.junit.Assert.assertTrue
import org.junit.Test

class CourseGeometryRecoveryQueryTest {
    @Test fun recoveryQueryIsGenericAndExpandsGolfFeatureTagCoverage() {
        val query = CourseGeometryLoader.buildRecoveryQuery(
            GolfCourse(123L, "relation", "Any course", 0.0, 0.0, 0f)
        )
        assertTrue(query.contains("relation(123)"))
        assertTrue(query.contains("[\"golf\"]"))
        assertTrue(query.contains("[\"golf:hole\"]"))
        assertTrue(query.contains("way(r.course)"))
        assertTrue(query.contains("out body geom"))
    }

    @Test fun recoveryQuerySupportsWayBasedCourseBoundaries() {
        val query = CourseGeometryLoader.buildRecoveryQuery(
            GolfCourse(456L, "way", "Another course", 0.0, 0.0, 0f)
        )
        assertTrue(query.contains("way(456)"))
        assertTrue(!query.contains("way(r.course)"))
    }
}
