package com.watchthisdrive.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    @Test fun targetedRecoveryQueryOnlyRequestsTheReportedFeatureCategory() {
        val course = GolfCourse(123L, "relation", "Any course", 0.0, 0.0, 0f)
        val query = CourseGeometryLoader.buildRecoveryQuery(course, "green")
        assertTrue(query.contains("""["golf"="green"]"""))
        assertTrue(!query.contains("""["golf"="bunker"]"""))
        assertTrue(!query.contains("""["golf"="fairway"]"""))
        assertTrue(query.contains("out body geom"))
    }

    @Test fun unknownRecoveryCategoryFallsBackToGenericQueryRatherThanBuildingUnsafeFilter() {
        val query = CourseGeometryLoader.buildRecoveryQuery(
            GolfCourse(123L, "relation", "Any course", 0.0, 0.0, 0f),
            "untrusted-category"
        )
        assertTrue(query.contains("""["golf"]"""))
    }

    @Test fun readsOnlySupportedNineOrEighteenHoleCourseMetadata() {
        assertEquals(9, CourseDiscovery.parseExpectedHoleCount(JSONObject("""{"holes":"9"}""")))
        assertEquals(18, CourseDiscovery.parseExpectedHoleCount(JSONObject("""{"golf:holes":"18"}""")))
        assertNull(CourseDiscovery.parseExpectedHoleCount(JSONObject("""{"holes":"27"}""")))
    }

}
