package com.watchthisdrive.app.ui

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
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

@Composable
fun GolfMap(location: Location?, onLocate: () -> Unit) {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(Bundle()) }
    }
    var mapReady by remember { mutableStateOf(false) }
    val key = BuildConfig.MAPTILER_API_KEY
    val styleUrl = if (key.isNotBlank()) "https://api.maptiler.com/maps/satellite/style.json?key=$key" else ""

    DisposableEffect(mapView) {
        mapView.onStart()
        mapView.getMapAsync { map ->
            map.setStyle(if (styleUrl.isNotBlank()) Style.Builder().fromUri(styleUrl) else Style.Builder().fromJson(EMPTY_STYLE)) {
                map.uiSettings.isCompassEnabled = true
                map.uiSettings.isLogoEnabled = true
                map.uiSettings.isAttributionEnabled = true
                map.cameraPosition = CameraPosition.Builder().target(LatLng(39.5, -98.35)).zoom(3.5).build()
                mapReady = true
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
                map.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), 16.0))
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
        FloatingActionButton(onClick = onLocate, modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(18.dp), containerColor = Color(0xFFB8F36B), contentColor = Color(0xFF101713)) {
            Icon(Icons.Filled.MyLocation, contentDescription = "Find my location")
        }
    }
}

private const val EMPTY_STYLE = """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#101713"}}]}"""
