package com.watchthisdrive.app.data

/**
 * Honest, source-based diagnostics for a course reconstruction.
 * These counts describe retrieved OSM data; they are not claims about
 * everything physically present on the course.
 */
data class CourseMappingQuality(
    val retrievedFeatureCount: Int,
    val holeCenterlineCount: Int,
    val reconstructedHoleCount: Int,
    val teeCount: Int,
    val greenCount: Int,
    val fairwayCount: Int,
    val bunkerCount: Int,
    val waterCount: Int,
    val inferredFairwayCount: Int,
    val inferredHoleCount: Int,
    val expectedHoleCount: Int?,
    val missingNumberedHoles: List<Int>,
    val unresolvedNumberedHoles: List<Int>,
    val geometryIssues: Int,
    val holesWithoutGreen: Int
) {
    val needsAttention: Boolean
        get() = unresolvedNumberedHoles.isNotEmpty() ||
            holesWithoutGreen > 0 || geometryIssues > 0 ||
            holeCenterlineCount == 0 || greenCount == 0 ||
            fairwayCount == 0

    companion object {
        fun assess(
            geometry: CourseGeometry,
            reconstruction: CourseReconstruction
        ): CourseMappingQuality {
            val usable = geometry.features.filter { it.hasUsableGeometry }
            val sourceUsable = usable.filter { it.origin == CourseFeatureOrigin.SOURCE }
            val centerlines = sourceUsable.filter { it.featureType == "hole" }
            val numbered = centerlines.mapNotNull { it.holeNumber?.toIntOrNull() }
                .filter { it in 1..72 }.toSet()
            // Prefer explicit course metadata; otherwise use a complete 9/18 numbering range.
            val expected = geometry.expectedHoleCount?.takeIf { it == 9 || it == 18 }
                ?: when {
                    (numbered.maxOrNull() ?: 0) > 9 -> 18
                    numbered.maxOrNull() == 9 -> 9
                    else -> null
                }
            val missing = if (numbered.size >= 2 && expected != null) {
                (1..expected).filterNot(numbered::contains)
            } else emptyList()
            val inferredHoleNumbers = usable.filter { it.origin == CourseFeatureOrigin.INFERRED && it.featureType == "hole" }
                .mapNotNull { it.holeNumber?.toIntOrNull() }.toSet()
            val unresolved = missing.filterNot(inferredHoleNumbers::contains)
            fun count(type: String) = sourceUsable.count { it.featureType == type }
            return CourseMappingQuality(
                retrievedFeatureCount = geometry.features.size,
                holeCenterlineCount = centerlines.size,
                reconstructedHoleCount = reconstruction.holes.size,
                teeCount = count("tee"),
                greenCount = count("green"),
                fairwayCount = count("fairway"),
                bunkerCount = count("bunker"),
                waterCount = count("water_hazard"),
                inferredFairwayCount = usable.count { it.featureType == "fairway" && it.origin == CourseFeatureOrigin.INFERRED },
                inferredHoleCount = usable.count { it.featureType == "hole" && it.origin == CourseFeatureOrigin.INFERRED },
                expectedHoleCount = expected,
                missingNumberedHoles = missing,
                unresolvedNumberedHoles = unresolved,
                geometryIssues = geometry.incompleteRelations.size,
                holesWithoutGreen = reconstruction.holes.count { it.green == null }
            )
        }
    }
}

enum class MissingCourseFeature(val label: String, val osmTag: String) {
    HOLE("Hole", "hole"),
    FAIRWAY("Fairway", "fairway"),
    GREEN("Green", "green"),
    TEE("Tee", "tee"),
    BUNKER("Bunker", "bunker"),
    WATER("Water hazard", "water_hazard")
}
