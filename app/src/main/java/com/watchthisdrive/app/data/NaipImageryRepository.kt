package com.watchthisdrive.app.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max

/**
 * Lazily fetches a small NAIP aerial reference image for a selected US course.
 * This is RGB imagery for visual reference, not a semantic golf-feature map.
 */
object NaipImageryRepository {
    private const val IMAGE_SERVER =
        "https://imagery.nationalmap.gov/arcgis/rest/services/USGSNAIPImagery/ImageServer"
    private const val CACHE_MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L
    private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024

    data class Bounds(
        val west: Double,
        val south: Double,
        val east: Double,
        val north: Double
    )

    fun loadOrFetch(
        context: Context,
        course: GolfCourse,
        geometry: CourseGeometry?
    ): File? {
        if (!isWithinConus(course.latitude, course.longitude)) return null
        val bounds = boundsFor(course, geometry) ?: return null
        val key = listOf(
            course.elementType, course.id,
            "%.5f".format(Locale.US, bounds.west),
            "%.5f".format(Locale.US, bounds.south),
            "%.5f".format(Locale.US, bounds.east),
            "%.5f".format(Locale.US, bounds.north)
        ).joinToString("_").hashCode().toUInt().toString(16)
        val directory = File(context.cacheDir, "naip").apply { mkdirs() }
        val imageFile = File(directory, "course_$key.jpg")
        val metadataFile = File(directory, "course_$key.json")
        if (imageFile.exists() && imageFile.length() > 0 &&
            System.currentTimeMillis() - imageFile.lastModified() < CACHE_MAX_AGE_MS
        ) return imageFile

        val widthMeters = (bounds.east - bounds.west) * 111_320.0 *
            cos(Math.toRadians(course.latitude)).coerceAtLeast(0.1)
        val heightMeters = (bounds.north - bounds.south) * 111_320.0
        if (widthMeters <= 0.0 || heightMeters <= 0.0) return null
        val aspect = widthMeters / heightMeters
        val width = if (aspect >= 1.0) 1024 else (1024 * aspect).toInt().coerceIn(256, 1024)
        val height = if (aspect >= 1.0) (1024 / aspect).toInt().coerceIn(256, 1024) else 1024

        val request = URL(
            "$IMAGE_SERVER/exportImage" +
                "?bbox=${bounds.west},${bounds.south},${bounds.east},${bounds.north}" +
                "&bboxSR=4326&imageSR=4326&size=$width,$height&format=jpg&f=image"
        )
        val connection = request.openConnection() as HttpURLConnection
        val temporary = File(directory, imageFile.name + ".tmp")
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 25_000
            connection.setRequestProperty("Accept", "image/jpeg")
            connection.setRequestProperty("User-Agent", "WatchThisDrive/0.2 (NAIP public imagery)")
            val code = connection.responseCode
            if (code !in 200..299) return null
            val contentType = connection.contentType.orEmpty().lowercase(Locale.US)
            if (!contentType.startsWith("image/")) return null
            var total = 0
            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_IMAGE_BYTES) {
                            temporary.delete()
                            return null
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (total == 0) {
                temporary.delete()
                return null
            }
            if (!temporary.renameTo(imageFile)) {
                temporary.copyTo(imageFile, overwrite = true)
                temporary.delete()
            }
            metadataFile.writeText(
                JSONObject()
                    .put("source", IMAGE_SERVER)
                    .put("provider", "USDA NAIP via USGS The National Map")
                    .put("attribution", "USDA Farm Production and Conservation Business Center, Geospatial Enterprise Operations")
                    .put("license", "Public domain; source credit requested")
                    .put("retrievedAtEpochMs", System.currentTimeMillis())
                    .put("bboxWgs84", listOf(bounds.west, bounds.south, bounds.east, bounds.north))
                    .put("width", width)
                    .put("height", height)
                    .toString()
            )
            return imageFile
        } catch (_: Exception) {
            temporary.delete()
            return null
        } finally {
            connection.disconnect()
        }
    }

    fun boundsFor(course: GolfCourse, geometry: CourseGeometry?): Bounds? {
        val boundaryPoints = geometry?.features
            ?.filter { it.featureType == "course_boundary" }
            ?.flatMap { it.points }
            .orEmpty()
        val points = boundaryPoints.ifEmpty {
            val latPad = 0.009
            val lonPad = 0.009 / cos(Math.toRadians(course.latitude)).coerceAtLeast(0.2)
            return Bounds(
                west = course.longitude - lonPad,
                south = course.latitude - latPad,
                east = course.longitude + lonPad,
                north = course.latitude + latPad
            )
        }
        val west = points.minOf { it.longitude }
        val east = points.maxOf { it.longitude }
        val south = points.minOf { it.latitude }
        val north = points.maxOf { it.latitude }
        val latPad = max(0.0007, (north - south) * 0.05)
        val lonPad = max(0.0007, (east - west) * 0.05)
        val result = Bounds(west - lonPad, south - latPad, east + lonPad, north + latPad)
        if (result.east - result.west > 0.12 || result.north - result.south > 0.12) return null
        return result
    }

    private fun isWithinConus(latitude: Double, longitude: Double): Boolean =
        latitude in 24.0..50.0 && longitude in -125.0..-66.0
}
