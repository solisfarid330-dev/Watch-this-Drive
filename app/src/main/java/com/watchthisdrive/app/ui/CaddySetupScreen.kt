package com.watchthisdrive.app.ui

import android.Manifest
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SportsGolf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalUriHandler
import androidx.core.content.ContextCompat
import com.watchthisdrive.app.data.ClubCarryDistances
import com.watchthisdrive.app.data.CourseCacheSummary
import com.watchthisdrive.app.data.RoundScorecard
import com.watchthisdrive.app.data.RoundScorecardStore
import com.watchthisdrive.app.data.recommendClubForCarryDistance
import java.text.DateFormat
import java.util.Date

@Composable
fun CaddySetupScreen(
    context: Context,
    carryDistances: Map<String, Int>,
    onChange: (Map<String, Int>) -> Unit,
    golferName: String,
    onGolferNameChange: (String) -> Unit,
    savedRounds: List<RoundScorecardStore.SavedRound>,
    cachedCourses: List<CourseCacheSummary>,
    mappingReports: List<Pair<String, List<String>>>,
    mappingPreferences: List<Pair<String, Int>>,
    activeRound: RoundScorecard?,
    onDeleteCourse: (Long) -> Unit,
    onDeleteScorecard: (RoundScorecardStore.SavedRound) -> Unit,
    onDeleteMappingReport: (String) -> Unit,
    onDeleteMappingPreference: (String) -> Unit,
    onDeleteActiveRound: () -> Unit,
    onDeleteAll: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenDeviceLocationSettings: () -> Unit,
    permissionRevision: Int
) {
    var showCarries by remember { mutableStateOf(false) }
    var testDistance by remember { mutableFloatStateOf(150f) }
    var showLocation by remember { mutableStateOf(false) }
    var showData by remember { mutableStateOf(false) }
    var showCredits by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var pendingCourse by remember { mutableStateOf<CourseCacheSummary?>(null) }
    var pendingRound by remember { mutableStateOf<RoundScorecardStore.SavedRound?>(null) }
    var confirmDeleteActive by remember { mutableStateOf(false) }
    val hasFineLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val hasCoarseLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val deviceLocationEnabled = remember(permissionRevision) {
        runCatching { val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager; if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
    }
    val scorecardStorageBytes = runCatching { java.io.File(context.applicationInfo.dataDir, "shared_prefs/round_scorecards.xml").length() }.getOrDefault(0L)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("SETTINGS", color = Lime, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text("Make WTD yours.", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Control location access, manage what is saved on this phone, and see the services behind WTD.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = golferName,
            onValueChange = { onGolferNameChange(it.take(28)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Golfer name for shared scorecards") },
            placeholder = { Text("Enter your name") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = Lime, unfocusedBorderColor = Color(0xFF526257),
                focusedLabelColor = Lime, unfocusedLabelColor = Muted, cursorColor = Lime
            )
        )
        Text("This name appears on your page when you export a scorecard. Other players keep their own names.", color = Muted, style = MaterialTheme.typography.bodySmall)

        SettingsActionCard("Club Distances", "${carryDistances.size} clubs configured · carry in yards", { Icon(Icons.Filled.SportsGolf, null, tint = Lime) }, showCarries) { showCarries = !showCarries }
        if (showCarries) {
            SettingsDetail {
                Text("PERSONALIZE YOUR CADDY", color = Lime, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Text(
                    "The starter distances are general estimates, not measurements of your game. For more useful club recommendations, calibrate these numbers to your own swing.",
                    color = Color.White, style = MaterialTheme.typography.bodyMedium
                )
                Text("1. Visit a driving range. Warm up first, then hit several shots with each club you use.", color = Color.White, style = MaterialTheme.typography.bodySmall)
                Text("2. Measure carry, not total distance. Use a launch monitor or reliable carry-distance markers where available. Carry is where the ball first lands; ignore bounce and roll.", color = Color.White, style = MaterialTheme.typography.bodySmall)
                Text("3. Use about 5–10 reasonably solid shots per club. Ignore obvious mishits, then average the remaining carry distances. Record your normal, repeatable shot—not your longest-ever hit.", color = Color.White, style = MaterialTheme.typography.bodySmall)
                Text("4. Enter that average in the matching club below. Repeat for the clubs you carry, and update the numbers as your swing or equipment changes.", color = Color.White, style = MaterialTheme.typography.bodySmall)
                Text("Tip: use the same units (yards) throughout. If the range only shows total distance and you cannot estimate carry reliably, don't treat that number as carry—leave the starter value until you can measure it.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Text("CARRY DISTANCE (YD) · THROUGH THE AIR TO FIRST LANDING", color = Lime, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            ClubCarryDistances.clubNames.forEach { club ->
                var value by remember(club, carryDistances[club]) { mutableStateOf(carryDistances[club]?.toString().orEmpty()) }
                Surface(color = Panel, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(club, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = value, onValueChange = { raw ->
                            value = raw.filter(Char::isDigit).take(3)
                            val yards = value.toIntOrNull()?.takeIf { it in 1..400 }
                            val updated = carryDistances.toMutableMap().apply { if (yards == null) remove(club) else put(club, yards) }
                            ClubCarryDistances.save(context, club, yards)
                            onChange(updated)
                        }, modifier = Modifier.width(104.dp), singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), placeholder = { Text("yd", color = Muted) },
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Lime, unfocusedBorderColor = Color(0xFF526257), cursorColor = Lime))
                    }
                }

            }
            SettingsDetail {
                Text("TRY YOUR CADDY", color = Lime, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Text("Test a target distance without being on a course or using GPS. This preview uses your saved carry distances only; it does not simulate or change your GPS position.", color = Muted, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("TEST DISTANCE", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("${testDistance.toInt()} yd", color = Lime, fontWeight = FontWeight.Bold)
                }
                Slider(value = testDistance, onValueChange = { testDistance = it }, valueRange = 30f..350f, steps = 31)
                val preview = recommendClubForCarryDistance(carryDistances, testDistance.toInt())
                Text("YOUR CADDY RECOMMENDS", color = Lime, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Text(preview?.name ?: "Add club carry distances", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(when {
                    preview == null -> "No club carry values are configured yet."
                    preview.isBeyondLongestCarry -> "Longest club · ${preview.carryYards} yd carry. Target is beyond your longest configured carry."
                    else -> "${preview.carryYards} yd carry · simulated target distance ${testDistance.toInt()} yd"
                }, color = Muted, style = MaterialTheme.typography.bodySmall)
                Text("SIMULATED TEST · NOT GPS", color = Color(0xFFFFC857), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }

        SettingsActionCard("Location & GPS", when {
            !deviceLocationEnabled -> "Device location services are switched off"
            hasFineLocation -> "Precise location allowed"
            hasCoarseLocation -> "Approximate location allowed"
            else -> "WTD does not currently have location permission"
        }, { Icon(Icons.Filled.GpsFixed, null, tint = Lime) }, showLocation) { showLocation = !showLocation }
        if (showLocation) SettingsDetail {
            Text("WTD uses location in the foreground to find nearby golf courses, show your position on the course map, and calculate distances to the pin or a selected target. WTD does not request background location.", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            StatusLine("Device location services", if (deviceLocationEnabled) "On" else "Off")
            StatusLine("App permission", when { hasFineLocation -> "Precise"; hasCoarseLocation -> "Approximate"; else -> "Not granted" })
            Text("For the best on-course distances, allow precise location and keep your phone's Location setting enabled. You can still search by course name or location without GPS access.", color = Muted, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onRequestLocationPermission, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)) { Text(if (hasFineLocation || hasCoarseLocation) "CHECK / CHANGE LOCATION PERMISSION" else "ALLOW LOCATION") }
            OutlinedButton(onClick = onOpenAppSettings, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Lime)) { Text("OPEN WTD APP PERMISSIONS") }
            OutlinedButton(onClick = onOpenDeviceLocationSettings, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Lime)) { Text("OPEN DEVICE LOCATION SETTINGS") }
            Text("Android controls the final permission choice. In App permissions you can switch between approximate and precise access, or deny it.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }

        SettingsActionCard("On-Device Data", "${cachedCourses.size} course maps · ${savedRounds.size} saved scorecards${if (activeRound != null) " · active round saved" else ""}", { Icon(Icons.Filled.Folder, null, tint = Lime) }, showData) { showData = !showData }
        if (showData) SettingsDetail {
            Text("Everything listed here is stored in WTD's private app storage. WTD does not upload it to a WTD cloud account; Android may back up app data according to your device's backup settings.", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            Text("Total saved course-map storage: ${formatBytes(cachedCourses.sumOf { it.fileSizeBytes })}", color = Muted, style = MaterialTheme.typography.bodySmall)
            Text("SAVED COURSE MAPS", color = Lime, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            if (cachedCourses.isEmpty()) Text("No saved course maps.", color = Muted, style = MaterialTheme.typography.bodySmall)
            cachedCourses.forEach { course ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(course.courseName, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("${course.featureCount} map features · ${formatBytes(course.fileSizeBytes)} · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(course.lastModified))}", color = Muted, style = MaterialTheme.typography.bodySmall)
                        Text("files/mapped_courses/course_${course.courseId}.json", color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { pendingCourse = course }) { Icon(Icons.Filled.Delete, "Delete ${course.courseName}", tint = Color(0xFFFFB4AB)) }
                }
                HorizontalDivider(color = Color(0xFF344238))
            }
            Text("SAVED SCORECARDS", color = Lime, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            if (savedRounds.isEmpty()) Text("No completed scorecards.", color = Muted, style = MaterialTheme.typography.bodySmall)
            savedRounds.forEach { saved ->
                val round = saved.round
                val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(saved.completedAt))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(round.courseName, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("${round.players.joinToString { player -> "${player.name}: ${player.scores.values.sum()} strokes" }} · ${round.holePars.size} holes · $date", color = Muted, style = MaterialTheme.typography.bodySmall)
                        Text("shared_prefs/round_scorecards.xml", color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { pendingRound = saved }) { Icon(Icons.Filled.Delete, "Delete scorecard", tint = Color(0xFFFFB4AB)) }
                }
                HorizontalDivider(color = Color(0xFF344238))
            }
            Text("COURSE MAPPING NOTES", color = Lime, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            if (mappingReports.isEmpty() && mappingPreferences.isEmpty()) {
                Text("No missing-feature reports or manual hole-count preferences saved.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            mappingReports.forEach { (key, features) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(key.removePrefix("course_"), color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Reported missing: ${features.joinToString()}", color = Muted, style = MaterialTheme.typography.bodySmall)
                        Text("shared_prefs/course_mapping_reports.xml", color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { onDeleteMappingReport(key) }) { Icon(Icons.Filled.Delete, "Delete mapping report", tint = Color(0xFFFFB4AB)) }
                }
            }
            mappingPreferences.forEach { (key, holeCount) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(key.removePrefix("expected_holes_"), color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Manual target: $holeCount holes · shared_prefs/course_mapping_preferences.xml", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { onDeleteMappingPreference(key) }) { Icon(Icons.Filled.Delete, "Delete hole-count preference", tint = Color(0xFFFFB4AB)) }
                }
            }

            if (activeRound != null) {
                Text("ACTIVE ROUND", color = Lime, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(activeRound.courseName, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("In-progress scores · private app storage", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { confirmDeleteActive = true }) { Icon(Icons.Filled.Delete, "Delete active round", tint = Color(0xFFFFB4AB)) }
                }
            }
            HorizontalDivider(color = Color(0xFF344238))
            Text("STORAGE LOCATION", color = Lime, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Text("Course maps: ${context.filesDir.absolutePath}/mapped_courses/", color = Color.White, style = MaterialTheme.typography.bodySmall)
            Text("Active map recovery snapshot: ${context.filesDir.absolutePath}/active_course_geometry.json (when an active course map is cached)", color = Muted, style = MaterialTheme.typography.bodySmall)
            Text("Scorecards: ${context.applicationInfo.dataDir}/shared_prefs/round_scorecards.xml (${formatBytes(scorecardStorageBytes)})", color = Color.White, style = MaterialTheme.typography.bodySmall)
            Text("Course mapping reports: ${context.applicationInfo.dataDir}/shared_prefs/course_mapping_reports.xml", color = Muted, style = MaterialTheme.typography.bodySmall)
            Text("Manual hole-count preferences: ${context.applicationInfo.dataDir}/shared_prefs/course_mapping_preferences.xml", color = Muted, style = MaterialTheme.typography.bodySmall)
            Text("Golfer name: ${context.applicationInfo.dataDir}/shared_prefs/golfer_profile.xml", color = Muted, style = MaterialTheme.typography.bodySmall)
            Text("Club carry distances: ${context.applicationInfo.dataDir}/shared_prefs/club_carry_distances.xml", color = Muted, style = MaterialTheme.typography.bodySmall)
            Text("Android normally hides this private folder from the Files app. This inventory is the in-app view of those files. Clearing app storage or uninstalling WTD removes local files; Android backup and restore behavior depends on your device settings.", color = Muted, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { confirmDeleteAll = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Delete, null, tint = Color(0xFFFFB4AB)); Spacer(Modifier.width(8.dp)); Text("DELETE ALL ON-DEVICE DATA", color = Color(0xFFFFB4AB), fontWeight = FontWeight.Bold)
            }
        }

        SettingsActionCard("Credits & Data Sources", "People, open data, mapping and platform services", { Icon(Icons.Filled.Info, null, tint = Lime) }, showCredits) { showCredits = !showCredits }
        if (showCredits) SettingsDetail {
            Text("PEOPLE", color = Lime, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Credit("Product direction & on-course validation", "Watch This Drive project owner")
            Credit("Software development", "Watch This Drive project, with AI development assistance from OpenAI")
            HorizontalDivider(color = Color(0xFF344238))
            Text("MAPS, COURSE DATA & SERVICES", color = Lime, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Credit("OpenStreetMap contributors", "Course boundaries and mapped golf features · Open Database License (ODbL 1.0)", "https://www.openstreetmap.org/copyright")
            Credit("Overpass API", "Queries OpenStreetMap data through public Overpass instances", "https://wiki.openstreetmap.org/wiki/Overpass_API")
            Credit("Nominatim", "OpenStreetMap-based place and address search", "https://nominatim.org/")
            Credit("OpenGolfAPI", "Optional course scorecard and tee metadata enrichment", "https://opengolfapi.org/")
            Credit("MapTiler", "Satellite map tiles and map style services", "https://www.maptiler.com/")
            Credit("MapLibre Native", "Open-source map rendering SDK", "https://maplibre.org/")
            Credit("USGS / USDA NAIP", "Aerial imagery used as visual reference; not automatically classified", "https://www.usgs.gov/centers/eros/science/usgs-eros-archive-aerial-photography")
            Credit("Google Play services Location", "Foreground device-location provider", "https://developers.google.com/android/reference/com/google/android/gms/location/package-summary")
            HorizontalDivider(color = Color(0xFF344238))
            Text("WTD is an independent golf companion. Third-party names and services belong to their respective owners. Data coverage and accuracy depend on source availability.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
    }

    if (pendingCourse != null) AlertDialog(onDismissRequest = { pendingCourse = null }, title = { Text("Delete saved course map?") }, text = { Text("Remove the local map for ${pendingCourse!!.courseName}? This does not delete scorecards. If an active round uses this course, its map will need to be downloaded again to resume the map view.") }, confirmButton = { TextButton(onClick = { val id = pendingCourse!!.courseId; pendingCourse = null; onDeleteCourse(id) }) { Text("DELETE", color = Color(0xFFFFB4AB)) } }, dismissButton = { TextButton(onClick = { pendingCourse = null }) { Text("CANCEL") } })
    if (pendingRound != null) AlertDialog(onDismissRequest = { pendingRound = null }, title = { Text("Delete saved scorecard?") }, text = { Text("Permanently remove the saved round at ${pendingRound!!.round.courseName}?") }, confirmButton = { TextButton(onClick = { val item = pendingRound!!; pendingRound = null; onDeleteScorecard(item) }) { Text("DELETE", color = Color(0xFFFFB4AB)) } }, dismissButton = { TextButton(onClick = { pendingRound = null }) { Text("CANCEL") } })
    if (confirmDeleteActive) AlertDialog(onDismissRequest = { confirmDeleteActive = false }, title = { Text("Delete active round?") }, text = { Text("This removes the in-progress scorecard and ends the active game.") }, confirmButton = { TextButton(onClick = { confirmDeleteActive = false; onDeleteActiveRound() }) { Text("DELETE", color = Color(0xFFFFB4AB)) } }, dismissButton = { TextButton(onClick = { confirmDeleteActive = false }) { Text("CANCEL") } })
    if (confirmDeleteAll) AlertDialog(onDismissRequest = { confirmDeleteAll = false }, title = { Text("Delete all on-device data?") }, text = { Text("This permanently deletes saved course maps, completed and active scorecards, course-mapping reports and preferences, temporary scorecard exports, and custom club carry settings (starter defaults will be restored) and your saved golfer name. This cannot be undone.") }, confirmButton = { TextButton(onClick = { confirmDeleteAll = false; onDeleteAll() }) { Text("DELETE EVERYTHING", color = Color(0xFFFFB4AB)) } }, dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("CANCEL") } })
}

private val Lime = Color(0xFFB8F36B)
private val Ink = Color(0xFF101713)
private val Muted = Color(0xFFB6C1B9)
private val Panel = Color(0xFF1B2520)

@Composable
private fun SettingsActionCard(title: String, subtitle: String, icon: @Composable () -> Unit, expanded: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            icon(); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Lime)
        }
    }
}
@Composable
private fun SettingsDetail(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF141E18)), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
    }
}
@Composable
private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, style = MaterialTheme.typography.bodySmall)
        Text(value, color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
    }
}
@Composable
private fun Credit(name: String, detail: String, url: String? = null) {
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(name, color = Color.White, fontWeight = FontWeight.SemiBold)
        Text(detail, color = Muted, style = MaterialTheme.typography.bodySmall)
        if (url != null) Text(url, color = Lime, style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable { runCatching { uriHandler.openUri(url) } })
    }
}
private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1_048_576 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
}
