package com.watchthisdrive.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseGeometryParserTest {
    private val course = GolfCourse(123L, "relation", "Test Golf Course", 0.0, 0.0, 0f)

    @Test
    fun parsesRealSouthPadreOverpassFixture() {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/south-padre-hole1-overpass.json")
        val json = stream!!.bufferedReader().use { it.readText() }
        val result = CourseGeometryLoader.parseGeometry(json, course)

        val green = result.features.single { it.featureType == "green" }
        val hole = result.features.single { it.featureType == "hole" }
        val fairway = result.features.single { it.featureType == "fairway" }

        assertEquals(CourseGeometryType.POLYGON, green.geometryType)
        assertTrue(green.hasUsableGeometry)
        assertEquals(CourseGeometryType.LINE, hole.geometryType)
        assertEquals(3, hole.points.size)
        assertEquals(CourseGeometryType.POLYGON, fairway.geometryType)
        assertEquals(2, fairway.rings.size)
        assertTrue(fairway.hasUsableGeometry)
        assertTrue(result.incompleteRelations.isEmpty())
    }

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
    fun stitchesSegmentsAttachingAtOppositeRingEnds() {
        val json = """
            {"elements":[{"type":"relation","id":25,
            "tags":{"golf":"fairway","type":"multipolygon"},
            "members":[
              {"type":"way","ref":1,"role":"outer","geometry":[
                {"lat":0.0,"lon":0.0},{"lat":0.0,"lon":1.0}]},
              {"type":"way","ref":2,"role":"outer","geometry":[
                {"lat":1.0,"lon":0.0},{"lat":0.0,"lon":0.0}]},
              {"type":"way","ref":3,"role":"outer","geometry":[
                {"lat":0.0,"lon":1.0},{"lat":1.0,"lon":1.0}]},
              {"type":"way","ref":4,"role":"outer","geometry":[
                {"lat":1.0,"lon":1.0},{"lat":1.0,"lon":0.0}]}
            ]}]}
        """.trimIndent()
        val result = CourseGeometryLoader.parseGeometry(json, course)
        val fairway = result.features.single()
        assertEquals(CourseGeometryType.POLYGON, fairway.geometryType)
        assertEquals(5, fairway.rings.single().size)
        assertTrue(fairway.hasUsableGeometry)
        assertTrue(result.incompleteRelations.isEmpty())
    }

    @Test
    fun preservesMultipleOuterRingsAsMultiPolygon() {
        val json = """
            {"elements":[{"type":"relation","id":30,
            "tags":{"golf":"fairway","type":"multipolygon"},
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
    fun rejectsSelfIntersectingAreaAndReportsIt() {
        val json = """
            {"elements":[{"type":"way","id":50,"tags":{"golf":"green"},
            "geometry":[{"lat":0.0,"lon":0.0},{"lat":1.0,"lon":1.0},
            {"lat":0.0,"lon":1.0},{"lat":1.0,"lon":0.0},
            {"lat":0.0,"lon":0.0}]}]}
        """.trimIndent()
        val result = CourseGeometryLoader.parseGeometry(json, course)
        assertTrue(result.features.isEmpty())
        val warning = result.incompleteRelations.single()
        assertTrue(warning.contains("zero area") || warning.contains("self-intersects"))
    }

    @Test
    fun assemblesRouteStyleHoleRelationAsLine() {
        val json = """
            {"elements":[{"type":"relation","id":70,"tags":{"golf":"hole","ref":"3","type":"route"},
            "members":[
              {"type":"way","ref":1,"role":"","geometry":[
                {"lat":0.0,"lon":0.0},{"lat":0.0,"lon":1.0}]},
              {"type":"way","ref":2,"role":"","geometry":[
                {"lat":0.0,"lon":1.0},{"lat":1.0,"lon":1.0}]}
            ]}]}
        """.trimIndent()
        val result = CourseGeometryLoader.parseGeometry(json, course)
        val hole = result.features.single()
        assertEquals(CourseGeometryType.LINE, hole.geometryType)
        assertEquals(3, hole.points.size)
        assertTrue(hole.hasUsableGeometry)
    }

    @Test
    fun assemblesRouteHoleSegmentsFromBothEnds() {
        val json = """
            {"elements":[{"type":"relation","id":71,"tags":{"golf":"hole","ref":"4","type":"route"},
            "members":[
              {"type":"way","ref":1,"role":"","geometry":[
                {"lat":0.0,"lon":0.0},{"lat":0.0,"lon":1.0}]},
              {"type":"way","ref":2,"role":"","geometry":[
                {"lat":1.0,"lon":0.0},{"lat":0.0,"lon":0.0}]},
              {"type":"way","ref":3,"role":"","geometry":[
                {"lat":0.0,"lon":1.0},{"lat":1.0,"lon":1.0}]}
            ]}]}
        """.trimIndent()
        val result = CourseGeometryLoader.parseGeometry(json, course)
        val hole = result.features.single()
        assertEquals(CourseGeometryType.LINE, hole.geometryType)
        assertEquals(4, hole.points.size)
        assertTrue(hole.hasUsableGeometry)
    }

    @Test
    fun closedCartPathRemainsALine() {
        val json = """
            {"elements":[{"type":"way","id":42,"tags":{"golf":"cartpath"},
            "geometry":[{"lat":0.0,"lon":0.0},{"lat":0.0,"lon":1.0},
            {"lat":1.0,"lon":1.0},{"lat":1.0,"lon":0.0},
            {"lat":0.0,"lon":0.0}]}]}
        """.trimIndent()
        val result = CourseGeometryLoader.parseGeometry(json, course)
        assertEquals(CourseGeometryType.LINE, result.features.single().geometryType)
    }

    @Test
    fun incompleteMultipolygonIsNotReportedAsUsable() {
        val json = """
            {"elements":[{"type":"relation","id":60,"tags":{"golf":"fairway","type":"multipolygon"},
            "members":[{"type":"way","ref":1,"role":"outer","geometry":[
              {"lat":0.0,"lon":0.0},{"lat":0.0,"lon":1.0},{"lat":1.0,"lon":1.0}]}]}]}
        """.trimIndent()
        val result = CourseGeometryLoader.parseGeometry(json, course)
        assertTrue(result.features.isEmpty())
        assertTrue(result.incompleteRelations.single().contains("could not close"))
    }

    @Test
    fun closedHoleCenterlineRemainsALine() {
        val json = """
            {"elements":[{"type":"way","id":41,"tags":{"golf":"hole","ref":"2"},
            "geometry":[{"lat":0.0,"lon":0.0},{"lat":0.1,"lon":0.2},
            {"lat":0.2,"lon":0.3},{"lat":0.0,"lon":0.0}]}]}
        """.trimIndent()
        val result = CourseGeometryLoader.parseGeometry(json, course)
        val hole = result.features.single()
        assertEquals(CourseGeometryType.LINE, hole.geometryType)
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


    private fun parseCourseFixture(fileName: String, id: Long, name: String): CourseGeometry {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/$fileName")
        val json = stream!!.bufferedReader().use { it.readText() }
        return CourseGeometryLoader.parseGeometry(
            json,
            GolfCourse(id, "relation", name, 0.0, 0.0, 0f)
        )
    }

    @Test
    fun parsesCompleteSouthPadreCourseSnapshot() {
        val result = parseCourseFixture(
            "south-padre-course-overpass.json", 19071561L, "South Padre Island Golf Club"
        )
        assertEquals(18, result.countWithUsableGeometry("hole"))
        assertEquals(18, result.countWithUsableGeometry("green"))
        assertEquals(17, result.countWithUsableGeometry("fairway"))
        assertEquals(42, result.countWithUsableGeometry("bunker"))
        assertEquals(49, result.countWithUsableGeometry("tee"))
        assertEquals(2, result.countWithUsableGeometry("water_hazard"))
        assertEquals(23, result.countWithUsableGeometry("cartpath"))
        assertEquals(14, result.countWithUsableGeometry("rough"))
        assertEquals(142, result.features.count { it.geometryType == CourseGeometryType.POLYGON && it.hasUsableGeometry })
        assertEquals(41, result.features.count { it.geometryType == CourseGeometryType.LINE && it.hasUsableGeometry })
        assertEquals(18, result.features.filter { it.featureType == "hole" }.map { it.holeNumber }.toSet().size)
        val fairwayRelations = result.features.filter { it.elementType == "relation" && it.featureType == "fairway" }
        assertEquals(16, fairwayRelations.size)
        assertTrue(fairwayRelations.all { it.geometryType == CourseGeometryType.POLYGON && it.rings.size >= 2 })
        assertTrue(fairwayRelations.any { it.rings.size == 4 })
        assertTrue(result.features.all { it.hasUsableGeometry })
        assertTrue("Unexpected relation errors: ${result.incompleteRelations}", result.incompleteRelations.isEmpty())
    }

    @Test
    fun parsesCompleteWestwoodCourseSnapshot() {
        val result = parseCourseFixture(
            "westwood-course-overpass.json", 125353929L, "Westwood Country Club"
        )
        assertEquals(18, result.countWithUsableGeometry("hole"))
        assertEquals(18, result.countWithUsableGeometry("green"))
        assertEquals(19, result.countWithUsableGeometry("fairway"))
        assertEquals(62, result.countWithUsableGeometry("bunker"))
        assertEquals(56, result.countWithUsableGeometry("tee"))
        assertEquals(9, result.countWithUsableGeometry("water_hazard"))
        assertEquals(18, result.countWithUsableGeometry("pin"))
        assertEquals(165, result.features.count { it.geometryType == CourseGeometryType.POLYGON && it.hasUsableGeometry })
        assertEquals(18, result.features.count { it.geometryType == CourseGeometryType.LINE && it.hasUsableGeometry })
        assertEquals(18, result.features.count { it.geometryType == CourseGeometryType.POINT && it.hasUsableGeometry })
        assertEquals(18, result.features.filter { it.featureType == "hole" }.map { it.holeNumber }.toSet().size)
        val fairwayRelations = result.features.filter { it.elementType == "relation" && it.featureType == "fairway" }
        assertEquals(15, fairwayRelations.size)
        assertTrue(fairwayRelations.all { it.geometryType == CourseGeometryType.POLYGON && it.rings.size == 2 })
        assertTrue(result.features.all { it.hasUsableGeometry })
        assertTrue("Unexpected relation errors: ${result.incompleteRelations}", result.incompleteRelations.isEmpty())
    }

    @Test
    fun parsesCottonwoodSnapshotWithoutInventingMissingAreas() {
        val result = parseCourseFixture(
            "cottonwood-creek-course-overpass.json", 1337838568L, "Cottonwood Creek Country Club"
        )
        assertEquals(9, result.countWithUsableGeometry("hole"))
        assertEquals(9, result.countWithUsableGeometry("pin"))
        assertEquals(0, result.count("green"))
        assertEquals(0, result.count("fairway"))
        assertEquals(0, result.count("bunker"))
        assertEquals(9, result.features.count { it.geometryType == CourseGeometryType.LINE && it.hasUsableGeometry })
        assertEquals(9, result.features.count { it.geometryType == CourseGeometryType.POINT && it.hasUsableGeometry })
        assertEquals(9, result.features.filter { it.featureType == "hole" }.map { it.holeNumber }.toSet().size)
        assertTrue(result.features.all { it.hasUsableGeometry })
        assertTrue(result.incompleteRelations.isEmpty())
    }
    @Test
    fun rejectsInnerRingThatCrossesOuterBoundary() {
        val json = """
            {"elements":[{"type":"relation","id":80,
            "tags":{"golf":"fairway","type":"multipolygon"},
            "members":[
              {"type":"way","ref":1,"role":"outer","geometry":[
                {"lat":0.0,"lon":0.0},{"lat":0.0,"lon":2.0},
                {"lat":2.0,"lon":2.0},{"lat":2.0,"lon":0.0},{"lat":0.0,"lon":0.0}]},
              {"type":"way","ref":2,"role":"inner","geometry":[
                {"lat":0.5,"lon":0.5},{"lat":0.5,"lon":2.5},
                {"lat":1.5,"lon":2.5},{"lat":1.5,"lon":0.5},{"lat":0.5,"lon":0.5}]}
            ]}]}
        """.trimIndent()
        val result = CourseGeometryLoader.parseGeometry(json, course)
        val fairway = result.features.single()
        assertEquals(false, fairway.hasUsableGeometry)
        assertTrue(fairway.geometryStatus.contains("inner ring is not fully contained"))
        assertTrue(result.incompleteRelations.any { it.contains("inner ring is not fully contained") })
    }

    @Test
    fun rejectsOverlappingOuterComponents() {
        val json = """
            {"elements":[{"type":"relation","id":81,
            "tags":{"golf":"fairway","type":"multipolygon"},
            "members":[
              {"type":"way","ref":1,"role":"outer","geometry":[
                {"lat":0.0,"lon":0.0},{"lat":0.0,"lon":2.0},
                {"lat":2.0,"lon":2.0},{"lat":2.0,"lon":0.0},{"lat":0.0,"lon":0.0}]},
              {"type":"way","ref":2,"role":"outer","geometry":[
                {"lat":1.0,"lon":1.0},{"lat":1.0,"lon":3.0},
                {"lat":3.0,"lon":3.0},{"lat":3.0,"lon":1.0},{"lat":1.0,"lon":1.0}]}
            ]}]}
        """.trimIndent()
        val result = CourseGeometryLoader.parseGeometry(json, course)
        val fairway = result.features.single()
        assertEquals(false, fairway.hasUsableGeometry)
        assertTrue(fairway.geometryStatus.contains("outer rings overlap or touch"))
    }

    @Test
    fun rejectsNestedInnerRings() {
        val json = """
            {"elements":[{"type":"relation","id":82,
            "tags":{"golf":"fairway","type":"multipolygon"},
            "members":[
              {"type":"way","ref":1,"role":"outer","geometry":[
                {"lat":0.0,"lon":0.0},{"lat":0.0,"lon":3.0},
                {"lat":3.0,"lon":3.0},{"lat":3.0,"lon":0.0},{"lat":0.0,"lon":0.0}]},
              {"type":"way","ref":2,"role":"inner","geometry":[
                {"lat":0.5,"lon":0.5},{"lat":0.5,"lon":2.5},
                {"lat":2.5,"lon":2.5},{"lat":2.5,"lon":0.5},{"lat":0.5,"lon":0.5}]},
              {"type":"way","ref":3,"role":"inner","geometry":[
                {"lat":1.0,"lon":1.0},{"lat":1.0,"lon":2.0},
                {"lat":2.0,"lon":2.0},{"lat":2.0,"lon":1.0},{"lat":1.0,"lon":1.0}]}
            ]}]}
        """.trimIndent()
        val result = CourseGeometryLoader.parseGeometry(json, course)
        val fairway = result.features.single()
        assertEquals(false, fairway.hasUsableGeometry)
        assertTrue(fairway.geometryStatus.contains("inner rings overlap, touch, or are nested"))
    }

}
