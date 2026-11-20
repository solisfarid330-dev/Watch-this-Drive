package com.watchthisdrive.app.data

import android.location.Location
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

data class GolfCourse(
    val id: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Float
) {
    val distanceMiles: Double
        get() = distanceMeters / 1609.344
}

object CourseDiscovery {

    private const val SEARCH_RADIUS_METERS = 15000

    fun searchNearby(
        latitude: Double,
        longitude: Double,
        onResult: (Result<List<GolfCourse>>) -> Unit
    ) {
        thread {
            val result = runCatching {
                val query = """
                    [out:json][timeout:25];
                    (
                      node["leisure"="golf_course"](around:$SEARCH_RADIUS_METERS,$latitude,$longitude);
                      way["leisure"="golf_course"](around:$SEARCH_RADIUS_METERS,$latitude,$longitude);
                      relation["leisure"="golf_course"](around:$SEARCH_RADIUS_METERS,$latitude,$longitude);
                    );
                    out center tags;
                """.trimIndent()

                val connection = URL(
                    "https://overpass-api.de/api/interpreter"
                ).openConnection() as HttpURLConnection

                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 15000
                    connection.readTimeout = 35000
                    connection.doOutput = true
                    connection.setRequestProperty(
                        "Content-Type",
                        "application/x-www-form-urlencoded; charset=UTF-8"
                    )
                    connection.setRequestProperty(
                        "User-Agent",
                        "WatchThisDrive/0.1 Android Golf App"
                    )

                    val body = "data=" + URLEncoder.encode(query, "UTF-8")
                    connection.outputStream.use {
                        it.write(body.toByteArray(Charsets.UTF_8))
                    }

                    if (connection.responseCode !in 200..299) {
                        throw Exception("Course search service returned HTTP ${connection.responseCode}")
                    }

                    val response = connection.inputStream.bufferedReader().use {
                        it.readText()
                    }

                    parseCourses(response, latitude, longitude)
                } finally {
                    connection.disconnect()
                }
            }

            Handler(Looper.getMainLooper()).post {
                onResult(result)
            }
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
                    name = name,
                    latitude = lat,
                    longitude = lon,
                    distanceMeters = distance[0]
                )
            )
        }

        return courses
            .distinctBy { "${it.name.lowercase()}_${it.latitude}_${it.longitude}" }
            .sortedBy { it.distanceMeters }
    }
}
