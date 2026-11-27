package com.watchthisdrive.app.data

data class ClubRecommendation(
    val name: String,
    val carryYards: Int,
    val isBeyondLongestCarry: Boolean
)

/**
 * Selects the shortest club that can cover the remaining distance. If the
 * target is beyond every configured carry, the longest club is still advised.
 * Carry values are through-the-air distances, before bounce and roll.
 */
fun recommendClubForCarryDistance(
    carryDistances: Map<String, Int>,
    distanceYards: Int
): ClubRecommendation? {
    if (carryDistances.isEmpty() || distanceYards < 0) return null
    val clubs = carryDistances.entries
        .filter { it.value in 1..400 }
        .sortedWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
    if (clubs.isEmpty()) return null
    val longest = clubs.last()
    val beyondLongest = distanceYards > longest.value
    val selected = if (beyondLongest) longest else clubs.firstOrNull { it.value >= distanceYards } ?: longest
    return ClubRecommendation(selected.key, selected.value, beyondLongest)
}
