package com.watchthisdrive.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClubRecommendationTest {
    private val carries = mapOf("Driver" to 230, "3 Wood" to 215, "5 Iron" to 165, "7 Iron" to 140, "Pitching Wedge" to 105)

    @Test fun recommendsLongestClubWhenTargetIsBeyondEveryCarry() {
        val result = recommendClubForCarryDistance(carries, 265)
        assertEquals("Driver", result?.name)
        assertEquals(230, result?.carryYards)
        assertEquals(true, result?.isBeyondLongestCarry)
    }

    @Test fun recommendsShortestClubThatCanCoverDistanceWhenInRange() {
        val result = recommendClubForCarryDistance(carries, 180)
        assertEquals("3 Wood", result?.name)
        assertEquals(false, result?.isBeyondLongestCarry)
    }

    @Test fun recommendsShortestClubForVeryShortDistance() {
        val result = recommendClubForCarryDistance(carries, 45)
        assertEquals("Pitching Wedge", result?.name)
    }

    @Test fun ignoresInvalidCarryValuesAndReturnsNullWhenNoClubsAreConfigured() {
        assertNull(recommendClubForCarryDistance(emptyMap(), 100))
        assertNull(recommendClubForCarryDistance(mapOf("Odd" to 0, "Invalid" to 500), 100))
    }
}
