package com.watchthisdrive.app.ui

import android.content.Context
import android.location.Location
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.watchthisdrive.app.data.CourseFeature
import com.watchthisdrive.app.data.CourseGeometry
import com.watchthisdrive.app.data.CourseGeometryLoader
import com.watchthisdrive.app.data.GolfCourse
import com.watchthisdrive.app.data.HoleReconstructor
import java.util.Locale
import kotlin.math.roundToInt

private data class ClubDistance(val name: String, val carryYards: String)

private val defaultClubs = listOf(
    ClubDistance("Driver", "220"), ClubDistance("3 Wood", "200"),
    ClubDistance("5 Wood", "185"), ClubDistance("4 Iron", "170"),
    ClubDistance("5 Iron", "160"), ClubDistance("6 Iron", "150"),
    ClubDistance("7 Iron", "140"), ClubDistance("8 Iron", "130"),
    ClubDistance("9 Iron", "120"), ClubDistance("Pitching Wedge", "105"),
    ClubDistance("Gap Wedge", "90"), ClubDistance("Sand Wedge", "75"),
    ClubDistance("Lob Wedge", "60")
)

private val caddyGreen = Color(0xFFB8F36B)
private val caddyCard = Color(0xFF1D2A22)
private val caddyMuted = Color(0xFFB6C1B9)

@Composable
fun CaddyScreen(
    course: GolfCourse?,
    location: Location?,
    onLocate: () -> Unit,
    onFindCourse: () -> Unit,
    activeHole: Int,
    geometry: CourseGeometry?,
    modifier: Modifier = Modifier
) {
    PlayingCaddyScreen(course, location, onLocate, onFindCourse, activeHole, geometry, modifier)
}

@Composable
private fun PlayingCaddyScreen(
    course: GolfCourse?,
    location: Location?,
    onLocate: () -> Unit,
    onFindCourse: () -> Unit,
    activeHole: Int,
    geometry: CourseGeometry?,
    modifier: Modifier = Modifier
) {
    var selectedGreenId by remember(course?.id, activeHole) { mutableStateOf<Long?>(null) }
    val greens = geometry?.features.orEmpty()
        .filter { it.featureType.equals("green", ignoreCase = true) }
        .sortedWith(compareBy<CourseFeature> { it.holeNumber?.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.id })
    val numberedGreens = greens.filter { it.holeNumber?.trim()?.toIntOrNull() == activeHole }
    val selectedGreen = numberedGreens.firstOrNull { it.id == selectedGreenId }
        ?: numberedGreens.firstOrNull()
        ?: greens.firstOrNull { it.id == selectedGreenId }
        ?: greens.firstOrNull()
    val target = selectedGreen?.let { greenCenter(it) }
    val loading = course != null && geometry == null
    val error: String? = null
    val preferences = LocalContext.current.getSharedPreferences("watch_this_drive_caddy", Context.MODE_PRIVATE)
    val clubs = remember {
        defaultClubs.map { club ->
            club.copy(carryYards = preferences.getString("carry_" + club.name, club.carryYards) ?: club.carryYards)
        }
    }

    val distanceYards = if (location != null && target != null) {
        val meters = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, target.latitude, target.longitude, meters)
        (meters[0] * 1.0936133).toInt()
    } else null

    val recommendation = distanceYards?.let { yards ->
        val valid = clubs.mapNotNull { club ->
            club.carryYards.toIntOrNull()?.takeIf { it > 0 }?.let { club to it }
        }.sortedBy { it.second }
        valid.firstOrNull { it.second >= yards } ?: valid.lastOrNull()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, top = 8.dp, end = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("ON-COURSE ASSISTANT", color = caddyGreen, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text("Playing Caddy", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(course?.name ?: "Choose a course to get started", color = caddyMuted)
            }
        }
        if (course == null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = caddyCard), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Start with your course", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Choose a mapped course. The caddy will look for its greens and use your GPS position to calculate the distance.", color = caddyMuted)
                        Button(onClick = onFindCourse, modifier = Modifier.fillMaxWidth()) { Text("FIND / SELECT COURSE") }
                    }
                }
            }
        } else {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = caddyCard), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("YOUR LOCATION", color = caddyGreen, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Text(if (location == null) "No GPS fix yet. Head outdoors and refresh your location." else "GPS position available", color = Color.White)
                        Button(onClick = onLocate, modifier = Modifier.fillMaxWidth()) { Text("REFRESH GPS LOCATION") }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = caddyCard), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("TARGET GREEN", color = caddyGreen, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        when {
                            loading -> Text("Loading mapped course greens…", color = caddyMuted)
                            error != null -> Text("Course mapping unavailable: " + error, color = Color(0xFFFFB4AB))
                            greens.isEmpty() -> Text("No greens are mapped for this course yet. We won't guess a target or show a made-up distance.", color = caddyMuted)
                            else -> {
                                var expanded by remember { mutableStateOf(false) }
                                val index = greens.indexOfFirst { it.id == selectedGreen?.id }.let { if (it < 0) 0 else it }
                                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text(greenLabel(greens[index], index))
                                }
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    greens.forEachIndexed { i, green ->
                                        DropdownMenuItem(
                                            text = { Text(greenLabel(green, i)) },
                                            onClick = { selectedGreenId = green.id; expanded = false }
                                        )
                                    }
                                }
                                Text(
                                    if (numberedGreens.isNotEmpty()) "Automatically selected from the mapped hole number."
                                    else "No green is tagged for hole $activeHole; select the correct mapped green.",
                                    color = caddyMuted,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF24372A)), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("DISTANCE TO GREEN", color = caddyGreen, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Text(distanceYards?.let { "$it yd" } ?: "—", color = Color.White, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                target == null -> "Waiting for a mapped green target."
                                location == null -> "Refresh GPS to calculate your distance."
                                else -> "Straight-line GPS distance to the selected green center."
                            },
                            color = caddyMuted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = caddyCard), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("CLUB RECOMMENDATION", color = caddyGreen, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Text(recommendation?.first?.name ?: "Waiting for distance", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(recommendation?.let { "Typical carry: " + it.second + " yd" } ?: "A recommendation appears when GPS and a mapped green are available.", color = caddyMuted)
                        Text("Recommendation uses carry distance only. It does not yet account for wind, slope, or pin position.", color = caddyMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun greenLabel(green: CourseFeature, index: Int): String =
    green.holeNumber?.let { "Hole " + it + " green" } ?: "Mapped green " + (index + 1)

private fun greenCenter(feature: CourseFeature): Location? {
    if (feature.points.isEmpty()) return null
    return Location("mapped-green").apply {
        latitude = feature.points.map { it.latitude }.average()
        longitude = feature.points.map { it.longitude }.average()
    }
}

@Composable
fun ClubDistancesSettings() = ClubManagementScreen()

@Composable
private fun ClubManagementScreen() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("watch_this_drive_caddy", Context.MODE_PRIVATE) }
    var clubs by remember {
        mutableStateOf(defaultClubs.map { club ->
            club.copy(carryYards = preferences.getString("carry_" + club.name, club.carryYards) ?: club.carryYards)
        })
    }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, top = 8.dp, end = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CLUB MANAGEMENT", color = caddyGreen, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text("Your club distances", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Set the distance each club normally carries the ball.", color = caddyMuted)
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = caddyCard), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("CARRY DISTANCE (YD)", color = caddyGreen, fontWeight = FontWeight.Bold)
                    Text("Distance the ball travels through the air to its first landing. Bounce and roll are not included. The caddy uses this number for club recommendations and hazard planning.", color = caddyMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        items(clubs, key = { it.name }) { club ->
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF18231C)), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = club.carryYards,
                    onValueChange = { value ->
                        val cleaned = value.filter(Char::isDigit).take(3)
                        clubs = clubs.map { current ->
                            if (current.name == club.name) current.copy(carryYards = cleaned) else current
                        }
                        savedMessage = null
                    },
                    label = { Text(club.name + " — carry yards") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(10.dp)
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        preferences.edit().apply {
                            clubs.forEach { club ->
                                putString("carry_" + club.name, club.carryYards)
                                remove("total_" + club.name)
                            }
                        }.apply()
                        savedMessage = "Club distances saved."
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("SAVE CLUB DISTANCES") }
                savedMessage?.let { Text(it, color = caddyGreen, style = MaterialTheme.typography.bodySmall) }
                Text("You can change these estimates whenever you like. Shot learning will update them as you play.", color = caddyMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
