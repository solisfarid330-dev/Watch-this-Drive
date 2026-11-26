package com.watchthisdrive.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.*

data class CourseScorecardHole(
    val number: Int,
    val par: Int?,
    val handicap: Int?,
    val yardage: Int?
)

data class CourseTeeSet(
    val name: String,
    val courseRating: Double?,
    val slopeRating: Int?,
    val totalYardage: Int?
)

data class ExternalCourseMetadata(
    val sourceId: String,
    val name: String,
    val city: String?,
    val state: String?,
    val holeCount: Int?,
    val totalPar: Int?,
    val totalYardage: Int?,
    val holes: List<CourseScorecardHole>,
    val tees: List<CourseTeeSet>,
    val attribution: String = "© OpenStreetMap contributors (ODbL 1.0) via OpenGolfAPI"
) {
    val hasUsefulScorecard: Boolean
        get() = holes.isNotEmpty() || tees.isNotEmpty() || holeCount != null
}

/**
 * OpenGolfAPI enriches an already-discovered OSM course. It never creates or
 * substitutes OSM boundary IDs, and a weak name/location match is rejected.
 */
object OpenGolfApiCourseLoader {
    // OpenGolf's published quickstart and deployed route source use /v1;
    // its current OpenAPI document also advertises /api/v1. Prefer /v1 and
    // fall back only on a 404 so either deployment alias is supported.
    private const val PRIMARY_BASE_URL = "https://api.opengolfapi.org/v1/courses"
    private const val COMPAT_BASE_URL = "https://api.opengolfapi.org/api/v1/courses"
    private const val MAX_MATCH_DISTANCE_METERS = 5000.0

    fun load(course: GolfCourse, onResult: (Result<ExternalCourseMetadata?>) -> Unit) {
        thread(name = "opengolf-course-metadata") {
            val result = runCatching { loadBlocking(course) }
            Handler(Looper.getMainLooper()).post { onResult(result) }
        }
    }

    internal fun buildSearchUrl(course: GolfCourse): String =
        buildSearchUrls(course).first()

    internal fun buildSearchUrls(course: GolfCourse): List<String> = listOf(PRIMARY_BASE_URL, COMPAT_BASE_URL).map { base ->
        "$base/search?q=${URLEncoder.encode(course.name.trim(), "UTF-8")}" +
            "&lat=${course.latitude}&lng=${course.longitude}&radius_mi=10&limit=20"
    }

    internal fun chooseMatch(
        candidates: JSONArray,
        courseName: String,
        latitude: Double,
        longitude: Double,
        osmId: Long
    ): JSONObject? {
        val targetName = normalizeName(courseName)
        val ranked = (0 until candidates.length()).mapNotNull { index ->
            val candidate = candidates.optJSONObject(index) ?: return@mapNotNull null
            val candidateName = normalizeName(
                candidate.optString("course_name", candidate.optString("name"))
            )
            if (candidateName.isBlank()) return@mapNotNull null
            val exactName = candidateName == targetName
            // Normalize common course-type suffix variants without fuzzy matching
            // the distinctive course name itself.
            fun identityName(value: String): String = value
                .removeSuffix("golfclub")
                .removeSuffix("golfcourse")
                .removeSuffix("countryclub")
            val similarName = identityName(candidateName).isNotBlank() &&
                identityName(candidateName) == identityName(targetName)

            val candidateLat = candidate.optDouble("latitude", Double.NaN)
            val candidateLon = candidate.optDouble("longitude", Double.NaN)
            val candidateOsmId = candidate.optLong("osm_id", Long.MIN_VALUE)
            val exactOsmMatch = candidateOsmId == osmId
            val distance = if (candidateLat.isFinite() && candidateLon.isFinite()) {
                distanceMeters(latitude, longitude, candidateLat, candidateLon)
            } else Double.POSITIVE_INFINITY

            if (!exactOsmMatch && distance > MAX_MATCH_DISTANCE_METERS) return@mapNotNull null
            // Name variants such as "Golf Club" vs "Golf Course" are common.
            // Accept only strong containment variants, and only with the existing
            // tight geographic match (or an exact OSM ID); never fuzzy-match by name alone.
            if (!exactOsmMatch && !exactName && !similarName) return@mapNotNull null
            Triple(candidate, if (exactOsmMatch) 0.0 else distance + if (exactName) 0.0 else 1.0, exactOsmMatch)
        }
        return ranked.minByOrNull { it.second }?.first
    }

    internal fun parseMetadata(
        detail: JSONObject,
        fallback: JSONObject
    ): ExternalCourseMetadata {
        // The API has returned both flat objects and {data:{course:{...}}}
        // envelopes. Normalize the course object before reading scalar fields.
        val payload = detail.optJSONObject("data") ?: detail
        val courseDetail = payload.optJSONObject("course") ?: payload
        val sourceId = courseDetail.optString("id").ifBlank { fallback.optString("id") }
        val name = courseDetail.optString("course_name")
            .ifBlank { courseDetail.optString("name") }
            .ifBlank { fallback.optString("course_name", fallback.optString("name")) }
        val holesArray = findArray(courseDetail, "holes")
            ?: findArray(courseDetail, "scorecard")
            ?: JSONArray()
        val holes = (0 until holesArray.length()).mapNotNull { index ->
            val item = holesArray.optJSONObject(index) ?: return@mapNotNull null
            val number = item.optInt("hole", item.optInt("hole_number", item.optInt("number", 0)))
            if (number !in 1..36) return@mapNotNull null
            CourseScorecardHole(
                number = number,
                par = item.optIntOrNull("par")?.takeIf { it in 3..6 },
                handicap = firstInt(item, "handicap", "handicap_index", "stroke_index", "mens_handicap"),
                yardage = firstInt(item, "yardage", "yards", "length_yards", "distance_yards", "yardage_yards")
                    ?: firstInt(item, "meters", "length_meters")?.let { (it * 1.0936133).roundToInt() }
            )
        }.distinctBy { it.number }.sortedBy { it.number }

        val teesArray = findArray(courseDetail, "tees") ?: JSONArray()
        val tees = (0 until teesArray.length()).mapNotNull { index ->
            val item = teesArray.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name")
                .ifBlank { item.optString("tee_name") }
                .ifBlank { item.optString("color") }
            if (name.isBlank()) return@mapNotNull null
            CourseTeeSet(
                name = name,
                courseRating = firstDouble(item, "course_rating", "rating"),
                slopeRating = firstInt(item, "slope_rating", "slope")?.takeIf { it in 55..200 },
                totalYardage = firstInt(item, "total_yardage", "yardage", "yards")
            )
        }

        val fallbackHoleCount = fallback.optIntOrNull("holes")
            ?: fallback.optIntOrNull("hole_count")
        return ExternalCourseMetadata(
            sourceId = sourceId,
            name = name,
            city = courseDetail.optString("city").ifBlank { fallback.optString("city") }.takeIf { it.isNotBlank() },
            state = courseDetail.optString("state").ifBlank { fallback.optString("state") }.takeIf { it.isNotBlank() },
            holeCount = courseDetail.optIntOrNull("hole_count")
                ?: courseDetail.optIntOrNull("holes_count")
                ?: fallbackHoleCount
                ?: holes.size.takeIf { it in 1..36 },
            totalPar = courseDetail.optIntOrNull("par_total")
                ?: courseDetail.optIntOrNull("total_par")
                ?: fallback.optIntOrNull("par_total"),
            totalYardage = courseDetail.optIntOrNull("total_yardage")
                ?: fallback.optIntOrNull("total_yardage")
                ?: tees.mapNotNull { it.totalYardage }.maxOrNull(),
            holes = holes,
            tees = tees
        )
    }

    private fun loadBlocking(course: GolfCourse): ExternalCourseMetadata? {
        val search = getCourseJson(course, "/search")
        val candidates = findArray(search, "courses")
            ?: findArray(search, "results")
            ?: JSONArray()
        val match = chooseMatch(
            candidates, course.name, course.latitude, course.longitude, course.id
        ) ?: return null
        val id = match.optString("id")
        if (id.isBlank()) return null
        val encodedId = URLEncoder.encode(id, "UTF-8")
        val detail = getCourseJson(course, "/$encodedId")
        // The course detail response includes a compact scorecard, while the
        // dedicated holes endpoint carries richer per-hole facts such as yardage.
        val holesDetail = runCatching { getCourseJson(course, "/$encodedId/holes") }.getOrNull()
        val teesDetail = runCatching { getCourseJson(course, "/$encodedId/tees") }.getOrNull()
        val enrichedDetail = JSONObject(detail.toString())
        holesDetail?.let { response ->
            (findArray(response, "holes") ?: findArray(response, "scorecard") ?: findArray(response, "data"))?.let {
                enrichedDetail.put("holes", it)
            }
        }
        teesDetail?.let { response ->
            (findArray(response, "tees") ?: findArray(response, "data"))?.let {
                enrichedDetail.put("tees", it)
            }
        }
        return parseMetadata(enrichedDetail, match).takeIf { it.hasUsefulScorecard }
    }

    private fun getCourseJson(course: GolfCourse, suffix: String): JSONObject {
        var lastFailure: Throwable? = null
        listOf(PRIMARY_BASE_URL, COMPAT_BASE_URL).forEachIndexed { index, base ->
            try {
                val query = if (suffix == "/search") {
                    "q=${URLEncoder.encode(course.name.trim(), "UTF-8")}&lat=${course.latitude}&lng=${course.longitude}&radius_mi=10&limit=20"
                } else null
                val url = if (query != null) "$base/search?$query" else "$base$suffix"
                return getJson(url)
            } catch (problem: Throwable) {
                lastFailure = problem
                val isNotFound = problem.message?.contains("HTTP 404") == true
                if (!isNotFound || index == 1) throw problem
            }
        }
        throw lastFailure ?: IllegalStateException("OpenGolfAPI course endpoint is unavailable")
    }

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 12000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "WatchThisDrive/0.3 Android Golf App")
            val code = connection.responseCode
            if (code !in 200..299) throw Exception("OpenGolfAPI returned HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return parseJsonResponse(body)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseJsonResponse(body: String): JSONObject {
        val trimmed = body.trimStart()
        return if (trimmed.startsWith("[")) JSONObject().put("data", JSONArray(body)) else JSONObject(body)
    }

    internal fun findArray(root: JSONObject, key: String): JSONArray? {
        root.optJSONArray(key)?.let { return it }
        root.optJSONArray("data")?.let { if (key == "data" || key in setOf("courses", "results", "holes", "scorecard", "tees")) return it }
        root.optJSONObject("data")?.optJSONArray(key)?.let { return it }
        root.optJSONObject("course")?.optJSONArray(key)?.let { return it }
        root.optJSONObject("data")?.optJSONObject("course")?.optJSONArray(key)?.let { return it }
        return null
    }

    private fun normalizeName(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")

    private fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val earthRadius = 6_371_008.8
        val lat1 = Math.toRadians(aLat)
        val lat2 = Math.toRadians(bLat)
        val deltaLat = lat2 - lat1
        val deltaLon = Math.toRadians(bLon - aLon)
        val h = sin(deltaLat / 2).pow(2) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2).pow(2)
        return 2.0 * earthRadius * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private fun firstInt(obj: JSONObject, vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { obj.optIntOrNull(it) }

    private fun firstDouble(obj: JSONObject, vararg keys: String): Double? =
        keys.firstNotNullOfOrNull { key ->
            if (!obj.has(key) || obj.isNull(key)) null
            else obj.optDouble(key, Double.NaN).takeIf { it.isFinite() }
        }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return when (val value = opt(key)) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }
}

/**
 * Applies only factual scorecard fields to already-mapped OSM hole features.
 * Yardages remain validation metadata; they never create or reshape geometry.
 */
internal fun enrichGeometryWithScorecard(
    geometry: CourseGeometry,
    metadata: ExternalCourseMetadata,
    preferredHoleCount: Int? = null
): CourseGeometry {
    val parsByHole = metadata.holes.mapNotNull { hole ->
        hole.par?.let { hole.number to it }
    }.toMap()
    val expected = preferredHoleCount?.takeIf { it == 9 || it == 18 }
        ?: metadata.holeCount?.takeIf { it == 9 || it == 18 }
        ?: geometry.expectedHoleCount
    return geometry.copy(
        expectedHoleCount = expected,
        features = geometry.features.map { feature ->
            val number = feature.holeNumber?.toIntOrNull()
            if (feature.featureType == "hole" && feature.par == null && number != null) {
                feature.copy(par = parsByHole[number] ?: feature.par)
            } else feature
        }
    )
}

/**
 * Public USGS/USDA NAIP imagery. The returned bitmap is visual evidence only;
 * it is not classified or promoted to course geometry.
 */
object NaipImageryLoader {
    private const val IMAGE_SERVER =
        "https://imagery.nationalmap.gov/arcgis/rest/services/USGSNAIPImagery/ImageServer"

    fun load(
        course: GolfCourse,
        onResult: (Result<Bitmap>) -> Unit
    ) {
        thread(name = "naip-course-imagery") {
            val result = runCatching {
                val connection = URL(buildExportUrl(course.latitude, course.longitude)).openConnection()
                    as HttpURLConnection
                try {
                    connection.connectTimeout = 10000
                    connection.readTimeout = 25000
                    connection.setRequestProperty("User-Agent", "WatchThisDrive/0.3 Android Golf App")
                    connection.setRequestProperty("Accept", "image/jpeg,image/png")
                    val code = connection.responseCode
                    if (code !in 200..299) throw Exception("NAIP imagery returned HTTP $code")
                    val bitmap = connection.inputStream.use { BitmapFactory.decodeStream(it) }
                        ?: throw Exception("NAIP imagery was unavailable at this location")
                    bitmap
                } finally {
                    connection.disconnect()
                }
            }
            Handler(Looper.getMainLooper()).post { onResult(result) }
        }
    }

    internal fun buildExportUrl(latitude: Double, longitude: Double): String {
        require(latitude in -85.0..85.0 && longitude in -180.0..180.0)
        val earthRadius = 6378137.0
        val latRadians = Math.toRadians(latitude)
        val x = earthRadius * Math.toRadians(longitude)
        val y = earthRadius * ln(tan(Math.PI / 4.0 + latRadians / 2.0))
        val halfWidth = 900.0 / cos(latRadians).coerceAtLeast(0.2)
        val bbox = "${x - halfWidth},${y - halfWidth},${x + halfWidth},${y + halfWidth}"
        val params = listOf(
            "bbox=${URLEncoder.encode(bbox, "UTF-8")}",
            "bboxSR=3857",
            "imageSR=3857",
            "size=1200,1200",
            "format=jpgpng",
            "pixelType=U8",
            "interpolation=RSP_Bilinear",
            "f=image"
        ).joinToString("&")
        return "$IMAGE_SERVER/exportImage?$params"
    }
}
