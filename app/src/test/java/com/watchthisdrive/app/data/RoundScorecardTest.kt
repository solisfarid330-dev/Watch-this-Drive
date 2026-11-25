package com.watchthisdrive.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoundScorecardTest {
    @Test
    fun userAndAdditionalPlayersKeepIndependentHoleScores() {
        val round = RoundScorecard.new("Test Course", mapOf("1" to 4, "2" to 3, "3" to 5))
        val user = round.user.copy(scores = mapOf("1" to 5, "2" to 3))
        val guest = RoundPlayer("guest", "Guest", mapOf("1" to 4, "2" to 4, "3" to 5))
        val updated = round.copy(players = listOf(user, guest))

        assertEquals(8, updated.totalFor(user))
        assertEquals(12, updated.totalFor(guest))
        assertEquals(1, updated.relativeToPar(user))
        assertEquals(1, updated.relativeToPar(guest))
    }

    @Test
    fun relativeScoreUsesOnlyHolesWithKnownPar() {
        val round = RoundScorecard.new("Test Course", mapOf("1" to 4, "2" to null, "3" to 5))
        val player = round.user.copy(scores = mapOf(1 to 5, 2 to 7, 3 to 4))

        assertEquals(0, round.relativeToPar(player))
    }

    @Test
    fun relativeScoreIsUnavailableUntilAParIsKnownForAScoredHole() {
        val round = RoundScorecard.new("Test Course", mapOf("1" to null))
        val player = round.user.copy(scores = mapOf("1" to 5))

        assertNull(round.relativeToPar(player))
    }
}
