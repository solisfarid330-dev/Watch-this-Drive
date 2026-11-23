package com.watchthisdrive.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoleReconstructorTest {
    private fun feature(
        id: Long,
        kind: String,
        lat: Double,
        lon: Double,
        hole: String? = "1",
        points: List<GeoPoint> = listOf(GeoPoint(lat, lon))
    ) = CourseFeature(id, "way", kind, hole, null, points)

    @Test
    fun derivesAnExplicitlyMarkedTeeFromPlayingLineWhenTeePadIsMissing() {
        val line = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.002, 0.0004),
            GeoPoint(0.004, 0.0)
        )
        val geometry = CourseGeometry(99, "Line-only fixture", listOf(
            feature(2, "green", 0.004, 0.0, "1"),
            feature(3, "hole", 0.0, 0.0, "1", line)
        ))

        val hole = HoleReconstructor.reconstruct(geometry).single()

        assertEquals(line.first(), hole.tee)
        assertTrue(hole.teeSource.isPredicted)
        assertTrue(hole.teeSource.source.orEmpty().contains("Derived from playing-line endpoint"))
        assertEquals(1, hole.number)
        assertTrue(hole.lengthMeters in 400.0..500.0)
    }

    @Test
    fun oneUnnumberedPlayingLineCannotCreateDuplicateTeeCandidatesForNearbyGreens() {
        val line = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.002, 0.0004),
            GeoPoint(0.004, 0.0)
        )
        val geometry = CourseGeometry(99, "Shared line fixture", listOf(
            feature(2, "green", 0.004, 0.0001, null),
            feature(3, "green", 0.004, -0.0001, null),
            feature(4, "hole", 0.0, 0.0, points = line)
        ))

        val holes = HoleReconstructor.reconstruct(geometry)

        assertEquals(1, holes.size)
        assertTrue(holes.single().teeSource.isPredicted)
    }

    @Test
    fun mappedTeeIsPreferredOverAnInferredPlayingLineEndpoint() {
        val line = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.002, 0.0004),
            GeoPoint(0.004, 0.0)
        )
        val mappedTee = GeoPoint(0.00002, 0.00008)
        val geometry = CourseGeometry(99, "Mapped tee fixture", listOf(
            feature(1, "tee", mappedTee.latitude, mappedTee.longitude, "1"),
            feature(2, "green", 0.004, 0.0, "1"),
            feature(3, "hole", 0.0, 0.0, "1", line)
        ))

        val hole = HoleReconstructor.reconstruct(geometry).single()

        assertEquals(mappedTee, hole.tee)
        assertFalse(hole.teeSource.isPredicted)
    }

    @Test
    fun doesNotDeriveTeeFromPlayingLineAssignedToAnotherHole() {
        val line = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.002, 0.0004),
            GeoPoint(0.004, 0.0)
        )
        val geometry = CourseGeometry(99, "Mismatched line fixture", listOf(
            feature(2, "green", 0.004, 0.0, "1"),
            feature(3, "hole", 0.0, 0.0, "2", line)
        ))

        assertTrue(HoleReconstructor.reconstruct(geometry).isEmpty())
    }

    @Test
    fun greenPolygonUsesAreaCentroidInsteadOfBiasedClosedRingVertexAverage() {
        val greenRing = listOf(
            GeoPoint(0.0038, 0.0),
            GeoPoint(0.0038, 0.0004),
            GeoPoint(0.0042, 0.0004),
            GeoPoint(0.0042, 0.0),
            GeoPoint(0.0038, 0.0)
        )
        val geometry = CourseGeometry(99, "Green centroid fixture", listOf(
            feature(1, "tee", 0.0, 0.0),
            feature(2, "green", 0.0, 0.0, points = greenRing)
        ))

        val hole = HoleReconstructor.reconstruct(geometry).single()

        assertEquals(0.004, hole.green.latitude, 0.0000001)
        assertEquals(0.0002, hole.green.longitude, 0.0000001)
    }

    @Test
    fun explicitHoleNumbersPairTheCorrectTeeAndGreen() {
        val geometry = CourseGeometry(
            courseId = 99,
            courseName = "Fixture",
            features = listOf(
                feature(1, "tee", 0.0, 0.0, "1"),
                feature(2, "green", 0.004, 0.0, "1"),
                feature(3, "tee", 0.0, 0.01, "2"),
                feature(4, "green", 0.004, 0.01, "2")
            )
        )

        val holes = HoleReconstructor.reconstruct(geometry)

        assertEquals(listOf(1, 2), holes.map { it.number })
        assertEquals(2, holes.size)
        assertTrue(holes.all { it.lengthMeters in 400.0..500.0 })
    }

    @Test
    fun mappedFairwayIsPreservedInsteadOfReplacingItWithCorridor() {
        val fairway = listOf(
            GeoPoint(0.0002, -0.0002),
            GeoPoint(0.0012, -0.0002),
            GeoPoint(0.0038, 0.0002),
            GeoPoint(0.0038, 0.0010),
            GeoPoint(0.0012, 0.0006),
            GeoPoint(0.0002, 0.0006),
            GeoPoint(0.0002, -0.0002)
        )
        val geometry = CourseGeometry(
            99, "Fixture", listOf(
                feature(1, "tee", 0.0, 0.0),
                feature(2, "green", 0.004, 0.0004),
                feature(3, "fairway", 0.0, 0.0, points = fairway)
            )
        )

        val hole = HoleReconstructor.reconstruct(geometry).single()

        assertTrue(hole.usesMappedFairway)
        assertEquals(fairway, hole.mappedFairways.single().points)
        assertTrue(hole.fairwayEstimate.isEmpty())
    }

    @Test
    fun missingFairwayUsesExplicitlyEstimatedCorridor() {
        val geometry = CourseGeometry(
            99, "Fixture", listOf(
                feature(1, "tee", 0.0, 0.0),
                feature(2, "green", 0.004, 0.0)
            )
        )

        val hole = HoleReconstructor.reconstruct(geometry).single()

        assertFalse(hole.usesMappedFairway)
        assertTrue(hole.fairwayEstimate.size >= 4)
    }

    @Test
    fun mappedHoleCenterlineIsRetainedForDoglegRouting() {
        val centerline = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.001, 0.0005),
            GeoPoint(0.002, 0.0025),
            GeoPoint(0.004, 0.004)
        )
        val geometry = CourseGeometry(
            99, "Fixture", listOf(
                feature(1, "tee", 0.0, 0.0),
                feature(2, "green", 0.004, 0.004),
                feature(3, "hole", 0.0, 0.0, points = centerline)
            )
        )

        val hole = HoleReconstructor.reconstruct(geometry).single()

        assertEquals(3L, hole.mappedCenterline?.id)
        assertEquals(centerline, hole.mappedCenterline?.points)
    }
    @Test
    fun explicitHoleNumbersPreventCrossHoleTeeGreenPairing() {
        val geometry = CourseGeometry(
            99, "Fixture", listOf(
                feature(1, "tee", 0.0, 0.0, "1"),
                feature(2, "green", 0.004, 0.0, "2"),
                feature(3, "tee", 0.0, 0.01, "2"),
                feature(4, "green", 0.004, 0.01, "1")
            )
        )

        assertTrue(HoleReconstructor.reconstruct(geometry).isEmpty())
    }
    @Test
    fun mappedTreesAndPathsAreAssociatedWithTheirNearbyHole() {
        val fairway = listOf(
            GeoPoint(0.0002, -0.0002),
            GeoPoint(0.0038, -0.0002),
            GeoPoint(0.0038, 0.0006),
            GeoPoint(0.0002, 0.0006),
            GeoPoint(0.0002, -0.0002)
        )
        val geometry = CourseGeometry(
            99, "Fixture", listOf(
                feature(1, "tee", 0.0, 0.0),
                feature(2, "green", 0.004, 0.0002),
                feature(3, "fairway", 0.0, 0.0, points = fairway),
                feature(4, "tree", 0.002, 0.0008),
                feature(5, "path", 0.002, 0.0009, points = listOf(
                    GeoPoint(0.001, 0.0009), GeoPoint(0.003, 0.0009)
                ))
            )
        )

        val hole = HoleReconstructor.reconstruct(geometry).single()

        assertEquals(1, hole.trees.size)
        assertEquals(5L, hole.paths.single().id)
    }

    @Test
    fun estimatedFairwayFollowsMappedDoglegCenterline() {
        val route = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.001, 0.0004),
            GeoPoint(0.0012, 0.0025),
            GeoPoint(0.004, 0.003)
        )
        val geometry = CourseGeometry(99, "Dogleg", listOf(
            feature(1, "tee", 0.0, 0.0),
            feature(2, "green", 0.004, 0.003),
            feature(3, "hole", 0.0, 0.0, points = route)
        ))

        val hole = HoleReconstructor.reconstruct(geometry).single()
        val directMidpoint = GeoPoint(0.002, 0.0015)
        val routeBend = route[2]

        assertEquals(route, hole.mappedCenterline?.points)
        assertTrue(hole.fairwayEstimate.isNotEmpty())
        // Dogleg yardage follows the trusted mapped route, not the shorter
        // straight-line distance between tee and green.
        assertTrue(hole.lengthMeters > 600.0)
        // The generated corridor must preserve the dogleg bend, not collapse
        // to the straight tee-green axis.
        assertTrue(hole.fairwayEstimate.any { point ->
            kotlin.math.abs(point.longitude - routeBend.longitude) < 0.0003 &&
                kotlin.math.abs(point.latitude - routeBend.latitude) < 0.0003
        })
        assertTrue(kotlin.math.abs(routeBend.longitude - directMidpoint.longitude) > 0.0005)
    }

    @Test
    fun centerlineMustConnectNearBothHoleEndpoints() {
        val unrelated = listOf(
            GeoPoint(0.001, 0.002),
            GeoPoint(0.002, 0.002),
            GeoPoint(0.003, 0.002)
        )
        val geometry = CourseGeometry(99, "Fixture", listOf(
            feature(1, "tee", 0.0, 0.0),
            feature(2, "green", 0.004, 0.004),
            feature(3, "hole", 0.0, 0.0, points = unrelated)
        ))

        val hole = HoleReconstructor.reconstruct(geometry).single()
        assertEquals(null, hole.mappedCenterline)
    }

    @Test
    fun centerlineThatLeavesMappedFairwayIsRejectedButFairwayIsPreserved() {
        val fairway = listOf(
            GeoPoint(0.0002, -0.00025),
            GeoPoint(0.0038, -0.00025),
            GeoPoint(0.0038, 0.00025),
            GeoPoint(0.0002, 0.00025),
            GeoPoint(0.0002, -0.00025)
        )
        // Endpoints are plausible, but the route makes a large lateral detour
        // outside the mapped fairway between the tee and green.
        val badRoute = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.001, 0.0018),
            GeoPoint(0.0025, 0.0018),
            GeoPoint(0.004, 0.0)
        )
        val geometry = CourseGeometry(99, "Alignment fixture", listOf(
            feature(1, "tee", 0.0, 0.0),
            feature(2, "green", 0.004, 0.0),
            feature(3, "fairway", 0.0, 0.0, points = fairway),
            feature(4, "hole", 0.0, 0.0, points = badRoute)
        ))

        val hole = HoleReconstructor.reconstruct(geometry).single()

        assertEquals(null, hole.mappedCenterline)
        assertTrue(hole.usesMappedFairway)
        assertEquals(fairway, hole.mappedFairways.single().points)
    }

    @Test
    fun explicitlyNumberedFairwayFromAnotherHoleIsNotAssigned() {
        val neighboringFairway = listOf(
            GeoPoint(0.0002, -0.0003),
            GeoPoint(0.0038, -0.0003),
            GeoPoint(0.0038, 0.0003),
            GeoPoint(0.0002, 0.0003),
            GeoPoint(0.0002, -0.0003)
        )
        val geometry = CourseGeometry(99, "Numbered fairway fixture", listOf(
            feature(1, "tee", 0.0, 0.0, "1"),
            feature(2, "green", 0.004, 0.0, "1"),
            feature(3, "fairway", 0.0, 0.0, "2", neighboringFairway)
        ))

        val hole = HoleReconstructor.reconstruct(geometry).single()

        assertTrue(hole.mappedFairways.isEmpty())
        assertFalse(hole.usesMappedFairway)
    }

    @Test
    fun overlappingFairwayPolygonsCanFormOneConnectedHoleRoute() {
        val first = listOf(
            GeoPoint(0.0002, -0.0005),
            GeoPoint(0.0022, -0.0005),
            GeoPoint(0.0022, 0.0005),
            GeoPoint(0.0002, 0.0005),
            GeoPoint(0.0002, -0.0005)
        )
        val second = listOf(
            GeoPoint(0.0016, -0.0001),
            GeoPoint(0.0038, -0.0001),
            GeoPoint(0.0038, 0.0009),
            GeoPoint(0.0016, 0.0009),
            GeoPoint(0.0016, -0.0001)
        )
        val geometry = CourseGeometry(99, "Connected fairway fixture", listOf(
            feature(1, "tee", 0.0, 0.0),
            feature(2, "green", 0.004, 0.0004),
            feature(3, "fairway", 0.0, 0.0, points = first),
            feature(4, "fairway", 0.0, 0.0, points = second)
        ))

        val hole = HoleReconstructor.reconstruct(geometry).single()

        assertEquals(setOf(3L, 4L), hole.mappedFairways.map { it.id }.toSet())
    }

    @Test
    fun centerlineFollowingMappedFairwayIsRetained() {
        val fairway = listOf(
            GeoPoint(0.0002, -0.0003),
            GeoPoint(0.0038, -0.0003),
            GeoPoint(0.0038, 0.0003),
            GeoPoint(0.0002, 0.0003),
            GeoPoint(0.0002, -0.0003)
        )
        val route = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.001, 0.0),
            GeoPoint(0.0025, 0.0),
            GeoPoint(0.004, 0.0)
        )
        val geometry = CourseGeometry(99, "Aligned fixture", listOf(
            feature(1, "tee", 0.0, 0.0),
            feature(2, "green", 0.004, 0.0),
            feature(3, "fairway", 0.0, 0.0, points = fairway),
            feature(4, "hole", 0.0, 0.0, points = route)
        ))

        val hole = HoleReconstructor.reconstruct(geometry).single()

        assertEquals(route, hole.mappedCenterline?.points)
        assertTrue(hole.usesMappedFairway)
    }

    @Test
    fun parAndHandicapComeOnlyFromTheMatchingHoleMetadata() {
        val geometry = CourseGeometry(99, "Metadata fixture", listOf(
            CourseFeature(1, "way", "tee", "1", null, listOf(GeoPoint(0.0, 0.0))),
            CourseFeature(2, "way", "green", "1", null, listOf(GeoPoint(0.004, 0.0))),
            CourseFeature(3, "relation", "hole", "1", null,
                listOf(GeoPoint(0.0, 0.0), GeoPoint(0.002, 0.0), GeoPoint(0.004, 0.0)),
                par = 5, handicap = 7),
            CourseFeature(4, "relation", "hole", "2", null,
                listOf(GeoPoint(0.0, 0.01), GeoPoint(0.004, 0.01)),
                par = 3, handicap = 1)
        ))

        val hole = HoleReconstructor.reconstruct(geometry).single()

        assertEquals(5, hole.par)
        assertEquals(7, hole.handicap)
    }

    @Test
    fun centerlineAndFairwayMustAgreeAcrossTheFullRoute() {
        val fairway = listOf(
            GeoPoint(0.0002, -0.00035),
            GeoPoint(0.0038, -0.00035),
            GeoPoint(0.0038, 0.00035),
            GeoPoint(0.0002, 0.00035),
            GeoPoint(0.0002, -0.00035)
        )
        val route = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.001, 0.00015),
            GeoPoint(0.002, 0.00018),
            GeoPoint(0.003, -0.00012),
            GeoPoint(0.004, 0.0)
        )
        val geometry = CourseGeometry(99, "Alignment fixture", listOf(
            feature(1, "tee", 0.0, 0.0),
            feature(2, "green", 0.004, 0.0),
            feature(3, "fairway", 0.0, 0.0, points = fairway),
            feature(4, "hole", 0.0, 0.0, points = route)
        ))

        val hole = HoleReconstructor.reconstruct(geometry).single()

        assertEquals(route, hole.mappedCenterline?.points)
        assertTrue(hole.usesMappedFairway)
    }

    @Test
    fun centerlineWithSustainedLateralFairwayDepartureIsRejected() {
        val fairway = listOf(
            GeoPoint(0.0002, -0.00025),
            GeoPoint(0.0038, -0.00025),
            GeoPoint(0.0038, 0.00025),
            GeoPoint(0.0002, 0.00025),
            GeoPoint(0.0002, -0.00025)
        )
        val route = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.001, 0.0008),
            GeoPoint(0.003, 0.0008),
            GeoPoint(0.004, 0.0)
        )
        val geometry = CourseGeometry(99, "Moderate offset fixture", listOf(
            feature(1, "tee", 0.0, 0.0),
            feature(2, "green", 0.004, 0.0),
            feature(3, "fairway", 0.0, 0.0, points = fairway),
            feature(4, "hole", 0.0, 0.0, points = route)
        ))

        val hole = HoleReconstructor.reconstruct(geometry).single()

        assertEquals(null, hole.mappedCenterline)
        assertTrue(hole.usesMappedFairway)
        assertEquals(fairway, hole.mappedFairways.single().points)
    }

    @Test
    fun explicitlyNumberedFairwayFragmentsSurviveWhenCenterlineOnlyCoversPartOfThem() {
        val first = listOf(
            GeoPoint(0.0002, -0.0003),
            GeoPoint(0.0018, -0.0003),
            GeoPoint(0.0018, 0.0003),
            GeoPoint(0.0002, 0.0003),
            GeoPoint(0.0002, -0.0003)
        )
        val second = listOf(
            GeoPoint(0.0020, -0.0003),
            GeoPoint(0.0038, -0.0003),
            GeoPoint(0.0038, 0.0003),
            GeoPoint(0.0020, 0.0003),
            GeoPoint(0.0020, -0.0003)
        )
        val route = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.001, 0.00015),
            GeoPoint(0.003, 0.00015),
            GeoPoint(0.004, 0.0)
        )
        val geometry = CourseGeometry(99, "Numbered dogleg fairway", listOf(
            feature(1, "tee", 0.0, 0.0, "1"),
            feature(2, "green", 0.004, 0.0, "1"),
            feature(3, "fairway", 0.0, 0.0, "1", first),
            feature(4, "fairway", 0.0, 0.0, "1", second),
            feature(5, "hole", 0.0, 0.0, "1", route)
        ))

        val hole = HoleReconstructor.reconstruct(geometry).single()

        assertTrue(hole.usesMappedFairway)
        assertEquals(setOf(3L, 4L), hole.mappedFairways.map { it.id }.toSet())
        assertTrue(hole.fairwayEstimate.isEmpty())
    }

    @Test
    fun explicitlyNumberedHazardsFromNeighboringHoleAreExcluded() {
        val route = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.002, 0.0), GeoPoint(0.004, 0.0))
        val geometry = CourseGeometry(99, "Hazard scoping fixture", listOf(
            feature(1, "tee", 0.0, 0.0, "1"),
            feature(2, "green", 0.004, 0.0, "1"),
            feature(3, "hole", 0.0, 0.0, "1", route),
            feature(4, "bunker", 0.002, 0.0002, "2", listOf(
                GeoPoint(0.0019, 0.0001), GeoPoint(0.0021, 0.0001),
                GeoPoint(0.0021, 0.0003), GeoPoint(0.0019, 0.0003),
                GeoPoint(0.0019, 0.0001)
            )),
            feature(5, "bunker", 0.003, -0.0002, null, listOf(
                GeoPoint(0.0029, -0.0003), GeoPoint(0.0031, -0.0003),
                GeoPoint(0.0031, -0.0001), GeoPoint(0.0029, -0.0001),
                GeoPoint(0.0029, -0.0003)
            ))
        ))

        val hole = HoleReconstructor.reconstruct(geometry).single()
        assertEquals(listOf(5L), hole.bunkers.map { it.id })
    }

}
