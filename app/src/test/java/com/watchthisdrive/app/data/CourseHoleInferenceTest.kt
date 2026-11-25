package com.watchthisdrive.app.data

import org.junit.Assert.*
import org.junit.Test

class CourseHoleInferenceTest {
    private fun tee() = CourseFeature(106,"node","tee","6",null,listOf(GeoPoint(0.0,0.010)),CourseGeometryType.POINT)
    private fun green() = CourseFeature(206,"way","green","6",null,
        listOf(GeoPoint(0.0024,0.0124),GeoPoint(0.0024,0.0126),GeoPoint(0.0026,0.0126),GeoPoint(0.0026,0.0124),GeoPoint(0.0024,0.0124)),
        CourseGeometryType.POLYGON,listOf(listOf(GeoPoint(0.0024,0.0124),GeoPoint(0.0024,0.0126),GeoPoint(0.0026,0.0126),GeoPoint(0.0026,0.0124),GeoPoint(0.0024,0.0124))))
    private fun fairway() = CourseFeature(306,"way","fairway","6",null,
        listOf(GeoPoint(-0.0002,0.0098),GeoPoint(0.0028,0.0122),GeoPoint(0.0030,0.0120),GeoPoint(0.0,0.0096),GeoPoint(-0.0002,0.0098)),
        CourseGeometryType.POLYGON,listOf(listOf(GeoPoint(-0.0002,0.0098),GeoPoint(0.0028,0.0122),GeoPoint(0.0030,0.0120),GeoPoint(0.0,0.0096),GeoPoint(-0.0002,0.0098))))
    private fun holes()=(1..9).filter{it!=6}.map { n -> CourseFeature(n.toLong(),"way","hole",n.toString(),null,
        listOf(GeoPoint(0.0,n*0.001),GeoPoint(0.004,n*0.001)),CourseGeometryType.LINE) }

    @Test fun infersOnlyWhenNumberedTeeGreenAndFairwayAgree() {
        val input=CourseGeometry(1,"Generic",holes()+listOf(tee(),green(),fairway()))
        val result=CourseHoleInference.addInferredHoles(input)
        val inferred=result.features.single{it.origin==CourseFeatureOrigin.INFERRED&&it.featureType=="hole"}
        assertEquals("6",inferred.holeNumber)
        assertEquals(0.65,inferred.confidence!!,0.0)
        assertEquals(1,CourseHoleInference.addInferredHoles(result).features.count{it.origin==CourseFeatureOrigin.INFERRED&&it.featureType=="hole"})
    }

    @Test fun doesNotGuessFromUnnumberedMarkers() {
        val input=CourseGeometry(1,"Generic",holes()+listOf(tee().copy(holeNumber=null),green().copy(holeNumber=null),fairway()))
        assertFalse(CourseHoleInference.addInferredHoles(input).features.any{it.origin==CourseFeatureOrigin.INFERRED&&it.featureType=="hole"})
    }

    @Test fun infersFromExplicitNumberedTeeAndGreenEvenWhenFairwayIsMissing() {
        val input=CourseGeometry(1,"Generic",holes()+listOf(tee(),green()))
        val inferred=CourseHoleInference.addInferredHoles(input).features.single {
            it.origin==CourseFeatureOrigin.INFERRED && it.featureType=="hole"
        }
        assertEquals("6", inferred.holeNumber)
        assertEquals(0.45, inferred.confidence!!, 0.0)
        assertEquals("inferred_from_numbered_tee_green_without_fairway", inferred.geometryStatus)
    }
    @Test fun reconstructsMissingUnnumberedHolesFromSouthPadreMapEvidence() {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/south-padre-course-overpass.json")
        val json = stream!!.bufferedReader().use { it.readText() }
        val parsed = CourseGeometryLoader.parseGeometry(
            json, GolfCourse(19071561L, "relation", "Validation course", 0.0, 0.0, 0f)
        )
        val withTwoMissing = parsed.copy(features = parsed.features.filterNot {
            it.featureType == "hole" && it.holeNumber in setOf("6", "7")
        })
        val result = CourseHoleInference.addInferredHoles(withTwoMissing)
        val inferred = result.features.filter { it.featureType == "hole" && it.origin == CourseFeatureOrigin.INFERRED }
        assertEquals(2, inferred.size)
        assertEquals(setOf("6", "7"), inferred.mapNotNull { it.holeNumber }.toSet())
    }

    @Test fun usesCourseMetadataToRecoverTheFinalNumberedHole() {
        val input = CourseGeometry(
            courseId = 1, courseName = "Nine-hole course",
            features = (1..8).map { n -> CourseFeature(
                n.toLong(), "way", "hole", n.toString(), null,
                listOf(GeoPoint(0.0, n * 0.001), GeoPoint(0.002, n * 0.001)),
                CourseGeometryType.LINE
            ) } + listOf(
                CourseFeature(109, "node", "tee", "9", null, listOf(GeoPoint(0.0, 0.010)), CourseGeometryType.POINT),
                CourseFeature(209, "way", "green", "9", null,
                    listOf(GeoPoint(0.0024,0.0124),GeoPoint(0.0024,0.0126),GeoPoint(0.0026,0.0126),GeoPoint(0.0026,0.0124),GeoPoint(0.0024,0.0124)),
                    CourseGeometryType.POLYGON,
                    listOf(listOf(GeoPoint(0.0024,0.0124),GeoPoint(0.0024,0.0126),GeoPoint(0.0026,0.0126),GeoPoint(0.0026,0.0124),GeoPoint(0.0024,0.0124)))),
                CourseFeature(309, "way", "fairway", "9", null,
                    listOf(GeoPoint(-0.0002,0.0098),GeoPoint(0.0028,0.0122),GeoPoint(0.0030,0.0120),GeoPoint(0.0,0.0096),GeoPoint(-0.0002,0.0098)),
                    CourseGeometryType.POLYGON,
                    listOf(listOf(GeoPoint(-0.0002,0.0098),GeoPoint(0.0028,0.0122),GeoPoint(0.0030,0.0120),GeoPoint(0.0,0.0096),GeoPoint(-0.0002,0.0098))))
            ),
            expectedHoleCount = 9
        )
        val result = CourseHoleInference.addInferredHoles(input)
        assertTrue(result.features.any { it.origin == CourseFeatureOrigin.INFERRED && it.featureType == "hole" && it.holeNumber == "9" })
    }

}
