package com.watchthisdrive.app.ui

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import android.location.Location
import com.watchthisdrive.app.BuildConfig
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
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import com.watchthisdrive.app.data.CourseFeature
import com.watchthisdrive.app.data.CourseGeometry
import com.watchthisdrive.app.data.CourseGeometryType
import com.watchthisdrive.app.data.CourseReconstruction
import com.watchthisdrive.app.data.GeoPoint
import com.watchthisdrive.app.data.PinSource
import org.maplibre.android.camera.CameraUpdateFactory

@Composable
fun GolfMap(
    location: Location?,
    onLocate: () -> Unit,
    courseGeometry: CourseGeometry? = null,
    reconstruction: CourseReconstruction? = null,
    activeHole: com.watchthisdrive.app.data.ReconstructedHole? = null,
    playerLocation: Location? = null,
    targetPoint: GeoPoint? = null,
    onMapTap: ((GeoPoint) -> Unit)? = null,
    northUp: Boolean = false
) {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(Bundle()) }
    }
    var mapReady by remember { mutableStateOf(false) }
    val key = BuildConfig.MAPTILER_API_KEY
    val latestOnMapTap by rememberUpdatedState(onMapTap)
    val styleUrl = if (key.isNotBlank()) "https://api.maptiler.com/maps/satellite/style.json?key=$key" else ""

    DisposableEffect(mapView) {
        mapView.onStart()
        mapView.getMapAsync { map ->
            map.setStyle(if (styleUrl.isNotBlank()) Style.Builder().fromUri(styleUrl) else Style.Builder().fromJson(EMPTY_STYLE)) {
                map.uiSettings.isCompassEnabled = true
                map.uiSettings.isLogoEnabled = true
                map.uiSettings.isAttributionEnabled = true
                map.addOnMapClickListener { point ->
                    latestOnMapTap?.invoke(GeoPoint(point.latitude, point.longitude))
                    latestOnMapTap != null
                }
                map.cameraPosition = CameraPosition.Builder().target(LatLng(39.5, -98.35)).zoom(3.5).build()
                mapReady = true
            }
        }
        onDispose {
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    val holeViewport = activeHole?.let { HoleMapViewport.from(it) }
    val cameraLocation = playerLocation ?: location
    val followBearing = if (northUp) 0.0 else holeViewport?.bearing ?: 0.0
    val followCenter = if (cameraLocation != null && holeViewport != null) {
        pointAhead(cameraLocation.latitude, cameraLocation.longitude, followBearing, 55.0)
    } else null

    LaunchedEffect(cameraLocation?.latitude, cameraLocation?.longitude, mapReady, holeViewport, activeHole, northUp) {
        if (mapReady) {
            mapView.getMapAsync { map ->
                if (holeViewport != null) {
                    val center = followCenter ?: holeViewport.center
                    val camera = CameraPosition.Builder()
                        .target(LatLng(center.latitude, center.longitude))
                        .zoom(if (cameraLocation != null) maxOf(16.5, holeViewport.zoom) else holeViewport.zoom)
                        .bearing(if (northUp) 0.0 else holeViewport.bearing)
                        .build()
                    map.animateCamera(CameraUpdateFactory.newCameraPosition(camera), 650)
                } else if (cameraLocation != null) {
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(cameraLocation.latitude, cameraLocation.longitude), 16.0
                        ), 800
                    )
                }
            }
        }
    }

    LaunchedEffect(courseGeometry, reconstruction, activeHole, playerLocation?.latitude, playerLocation?.longitude, targetPoint, mapReady) {
        if (courseGeometry != null && mapReady) {
            mapView.getMapAsync { map ->
                map.getStyle { style ->
                    if (activeHole != null) {
                        addActiveHoleOverlays(style, activeHole, playerLocation, targetPoint)
                    } else {
                        addCourseOverlays(style, courseGeometry, reconstruction)
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (key.isBlank()) {
            Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xEE18231C))) {
                Text("MapTiler key hasn't been configured yet. The map will load once the project secret is added.", color = Color.White, modifier = Modifier.padding(14.dp))
            }
        }
        if (activeHole == null) FloatingActionButton(onClick = onLocate, modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(18.dp), containerColor = Color(0xFFB8F36B), contentColor = Color(0xFF101713)) {
            Icon(Icons.Filled.MyLocation, contentDescription = "Find my location")
        }
    }
}

private fun addCourseOverlays(style: Style, geometry: CourseGeometry, reconstruction: CourseReconstruction?) {
    val areas = listOf(
        "fairway" to "#8DBB65",
        "green" to "#B8F36B",
        "bunker" to "#D8C28B",
        "water_hazard" to "#368CB5",
        "rough" to "#426B43",
        "tee" to "#E5EAD7"
    )
    areas.forEach { (type, color) ->
        val sourceId = "wtd-$type-source"
        val layerId = "wtd-$type-fill"
        val json = featureCollection(geometry.features.filter { it.featureType == type && it.hasUsableGeometry })
        ensureSource(style, sourceId, json)
        if (style.getLayer(layerId) == null) {
            style.addLayer(FillLayer(layerId, sourceId).withProperties(fillColor(color), fillOpacity(0.58f)))
        }
        val outlineId = "$layerId-outline"
        if (style.getLayer(outlineId) == null) {
            style.addLayer(LineLayer(outlineId, sourceId).withProperties(lineColor(color), lineWidth(1.8f)))
        }
    }

    val holes = geometry.features.filter { it.featureType == "hole" && it.geometryType == CourseGeometryType.LINE }
    val holeSource = "wtd-hole-lines-source"
    ensureSource(style, holeSource, featureCollection(holes))
    if (style.getLayer("wtd-hole-lines") == null) {
        style.addLayer(LineLayer("wtd-hole-lines", holeSource).withProperties(lineColor("#FFFFFF"), lineWidth(2.2f)))
    }

    val mappedPinPoints = geometry.features
        .filter { it.featureType == "pin" && it.geometryType == CourseGeometryType.POINT }
        .flatMap { it.points }
    val estimatedPinPoints = reconstruction?.holes.orEmpty()
        .filter { it.pin != null && it.pinSource == PinSource.ESTIMATED_GREEN_CENTER }
        .map { it.pin!! }
    val mappedPinSource = "wtd-mapped-pin-points-source"
    ensureSource(style, mappedPinSource, pointFeatureCollection(mappedPinPoints.map { it to PinSource.MAPPED }))
    if (style.getLayer("wtd-mapped-pin-points") == null) {
        style.addLayer(CircleLayer("wtd-mapped-pin-points", mappedPinSource).withProperties(circleRadius(5.5f), circleColor("#FF6B6B")))
    }
    val estimatedPinSource = "wtd-estimated-pin-points-source"
    ensureSource(style, estimatedPinSource, pointFeatureCollection(estimatedPinPoints.map { it to PinSource.ESTIMATED_GREEN_CENTER }))
    if (style.getLayer("wtd-estimated-pin-points") == null) {
        style.addLayer(CircleLayer("wtd-estimated-pin-points", estimatedPinSource).withProperties(circleRadius(5.5f), circleColor("#FFC857")))
    }
}

private fun ensureSource(style: Style, id: String, geoJson: String) {
    val existing = style.getSourceAs<GeoJsonSource>(id)
    if (existing == null) style.addSource(GeoJsonSource(id, geoJson)) else existing.setGeoJson(geoJson)
}

private fun featureCollection(features: List<CourseFeature>): String {
    val collection = JSONArray()
    features.forEach { feature ->
        val geometry = geometryJson(feature) ?: return@forEach
        collection.put(JSONObject().put("type", "Feature").put("properties", JSONObject().put("golf", feature.featureType))
            .put("geometry", geometry))
    }
    return JSONObject().put("type", "FeatureCollection").put("features", collection).toString()
}

private fun pointFeatureCollection(points: List<Pair<GeoPoint, PinSource?>>): String {
    val features = JSONArray()
    points.forEach { (point, source) ->
        val coordinates = JSONArray().put(point.longitude).put(point.latitude)
        val geometry = JSONObject().put("type", "Point").put("coordinates", coordinates)
        val properties = JSONObject().put("source", source?.name ?: "mapped")
        features.put(JSONObject().put("type", "Feature").put("properties", properties).put("geometry", geometry))
    }
    return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
}

private fun geometryJson(feature: CourseFeature): JSONObject? {
    fun coordinate(point: GeoPoint) = JSONArray().put(point.longitude).put(point.latitude)
    fun line(points: List<GeoPoint>) = JSONArray().also { array -> points.forEach { array.put(coordinate(it)) } }
    fun polygon(rings: List<List<GeoPoint>>) = JSONArray().also { array -> rings.forEach { array.put(line(it)) } }
    return when (feature.geometryType) {
        CourseGeometryType.POINT -> feature.points.firstOrNull()?.let { JSONObject().put("type", "Point").put("coordinates", coordinate(it)) }
        CourseGeometryType.LINE -> JSONObject().put("type", "LineString").put("coordinates", line(feature.points))
        CourseGeometryType.POLYGON -> JSONObject().put("type", "Polygon").put("coordinates", polygon(feature.rings))
        CourseGeometryType.MULTI_POLYGON -> JSONObject().put("type", "MultiPolygon").put("coordinates",
            JSONArray().also { array -> feature.polygons.forEach { array.put(polygon(it)) } })
        CourseGeometryType.UNKNOWN -> null
    }
}

private fun addActiveHoleOverlays(
    style: Style,
    hole: com.watchthisdrive.app.data.ReconstructedHole,
    player: Location?,
    target: GeoPoint?
) {
    val areaFeatures = buildList {
        hole.tee?.let(::add)
        hole.green?.let(::add)
        addAll(hole.fairways)
        addAll(hole.bunkers)
        addAll(hole.waterHazards)
    }
    val colors = mapOf(
        "fairway" to "#8DBB65", "green" to "#B8F36B", "bunker" to "#D8C28B",
        "water_hazard" to "#368CB5", "tee" to "#E5EAD7"
    )
    colors.forEach { (type, color) ->
        val features = areaFeatures.filter { it.featureType == type && it.hasUsableGeometry }
        val sourceId = "wtd-active-$type-source"
        ensureSource(style, sourceId, featureCollection(features))
        if (style.getLayer("wtd-active-$type-fill") == null) {
            style.addLayer(FillLayer("wtd-active-$type-fill", sourceId)
                .withProperties(fillColor(color), fillOpacity(0.68f)))
        }
        if (style.getLayer("wtd-active-$type-outline") == null) {
            style.addLayer(LineLayer("wtd-active-$type-outline", sourceId)
                .withProperties(lineColor(color), lineWidth(2.0f)))
        }
    }

    val centerline = lineFeatureCollection(hole.centerline)
    ensureSource(style, "wtd-active-centerline-source", centerline)
    if (style.getLayer("wtd-active-centerline") == null) {
        style.addLayer(LineLayer("wtd-active-centerline", "wtd-active-centerline-source")
            .withProperties(lineColor("#FFFFFF"), lineWidth(2.2f)))
    }
    val teePoint = hole.tee
        ?.takeIf { it.geometryType == CourseGeometryType.POINT }
        ?.points?.firstOrNull()
    ensureSource(style, "wtd-active-tee-source",
        pointFeatureCollection(teePoint?.let { listOf(it to null) }.orEmpty()))
    if (style.getLayer("wtd-active-tee") == null) {
        style.addLayer(CircleLayer("wtd-active-tee", "wtd-active-tee-source")
            .withProperties(circleRadius(6f), circleColor("#FFFFFF")))
    }

    val mappedPin = hole.pin?.takeIf { hole.pinSource == PinSource.MAPPED }
    ensureSource(style, "wtd-active-mapped-pin-source",
        pointFeatureCollection(mappedPin?.let { listOf(it to PinSource.MAPPED) }.orEmpty()))
    if (style.getLayer("wtd-active-mapped-pin") == null) {
        style.addLayer(CircleLayer("wtd-active-mapped-pin", "wtd-active-mapped-pin-source")
            .withProperties(circleRadius(7f), circleColor("#FF6B6B")))
    }
    val estimatedPin = hole.pin?.takeIf { hole.pinSource == PinSource.ESTIMATED_GREEN_CENTER }
    ensureSource(style, "wtd-active-estimated-pin-source",
        pointFeatureCollection(estimatedPin?.let { listOf(it to PinSource.ESTIMATED_GREEN_CENTER) }.orEmpty()))
    if (style.getLayer("wtd-active-estimated-pin") == null) {
        style.addLayer(CircleLayer("wtd-active-estimated-pin", "wtd-active-estimated-pin-source")
            .withProperties(circleRadius(7f), circleColor("#FFC857")))
    }
    val playerPoint = player?.let { GeoPoint(it.latitude, it.longitude) }
    val targetLine = if (playerPoint != null && target != null) lineFeatureCollection(listOf(playerPoint, target)) else JSONObject().put("type", "FeatureCollection").put("features", JSONArray()).toString()
    ensureSource(style, "wtd-active-target-line-source", targetLine)
    if (style.getLayer("wtd-active-target-line") == null) {
        style.addLayer(LineLayer("wtd-active-target-line", "wtd-active-target-line-source")
            .withProperties(lineColor("#FFFFFF"), lineWidth(1.6f)))
    }
    ensureSource(style, "wtd-active-player-source",
        pointFeatureCollection(playerPoint?.let { listOf(it to null) }.orEmpty()))
    if (style.getLayer("wtd-active-player") == null) {
        style.addLayer(CircleLayer("wtd-active-player", "wtd-active-player-source")
            .withProperties(circleRadius(7.5f), circleColor("#53C7FF")))
    }
    ensureSource(style, "wtd-active-target-source",
        pointFeatureCollection(target?.let { listOf(it to null) }.orEmpty()))
    if (style.getLayer("wtd-active-target") == null) {
        style.addLayer(CircleLayer("wtd-active-target", "wtd-active-target-source")
            .withProperties(circleRadius(6f), circleColor("#FFFFFF")))
    }
}

private fun lineFeatureCollection(points: List<GeoPoint>): String {
    val coordinates = JSONArray()
    points.forEach { coordinates.put(JSONArray().put(it.longitude).put(it.latitude)) }
    val feature = JSONObject().put("type", "Feature").put("properties", JSONObject())
        .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coordinates))
    return JSONObject().put("type", "FeatureCollection").put("features", JSONArray().put(feature)).toString()
}

private fun pointAhead(latitude: Double, longitude: Double, bearing: Double, meters: Double): GeoPoint {
    val angular = meters / 6_371_008.8
    val brng = Math.toRadians(bearing)
    val lat1 = Math.toRadians(latitude)
    val lon1 = Math.toRadians(longitude)
    val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(brng))
    val lon2 = lon1 + atan2(
        sin(brng) * sin(angular) * cos(lat1),
        cos(angular) - sin(lat1) * sin(lat2)
    )
    return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
}

private const val EMPTY_STYLE = """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#101713"}}]}"""
