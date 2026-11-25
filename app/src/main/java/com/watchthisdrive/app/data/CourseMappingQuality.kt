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
    val geometryIssues: Int,
    val holesWithoutGreen: Int
) {
    val needsAttention: Boolean
        get() = missingNumberedHoles.isNotEmpty() ||
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
            // Only report gaps when source numbering itself establishes the range.
            val missing = if (numbered.size >= 2 && numbered.maxOrNull()!! >= 9) {
                (1..numbered.maxOrNull()!!).filterNot(numbered::contains)
            } else emptyList()
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
                expectedHoleCount = when { numbered.maxOrNull() == 9 -> 9; (numbered.maxOrNull() ?: 0) > 9 -> 18; else -> null },
                missingNumberedHoles = missing,
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
