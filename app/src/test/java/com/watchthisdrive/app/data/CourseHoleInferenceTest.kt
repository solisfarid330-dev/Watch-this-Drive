package com.watchthisdrive.app.data

import org.junit.Assert.*
import org.junit.Test

class CourseHoleInferenceTest {
    private fun tee() = CourseFeature(106,"node","tee","6",null,listOf(GeoPoint(0.0,0.010)),CourseGeometryType.POINT)
    private fun green() = CourseFeature(206,"way","green","6",null,
        listOf(GeoPoint(0.004,0.014),GeoPoint(0.004,0.0142),GeoPoint(0.0042,0.0142),GeoPoint(0.0042,0.014),GeoPoint(0.004,0.014)),
        CourseGeometryType.POLYGON,listOf(listOf(GeoPoint(0.004,0.014),GeoPoint(0.004,0.0142),GeoPoint(0.0042,0.0142),GeoPoint(0.0042,0.014),GeoPoint(0.004,0.014))))
    private fun fairway() = CourseFeature(306,"way","fairway","6",null,
        listOf(GeoPoint(-0.0002,0.0098),GeoPoint(0.0044,0.0138),GeoPoint(0.0046,0.0136),GeoPoint(0.0,0.0096),GeoPoint(-0.0002,0.0098)),
        CourseGeometryType.POLYGON,listOf(listOf(GeoPoint(-0.0002,0.0098),GeoPoint(0.0044,0.0138),GeoPoint(0.0046,0.0136),GeoPoint(0.0,0.0096),GeoPoint(-0.0002,0.0098))))
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

    @Test fun doesNotInferWithoutMappedFairwayEvidence() {
        val input=CourseGeometry(1,"Generic",holes()+listOf(tee(),green()))
        assertFalse(CourseHoleInference.addInferredHoles(input).features.any{it.origin==CourseFeatureOrigin.INFERRED&&it.featureType=="hole"})
    }
}
