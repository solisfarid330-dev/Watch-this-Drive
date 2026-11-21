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
import org.json.JSONArray
import org.json.JSONObject
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
    onLocate: () -> Unit
) {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(Bundle()) }
    }
    var mapReady by remember { mutableStateOf(false) }
    var geometry by remember(course?.id) { mutableStateOf<CourseGeometry?>(null) }
    var geometryMessage by remember(course?.id) { mutableStateOf<String?>(null) }
    val key = BuildConfig.MAPTILER_API_KEY
    val styleUrl = if (key.isNotBlank()) "https://api.maptiler.com/maps/satellite/style.json?key=$key" else ""

    LaunchedEffect(course?.id) {
        geometry = null
        geometryMessage = null
        if (course != null) {
            geometryMessage = "Loading mapped course features…"
            CourseGeometryLoader.load(course) { result ->
                result.onSuccess {
                    geometry = it
                    geometryMessage = if (it.features.isEmpty()) {
                        "No mapped golf features were found for this course."
                    } else {
                        null
                    }
                }.onFailure {
                    geometryMessage = "Course features couldn't be loaded: " + (it.message ?: "unknown error")
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

    LaunchedEffect(location, mapReady) {
        if (location != null && mapReady) {
            mapView.getMapAsync { map ->
                map.animateCamera(
                    org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                        LatLng(location.latitude, location.longitude), 16.0
                    )
                )
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

    Box(Modifier.fillMaxSize()) {
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
    features.forEach { feature ->
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
        output.put(JSONObject().put("type", "Feature").put("geometry", geometry).put("properties", properties))
    }
    return JSONObject().put("type", "FeatureCollection").put("features", output).toString()
}

private fun normalizeFeatureKind(value: String): String? = when (value.lowercase()) {
    "fairway" -> "fairway"
    "green" -> "green"
    "bunker" -> "bunker"
    "water_hazard", "water" -> "water_hazard"
    "tee" -> "tee"
    else -> null
}

private fun featureSummary(features: List<CourseFeature>): String {
    val counts = listOf("tee", "fairway", "green", "bunker", "water_hazard").map { kind ->
        val count = features.count { normalizeFeatureKind(it.featureType) == kind }
        "$count ${if (kind == "water_hazard") "water" else kind}"
    }
    return counts.joinToString(" · ")
}

private const val COURSE_SOURCE_ID = "watch-this-drive-course-features"
private const val EMPTY_STYLE = """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#101713"}}]}"""
