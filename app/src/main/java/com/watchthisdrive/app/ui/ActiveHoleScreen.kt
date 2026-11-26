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
import androidx.compose.material3.Slider
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
import com.watchthisdrive.app.data.recommendClubForCarryDistance
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
    onExitRound: () -> Unit,
    onQuitRound: () -> Unit = {},
    simulationMode: Boolean = false
) {
    val context = LocalContext.current
    var showQuitDialog by remember { mutableStateOf(false) }
    val fused = remember { LocationServices.getFusedLocationProviderClient(context) }
    var player by remember { mutableStateOf<Location?>(null) }
    var gpsMessage by remember { mutableStateOf("Waiting for GPS") }
    var refreshingGps by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<GeoPoint?>(null) }
    var simulationProgress by remember(hole.number) { mutableFloatStateOf(0f) }
    val simulationPath = remember(hole.number, hole.centerline, hole.tee) { simulationPathFor(hole) }
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
        if (!simulationMode && !hasLocationPermission) {
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

    DisposableEffect(fused, hasLocationPermission, simulationMode) {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        var callback: LocationCallback? = null
        if (!simulationMode && hasLocationPermission && (fine || coarse)) {
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

    // Simulated positions stay inside WTD and never register an Android mock provider.
    val simulatedPlayer = if (simulationMode && simulationPath.size >= 2) {
        pointAlongPolyline(simulationPath, simulationProgress)?.let { point ->
            Location("wtd-simulation").apply { latitude = point.latitude; longitude = point.longitude; accuracy = 1f; time = System.currentTimeMillis() }
        }
    } else null
    // A cached fix from home must not drag the course map away from the selected hole.
    val playerOnHole = if (simulationMode) simulatedPlayer else player?.takeIf {
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
        recommendClubForCarryDistance(carryDistances, distance)
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF101713))) {
        GolfMap(
            location = playerOnHole, onLocate = {}, courseGeometry = geometry,
            reconstruction = reconstruction, activeHole = hole, playerLocation = playerOnHole,
            targetPoint = target, onMapTap = { target = it }, northUp = northUp
        )
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 5.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = Color(0xE91A251E), shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).heightIn(min = 66.dp)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(courseName, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    Text(
                        "HOLE ${hole.number}  ·  PAR ${hole.par ?: "—"}",
                        color = Color(0xFFB8F36B), style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold, maxLines = 1
                    )
                    if (!hole.teeToGreenOriented) {
                        Text("Direction uncertain", color = Color(0xFFFFC857), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
            Surface(color = Color(0xE91A251E), shape = RoundedCornerShape(12.dp), modifier = Modifier.heightIn(min = 66.dp)) {
                Column(
                    Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("THIS HOLE", color = Color(0xFFB6C1B9), style = MaterialTheme.typography.labelSmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(relativeLabel, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("$strokes strokes", color = Color(0xFFB8F36B), style = MaterialTheme.typography.labelSmall)
                    }
                    Text(
                        "ROUND  $overallStrokes · ${overallRelativeToPar?.let { if (it == 0) "E" else if (it > 0) "+$it" else it.toString() } ?: "—"}",
                        color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1
                    )
                }
            }
            IconButton(
                onClick = { northUp = !northUp },
                modifier = Modifier.size(42.dp).background(Color(0xE91A251E), RoundedCornerShape(14.dp))
            ) {
                Icon(if (northUp) Icons.Filled.Explore else Icons.Filled.Navigation, contentDescription = "Toggle map orientation", tint = Color.White)
            }
        }
        if (simulationMode) {
            Surface(color = Color(0xF0A65A00), shape = RoundedCornerShape(10.dp), modifier = Modifier.align(Alignment.TopCenter).padding(top = 75.dp)) {
                Text("SIMULATION MODE · NOT REAL GPS", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 3.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Surface(color = Color(0xE91A251E), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Column(Modifier.weight(0.92f), verticalArrangement = Arrangement.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (target != null) "TO TARGET" else "TO PIN", color = Color(0xFFB6C1B9), style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.weight(1f))
                            if (!simulationMode) {
                                Text(displayedGpsMessage, color = Color(0xFFB8F36B), style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.weight(1f))
                                TextButton(
                                    onClick = { refreshGps() },
                                    enabled = !refreshingGps,
                                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                                    modifier = Modifier.height(24.dp)
                                ) {
                                    if (refreshingGps) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Color(0xFFB8F36B))
                                    else Icon(Icons.Filled.Refresh, contentDescription = null, tint = Color(0xFFB8F36B), modifier = Modifier.size(13.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text("Refresh", color = Color(0xFFB8F36B), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                            }
                        }
                        Text((yardsToTarget ?: yardsToPin)?.let { "$it yd" } ?: "— yd", color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                        if (simulationMode && simulationPath.size >= 2) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("MOVE", color = Color(0xFFFFD166), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Slider(value = simulationProgress, onValueChange = { simulationProgress = it }, valueRange = 0f..1f, modifier = Modifier.weight(1f).height(22.dp))
                                Text("${(simulationProgress * 100).roundToInt()}%", color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (hole.pinSource == com.watchthisdrive.app.data.PinSource.ESTIMATED_GREEN_CENTER) {
                            Text("Estimated green-center pin", color = Color(0xFFFFC857), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                    Surface(color = Color(0xFF263B2A), shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1.08f)) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 4.dp), verticalArrangement = Arrangement.Center) {
                            Text("CADDY RECOMMENDS", color = Color(0xFFB8F36B), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(recommendedClub?.name ?: "Set carry distances", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.weight(1f))
                                Text(recommendedClub?.let { "${it.carryYards} yd" } ?: "—", color = Color(0xFFD5E2D8), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                            if (recommendedClub?.isBeyondLongestCarry == true) Text("Beyond longest carry", color = Color(0xFFFFD166), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                }
            }
            if (target != null) {
                Surface(color = Color(0xE91A251E), shape = RoundedCornerShape(11.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(yardsToTarget?.let { "Selected target · $it yd" } ?: "Target selected", color = Color.White, modifier = Modifier.weight(1f))
                        TextButton(onClick = { target = null }) { Text("Clear", color = Color(0xFFB8F36B)) }
                    }
                }
            }
            Surface(color = Color(0xF018231C), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 2.dp, vertical = 0.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = onExitRound, modifier = Modifier.size(30.dp)) { Icon(Icons.Filled.ArrowBack, contentDescription = "Leave game and return home", tint = Color.White) }
                    TextButton(onClick = { showQuitDialog = true }, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) { Text("QUIT", color = Color(0xFFFFB4AB), style = MaterialTheme.typography.labelSmall) }
                    TextButton(onClick = onPreviousHole, enabled = holeIndex > 0) { Text("Previous", color = Color.White) }
                    Text("${holeIndex + 1} / $holeCount", color = Color(0xFFB8F36B), fontWeight = FontWeight.Bold)
                    TextButton(onClick = onNextHole, enabled = holeIndex < holeCount - 1) { Text("Next", color = Color.White) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onStrokesChange(strokes - 1) }, enabled = strokes > 0, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.Remove, contentDescription = "Remove stroke", tint = Color.White) }
                        Text("$strokes", color = Color.White, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { onStrokesChange(strokes + 1) }, modifier = Modifier.size(34.dp)) { Icon(Icons.Filled.Add, contentDescription = "Add stroke", tint = Color(0xFFB8F36B)) }
                    }
                }
            }
        }
    }
    if (showQuitDialog) AlertDialog(
        onDismissRequest = { showQuitDialog = false },
        title = { Text("Quit without saving?") },
        text = { Text("This discards the active round and all scores entered in it. It will not appear in saved games.") },
        confirmButton = { TextButton(onClick = { showQuitDialog = false; onQuitRound() }) { Text("QUIT & DISCARD", color = Color(0xFFFFB4AB)) } },
        dismissButton = { TextButton(onClick = { showQuitDialog = false }) { Text("KEEP PLAYING") } }
    )
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

private fun simulationPathFor(hole: ReconstructedHole): List<GeoPoint> {
    val line = hole.centerline
    if (line.size < 2) return emptyList()
    val teePoints = hole.tee?.let { feature -> feature.points.takeIf { it.isNotEmpty() } ?: feature.rings.firstOrNull()?.dropLast(1).orEmpty() }.orEmpty()
    if (teePoints.isEmpty()) return line
    val tee = GeoPoint(teePoints.map { it.latitude }.average(), teePoints.map { it.longitude }.average())
    fun meters(a: GeoPoint, b: GeoPoint): Double {
        val lat = Math.toRadians((a.latitude + b.latitude) / 2.0)
        val dx = Math.toRadians(b.longitude - a.longitude) * 6_371_008.8 * kotlin.math.cos(lat)
        val dy = Math.toRadians(b.latitude - a.latitude) * 6_371_008.8
        return kotlin.math.hypot(dx, dy)
    }
    return if (meters(tee, line.last()) < meters(tee, line.first())) line.reversed() else line
}

private fun pointAlongPolyline(points: List<GeoPoint>, fraction: Float): GeoPoint? {
    if (points.size < 2) return points.firstOrNull()
    fun segmentLength(a: GeoPoint, b: GeoPoint): Double {
        val lat = Math.toRadians((a.latitude + b.latitude) / 2.0)
        val dx = Math.toRadians(b.longitude - a.longitude) * 6_371_008.8 * kotlin.math.cos(lat)
        val dy = Math.toRadians(b.latitude - a.latitude) * 6_371_008.8
        return kotlin.math.hypot(dx, dy)
    }
    val lengths = points.zipWithNext { a, b -> segmentLength(a, b) }
    val total = lengths.sum()
    if (total <= 0.0) return points.first()
    var remaining = total * fraction.coerceIn(0f, 1f)
    lengths.forEachIndexed { index, length ->
        if (remaining <= length || index == lengths.lastIndex) {
            val t = if (length <= 0.0) 0.0 else (remaining / length).coerceIn(0.0, 1.0)
            val a = points[index]; val b = points[index + 1]
            return GeoPoint(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
        }
        remaining -= length
    }
    return points.last()
}