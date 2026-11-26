package com.watchthisdrive.app.data

/**
 * Merge published per-hole course facts into a round. Player-entered pars take
 * precedence; absent source values remain unknown rather than being guessed.
 */
fun RoundScorecard.withCourseScorecardFacts(metadata: ExternalCourseMetadata): RoundScorecard {
    val parsByHole = metadata.holes.mapNotNull { hole ->
        hole.par?.let { hole.number.toString() to it }
    }.toMap()
    val yardagesByHole = metadata.holes.mapNotNull { hole ->
        hole.yardage?.let { hole.number.toString() to it }
    }.toMap()
    return copy(
        holePars = holePars.mapValues { (number, existing) -> existing ?: parsByHole[number] },
        holeYardages = holeYardages + yardagesByHole
    )
}
