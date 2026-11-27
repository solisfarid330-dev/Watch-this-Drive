package com.watchthisdrive.app.data

import android.content.Context

object ClubCarryDistances {
    val clubNames = listOf(
        "Driver", "3 Wood", "5 Wood", "3 Hybrid", "4 Hybrid",
        "4 Iron", "5 Iron", "6 Iron", "7 Iron", "8 Iron", "9 Iron",
        "Pitching Wedge", "Gap Wedge", "Sand Wedge", "Lob Wedge"
    )
    private const val PREFS = "club_carry_distances"
    private const val DEFAULTS_INITIALIZED = "starter_carries_initialized"

    // Generic editable starter carry estimates in yards, not player-specific recommendations.
    private val starterCarries = linkedMapOf(
        "Driver" to 220, "3 Wood" to 200, "5 Wood" to 185,
        "4 Iron" to 170, "5 Iron" to 160, "6 Iron" to 150,
        "7 Iron" to 140, "8 Iron" to 130, "9 Iron" to 120,
        "Pitching Wedge" to 105,
        "Gap Wedge" to 90, "Sand Wedge" to 75, "Lob Wedge" to 60
    )

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    fun load(context: Context): Map<String, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(DEFAULTS_INITIALIZED, false)) {
            val editor = prefs.edit()
            starterCarries.forEach { (club, yards) ->
                if (!prefs.contains(club)) editor.putInt(club, yards)
            }
            editor.putBoolean(DEFAULTS_INITIALIZED, true).apply()
        }
        return clubNames.mapNotNull { club ->
            val value = prefs.getInt(club, 0)
            if (value in 1..400) club to value else null
        }.toMap()
    }

    fun save(context: Context, club: String, carryYards: Int?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putBoolean(DEFAULTS_INITIALIZED, true)
            if (carryYards == null || carryYards !in 1..400) remove(club)
            else putInt(club, carryYards)
        }.apply()
    }
}
