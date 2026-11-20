package com.watchthisdrive.app.data

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

data class GeoPoint(
    val latitude: Double,
    val longitude: Double
)

data class CourseFeature(
    val id: Long,
    val elementType: String,
    val featureType: String,
    val holeNumber: String?,
    val name: String?,
    val points: List<GeoPoint>
)

data class CourseGeometry(
    val courseId: Long,
    val courseName: String,
    val features: List<CourseFeature>
)

object CourseGeometryLoader {

    private val OVERPASS_SERVERS = listOf(
        "https://ovp.dresel.systems/api-na/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass-api.de/api/interpreter"
    )

    fun load(
        course: GolfCourse,
        onResult: (Result<CourseGeometry>) -> Unit
    ) {
        thread {
            val result = runCatching {
                val query = buildCourseQuery(course)

                var lastError: Exception? = null

                for (server in OVERPASS_SERVERS) {
                    try {
                        return@runCatching fetchGeometry(
                            server,
                            query,
                            course
                        )
                    } catch (e: Exception) {
                        lastError = e
                    }
                }

                throw Exception(
                    "All geometry servers failed. " +
                        (lastError?.message ?: "")
                )
            }

            Handler(Looper.getMainLooper()).post {
                onResult(result)
            }
        }
    }

    private fun buildCourseQuery(course: GolfCourse): String {
        val element = when (course.elementType.lowercase()) {
            "relation" -> "relation(${course.id});"
            "way" -> "way(${course.id});"
            else -> throw Exception(
                "This course is represented by an OSM node, " +
                    "which does not provide a course boundary. " +
                    "Boundary discovery is needed before its features can be mapped reliably."
            )
        }

        return """
            [out:json][timeout:60];
            $element
            map_to_area -> .courseArea;
            (
              nwr(area.courseArea)["golf"];
            );
            out center geom;
        """.trimIndent()
    }

    private fun fetchGeometry(
        server: String,
        query: String,
        course: GolfCourse
    ): CourseGeometry {
        val connection = URL(server).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 70000
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
                throw Exception(
                    "Geometry server returned HTTP $responseCode"
                )
            }

            val response = connection.inputStream.bufferedReader().use {
                it.readText()
            }

            return parseGeometry(response, course)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseGeometry(
        response: String,
        course: GolfCourse
    ): CourseGeometry {
        val elements = JSONObject(response).getJSONArray("elements")
        val features = mutableListOf<CourseFeature>()

        for (i in 0 until elements.length()) {
            val element = elements.getJSONObject(i)
            val tags = element.optJSONObject("tags") ?: continue
            val golfType = tags.optString("golf").trim()

            if (golfType.isBlank()) continue

            val points = mutableListOf<GeoPoint>()
            val geometry = element.optJSONArray("geometry")

            if (geometry != null) {
                for (j in 0 until geometry.length()) {
                    val point = geometry.getJSONObject(j)
                    val lat = point.optDouble("lat", Double.NaN)
                    val lon = point.optDouble("lon", Double.NaN)

                    if (lat.isFinite() && lon.isFinite()) {
                        points.add(GeoPoint(lat, lon))
                    }
                }
            }

            if (points.isEmpty()) {
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

                if (lat.isFinite() && lon.isFinite()) {
                    points.add(GeoPoint(lat, lon))
                }
            }

            if (points.isEmpty()) continue

            val holeNumber = tags.optString("ref")
                .takeIf { it.isNotBlank() }
                ?: tags.optString("golf:hole")
                    .takeIf { it.isNotBlank() }

            val name = tags.optString("name")
                .takeIf { it.isNotBlank() }

            features.add(
                CourseFeature(
                    id = element.optLong("id"),
                    elementType = element.optString("type"),
                    featureType = golfType,
                    holeNumber = holeNumber,
                    name = name,
                    points = points
                )
            )
        }

        return CourseGeometry(
            courseId = course.id,
            courseName = course.name,
            features = features
        )
    }
}
