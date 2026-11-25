package com.watchthisdrive.app.data

import android.content.Context

object ClubCarryDistances {
    val clubNames = listOf(
        "Driver", "3 Wood", "5 Wood", "3 Hybrid", "4 Hybrid",
        "4 Iron", "5 Iron", "6 Iron", "7 Iron", "8 Iron", "9 Iron",
        "Pitching Wedge", "Gap Wedge", "Sand Wedge", "Lob Wedge"
    )
    private const val PREFS = "club_carry_distances"

    fun load(context: Context): Map<String, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return clubNames.mapNotNull { club ->
            val value = prefs.getInt(club, 0)
            if (value in 1..400) club to value else null
        }.toMap()
    }

    fun save(context: Context, club: String, carryYards: Int?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (carryYards == null || carryYards !in 1..400) remove(club)
            else putInt(club, carryYards)
        }.apply()
    }
}
