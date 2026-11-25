package com.watchthisdrive.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CourseExternalSourcesTest {

    @Test
    fun currentOpenGolfSearchContractUsesCourseNameAndApiV1Path() {
        val url = OpenGolfApiCourseLoader.buildSearchUrl("South Padre Island Golf Club")
        assertTrue(url.startsWith("https://api.opengolfapi.org/api/v1/courses/search?"))
        assertTrue(url.contains("q=South+Padre+Island+Golf+Club"))
        assertTrue(url.contains("limit=20"))
    }

    @Test
    fun matchingRequiresExactNormalizedNameAndNearbyCoordinates() {
        val candidates = JSONArray().put(
            JSONObject().put("id", "far").put("course_name", "South Padre Island Golf Club")
                .put("latitude", 29.0).put("longitude", -97.0)
        ).put(
            JSONObject().put("id", "near").put("course_name", "South Padre Island Golf Club")
                .put("latitude", 26.1).put("longitude", -97.2)
        ).put(
            JSONObject().put("id", "wrong").put("course_name", "South Padre Country Club")
                .put("latitude", 26.1).put("longitude", -97.2)
        )

        val selected = OpenGolfApiCourseLoader.chooseMatch(
            candidates, "South Padre Island Golf Club", 26.1, -97.2, 123L
        )
        assertEquals("near", selected?.optString("id"))
    }

    @Test
    fun matchingRejectsSameNameWhenLocationIsTooFarAway() {
        val candidates = JSONArray().put(
            JSONObject().put("id", "far").put("course_name", "Pine Valley Golf Club")
                .put("latitude", 40.0).put("longitude", -74.0)
        )
        assertNull(
            OpenGolfApiCourseLoader.chooseMatch(
                candidates, "Pine Valley Golf Club", 26.0, -97.0, 123L
            )
        )
    }

    @Test
    fun matchingAllowsExactOsmIdEvenIfCourseCentroidDiffers() {
        val candidates = JSONArray().put(
            JSONObject().put("id", "osm-linked").put("course_name", "Test Golf Club")
                .put("osm_id", 77L).put("latitude", 40.0).put("longitude", -74.0)
        )
        val selected = OpenGolfApiCourseLoader.chooseMatch(
            candidates, "Test Golf Club", 26.0, -97.0, 77L
        )
        assertEquals("osm-linked", selected?.optString("id"))
    }

    @Test
    fun parsesCurrentCourseDetailScorecardAndTeeFields() {
        val detail = JSONObject(
            """{
              "id":"course-1",
              "course_name":"Test Golf Club",
              "city":"Dallas",
              "state":"TX",
              "par_total":72,
              "holes":[
                {"hole":1,"par":4,"handicap_index":7,"yardage":385},
                {"hole":2,"par":5,"handicap_index":1,"yardage":510}
              ],
              "tees":[
                {"name":"Blue","course_rating":71.2,"slope_rating":128,"total_yardage":6800}
              ]
            }"""
        )
        val metadata = OpenGolfApiCourseLoader.parseMetadata(detail, JSONObject())
        assertEquals(2, metadata.holeCount)
        assertEquals(72, metadata.totalPar)
        assertEquals(385, metadata.holes.first().yardage)
        assertEquals(7, metadata.holes.first().handicap)
        assertEquals("Blue", metadata.tees.single().name)
        assertEquals(128, metadata.tees.single().slopeRating)
        assertEquals(6800, metadata.totalYardage)
    }

    @Test
    fun nationalNaipUrlRequestsGeoreferencedImageForCourseCenter() {
        val url = NaipImageryLoader.buildExportUrl(26.1, -97.2)
        assertTrue(url.startsWith("https://imagery.nationalmap.gov/arcgis/rest/services/USGSNAIPImagery/ImageServer/exportImage?"))
        assertTrue(url.contains("bboxSR=3857"))
        assertTrue(url.contains("imageSR=3857"))
        assertTrue(url.contains("size=1200%2C1200"))
        assertTrue(url.contains("format=jpgpng"))
        assertTrue(url.contains("f=image"))
    }
}
