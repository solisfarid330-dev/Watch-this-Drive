package com.watchthisdrive.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.location.*
import com.watchthisdrive.app.data.CourseGeometry
import com.watchthisdrive.app.data.ClubCarryDistances
import com.watchthisdrive.app.data.CourseReconstruction
import com.watchthisdrive.app.data.GeoPoint
import com.watchthisdrive.app.data.ReconstructedHole
import kotlin.math.roundToInt

@Composable
fun ActiveHoleScreen(
    courseName: String,
    hole: ReconstructedHole,
    holeIndex: Int,
    holeCount: Int,
    strokes: Int,
    overallStrokes: Int,
    overallRelativeToPar: Int?,
    carryDistances: Map<String, Int>,
    geometry: CourseGeometry?,
    reconstruction: CourseReconstruction?,
    onStrokesChange: (Int) -> Unit,
    onPreviousHole: () -> Unit,
    onNextHole: () -> Unit,
    onExitRound: () -> Unit
) {
    val context = LocalContext.current
    val fused = remember { LocationServices.getFusedLocationProviderClient(context) }
    var player by remember { mutableStateOf<Location?>(null) }
    var gpsMessage by remember { mutableStateOf("Waiting for GPS") }
    var refreshingGps by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<GeoPoint?>(null) }
    LaunchedEffect(hole.number) { target = null }
    var northUp by remember { mutableStateOf(false) }
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasLocationPermission =
            result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (hasLocationPermission) {
            gpsMessage = "Getting a fresh GPS fix…"
            refreshingGps = true
            try {
                fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { fresh ->
                        refreshingGps = false
                        if (fresh != null) {
                            player = fresh
                            gpsMessage = "GPS ±${fresh.accuracy.toInt()} m"
                        } else gpsMessage = "No fresh fix yet — keep GPS enabled and try again"
                    }
                    .addOnFailureListener {
                        refreshingGps = false
                        gpsMessage = "Couldn't refresh GPS — try again"
                    }
            } catch (_: SecurityException) {
                refreshingGps = false
                gpsMessage = "Location permission needed"
            }
        } else {
            refreshingGps = false
            gpsMessage = "Location permission denied"
        }
    }
    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    fun refreshGps() {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            gpsMessage = "Requesting location permission…"
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        refreshingGps = true
        gpsMessage = "Getting a fresh GPS fix…"
        try {
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { fresh ->
                    refreshingGps = false
                    if (fresh != null) {
                        player = fresh
                        gpsMessage = "GPS ±${fresh.accuracy.toInt()} m"
                    } else gpsMessage = "No fresh fix yet — keep GPS enabled and try again"
                }
                .addOnFailureListener {
                    refreshingGps = false
                    gpsMessage = "Couldn't refresh GPS — try again"
                }
        } catch (_: SecurityException) {
            refreshingGps = false
            gpsMessage = "Location permission needed"
        }
    }

    DisposableEffect(fused, hasLocationPermission) {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        var callback: LocationCallback? = null
        if (hasLocationPermission && (fine || coarse)) {
            callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val candidate = result.lastLocation ?: return
                    val now = System.currentTimeMillis()
                    val ageMillis = now - candidate.time
                    if (candidate.time <= 0L || ageMillis > 15_000L || ageMillis < -5_000L) {
                        gpsMessage = "Waiting for a fresh GPS fix"
                        return
                    }
                    if (candidate.accuracy > 35f) {
                        gpsMessage = "GPS accuracy ±${candidate.accuracy.toInt()} m"
                        return
                    }
                    val previous = player
                    if (previous != null && candidate.time < previous.time) return
                    if (previous != null) {
                        val elapsedSeconds = ((candidate.time - previous.time).coerceAtLeast(1L)) / 1000f
                        val jumpMeters = previous.distanceTo(candidate)
                        val plausibleJumpMeters = maxOf(120f, elapsedSeconds * 15f)
                        if (jumpMeters > plausibleJumpMeters && candidate.accuracy >= previous.accuracy) {
                            gpsMessage = "Ignoring an inconsistent GPS position"
                            return
                        }
                    }
                    player = candidate
                    gpsMessage = "GPS ±${candidate.accuracy.toInt()} m"
                }
            }
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1500L)
                .setMinUpdateIntervalMillis(1000L)
                .setMinUpdateDistanceMeters(1f)
                .build()
            try {
                fused.requestLocationUpdates(request, callback, context.mainLooper)
                fused.lastLocation.addOnSuccessListener { last ->
                    val ageMillis = last?.let { System.currentTimeMillis() - it.time }
                    if (player == null && last != null && last.accuracy <= 35f &&
                        ageMillis != null && ageMillis in -5_000L..15_000L) player = last
                }
            } catch (_: SecurityException) {
                gpsMessage = "Location permission needed"
            }
        } else if (!hasLocationPermission) gpsMessage = "Location permission needed"
        onDispose { callback?.let { fused.removeLocationUpdates(it) } }
    }

    // A cached fix from home must not drag the course map away from the selected hole.
    val playerOnHole = player?.takeIf {
        distanceToPolylineMeters(it.latitude, it.longitude, hole.centerline) <= 250.0
    }
    val displayedGpsMessage = if (player != null && playerOnHole == null) {
        "GPS fix is away from this hole"
    } else gpsMessage
    val pinPoint = hole.pin
    val pinDistance = if (playerOnHole != null && pinPoint != null) FloatArray(1).also {
        Location.distanceBetween(playerOnHole.latitude, playerOnHole.longitude, pinPoint.latitude, pinPoint.longitude, it)
    }[0] else null
    val targetDistance = if (playerOnHole != null && target != null) FloatArray(1).also {
        Location.distanceBetween(playerOnHole.latitude, playerOnHole.longitude, target!!.latitude, target!!.longitude, it)
    }[0] else null
    val relativeScore = hole.par?.takeIf { strokes > 0 }?.let { strokes - it }
    val relativeLabel = when {
        relativeScore == null -> "—"
        relativeScore == 0 -> "E"
        relativeScore > 0 -> "+$relativeScore"
        else -> relativeScore.toString()
    }
    val yardsToPin = pinDistance?.let { (it * 1.0936133f).roundToInt() }
    val yardsToTarget = targetDistance?.let { (it * 1.0936133f).roundToInt() }
    val caddyDistance = yardsToTarget ?: yardsToPin
    val recommendedClub = caddyDistance?.let { distance ->
        carryDistances.entries
            .filter { it.value >= distance }
            .minByOrNull { it.value }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF101713))) {
        GolfMap(
            location = playerOnHole, onLocate = {}, courseGeometry = geometry,
            reconstruction = reconstruction, activeHole = hole, playerLocation = playerOnHole,
            targetPoint = target, onMapTap = { target = it }, northUp = northUp
        )
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top
        ) {
            Surface(color = Color(0xE91A251E), shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(courseName, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    Text("HOLE ${hole.number}", color = Color(0xFFB8F36B), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(hole.par?.let { "PAR $it" } ?: "PAR —", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    if (!hole.teeToGreenOriented) Text("Hole direction uncertain", color = Color(0xFFFFC857), style = MaterialTheme.typography.labelSmall)
                }
            }
            Surface(color = Color(0xE91A251E), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("THIS HOLE", color = Color(0xFFB6C1B9), style = MaterialTheme.typography.labelSmall)
                    Text(relativeLabel, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Strokes $strokes", color = Color(0xFFB8F36B), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("ROUND", color = Color(0xFFB6C1B9), style = MaterialTheme.typography.labelSmall)
                    Text("$overallStrokes · ${overallRelativeToPar?.let { if (it == 0) "E" else if (it > 0) "+$it" else it.toString() } ?: "—"}", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
            IconButton(onClick = { northUp = !northUp }, modifier = Modifier.background(Color(0xE91A251E), RoundedCornerShape(14.dp))) {
                Icon(if (northUp) Icons.Filled.Explore else Icons.Filled.Navigation, contentDescription = "Toggle map orientation", tint = Color.White)
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Surface(color = Color(0xE91A251E), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (target != null) "DISTANCE TO TARGET" else "DISTANCE TO PIN", color = Color(0xFFB6C1B9), style = MaterialTheme.typography.labelSmall)
                        Text((yardsToTarget ?: yardsToPin)?.let { "$it yd" } ?: "— yd", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(displayedGpsMessage, color = Color(0xFFB8F36B), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            Spacer(Modifier.width(4.dp))
                            TextButton(onClick = { refreshGps() }, enabled = !refreshingGps, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                                if (refreshingGps) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = Color(0xFFB8F36B))
                                else Icon(Icons.Filled.Refresh, contentDescription = "Refresh GPS", tint = Color(0xFFB8F36B), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("GPS", color = Color(0xFFB8F36B), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (hole.pinSource == com.watchthisdrive.app.data.PinSource.ESTIMATED_GREEN_CENTER) Text("Estimated green-center pin", color = Color(0xFFFFC857), style = MaterialTheme.typography.labelSmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("CLUB", color = Color(0xFFB6C1B9), style = MaterialTheme.typography.labelSmall)
                        Text(
                            recommendedClub?.key ?: "Set carry distances",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            when {
                                recommendedClub != null -> "${recommendedClub.value} yd carry"
                                caddyDistance == null -> "Waiting for yardage"
                                carryDistances.isEmpty() -> "No club data configured"
                                else -> "No club reaches target"
                            },
                            color = Color(0xFFB6C1B9),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            if (target != null) {
                Surface(color = Color(0xE91A251E), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(yardsToTarget?.let { "Selected target · $it yd" } ?: "Target selected", color = Color.White, modifier = Modifier.weight(1f))
                        TextButton(onClick = { target = null }) { Text("Clear", color = Color(0xFFB8F36B)) }
                    }
                }
            } else Text("Tap the map to measure to any target", color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.labelMedium)
            Surface(color = Color(0xF018231C), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = onExitRound) { Icon(Icons.Filled.ArrowBack, contentDescription = "Leave game and return home", tint = Color.White) }
                    TextButton(onClick = onPreviousHole, enabled = holeIndex > 0) { Text("Previous", color = Color.White) }
                    Text("${holeIndex + 1} / $holeCount", color = Color(0xFFB8F36B), fontWeight = FontWeight.Bold)
                    TextButton(onClick = onNextHole, enabled = holeIndex < holeCount - 1) { Text("Next", color = Color.White) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onStrokesChange(strokes - 1) }, enabled = strokes > 0) { Icon(Icons.Filled.Remove, contentDescription = "Remove stroke", tint = Color.White) }
                        Text("$strokes", color = Color.White, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { onStrokesChange(strokes + 1) }) { Icon(Icons.Filled.Add, contentDescription = "Add stroke", tint = Color(0xFFB8F36B)) }
                    }
                }
            }
        }
    }
}

private fun distanceToPolylineMeters(latitude: Double, longitude: Double, points: List<GeoPoint>): Double {
    if (points.size < 2) return Double.POSITIVE_INFINITY
    val radius = 6_371_008.8
    val meanLatitude = Math.toRadians(latitude)
    val px = Math.toRadians(longitude) * radius * kotlin.math.cos(meanLatitude)
    val py = Math.toRadians(latitude) * radius
    var minimum = Double.POSITIVE_INFINITY
    points.zipWithNext().forEach { (a, b) ->
        val ax = Math.toRadians(a.longitude) * radius * kotlin.math.cos(meanLatitude)
        val ay = Math.toRadians(a.latitude) * radius
        val bx = Math.toRadians(b.longitude) * radius * kotlin.math.cos(meanLatitude)
        val by = Math.toRadians(b.latitude) * radius
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared <= 0.0) 0.0 else
            (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        val nearestX = ax + t * dx
        val nearestY = ay + t * dy
        minimum = minOf(minimum, kotlin.math.hypot(px - nearestX, py - nearestY))
    }
    return minimum
}
