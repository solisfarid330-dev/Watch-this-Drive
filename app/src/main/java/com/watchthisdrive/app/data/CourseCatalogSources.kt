package com.watchthisdrive.app.data

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

internal fun stableCourseRecordId(value: String): Long {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
    var bits = 0L
    for (index in 0 until 8) {
        bits = (bits shl 8) or (digest[index].toLong() and 0xffL)
    }
    return -((bits and Long.MAX_VALUE).coerceAtLeast(1L))
}

/**
 * Supplemental public course catalogs. Provider records are discovery metadata,
 * never substitutes for surveyed hole geometry. OSM remains the geometry source.
 *
 * TeeTime.AI: ODbL / CC-BY-SA; OpenGolfAPI: ODbL.
 */
object CourseCatalogSources {
    private const val TEE_TIME_BASE = "https://teetime.ai/api/v1"
    private const val OPEN_GOLF_BASE = "https://api.opengolfapi.org/v1"
    private const val NOMINATIM_REVERSE = "https://nominatim.openstreetmap.org/reverse"
    private const val CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    private const val SEARCH_RADIUS_METERS = 15_000f

    private data class Region(
        val countryCode: String,
        val admin1: String?,
        val stateCode: String?,
        val city: String?
    )

    /**
     * Returns nearby provider records. A seven-day on-device cache prevents
     * repeated downloads and keeps course discovery useful when offline.
     */
    fun searchNearby(
        context: Context,
        latitude: Double,
        longitude: Double,
        searchTerm: String? = null
    ): List<GolfCourse> {
        val offline = runCatching {
            OfflineCourseCatalog.searchNearby(context.applicationContext, latitude, longitude)
        }.getOrDefault(emptyList())
        val region = runCatching {
            reverseRegion(context.applicationContext, latitude, longitude)
        }.getOrNull() ?: return offline
        val cacheKey = listOf(region.countryCode, region.stateCode ?: region.admin1.orEmpty())
            .joinToString("_").lowercase(Locale.US).replace(Regex("[^a-z0-9_-]"), "_")
        val cacheFile = File(context.filesDir, "course_catalog_$cacheKey.json")
        val cached = readCache(cacheFile)
        val cacheFresh = cached != null && System.currentTimeMillis() - cacheFile.lastModified() < CACHE_MAX_AGE_MS
        if (cacheFresh) return mergeCatalogs(offline + cached!!).filterNearby(latitude, longitude)

        val records = linkedMapOf<String, GolfCourse>()
        offline.forEach { course ->
            course.openGolfId?.let { records["open:$it"] = course }
        }
        val failures = mutableListOf<Throwable>()

        runCatching { fetchTeeTimeRegion(region) }
            .onSuccess { list -> list.forEach { records["tee:${it.teeTimeId}"] = it } }
            .onFailure { failures += it }

        if (region.countryCode == "US" && region.stateCode != null) {
            runCatching { fetchOpenGolfState(region.stateCode) }
                .onSuccess { list -> list.forEach { records["open:${it.openGolfId}"] = it } }
                .onFailure { failures += it }

            val query = searchTerm?.takeIf { it.isNotBlank() } ?: region.city
            if (!query.isNullOrBlank()) {
                runCatching { fetchOpenGolfSearch(query, region.stateCode) }
                    .onSuccess { list -> list.forEach { records["open:${it.openGolfId}"] = it } }
                    .onFailure { failures += it }
            }
        }

        val result = mergeCatalogs(records.values.toList())
        if (result.isNotEmpty()) writeCache(cacheFile, result)
        return mergeCatalogs(result.ifEmpty { offline + cached.orEmpty() }).filterNearby(latitude, longitude)
    }

    /** Direct course-name search; unlike nearby search this does not require geocoding. */
    fun searchByText(context: Context, query: String): List<GolfCourse> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return emptyList()
        val offline = runCatching {
            OfflineCourseCatalog.searchByName(context.applicationContext, normalizedQuery)
        }.getOrDefault(emptyList())
        val cacheFile = File(
            context.filesDir,
            "course_query_${stableCourseRecordId(normalizedQuery.lowercase(Locale.ROOT))}.json"
        )
        val cached = readCache(cacheFile)
        if (cached != null && System.currentTimeMillis() - cacheFile.lastModified() < CACHE_MAX_AGE_MS) {
            return mergeCatalogs(offline + cached)
        }
        val results = linkedMapOf<String, GolfCourse>()
        offline.forEach { course ->
            course.openGolfId?.let { results["open:$it"] = course }
        }
        runCatching { fetchOpenGolfSearch(normalizedQuery, null) }
            .onSuccess { list -> list.forEach { course -> course.openGolfId?.let { results["open:$it"] = course } } }
        runCatching { fetchTeeTimeSearch(normalizedQuery) }
            .onSuccess { list -> list.forEach { course -> course.teeTimeId?.let { results["tee:$it"] = course } } }
        val merged = mergeCatalogs(results.values.toList())
        if (merged.isNotEmpty()) writeCache(cacheFile, merged)
        return mergeCatalogs(merged.ifEmpty { offline + cached.orEmpty() })
    }

    private fun fetchTeeTimeSearch(query: String): List<GolfCourse> {
        val connection = URL("https://teetime.ai/api/mcp").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 6_000
            connection.readTimeout = 12_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "WatchThisDrive/0.2 (Android golf app)")
            val body = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/call")
                .put("params", JSONObject()
                    .put("name", "search_golf_courses")
                    .put("arguments", JSONObject().put("query", query).put("country", "US").put("limit", 30)))
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("TeeTime.AI search returned HTTP $code")
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val result = root.optJSONObject("result") ?: return emptyList()
            val structured = result.optJSONObject("structuredContent")
            val structuredCourses = structured?.optJSONArray("courses")
            if (structuredCourses != null) {
                return (0 until structuredCourses.length())
                    .mapNotNull { parseTeeTime(structuredCourses.optJSONObject(it)) }
            }
            val content = result.optJSONArray("content") ?: return emptyList()
            for (index in 0 until content.length()) {
                val text = content.optJSONObject(index)?.optString("text").orEmpty()
                if (text.isBlank()) continue
                val parsed = runCatching { JSONObject(text) }.getOrNull()
                val courses = parsed?.optJSONArray("courses")
                if (courses != null) {
                    return (0 until courses.length())
                        .mapNotNull { parseTeeTime(courses.optJSONObject(it)) }
                }
                val array = runCatching { JSONArray(text) }.getOrNull()
                if (array != null) {
                    return (0 until array.length()).mapNotNull { parseTeeTime(array.optJSONObject(it)) }
                }
            }
            return emptyList()
        } finally {
            connection.disconnect()
        }
    }

    private fun mergeCatalogs(courses: List<GolfCourse>): List<GolfCourse> {
        val groups = mutableListOf<MutableList<GolfCourse>>()
        courses.sortedBy { it.distanceMeters }.forEach { course ->
            val normalized = course.name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
            val group = groups.firstOrNull { existing ->
                val first = existing.first()
                val firstName = first.name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
                if (firstName != normalized) false else {
                    val distance = FloatArray(1)
                    Location.distanceBetween(
                        course.latitude, course.longitude,
                        first.latitude, first.longitude, distance
                    )
                    distance[0] <= 1_500f
                }
            }
            if (group == null) groups += mutableListOf(course) else group += course
        }
        return groups.map { group ->
            val best = group.maxByOrNull { candidate ->
                listOfNotNull(candidate.address, candidate.phone, candidate.website).size * 10 +
                    candidate.scorecard.size * 2 +
                    (candidate.par?.let { 1 } ?: 0) +
                    (candidate.holes?.let { 1 } ?: 0)
            } ?: group.first()
            val identity = group.firstOrNull { it.openGolfId != null }
                ?: group.firstOrNull { it.teeTimeId != null }
                ?: best
            best.copy(
                id = identity.id,
                elementType = identity.elementType,
                city = best.city ?: group.firstNotNullOfOrNull { it.city },
                region = best.region ?: group.firstNotNullOfOrNull { it.region },
                countryCode = best.countryCode ?: group.firstNotNullOfOrNull { it.countryCode },
                address = best.address ?: group.firstNotNullOfOrNull { it.address },
                postalCode = best.postalCode ?: group.firstNotNullOfOrNull { it.postalCode },
                holes = best.holes ?: group.firstNotNullOfOrNull { it.holes },
                par = best.par ?: group.firstNotNullOfOrNull { it.par },
                courseType = best.courseType ?: group.firstNotNullOfOrNull { it.courseType },
                website = best.website ?: group.firstNotNullOfOrNull { it.website },
                phone = best.phone ?: group.firstNotNullOfOrNull { it.phone },
                sourceNames = group.flatMap { it.sourceNames }.toSet(),
                openGolfId = group.firstNotNullOfOrNull { it.openGolfId },
                teeTimeId = group.firstNotNullOfOrNull { it.teeTimeId },
                scorecard = group.maxByOrNull { it.scorecard.size }?.scorecard.orEmpty().ifEmpty { best.scorecard }
            )
        }
    }

    private fun List<GolfCourse>.filterNearby(latitude: Double, longitude: Double): List<GolfCourse> =
        mapNotNull { course ->
            if (!course.latitude.isFinite() || !course.longitude.isFinite()) return@mapNotNull null
            val distance = FloatArray(1)
            Location.distanceBetween(latitude, longitude, course.latitude, course.longitude, distance)
            if (distance[0] > SEARCH_RADIUS_METERS) return@mapNotNull null
            course.copy(distanceMeters = distance[0])
        }.sortedBy { it.distanceMeters }

    private fun reverseRegion(context: Context, latitude: Double, longitude: Double): Region? {
        val cacheKey = "course_region_${"%.3f".format(Locale.US, latitude)}_${"%.3f".format(Locale.US, longitude)}"
        val cacheFile = File(context.filesDir, "$cacheKey.json")
        if (cacheFile.exists() &&
            System.currentTimeMillis() - cacheFile.lastModified() < CACHE_MAX_AGE_MS
        ) {
            runCatching {
                val cached = JSONObject(cacheFile.readText())
                return Region(
                    cached.optString("country").takeIf { it.isNotBlank() } ?: return@runCatching null,
                    cached.optString("admin1").takeIf { it.isNotBlank() },
                    cached.optString("stateCode").takeIf { it.isNotBlank() },
                    cached.optString("city").takeIf { it.isNotBlank() }
                )
            }.getOrNull()?.let { return it }
        }
        val url = URL(
            "$NOMINATIM_REVERSE?format=jsonv2&addressdetails=1&zoom=10" +
                "&lat=$latitude&lon=$longitude"
        )
        val json = requestJson(url)
        val address = json.optJSONObject("address") ?: return null
        val country = address.optString("country_code").uppercase(Locale.US)
        if (country.isBlank()) return null
        val iso = address.optString("ISO3166-2-lvl4")
        val stateCode = iso.takeIf { it.startsWith("US-") }?.substringAfter("US-")
            ?: address.optString("state_code").takeIf { it.length == 2 }?.uppercase(Locale.US)
        val admin1 = address.optString("state").takeIf { it.isNotBlank() }
            ?: address.optString("region").takeIf { it.isNotBlank() }
        val city = sequenceOf("city", "town", "village", "municipality", "county")
            .map { address.optString(it) }.firstOrNull { it.isNotBlank() }
        val region = Region(country, admin1, stateCode, city)
        runCatching {
            cacheFile.writeText(
                JSONObject()
                    .put("country", region.countryCode)
                    .put("admin1", region.admin1)
                    .put("stateCode", region.stateCode)
                    .put("city", region.city)
                    .toString()
            )
        }
        return region
    }

    private fun fetchTeeTimeRegion(region: Region): List<GolfCourse> {
        val admin = region.admin1 ?: return emptyList()
        val result = mutableListOf<GolfCourse>()
        var offset = 0
        var total = Int.MAX_VALUE
        while (offset < total && offset < 5_000) {
            val url = URL(
                "$TEE_TIME_BASE/courses?country=${enc(region.countryCode)}" +
                    "&admin1=${enc(admin)}&status=active&limit=1000&offset=$offset"
            )
            val envelope = requestJson(url)
            val courses = envelope.optJSONArray("courses") ?: JSONArray()
            total = envelope.optInt("total", courses.length())
            for (i in 0 until courses.length()) {
                parseTeeTime(courses.optJSONObject(i))?.let(result::add)
            }
            if (courses.length() == 0) break
            offset += courses.length()
        }
        return result
    }

    private fun fetchOpenGolfState(stateCode: String): List<GolfCourse> {
        val envelope = requestJson(URL("$OPEN_GOLF_BASE/courses/state/${enc(stateCode)}?limit=500"))
        val courses = envelope.optJSONArray("courses") ?: JSONArray()
        return (0 until courses.length()).mapNotNull { parseOpenGolf(courses.optJSONObject(it)) }
    }

    private fun fetchOpenGolfSearch(query: String, stateCode: String?): List<GolfCourse> {
        val stateFilter = stateCode?.let { "&state=${enc(it)}" }.orEmpty()
        val envelope = requestJson(
            URL("$OPEN_GOLF_BASE/courses/search?q=${enc(query)}$stateFilter&limit=100")
        )
        val courses = envelope.optJSONArray("courses") ?: JSONArray()
        return (0 until courses.length()).mapNotNull { parseOpenGolf(courses.optJSONObject(it)) }
    }

    private fun parseTeeTime(item: JSONObject?): GolfCourse? {
        item ?: return null
        if (item.optString("status", "active") != "active") return null
        val name = item.firstString("canonical_name", "name").orEmpty().trim()
        val lat = item.firstDouble("lat", "latitude") ?: Double.NaN
        val lon = item.firstDouble("lon", "longitude") ?: Double.NaN
        if (name.isBlank() || !lat.isFinite() || !lon.isFinite()) return null
        val id = item.firstString("course_id", "id") ?: return null
        val city = item.optString("city").takeIf { it.isNotBlank() }
        val region = item.optString("admin1").takeIf { it.isNotBlank() }
        val country = item.firstString("country_code", "country")
        return GolfCourse(
            id = stableId("teetime:$id"),
            elementType = "node",
            name = name,
            latitude = lat,
            longitude = lon,
            distanceMeters = Float.MAX_VALUE,
            city = city,
            region = region,
            countryCode = country,
            holes = item.optIntOrNull("holes"),
            par = item.optIntOrNull("par"),
            courseType = item.optString("course_type").takeIf { it.isNotBlank() },
            teeTimeId = id,
            sourceNames = setOf("TeeTime.AI")
        )
    }

    private fun parseOpenGolf(item: JSONObject?): GolfCourse? {
        item ?: return null
        val name = item.firstString("course_name", "name").orEmpty().trim()
        val lat = item.firstDouble("latitude", "lat") ?: Double.NaN
        val lon = item.firstDouble("longitude", "lon", "lng") ?: Double.NaN
        if (name.isBlank() || !lat.isFinite() || !lon.isFinite()) return null
        val id = item.optString("id").takeIf { it.isNotBlank() } ?: return null
        val scorecard = item.optJSONArray("scorecard")?.let(::parseScorecard)
            ?: item.optJSONArray("holes")?.let(::parseScorecard)
            ?: emptyList()
        return GolfCourse(
            id = stableId("opengolf:$id"),
            elementType = "node",
            name = name,
            latitude = lat,
            longitude = lon,
            distanceMeters = Float.MAX_VALUE,
            city = item.optString("city").takeIf { it.isNotBlank() },
            region = item.optString("state").takeIf { it.isNotBlank() },
            countryCode = item.optString("country").takeIf { it.isNotBlank() },
            address = item.optString("address").takeIf { it.isNotBlank() },
            postalCode = item.optString("postal_code").takeIf { it.isNotBlank() },
            holes = item.firstInt("holes", "hole_count"),
            par = item.firstInt("par_total", "par"),
            courseType = item.firstString("course_type", "type"),
            website = item.optString("website").takeIf { it.isNotBlank() },
            phone = item.optString("phone").takeIf { it.isNotBlank() },
            openGolfId = id,
            scorecard = scorecard,
            sourceNames = setOf("OpenGolfAPI")
        )
    }

    private fun parseScorecard(array: JSONArray): List<CourseHoleInfo> =
        (0 until array.length()).mapNotNull { index ->
            val hole = array.optJSONObject(index) ?: return@mapNotNull null
            val number = hole.firstInt("hole_number", "hole", "number") ?: return@mapNotNull null
            CourseHoleInfo(
                number = number,
                par = hole.optIntOrNull("par"),
                handicap = hole.firstInt("handicap_index", "handicap"),
                yardage = hole.firstInt("yards", "yardage")
            )
        }.filter { it.number in 1..18 }.sortedBy { it.number }

    private fun JSONObject.firstString(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            optString(key).takeIf { it.isNotBlank() && it != "null" }
        }

    private fun JSONObject.firstDouble(vararg keys: String): Double? =
        keys.firstNotNullOfOrNull { key ->
            if (!has(key) || isNull(key)) null else optDouble(key, Double.NaN).takeIf { it.isFinite() }
        }

    private fun JSONObject.firstInt(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key -> optIntOrNull(key) }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        val value = optInt(key, Int.MIN_VALUE)
        return value.takeIf { it != Int.MIN_VALUE && it > 0 }
    }

    private fun requestJson(url: URL): JSONObject {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 6_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "WatchThisDrive/0.2 (Android golf app)")
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("Course data service returned HTTP $code")
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    // Negative IDs reserve a separate namespace from positive OSM element IDs.
    private fun stableId(value: String): Long = stableCourseRecordId(value)

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun readCache(file: File): List<GolfCourse>? = runCatching {
        if (!file.exists()) return null
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            parseCached(item)
        }
    }.getOrNull()

    private fun writeCache(file: File, courses: List<GolfCourse>) {
        runCatching {
            val array = JSONArray()
            courses.forEach { course ->
                array.put(JSONObject().apply {
                    put("id", course.id)
                    put("name", course.name)
                    put("lat", course.latitude)
                    put("lon", course.longitude)
                    put("city", course.city)
                    put("region", course.region)
                    put("country", course.countryCode)
                    put("holes", course.holes)
                    put("par", course.par)
                    put("type", course.courseType)
                    put("address", course.address)
                    put("postal", course.postalCode)
                    put("website", course.website)
                    put("phone", course.phone)
                    put("openGolfId", course.openGolfId)
                    put("teeTimeId", course.teeTimeId)
                    put("sources", JSONArray(course.sourceNames.toList()))
                    put("scorecard", JSONArray().apply {
                        course.scorecard.forEach { hole ->
                            put(JSONObject().apply {
                                put("number", hole.number)
                                put("par", hole.par)
                                put("handicap", hole.handicap)
                                put("yardage", hole.yardage)
                            })
                        }
                    })
                })
            }
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(array.toString())
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    private fun parseCached(item: JSONObject): GolfCourse? {
        val id = item.optLong("id")
        val name = item.optString("name")
        val lat = item.optDouble("lat", Double.NaN)
        val lon = item.optDouble("lon", Double.NaN)
        if (id == 0L || name.isBlank() || !lat.isFinite() || !lon.isFinite()) return null
        val score = item.optJSONArray("scorecard") ?: JSONArray()
        return GolfCourse(
            id = id, elementType = "node", name = name, latitude = lat, longitude = lon,
            distanceMeters = Float.MAX_VALUE,
            city = item.optString("city").takeIf { it.isNotBlank() },
            region = item.optString("region").takeIf { it.isNotBlank() },
            countryCode = item.optString("country").takeIf { it.isNotBlank() },
            holes = item.optIntOrNull("holes"), par = item.optIntOrNull("par"),
            courseType = item.optString("type").takeIf { it.isNotBlank() },
            address = item.optString("address").takeIf { it.isNotBlank() },
            postalCode = item.optString("postal").takeIf { it.isNotBlank() },
            website = item.optString("website").takeIf { it.isNotBlank() },
            phone = item.optString("phone").takeIf { it.isNotBlank() },
            openGolfId = item.optString("openGolfId").takeIf { it.isNotBlank() },
            teeTimeId = item.optString("teeTimeId").takeIf { it.isNotBlank() },
            sourceNames = item.optJSONArray("sources")?.let { array ->
                (0 until array.length()).map { array.optString(it) }.toSet()
            } ?: emptySet(),
            scorecard = parseScorecard(score)
        )
    }
}
