package com.watchthisdrive.app.data

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class CourseHoleInfo(
    val number: Int,
    val par: Int?,
    val handicap: Int?,
    val yardage: Int?
)

data class GolfCourse(
    val id: Long,
    val elementType: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Float,
    val city: String? = null,
    val region: String? = null,
    val countryCode: String? = null,
    val address: String? = null,
    val postalCode: String? = null,
    val holes: Int? = null,
    val par: Int? = null,
    val courseType: String? = null,
    val website: String? = null,
    val phone: String? = null,
    val openGolfId: String? = null,
    val teeTimeId: String? = null,
    val scorecard: List<CourseHoleInfo> = emptyList(),
    val sourceNames: Set<String> = emptySet()
) {
    val persistenceKey: String
        get() = openGolfId?.let { "opengolf:$it" }
            ?: teeTimeId?.let { "teetime:$it" }
            ?: "${elementType.lowercase()}:$id"

    val distanceMiles: Double
        get() = distanceMeters / 1609.344
}

object CourseDiscovery {

    private const val SEARCH_RADIUS_METERS = 15000
    private val sourceExecutor = Executors.newFixedThreadPool(3)

    private val OVERPASS_SERVERS = listOf(
        "https://ovp.dresel.systems/api-na/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass-api.de/api/interpreter"
    )

    private data class SearchLocation(
        val latitude: Double,
        val longitude: Double
    )

    fun searchNearby(
        context: Context,
        latitude: Double,
        longitude: Double,
        onResult: (Result<List<GolfCourse>>) -> Unit
    ) {
        thread(name = "combined-course-search") {
            val osmFuture = sourceExecutor.submit<List<GolfCourse>> {
                searchAtCoordinates(latitude, longitude)
            }
            val catalogFuture = sourceExecutor.submit<List<GolfCourse>> {
                CourseCatalogSources.searchNearby(context.applicationContext, latitude, longitude)
            }
            val osm = runCatching { osmFuture.get(65, TimeUnit.SECONDS) }
            val catalogs = runCatching { catalogFuture.get(65, TimeUnit.SECONDS) }
            val result = when {
                osm.isSuccess || catalogs.isSuccess -> Result.success(
                    mergeCourseSources(osm.getOrDefault(emptyList()), catalogs.getOrDefault(emptyList()))
                )
                else -> Result.failure(
                    Exception(
                        "Course search services are unavailable. Please try again shortly. " +
                            (osm.exceptionOrNull()?.message ?: catalogs.exceptionOrNull()?.message.orEmpty())
                    )
                )
            }
            Handler(Looper.getMainLooper()).post { onResult(result) }
        }
    }

    fun searchByText(
        context: Context,
        searchText: String,
        onResult: (Result<List<GolfCourse>>) -> Unit
    ) {
        thread(name = "combined-course-text-search") {
            val directFuture = sourceExecutor.submit<List<GolfCourse>> {
                CourseCatalogSources.searchByText(context.applicationContext, searchText)
            }
            val locationFuture = sourceExecutor.submit<List<GolfCourse>> {
                val point = geocode(searchText)
                val osm = runCatching { searchAtCoordinates(point.latitude, point.longitude) }
                val catalogs = runCatching {
                    CourseCatalogSources.searchNearby(
                        context.applicationContext, point.latitude, point.longitude, searchText
                    )
                }
                if (osm.isFailure && catalogs.isFailure) {
                    throw Exception("Location-based course search is unavailable.")
                }
                mergeCourseSources(osm.getOrDefault(emptyList()), catalogs.getOrDefault(emptyList()))
            }
            val direct = runCatching { directFuture.get(30, TimeUnit.SECONDS) }
            val nearby = runCatching { locationFuture.get(65, TimeUnit.SECONDS) }
            val result = when {
                direct.isSuccess || nearby.isSuccess -> Result.success(
                    mergeCourseSources(
                        nearby.getOrDefault(emptyList()),
                        direct.getOrDefault(emptyList())
                    )
                )
                else -> Result.failure(
                    Exception("Course search services are unavailable. Try a course name with its city or state.")
                )
            }
            Handler(Looper.getMainLooper()).post { onResult(result) }
        }
    }

    private fun mergeCourseSources(
        osmCourses: List<GolfCourse>,
        catalogCourses: List<GolfCourse>
    ): List<GolfCourse> {
        val unmatchedCatalogs = catalogCourses.toMutableList()
        val merged = osmCourses.map { osm ->
            val normalized = normalizeCourseName(osm.name)
            val matchIndex = unmatchedCatalogs.indexOfFirst { catalog ->
                normalizeCourseName(catalog.name) == normalized &&
                    distanceMeters(osm.latitude, osm.longitude, catalog.latitude, catalog.longitude) <= 1_500f
            }
            if (matchIndex < 0) {
                osm.copy(sourceNames = osm.sourceNames + "OpenStreetMap")
            } else {
                val catalog = unmatchedCatalogs.removeAt(matchIndex)
                osm.copy(
                    city = catalog.city,
                    region = catalog.region,
                    countryCode = catalog.countryCode,
                    address = catalog.address,
                    postalCode = catalog.postalCode,
                    holes = catalog.holes,
                    par = catalog.par,
                    courseType = catalog.courseType,
                    website = catalog.website,
                    phone = catalog.phone,
                    openGolfId = catalog.openGolfId,
                    teeTimeId = catalog.teeTimeId,
                    scorecard = catalog.scorecard,
                    sourceNames = osm.sourceNames + catalog.sourceNames + "OpenStreetMap"
                )
            }
        }
        return (merged + unmatchedCatalogs).sortedBy { it.distanceMeters }
    }

    private fun normalizeCourseName(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0]
    }

    fun searchNearby(
        latitude: Double,
        longitude: Double,
        onResult: (Result<List<GolfCourse>>) -> Unit
    ) {
        thread {
            val result = runCatching {
                searchAtCoordinates(latitude, longitude)
            }

            Handler(Looper.getMainLooper()).post {
                onResult(result)
            }
        }
    }

    fun searchByText(
        searchText: String,
        onResult: (Result<List<GolfCourse>>) -> Unit
    ) {
        thread {
            val result = runCatching {
                val searchLocation = geocode(searchText)
                searchAtCoordinates(
                    searchLocation.latitude,
                    searchLocation.longitude
                )
            }

            Handler(Looper.getMainLooper()).post {
                onResult(result)
            }
        }
    }

    private fun geocode(searchText: String): SearchLocation {
        val encoded = URLEncoder.encode(searchText.trim(), "UTF-8")
        val url = URL(
            "https://nominatim.openstreetmap.org/search" +
                "?q=$encoded&format=jsonv2&limit=1"
        )

        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.setRequestProperty(
                "User-Agent",
                "WatchThisDrive/0.1 Android Golf App"
            )
            connection.setRequestProperty("Accept", "application/json")

            val responseCode = connection.responseCode

            if (responseCode !in 200..299) {
                throw Exception("Location search returned HTTP $responseCode")
            }

            val response = connection.inputStream.bufferedReader().use {
                it.readText()
            }

            val results = JSONArray(response)

            if (results.length() == 0) {
                throw Exception(
                    "We couldn't find that course or location. Try adding its city and state."
                )
            }

            val firstResult = results.getJSONObject(0)
            val latitude = firstResult.optString("lat").toDoubleOrNull()
                ?: throw Exception("The location search returned invalid coordinates.")
            val longitude = firstResult.optString("lon").toDoubleOrNull()
                ?: throw Exception("The location search returned invalid coordinates.")

            return SearchLocation(latitude, longitude)
        } finally {
            connection.disconnect()
        }
    }

    private fun searchAtCoordinates(
        latitude: Double,
        longitude: Double
    ): List<GolfCourse> {
        val query = """
            [out:json][timeout:25];
            (
              node["leisure"="golf_course"](around:$SEARCH_RADIUS_METERS,$latitude,$longitude);
              way["leisure"="golf_course"](around:$SEARCH_RADIUS_METERS,$latitude,$longitude);
              relation["leisure"="golf_course"](around:$SEARCH_RADIUS_METERS,$latitude,$longitude);
            );
            out center tags;
        """.trimIndent()

        var lastError: Exception? = null

        for (server in OVERPASS_SERVERS) {
            try {
                return fetchCourses(
                    server,
                    query,
                    latitude,
                    longitude
                )
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw Exception(
            "All course search servers failed. Please try again shortly. " +
                (lastError?.message ?: "")
        )
    }

    private fun fetchCourses(
        server: String,
        query: String,
        latitude: Double,
        longitude: Double
    ): List<GolfCourse> {
        val connection = URL(server).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 35000
            connection.doOutput = true

            connection.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded; charset=UTF-8"
            )
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(
                "User-Agent",
                "WatchThisDrive/0.1 Android Golf App"
            )

            val body = "data=" + URLEncoder.encode(query, "UTF-8")

            connection.outputStream.use {
                it.write(body.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode

            if (responseCode !in 200..299) {
                throw Exception("Search server returned HTTP $responseCode")
            }

            val response = connection.inputStream.bufferedReader().use {
                it.readText()
            }

            return parseCourses(response, latitude, longitude)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseCourses(
        response: String,
        userLatitude: Double,
        userLongitude: Double
    ): List<GolfCourse> {
        val elements = JSONObject(response).getJSONArray("elements")
        val courses = mutableListOf<GolfCourse>()

        for (i in 0 until elements.length()) {
            val element = elements.getJSONObject(i)
            val tags = element.optJSONObject("tags") ?: continue
            val name = tags.optString("name").trim()

            if (name.isBlank()) continue

            val center = element.optJSONObject("center")

            val lat = if (center != null) {
                center.optDouble("lat", Double.NaN)
            } else {
                element.optDouble("lat", Double.NaN)
            }

            val lon = if (center != null) {
                center.optDouble("lon", Double.NaN)
            } else {
                element.optDouble("lon", Double.NaN)
            }

            if (!lat.isFinite() || !lon.isFinite()) continue

            val distance = FloatArray(1)

            Location.distanceBetween(
                userLatitude,
                userLongitude,
                lat,
                lon,
                distance
            )

            courses.add(
                GolfCourse(
                    id = element.optLong("id"),
                    elementType = element.optString("type"),
                    name = name,
                    latitude = lat,
                    longitude = lon,
                    distanceMeters = distance[0],
                    sourceNames = setOf("OpenStreetMap")
                )
            )
        }

        // Preserve every OSM candidate. Grouping and boundary selection
        // will happen in the app instead of silently deleting records here.
        return courses.sortedBy { it.distanceMeters }
    }
}
