package com.watchthisdrive.app.data

/**
 * Merge course facts using the same source priority for every game mode:
 * reconstructed mapped-hole pars first, published API pars as fallback, and
 * any value already on the round (including a golfer's manual correction)
 * remains authoritative.
 */
fun RoundScorecard.withResolvedCourseFacts(
    geometryPars: Map<String, Int?>,
    metadata: ExternalCourseMetadata?
): RoundScorecard {
    val publishedPars = metadata?.holes.orEmpty().mapNotNull { hole ->
        hole.par?.let { hole.number.toString() to it }
    }.toMap()
    val publishedYardages = metadata?.holes.orEmpty().mapNotNull { hole ->
        hole.yardage?.let { hole.number.toString() to it }
    }.toMap()
    return copy(
        holePars = holePars.mapValues { (number, existing) ->
            existing ?: geometryPars[number] ?: publishedPars[number]
        },
        holeYardages = holeYardages.mapValues { (number, existing) ->
            existing ?: publishedYardages[number]
        } + publishedYardages.filterKeys { it !in holeYardages }
    )
}

/** Backwards-compatible API-only enrichment; mapped geometry should be supplied when available. */
fun RoundScorecard.withCourseScorecardFacts(metadata: ExternalCourseMetadata): RoundScorecard =
    withResolvedCourseFacts(emptyMap(), metadata)
