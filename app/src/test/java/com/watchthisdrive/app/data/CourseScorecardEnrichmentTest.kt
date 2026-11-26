package com.watchthisdrive.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CourseScorecardEnrichmentTest {
    private val metadata = ExternalCourseMetadata(
        sourceId = "api-course",
        name = "Test Golf Club",
        city = null,
        state = null,
        holeCount = 2,
        totalPar = 7,
        totalYardage = 700,
        holes = listOf(
            CourseScorecardHole(1, 4, null, 385),
            CourseScorecardHole(2, 3, null, 155)
        ),
        tees = emptyList()
    )

    @Test fun selectedCourseFactsPopulateScorecardOnlyRound() {
        val round = RoundScorecard.new("Test Golf Club", mapOf("1" to null, "2" to null))
            .copy(scorecardOnly = true)
        val enriched = round.withCourseScorecardFacts(metadata)
        assertEquals(4, enriched.holePars["1"])
        assertEquals(3, enriched.holePars["2"])
        assertEquals(385, enriched.holeYardages["1"])
        assertEquals(155, enriched.holeYardages["2"])
        assertEquals(true, enriched.scorecardOnly)
    }

    @Test fun playerEnteredParAndExistingYardageArePreserved() {
        val round = RoundScorecard.new("Test Golf Club", mapOf("1" to 5, "2" to null),
            holeYardages = mapOf("1" to 400))
        val enriched = round.withCourseScorecardFacts(metadata)
        assertEquals(5, enriched.holePars["1"])
        assertEquals(3, enriched.holePars["2"])
        assertEquals(400, enriched.holeYardages["1"])
        assertEquals(155, enriched.holeYardages["2"])
    }

    @Test fun reconstructedGeometryParWinsOverPublishedApiParInBothModes() {
        val activeRound = RoundScorecard.new("Test Golf Club", mapOf("1" to null, "2" to null))
        val scorecardOnlyRound = RoundScorecard.new("Test Golf Club", mapOf("1" to null, "2" to null))
            .copy(scorecardOnly = true)
        val geometryPars = mapOf("1" to 5, "2" to 4)

        val activeResolved = activeRound.withResolvedCourseFacts(geometryPars, metadata)
        val scorecardResolved = scorecardOnlyRound.withResolvedCourseFacts(geometryPars, metadata)

        assertEquals(activeResolved.holePars, scorecardResolved.holePars)
        assertEquals(5, scorecardResolved.holePars["1"])
        assertEquals(4, scorecardResolved.holePars["2"])
    }

    @Test fun existingManualParWinsOverGeometryAndApiFallbackFillsOnlyMissingHoles() {
        val round = RoundScorecard.new("Test Golf Club", mapOf("1" to 6, "2" to null, "3" to null))
        val enriched = round.withResolvedCourseFacts(
            geometryPars = mapOf("1" to 5, "2" to 4),
            metadata = metadata.copy(holes = metadata.holes + CourseScorecardHole(3, 5, null, 510))
        )

        assertEquals(6, enriched.holePars["1"])
        assertEquals(4, enriched.holePars["2"])
        assertEquals(5, enriched.holePars["3"])
    }
}
