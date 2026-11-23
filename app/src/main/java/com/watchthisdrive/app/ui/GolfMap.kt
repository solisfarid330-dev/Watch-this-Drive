package com.watchthisdrive.app.ui

import android.location.Location
import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.watchthisdrive.app.BuildConfig
import com.watchthisdrive.app.data.CourseFeature
import com.watchthisdrive.app.data.CourseGeometry
import com.watchthisdrive.app.data.CourseGeometryLoader
import com.watchthisdrive.app.data.GolfCourse
import com.watchthisdrive.app.data.GeoPoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.fillOutlineColor
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource

@Composable
fun GolfMap(
    location: Location?,
    course: GolfCourse?,
    cameraTarget: Location? = null,
    onLocate: () -> Unit,
    sharedGeometry: CourseGeometry? = null,
    sharedGeometryError: String? = null,
    useSharedGeometry: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(Bundle()) }
    }
    var mapReady by remember { mutableStateOf(false) }
    var geometry by remember(course?.id) {
        mutableStateOf(if (useSharedGeometry) sharedGeometry else sharedGeometry ?: course?.let { loadCachedCourseGeometry(context, it) })
    }
    var geometryMessage by remember(course?.id) {
        mutableStateOf(if (geometry != null) "Saved map · checking for updates…" else null)
    }
    var retryToken by remember(course?.id) { mutableIntStateOf(0) }
    val key = BuildConfig.MAPTILER_API_KEY
    val styleUrl = if (key.isNotBlank()) "https://api.maptiler.com/maps/satellite/style.json?key=$key" else ""

    LaunchedEffect(course?.id, retryToken, sharedGeometry, sharedGeometryError) {
        if (useSharedGeometry && sharedGeometry == null) {
            geometryMessage = when {
                sharedGeometryError != null && geometry != null -> "Saved map shown · refresh failed"
                sharedGeometryError != null -> "Course features couldn't be loaded: $sharedGeometryError"
                geometry == null -> "Loading course features…"
                else -> null
            }
        } else if (sharedGeometry != null) {
            geometry = sharedGeometry
            saveCachedGeometry(context, sharedGeometry)
            geometryMessage = if (sharedGeometryError != null) "Saved map shown · refresh failed" else null
        } else if (course != null) {
            geometryMessage = if (geometry == null) "Loading course features…" else "Saved map · refreshing in background…"
            CourseGeometryLoader.load(course) { result ->
                result.onSuccess { fresh ->
                    if (fresh.features.isNotEmpty()) {
                        saveCachedGeometry(context, fresh)
                        geometry = fresh
                        geometryMessage = null
                    } else if (geometry == null) {
                        geometryMessage = "No mapped golf features were found for this course."
                    } else {
                        geometryMessage = "Saved map shown · refresh returned no features"
                    }
                }.onFailure { error ->
                    geometryMessage = if (geometry != null) {
                        "Saved map shown · background refresh failed"
                    } else {
                        "Course features couldn't be loaded: " + (error.message ?: "unknown error")
                    }
                }
            }
        } else {
            geometryMessage = "Select a course to show its mapped features."
        }
    }

    DisposableEffect(mapView) {
        mapView.onStart()
        mapView.getMapAsync { map ->
            map.setStyle(
                if (styleUrl.isNotBlank()) Style.Builder().fromUri(styleUrl)
                else Style.Builder().fromJson(EMPTY_STYLE)
            ) { style ->
                map.uiSettings.isCompassEnabled = true
                map.uiSettings.isLogoEnabled = true
                map.uiSettings.isAttributionEnabled = true
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(39.5, -98.35)).zoom(3.5).build()
                mapReady = true
                geometry?.let { renderCourseFeatures(style, it.features) }
            }
        }
        onDispose {
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    // Camera movement is driven only by an explicit locate/course target.
    // Following every GPS sample made MapLibre animate repeatedly and caused
    // the whole Play screen to feel sluggish while walking.
    LaunchedEffect(cameraTarget, mapReady) {
        if (mapReady && cameraTarget != null) {
            mapView.getMapAsync { map ->
                map.animateCamera(
                    org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                        LatLng(cameraTarget.latitude, cameraTarget.longitude), 16.0
                    )
                )
            }
        }
    }

    // Update the player marker independently without moving the camera.
    LaunchedEffect(location, mapReady) {
        if (mapReady && location != null) {
            mapView.getMapAsync { map ->
                map.style?.let { renderPlayerLocation(it, location) }
            }
        }
    }

    LaunchedEffect(geometry, mapReady) {
        val current = geometry ?: return@LaunchedEffect
        if (mapReady) {
            mapView.getMapAsync { map ->
                map.style?.let { renderCourseFeatures(it, current.features) }
            }
        }
    }

    Box(modifier.fillMaxWidth().height(320.dp)) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (key.isBlank()) {
            Card(
                Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xEE18231C))
            ) {
                Text(
                    "MapTiler key hasn't been configured yet. The map will load once the project secret is added.",
                    color = Color.White,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }
        if (course != null) {
            Card(
                Modifier.align(androidx.compose.ui.Alignment.TopStart).padding(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xE618231C))
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Text(course.name, color = Color.White, style = MaterialTheme.typography.labelLarge)
                    Text(
                        geometryMessage ?: geometry?.let { featureSummary(it.features) } ?: "Preparing course map…",
                        color = Color(0xFFB8F36B),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (geometryMessage?.contains("failed") == true ||
                        geometryMessage?.startsWith("Course features couldn't be loaded") == true) {
                        Spacer(Modifier.height(6.dp))
                        TextButton(
                            onClick = { retryToken++ },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                        ) {
                            Text("REFRESH NOW", color = Color(0xFFB8F36B))
                        }
                    }
                }
            }
        }
        if (geometry != null) {
            Card(
                Modifier.align(androidx.compose.ui.Alignment.BottomStart).padding(start = 12.dp, bottom = 18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xE618231C))
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text("MAP KEY", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LegendItem("#72B85A", "Fairway")
                        LegendItem("#B8F36B", "Green")
                        LegendItem("#E6C77A", "Bunker")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LegendItem("#43B9E8", "Water")
                        LegendItem("#A7C98B", "Tee marker")
                        LegendItem("#B8F36B", "You")
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = onLocate,
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(18.dp),
            containerColor = Color(0xFFB8F36B),
            contentColor = Color(0xFF101713)
        ) {
            Icon(Icons.Filled.MyLocation, contentDescription = "Find my location")
        }
    }
}

@Composable
private fun LegendItem(hex: String, label: String) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(10.dp).padding(0.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.size(9.dp)) {
                drawCircle(Color(android.graphics.Color.parseColor(hex)))
            }
        }
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

private fun cacheKey(elementType: String, courseId: Long): String =
    "course_geometry_${elementType.lowercase()}_$courseId"

private fun cacheFile(context: android.content.Context, elementType: String, courseId: Long): File =
    File(context.filesDir, cacheKey(elementType, courseId) + ".json.gz")

internal fun loadCachedCourseGeometry(context: android.content.Context, course: GolfCourse): CourseGeometry? =
    runCatching {
        val file = cacheFile(context, course.elementType, course.id)
        val raw = if (file.exists()) {
            GZIPInputStream(file.inputStream()).bufferedReader().use { it.readText() }
        } else {
            // One-time migration from the old large SharedPreferences JSON blob.
            val preferences = context.getSharedPreferences(
                "course_geometry_cache", android.content.Context.MODE_PRIVATE
            )
            val legacy = preferences.getString("course_geometry_${course.id}", null) ?: return null
            runCatching {
                file.parentFile?.mkdirs()
                GZIPOutputStream(file.outputStream().buffered()).bufferedWriter().use { it.write(legacy) }
                preferences.edit().remove("course_geometry_${course.id}").apply()
            }
            legacy
        }
        val root = JSONObject(raw)
        // Schema v2 preserves source/confidence/predicted provenance. Ignore
        // old caches that silently lost that distinction when they were saved.
        if (root.optInt("schemaVersion", 0) != 2) return null
        val array = root.getJSONArray("features")
        val features = mutableListOf<CourseFeature>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val pointsJson = item.getJSONArray("points")
            val points = mutableListOf<GeoPoint>()
            for (j in 0 until pointsJson.length()) {
                val point = pointsJson.getJSONObject(j)
                points.add(GeoPoint(point.getDouble("lat"), point.getDouble("lon")))
            }
            features.add(CourseFeature(
                id = item.getLong("id"),
                elementType = item.getString("elementType"),
                featureType = item.getString("featureType"),
                holeNumber = item.optString("holeNumber").takeIf { it.isNotBlank() },
                name = item.optString("name").takeIf { it.isNotBlank() },
                points = points,
                par = item.optInt("par", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
                handicap = item.optInt("handicap", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
                source = item.optString("source").takeIf { it.isNotBlank() },
                confidence = item.optString("confidence").takeIf { it.isNotBlank() },
                isPredicted = item.optBoolean("isPredicted", false)
            ))
        }
        CourseGeometry(
            courseId = course.id,
            courseName = root.optString("courseName", course.name),
            features = features,
            courseElementType = root.optString("courseElementType", course.elementType)
        )
    }.getOrNull()

private fun saveCachedGeometry(context: android.content.Context, geometry: CourseGeometry) {
    // Geometry can be several megabytes. Compress it off the main thread instead
    // of serializing a large SharedPreferences string during map composition.
    thread(name = "course-geometry-cache") {
        runCatching {
            val features = JSONArray()
            geometry.features.forEach { feature ->
                val points = JSONArray()
                feature.points.forEach { point ->
                    points.put(JSONObject().put("lat", point.latitude).put("lon", point.longitude))
                }
                features.put(JSONObject()
                    .put("id", feature.id)
                    .put("elementType", feature.elementType)
                    .put("featureType", feature.featureType)
                    .put("holeNumber", feature.holeNumber ?: "")
                    .put("name", feature.name ?: "")
                    .put("points", points)
                    .put("source", feature.source ?: "")
                    .put("confidence", feature.confidence ?: "")
                    .put("isPredicted", feature.isPredicted)
                    .apply {
                        feature.par?.let { put("par", it) }
                        feature.handicap?.let { put("handicap", it) }
                    })
            }
            val root = JSONObject()
                .put("schemaVersion", 2)
                .put("courseId", geometry.courseId)
                .put("courseName", geometry.courseName)
                .put("courseElementType", geometry.courseElementType)
                .put("features", features)
            val file = cacheFile(context, geometry.courseElementType, geometry.courseId)
            val temp = File(file.parentFile, file.name + ".tmp")
            GZIPOutputStream(temp.outputStream().buffered()).bufferedWriter().use {
                it.write(root.toString())
            }
            if (!temp.renameTo(file)) {
                file.outputStream().use { output ->
                    temp.inputStream().use { input -> input.copyTo(output) }
                }
                temp.delete()
            }
        }
    }
}

private const val PLAYER_SOURCE_ID = "watch-this-drive-player-location"

private fun renderPlayerLocation(style: Style, location: Location) {
    val point = JSONObject()
        .put("type", "Feature")
        .put("geometry", JSONObject()
            .put("type", "Point")
            .put("coordinates", JSONArray().put(location.longitude).put(location.latitude)))
        .put("properties", JSONObject())
    val geoJson = JSONObject()
        .put("type", "FeatureCollection")
        .put("features", JSONArray().put(point))
        .toString()
    val existing = style.getSourceAs<GeoJsonSource>(PLAYER_SOURCE_ID)
    if (existing != null) {
        existing.setGeoJson(geoJson)
        return
    }
    style.addSource(GeoJsonSource(PLAYER_SOURCE_ID, geoJson))
    style.addLayer(CircleLayer("player-location-halo", PLAYER_SOURCE_ID).apply {
        setProperties(
            circleColor("#F8FFF0"),
            circleRadius(13f),
            circleStrokeColor("#173D2B"),
            circleStrokeWidth(2.5f)
        )
    })
    style.addLayer(CircleLayer("player-location-dot", PLAYER_SOURCE_ID).apply {
        setProperties(
            circleColor("#B8F36B"),
            circleRadius(7f),
            circleStrokeColor("#173D2B"),
            circleStrokeWidth(1.5f)
        )
    })
}

private fun renderCourseFeatures(style: Style, features: List<CourseFeature>) {
    val geoJson = buildCourseGeoJson(features)
    val existing = style.getSourceAs<GeoJsonSource>(COURSE_SOURCE_ID)
    if (existing != null) {
        existing.setGeoJson(geoJson)
        return
    }

    style.addSource(GeoJsonSource(COURSE_SOURCE_ID, geoJson))
    val kinds = listOf(
        FeatureStyle("fairway", "#72B85A", "#D8F2A1", 0.34f, 2.2f, 5.0f),
        FeatureStyle("green", "#B8F36B", "#F2FFD9", 0.48f, 2.8f, 6.0f),
        FeatureStyle("bunker", "#E6C77A", "#FFF0C2", 0.58f, 2.0f, 5.0f),
        FeatureStyle("water_hazard", "#43B9E8", "#B8EFFF", 0.52f, 2.2f, 5.0f),
        FeatureStyle("tee", "#A7C98B", "#E6F5D8", 0.48f, 2.0f, 5.0f)
    )

    kinds.forEach { featureStyle ->
        val polygonFilter = Expression.all(
            Expression.eq(Expression.get("kind"), Expression.literal(featureStyle.kind)),
            Expression.eq(Expression.get("shape"), Expression.literal("polygon"))
        )
        val lineFilter = Expression.all(
            Expression.eq(Expression.get("kind"), Expression.literal(featureStyle.kind)),
            Expression.eq(Expression.get("shape"), Expression.literal("line"))
        )
        val pointFilter = Expression.all(
            Expression.eq(Expression.get("kind"), Expression.literal(featureStyle.kind)),
            Expression.eq(Expression.get("shape"), Expression.literal("point"))
        )

        style.addLayer(
            FillLayer("course-${featureStyle.kind}-fill", COURSE_SOURCE_ID).apply {
                setFilter(polygonFilter)
                setProperties(
                    fillColor(featureStyle.fill),
                    fillOpacity(featureStyle.opacity),
                    fillOutlineColor(featureStyle.outline)
                )
            }
        )
        style.addLayer(
            LineLayer("course-${featureStyle.kind}-line", COURSE_SOURCE_ID).apply {
                setFilter(lineFilter)
                setProperties(
                    lineColor(featureStyle.outline),
                    lineWidth(featureStyle.lineWidth),
                    lineOpacity(0.95f)
                )
            }
        )
        style.addLayer(
            CircleLayer("course-${featureStyle.kind}-point", COURSE_SOURCE_ID).apply {
                setFilter(pointFilter)
                setProperties(
                    circleColor(featureStyle.fill),
                    circleRadius(featureStyle.pointRadius),
                    circleStrokeColor(featureStyle.outline),
                    circleStrokeWidth(1.5f)
                )
            }
        )
    }
}

private data class FeatureStyle(
    val kind: String,
    val fill: String,
    val outline: String,
    val opacity: Float,
    val lineWidth: Float,
    val pointRadius: Float
)

private fun buildCourseGeoJson(features: List<CourseFeature>): String {
    val output = JSONArray()
    val displayFeatures = groupNearbyTeeMarkers(features)
    displayFeatures.forEach { feature ->
        val kind = normalizeFeatureKind(feature.featureType) ?: return@forEach
        if (feature.points.isEmpty()) return@forEach
        val coordinates = JSONArray()
        feature.points.forEach { point ->
            coordinates.put(JSONArray().put(point.longitude).put(point.latitude))
        }
        val closed = feature.points.size >= 4 &&
            feature.points.first().let { first ->
                feature.points.last().let { last ->
                    kotlin.math.abs(first.latitude - last.latitude) < 0.000001 &&
                        kotlin.math.abs(first.longitude - last.longitude) < 0.000001
                }
            }
        val shape = when {
            feature.points.size == 1 -> "point"
            closed -> "polygon"
            else -> "line"
        }
        val geometry = when (shape) {
            "point" -> JSONObject().put("type", "Point").put("coordinates", coordinates.getJSONArray(0))
            "polygon" -> JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(coordinates))
            else -> JSONObject().put("type", "LineString").put("coordinates", coordinates)
        }
        val properties = JSONObject()
            .put("kind", kind)
            .put("shape", shape)
            .put("osm_id", feature.id)
            .put("element_type", feature.elementType)
            .put("group_size", if (feature.name?.startsWith("tee-group:") == true) feature.name.substringAfter(":").toIntOrNull() ?: 1 else 1)
        output.put(JSONObject().put("type", "Feature").put("geometry", geometry).put("properties", properties))
    }
    return JSONObject().put("type", "FeatureCollection").put("features", output).toString()
}

/**
 * OSM often stores each tee marker as a separate point. Nearby tee points are
 * grouped for display so a tee box is represented as one approximate area
 * marker rather than a cluster of distracting dots. This is display grouping,
 * not a claim that the resulting centroid is a surveyed tee-box boundary.
 */
private fun groupNearbyTeeMarkers(features: List<CourseFeature>): List<CourseFeature> {
    val teePoints = features.filter {
        normalizeFeatureKind(it.featureType) == "tee" && it.points.size == 1
    }
    if (teePoints.size < 2) return features

    val remaining = teePoints.toMutableSet()
    val grouped = mutableListOf<CourseFeature>()

    while (remaining.isNotEmpty()) {
        val seed = remaining.first()
        remaining.remove(seed)
        val cluster = mutableListOf(seed)
        var index = 0
        while (index < cluster.size) {
            val current = cluster[index++]
            val nearby = remaining.filter { candidate ->
                val sameKnownHole = current.holeNumber != null &&
                    candidate.holeNumber != null &&
                    current.holeNumber != candidate.holeNumber
                !sameKnownHole && distanceMeters(current.points[0], candidate.points[0]) <= 18.0
            }
            cluster.addAll(nearby)
            remaining.removeAll(nearby.toSet())
        }

        val center = GeoPoint(
            latitude = cluster.map { it.points[0].latitude }.average(),
            longitude = cluster.map { it.points[0].longitude }.average()
        )
        val holeNumbers = cluster.mapNotNull { it.holeNumber }.distinct()
        grouped.add(
            CourseFeature(
                id = cluster.minOf { it.id },
                elementType = if (cluster.size > 1) "derived" else cluster.first().elementType,
                featureType = "tee_group",
                holeNumber = holeNumbers.singleOrNull(),
                name = if (cluster.size > 1) "tee-group:${cluster.size}" else cluster.first().name,
                points = listOf(center)
            )
        )
    }

    val nonTeePoints = features.filterNot { it in teePoints }
    return nonTeePoints + grouped
}

private fun distanceMeters(a: com.watchthisdrive.app.data.GeoPoint, b: com.watchthisdrive.app.data.GeoPoint): Double {
    val earthRadius = 6_371_000.0
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
        kotlin.math.cos(lat1) * kotlin.math.cos(lat2) *
        kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
    return 2 * earthRadius * kotlin.math.asin(kotlin.math.sqrt(h))
}

private fun normalizeFeatureKind(value: String): String? = when (value.lowercase()) {
    "fairway" -> "fairway"
    "green" -> "green"
    "bunker" -> "bunker"
    "water_hazard", "water" -> "water_hazard"
    "tee", "tee_group" -> "tee"
    else -> null
}

private fun featureSummary(features: List<CourseFeature>): String {
    val labels = listOf(
        "tee" to "tee areas",
        "fairway" to "fairways",
        "green" to "greens",
        "bunker" to "bunkers",
        "water_hazard" to "water"
    )
    val counts = labels.mapNotNull { (kind, label) ->
        val count = features.count { normalizeFeatureKind(it.featureType) == kind }
        if (count > 0) "$count $label" else null
    }
    return if (counts.isEmpty()) {
        "No recognized mapped golf features"
    } else {
        "Mapped: ${counts.joinToString(" · ")}. Coverage may be incomplete."
    }
}

private const val COURSE_SOURCE_ID = "watch-this-drive-course-features"
private const val EMPTY_STYLE = """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#101713"}}]}"""
