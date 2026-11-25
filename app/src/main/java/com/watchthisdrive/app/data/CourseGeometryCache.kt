package com.watchthisdrive.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Small local snapshot of the selected course geometry. An active round must be
 * resumable even when the map provider is unavailable at the course.
 */
object CourseGeometryCache {
    private const val FILE_NAME = "active_course_geometry.json"

    fun save(context: Context, geometry: CourseGeometry) {
        runCatching {
            val root = JSONObject().apply {
                put("courseId", geometry.courseId)
                put("courseName", geometry.courseName)
                put("expectedHoleCount", geometry.expectedHoleCount ?: JSONObject.NULL)
                put("incompleteRelations", JSONArray(geometry.incompleteRelations))
                put("features", JSONArray().apply {
                    geometry.features.forEach { feature ->
                        put(JSONObject().apply {
                            put("id", feature.id)
                            put("elementType", feature.elementType)
                            put("featureType", feature.featureType)
                            put("holeNumber", feature.holeNumber ?: JSONObject.NULL)
                            put("name", feature.name ?: JSONObject.NULL)
                            put("geometryType", feature.geometryType.name)
                            put("geometryStatus", feature.geometryStatus)
                            put("par", feature.par ?: JSONObject.NULL)
                            put("origin", feature.origin.name)
                            put("confidence", feature.confidence ?: JSONObject.NULL)
                            put("points", pointsJson(feature.points))
                            put("rings", nestedPointsJson(feature.rings))
                            put("polygons", JSONArray().apply {
                                feature.polygons.forEach { polygon -> put(nestedPointsJson(polygon)) }
                            })
                        })
                    }
                })
            }
            val target = File(context.filesDir, FILE_NAME)
            val temporary = File(context.filesDir, "$FILE_NAME.tmp")
            temporary.writeText(root.toString(), Charsets.UTF_8)
            if (!temporary.renameTo(target)) {
                target.writeText(temporary.readText(Charsets.UTF_8), Charsets.UTF_8)
                temporary.delete()
            }
        }
    }

    fun load(context: Context, expectedCourseId: Long): CourseGeometry? = runCatching {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return null
        val root = JSONObject(file.readText(Charsets.UTF_8))
        if (root.optLong("courseId", Long.MIN_VALUE) != expectedCourseId) return null
        val featuresJson = root.getJSONArray("features")
        val features = (0 until featuresJson.length()).map { index ->
            val item = featuresJson.getJSONObject(index)
            val polygonsJson = item.optJSONArray("polygons") ?: JSONArray()
            val polygons = (0 until polygonsJson.length()).map { polygonIndex ->
                nestedPointsFromJson(polygonsJson.getJSONArray(polygonIndex))
            }
            CourseFeature(
                id = item.getLong("id"),
                elementType = item.getString("elementType"),
                featureType = item.getString("featureType"),
                holeNumber = item.optString("holeNumber").takeIf { it.isNotBlank() && it != "null" },
                name = item.optString("name").takeIf { it.isNotBlank() && it != "null" },
                points = pointsFromJson(item.optJSONArray("points") ?: JSONArray()),
                geometryType = runCatching { CourseGeometryType.valueOf(item.optString("geometryType")) }
                    .getOrDefault(CourseGeometryType.UNKNOWN),
                rings = nestedPointsFromJson(item.optJSONArray("rings") ?: JSONArray()),
                polygons = polygons,
                geometryStatus = item.optString("geometryStatus", "unverified"),
                par = if (item.isNull("par")) null else item.optInt("par"),
                origin = runCatching { CourseFeatureOrigin.valueOf(item.optString("origin")) }
                    .getOrDefault(CourseFeatureOrigin.SOURCE),
                confidence = if (item.isNull("confidence")) null else item.optDouble("confidence")
            )
        }
        val relations = root.optJSONArray("incompleteRelations") ?: JSONArray()
        CourseGeometry(
            courseId = root.getLong("courseId"),
            courseName = root.getString("courseName"),
            features = features,
            incompleteRelations = (0 until relations.length()).map { relations.getString(it) },
            expectedHoleCount = if (root.isNull("expectedHoleCount")) null else root.optInt("expectedHoleCount")
        )
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    private fun pointJson(point: GeoPoint) = JSONObject().put("lat", point.latitude).put("lon", point.longitude)

    private fun pointsJson(points: List<GeoPoint>) = JSONArray().apply {
        points.forEach { put(pointJson(it)) }
    }

    private fun nestedPointsJson(rings: List<List<GeoPoint>>) = JSONArray().apply {
        rings.forEach { put(pointsJson(it)) }
    }

    private fun pointsFromJson(points: JSONArray) = (0 until points.length()).map { index ->
        val point = points.getJSONObject(index)
        GeoPoint(point.getDouble("lat"), point.getDouble("lon"))
    }

    private fun nestedPointsFromJson(rings: JSONArray) = (0 until rings.length()).map { index ->
        pointsFromJson(rings.getJSONArray(index))
    }
}
