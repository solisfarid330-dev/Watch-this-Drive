package com.watchthisdrive.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CourseGeometryParserTest {
    private val course = GolfCourse(123L, "relation", "Test Golf Course", 0.0, 0.0, 0f)

    @Test
    fun parsesClosedWayAsPolygon() {
        val json = """
            {"elements":[{"type":"way","id":10,"tags":{"golf":"green","ref":"1"},
            "geometry":[
              {"lat":0.0,"lon":0.0},{"lat":0.0,"lon":1.0},
              {"lat":1.0,"lon":1.0},{"lat":1.0,"lon":0.0},
              {"lat":0.0,"lon":0.0}]}]}
        """.trimIndent()
        val result = CourseGeometryLoader.parseGeometry(json, course)
        val green = result.features.single()
        assertEquals(CourseGeometryType.POLYGON, green.geometryType)
        assertEquals(5, green.rings.single().size)
        assertTrue(green.hasUsableGeometry)
    }

    @Test
    fun assemblesMultipolygonOuterAndInnerRings() {
        val json = """
            {"elements":[{"type":"relation","id":20,
            "tags":{"golf":"fairway","type":"multipolygon"},
            "members":[
              {"type":"way","ref":1,"role":"outer","geometry":[
                {"lat":0.0,"lon":0.0},{"lat":0.0,"lon":2.0},{"lat":2.0,"lon":2.0}]},
              {"type":"way","ref":2,"role":"outer","geometry":[
                {"lat":2.0,"lon":2.0},{"lat":2.0,"lon":0.0},{"lat":0.0,"lon":0.0}]},
              {"type":"way","ref":3,"role":"inner","geometry":[
                {"lat":0.5,"lon":0.5},{"lat":1.5,"lon":0.5},
                {"lat":1.5,"lon":1.5},{"lat":0.5,"lon":1.5},
                {"lat":0.5,"lon":0.5}]}
            ]}]}
        """.trimIndent()
        val result = CourseGeometryLoader.parseGeometry(json, course)
        val fairway = result.features.single()
        assertEquals(CourseGeometryType.POLYGON, fairway.geometryType)
        assertEquals(2, fairway.rings.size)
        assertEquals(5, fairway.rings[0].size)
        assertTrue(fairway.hasUsableGeometry)
        assertTrue(result.incompleteRelations.isEmpty())
    }

    @Test
    fun preservesMultipleOuterRingsAsMultiPolygon() {
        val json = """
            {"elements":[{"type":"relation","id":30,
            "tags":{"golf":"fairway"},
            "members":[
              {"type":"way","ref":1,"role":"outer","geometry":[
                {"lat":0.0,"lon":0.0},{"lat":0.0,"lon":1.0},
                {"lat":1.0,"lon":1.0},{"lat":1.0,"lon":0.0},{"lat":0.0,"lon":0.0}]},
              {"type":"way","ref":2,"role":"outer","geometry":[
                {"lat":2.0,"lon":2.0},{"lat":2.0,"lon":3.0},
                {"lat":3.0,"lon":3.0},{"lat":3.0,"lon":2.0},{"lat":2.0,"lon":2.0}]}
            ]}]}
        """.trimIndent()
        val result = CourseGeometryLoader.parseGeometry(json, course)
        val fairway = result.features.single()
        assertEquals(CourseGeometryType.MULTI_POLYGON, fairway.geometryType)
        assertEquals(2, fairway.polygons.size)
    }

    @Test
    fun leavesOpenHoleWayAsLine() {
        val json = """
            {"elements":[{"type":"way","id":40,"tags":{"golf":"hole","ref":"1"},
            "geometry":[{"lat":0.0,"lon":0.0},{"lat":0.1,"lon":0.2}]}]}
        """.trimIndent()
        val result = CourseGeometryLoader.parseGeometry(json, course)
        val hole = result.features.single()
        assertEquals(CourseGeometryType.LINE, hole.geometryType)
        assertEquals(2, hole.points.size)
    }
}
