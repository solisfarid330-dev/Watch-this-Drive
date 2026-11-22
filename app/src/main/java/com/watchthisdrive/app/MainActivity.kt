package com.watchthisdrive.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.watchthisdrive.app.data.CourseDiscovery
import com.watchthisdrive.app.data.CourseGeometry
import com.watchthisdrive.app.data.CourseGeometryLoader
import com.watchthisdrive.app.data.GolfCourse
import com.watchthisdrive.app.ui.GolfMap
import com.watchthisdrive.app.ui.CaddyScreen
import com.watchthisdrive.app.ui.ClubDistancesSettings
import com.watchthisdrive.app.ui.CourseHoleArtworkPanel
import com.watchthisdrive.app.ui.GolfTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GolfTheme { WatchThisDriveApp() } }
    }
}

private enum class Tab(val label: String) {
    HOME("Home"), PLAY("Play"), SCORE("Scorecard"), SETTINGS("Settings")
}

@Composable
private fun WatchThisDriveApp() {
    var selected by remember { mutableStateOf(Tab.HOME) }
    var showNearbyCourses by remember { mutableStateOf(false) }
    var selectedCourse by remember { mutableStateOf<GolfCourse?>(null) }
    var activeCourse by remember { mutableStateOf<GolfCourse?>(null) }
    var location by remember { mutableStateOf<Location?>(null) }
    var mapTarget by remember { mutableStateOf<Location?>(null) }
    var locationMessage by remember { mutableStateOf("Location not checked yet") }

    val context = androidx.compose.ui.platform.LocalContext.current
    val fused = remember { LocationServices.getFusedLocationProviderClient(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted =
            result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (granted) {
            try {
                fused.lastLocation.addOnSuccessListener { found ->
                    location = found
                    locationMessage = if (found != null) {
                        "GPS fix received"
                    } else {
                        "Waiting for a GPS fix — try outdoors"
                    }
                }
            } catch (_: SecurityException) {
                locationMessage = "Location permission unavailable"
            }
        } else {
            locationMessage = "Location permission was not granted"
        }
    }

    fun locate() {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fine && !coarse) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            try {
                fused.lastLocation.addOnSuccessListener { found ->
                    location = found
                    locationMessage = if (found != null) {
                        "GPS fix received"
                    } else {
                        "No recent location yet — try outdoors"
                    }
                }
            } catch (_: SecurityException) {
                locationMessage = "Location permission unavailable"
            }
        }
    }

    Scaffold(
        containerColor = Color(0xFF101713),
        bottomBar = {
            if (!showNearbyCourses) {
                NavigationBar(
                    containerColor = Color(0xFF18231C),
                    contentColor = Color.White
                ) {
                    Tab.values().forEach { tab ->
                        val icon = when (tab) {
                            Tab.HOME -> Icons.Filled.Home
                            Tab.PLAY -> Icons.Filled.Map
                            Tab.SCORE -> Icons.Filled.Scoreboard
                            Tab.SETTINGS -> Icons.Filled.Settings
                        }

                        NavigationBarItem(
                            selected = selected == tab,
                            onClick = { selected = tab },
                            icon = { Icon(icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF101713),
                                selectedTextColor = Color(0xFFB8F36B),
                                indicatorColor = Color(0xFFB8F36B),
                                unselectedIconColor = Color(0xFFB6C1B9),
                                unselectedTextColor = Color(0xFFB6C1B9)
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(if (showNearbyCourses) PaddingValues(0.dp) else padding)
        ) {
            if (showNearbyCourses) {
                if (selectedCourse != null) {
                    CourseDetailScreen(
                        course = selectedCourse!!,
                        onBack = { selectedCourse = null },
                        onStartRound = {
                            activeCourse = selectedCourse
                            selectedCourse = null
                            showNearbyCourses = false
                            selected = Tab.PLAY
                            locate()
                        },
                        onViewOnMap = {
                            activeCourse = selectedCourse
                            mapTarget = Location("selected-course").apply {
                                latitude = selectedCourse!!.latitude
                                longitude = selectedCourse!!.longitude
                            }
                            selectedCourse = null
                            showNearbyCourses = false
                            selected = Tab.PLAY
                        }
                    )
                } else {
                    NearbyCoursesScreen(
                        location = location,
                        onLocate = { locate() },
                        onBack = { showNearbyCourses = false },
                        onCourseSelected = { course -> selectedCourse = course }
                    )
                }
            } else {
                when (selected) {
                    Tab.HOME -> HomeScreen(
                        locationMessage,
                        location,
                        onLocate = { locate() },
                        onFindCourses = {
                            showNearbyCourses = true
                            if (location == null) locate()
                        }
                    )

                    Tab.PLAY -> {
                        if (activeCourse == null) {
                            EmptyRoundScreen(onFindCourse = {
                                selectedCourse = null
                                showNearbyCourses = true
                            })
                        } else {
                            RoundHubScreen(
                                course = activeCourse!!,
                                location = location,
                                mapTarget = mapTarget,
                                onLocate = { mapTarget = null; locate() },
                                onFindCourse = {
                                    selectedCourse = null
                                    showNearbyCourses = true
                                }
                            )
                        }
                    }

                    Tab.SCORE -> ScorecardScreen(course = activeCourse)

                    Tab.SETTINGS -> SettingsScreen(
                        locationMessage = locationMessage,
                        location = location,
                        onLocate = { locate() }
                    )
                }
            }
        }
    }
}
 
@Composable
private fun EmptyRoundScreen(onFindCourse: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("READY TO PLAY?", color = Color(0xFFB8F36B), style = MaterialTheme.typography.labelLarge)
        Text("Your course belongs here.", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Choose a course to bring the satellite map, illustrated hole, GPS distances and caddy together.", color = Color(0xFFB6C1B9))
        Button(onClick = onFindCourse, modifier = Modifier.fillMaxWidth()) { Text("FIND A COURSE") }
    }
}

@Composable
private fun RoundHubScreen(
    course: GolfCourse,
    location: Location?,
    mapTarget: Location?,
    onLocate: () -> Unit,
    onFindCourse: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
        Text("YOUR ROUND", color = Color(0xFFB8F36B), style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 18.dp, top = 14.dp))
        Text(course.name, color = Color.White, style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
        GolfMap(location = mapTarget ?: location, course = course, onLocate = onLocate)
        CaddyScreen(course = course, location = location, onLocate = onLocate, onFindCourse = onFindCourse)
    }
}

@Composable
private fun ScorecardScreen(course: GolfCourse?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(course?.id) { context.getSharedPreferences("watch_this_drive_scorecard_${course?.id ?: 0}", android.content.Context.MODE_PRIVATE) }
    var scores by remember(course?.id) { mutableStateOf((1..18).map { hole ->
        prefs.getInt("strokes_$hole", 0)
    }) }
    var pars by remember(course?.id) { mutableStateOf((1..18).map { hole ->
        prefs.getInt("par_$hole", if (hole in listOf(1, 4, 7, 10, 13, 16)) 4 else 4)
    }) }
    val total = scores.sum()
    val played = scores.count { it > 0 }
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Text("SCORECARD", color = Color(0xFFB8F36B), style = MaterialTheme.typography.labelLarge)
        Text(course?.name ?: "No active round", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(if (played == 0) "Enter your score as you play each hole." else "$played holes · $total strokes · Par ${pars.take(played).sum()}",
            color = Color(0xFFB6C1B9))
        if (course == null) Text("Start a round from a selected course to keep its scorecard.", color = Color(0xFFB6C1B9))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items((1..18).toList()) { hole ->
                val index = hole - 1
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1D2A22))) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Hole $hole", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("Par ${pars[index]}", color = Color(0xFFB6C1B9), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = {
                            pars = pars.toMutableList().also { it[index] = (it[index] - 1).coerceIn(3, 5) }
                            prefs.edit().putInt("par_$hole", pars[index]).apply()
                        }) { Text("−") }
                        Text("${pars[index]}", color = Color.White)
                        TextButton(onClick = {
                            pars = pars.toMutableList().also { it[index] = (it[index] + 1).coerceIn(3, 5) }
                            prefs.edit().putInt("par_$hole", pars[index]).apply()
                        }) { Text("+") }
                        TextButton(onClick = {
                            scores = scores.toMutableList().also { it[index] = (it[index] - 1).coerceAtLeast(0) }
                            prefs.edit().putInt("strokes_$hole", scores[index]).apply()
                        }) { Text("−") }
                        Text("${scores[index].takeIf { it > 0 } ?: "—"}", color = Color(0xFFB8F36B), fontWeight = FontWeight.Bold)
                        TextButton(onClick = {
                            scores = scores.toMutableList().also { it[index] = (it[index] + 1).coerceAtMost(20) }
                            prefs.edit().putInt("strokes_$hole", scores[index]).apply()
                        }) { Text("+") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(locationMessage: String, location: Location?, onLocate: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("SETTINGS", color = Color(0xFFB8F36B), style = MaterialTheme.typography.labelLarge)
        Text("Location & clubs", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1D2A22))) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("GPS STATUS", color = Color(0xFFB8F36B), fontWeight = FontWeight.Bold)
                Text(locationMessage, color = Color.White)
                location?.let { Text("${"%.5f".format(Locale.US, it.latitude)}, ${"%.5f".format(Locale.US, it.longitude)}", color = Color(0xFFB6C1B9)) }
                OutlinedButton(onClick = onLocate, modifier = Modifier.fillMaxWidth()) { Text("CHECK MY LOCATION") }
            }
        }
        ClubDistancesSettings()
    }
}

@Composable
private fun HomeScreen(
    message: String,
    location: Location?,
    onLocate: () -> Unit,
    onFindCourses: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    "WATCH THIS DRIVE",
                    color = Color(0xFFB8F36B),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Every hole. Every shot.",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Your course, your game, one place.",
                    color = Color(0xFFB6C1B9),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Icon(
                Icons.Filled.SportsGolf,
                contentDescription = null,
                tint = Color(0xFFB8F36B),
                modifier = Modifier.size(48.dp)
            )
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF24372A)),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.fillMaxWidth().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(
                    Icons.Filled.Explore,
                    contentDescription = null,
                    tint = Color(0xFFB8F36B),
                    modifier = Modifier.size(30.dp)
                )
                Text(
                    "FIND YOUR COURSE",
                    color = Color(0xFFB8F36B),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Where are we playing?",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Search by course name, city or ZIP code, or discover mapped courses near you.",
                    color = Color(0xFFD0D9D2),
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = onFindCourses,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFB8F36B),
                        contentColor = Color(0xFF101713)
                    ),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null)
                    Spacer(Modifier.width(9.dp))
                    Text("SEARCH GOLF COURSES", fontWeight = FontWeight.Bold)
                }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1D2A22)),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(Icons.Filled.Map, null, tint = Color(0xFFB8F36B))
                    Text("LIVE COURSE VIEW", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Maps, hole artwork and GPS together.", color = Color(0xFFB6C1B9), style = MaterialTheme.typography.bodySmall)
                }
            }
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1D2A22)),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(Icons.Filled.AutoAwesome, null, tint = Color(0xFFB8F36B))
                    Text("SMART CADDY", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Club advice based on your carry distances.", color = Color(0xFFB6C1B9), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private data class CourseCandidateGroup(
    val name: String,
    val candidates: List<GolfCourse>
)

private fun groupCourseCandidates(courses: List<GolfCourse>): List<CourseCandidateGroup> {
    val groups = mutableListOf<MutableList<GolfCourse>>()

    courses.sortedBy { it.distanceMeters }.forEach { course ->
        val normalizedName = course.name
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]"), "")

        val matchingGroup = groups.firstOrNull { group ->
            val groupName = group.first().name
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]"), "")

            if (normalizedName != groupName) {
                false
            } else {
                group.any { existing ->
                    val distance = FloatArray(1)
                    Location.distanceBetween(
                        course.latitude,
                        course.longitude,
                        existing.latitude,
                        existing.longitude,
                        distance
                    )
                    distance[0] <= 1000f
                }
            }
        }

        if (matchingGroup == null) {
            groups.add(mutableListOf(course))
        } else {
            matchingGroup.add(course)
        }
    }

    return groups.map { group ->
        CourseCandidateGroup(
            name = group.first().name,
            candidates = group.sortedBy { it.distanceMeters }
        )
    }.sortedBy { it.candidates.first().distanceMeters }
}

@Composable
private fun NearbyCoursesScreen(
    location: Location?,
    onLocate: () -> Unit,
    onBack: () -> Unit,
    onCourseSelected: (GolfCourse) -> Unit
) {
    var courses by remember { mutableStateOf<List<GolfCourse>>(emptyList()) }
    var searchText by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var hasSearched by remember { mutableStateOf(false) }
    var searchDescription by remember { mutableStateOf("Search by course name, city, or ZIP code — GPS is not required.") }

    fun searchNearby() {
        val current = location
        if (current == null) {
            errorMessage = "Your GPS location isn't available. You can still search by course name, city, or ZIP code above."
            return
        }

        loading = true
        errorMessage = null
        hasSearched = true
        searchDescription = "Searching within 15 km of your current GPS location."

        CourseDiscovery.searchNearby(
            latitude = current.latitude,
            longitude = current.longitude
        ) { result ->
            result.onSuccess { found ->
                courses = found
                errorMessage = null
            }.onFailure { problem ->
                errorMessage = problem.message ?: "The nearby course search could not be completed."
                courses = emptyList()
            }
            loading = false
        }
    }

    fun searchByText() {
        val query = searchText.trim()
        if (query.isBlank()) {
            errorMessage = "Enter a course name, city, or ZIP code to search."
            return
        }

        loading = true
        errorMessage = null
        hasSearched = true
        searchDescription = "Searching around \"$query\". GPS is not required."

        CourseDiscovery.searchByText(query) { result ->
            result.onSuccess { found ->
                courses = found
                errorMessage = null
            }.onFailure { problem ->
                errorMessage = problem.message ?: "The course search could not be completed."
                courses = emptyList()
            }
            loading = false
        }
    }

    LaunchedEffect(location) {
        if (location != null && !hasSearched) {
            searchNearby()
        }
    }

    val groupedCourses = remember(courses) {
        groupCourseCandidates(courses)
    }

    Column(
        Modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "FIND A COURSE",
                    color = Color(0xFFB8F36B),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Search golf courses",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            IconButton(onClick = { searchNearby() }, enabled = !loading && location != null) {
                Icon(
                    Icons.Filled.MyLocation,
                    "Search near my location",
                    tint = Color(0xFFB8F36B)
                )
            }
        }

        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !loading,
            label = { Text("Course, city, or ZIP code") },
            placeholder = { Text("e.g. Lake Arlington Golf Course") },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null)
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFFB8F36B),
                unfocusedBorderColor = Color(0xFF64736A),
                focusedLabelColor = Color(0xFFB8F36B),
                unfocusedLabelColor = Color(0xFFB6C1B9),
                cursorColor = Color(0xFFB8F36B)
            )
        )

        Button(
            onClick = { searchByText() },
            enabled = !loading && searchText.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFB8F36B),
                contentColor = Color(0xFF101713)
            )
        ) {
            Icon(Icons.Filled.Search, null)
            Spacer(Modifier.width(8.dp))
            Text("SEARCH COURSES", fontWeight = FontWeight.Bold)
        }

        OutlinedButton(
            onClick = { if (location == null) onLocate() else searchNearby() },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Color(0xFFB8F36B),
                disabledContentColor = Color(0xFF64736A)
            )
        ) {
            Icon(Icons.Filled.MyLocation, null)
            Spacer(Modifier.width(8.dp))
            Text("USE MY CURRENT LOCATION")
        }

        Text(
            searchDescription,
            color = Color(0xFFB6C1B9),
            style = MaterialTheme.typography.bodySmall
        )

        when {
            loading -> {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(color = Color(0xFFB8F36B))
                    Spacer(Modifier.width(14.dp))
                    Text("Searching OpenStreetMap...", color = Color.White)
                }
            }

            errorMessage != null -> {
                InfoCard(message = errorMessage!!)
            }

            courses.isEmpty() && hasSearched -> {
                InfoCard(
                    message = "No mapped golf courses were found around that search. Try the full course name with its city and state, or search a nearby town."
                )
            }

            courses.isNotEmpty() -> {
                Text(
                    "${courses.size} boundary candidate${if (courses.size == 1) "" else "s"} across ${groupedCourses.size} course listing${if (groupedCourses.size == 1) "" else "s"}",
                    color = Color(0xFFB8F36B),
                    fontWeight = FontWeight.Bold
                )

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(
                        items = groupedCourses,
                        key = { group ->
                            val first = group.candidates.first()
                            "${group.name}-${first.elementType}-${first.id}"
                        }
                    ) { group ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF1D2A22)
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(9.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.GolfCourse,
                                        null,
                                        tint = Color(0xFFB8F36B),
                                        modifier = Modifier.size(27.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            group.name,
                                            color = Color.White,
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            "${group.candidates.size} possible ${if (group.candidates.size == 1) "boundary" else "boundaries"}",
                                            color = Color(0xFFB8F36B),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }

                                group.candidates.forEach { candidate ->
                                    OutlinedButton(
                                        onClick = { onCourseSelected(candidate) },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = Color.White
                                        ),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            Color(0xFF526257)
                                        ),
                                        contentPadding = PaddingValues(
                                            horizontal = 12.dp,
                                            vertical = 8.dp
                                        )
                                    ) {
                                        Column(
                                            Modifier.weight(1f),
                                            horizontalAlignment = Alignment.Start
                                        ) {
                                            Text(
                                                "${candidate.elementType.replaceFirstChar { it.uppercase() }} #${candidate.id}",
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                "${"%.1f".format(Locale.US, candidate.distanceMiles)} mi from search center",
                                                color = Color(0xFFB6C1B9),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        Icon(
                                            Icons.Filled.ChevronRight,
                                            contentDescription = "View this candidate"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            else -> {
                InfoCard(
                    message = "Search by course name, city, or ZIP code, or use your current location to find nearby courses."
                )
            }
        }
    }
}

@Composable
private fun InfoCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1D2A22)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            message,
            color = Color(0xFFB6C1B9),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun FeatureTile(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF18231C)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                null,
                tint = Color(0xFFB8F36B),
                modifier = Modifier.size(21.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(title, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun PlaceholderScreen(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = Color(0xFFB8F36B), modifier = Modifier.size(54.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            description,
            color = Color(0xFFB6C1B9),
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(18.dp))
        Text(
            "COMING IN A FUTURE BUILD",
            color = Color(0xFFB8F36B),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun CourseDetailScreen(
    course: GolfCourse,
    onBack: () -> Unit,
    onStartRound: () -> Unit,
    onViewOnMap: () -> Unit
) {
    var geometry by remember(course.id) {
        mutableStateOf<CourseGeometry?>(null)
    }
    var loading by remember(course.id) {
        mutableStateOf(true)
    }
    var error by remember(course.id) {
        mutableStateOf<String?>(null)
    }

    LaunchedEffect(course.id) {
        CourseGeometryLoader.load(course) { result ->
            result.fold(
                onSuccess = {
                    geometry = it
                    loading = false
                },
                onFailure = {
                    error = it.message ?: "Unable to load course geometry."
                    loading = false
                }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101713))
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        OutlinedButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Back to Nearby Courses")
        }

        Icon(
            Icons.Filled.GolfCourse,
            contentDescription = null,
            tint = Color(0xFFB8F36B),
            modifier = Modifier.size(56.dp)
        )

        Text(
            course.name,
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            "${"%.1f".format(Locale.US, course.distanceMiles)} miles from your location",
            color = Color(0xFFB6C1B9),
            style = MaterialTheme.typography.bodyLarge
        )

        CourseHoleArtworkPanel(course = course, geometry = geometry)

        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1B2520)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Course Mapping",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                when {
                    loading -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(
                                color = Color(0xFFB8F36B),
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                "Searching OpenStreetMap for course features...",
                                color = Color(0xFFB6C1B9)
                            )
                        }
                    }

                    error != null -> {
                        Text(
                            "Could not load course mapping: $error",
                            color = Color(0xFFFFB4AB)
                        )
                    }

                    else -> {
                        val features = geometry?.features.orEmpty()

                        Text(
                            "${features.size} mapped features found",
                            color = Color(0xFFB8F36B),
                            fontWeight = FontWeight.Bold
                        )

                        val featureTypes = listOf(
                            "Holes" to "hole",
                            "Tees" to "tee",
                            "Fairways" to "fairway",
                            "Greens" to "green",
                            "Bunkers" to "bunker",
                            "Water hazards" to "water_hazard"
                        )

                        featureTypes.forEach { (label, type) ->
                            val count = features.count {
                                it.featureType == type
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(label, color = Color.White)
                                Text(
                                    count.toString(),
                                    color = Color(0xFFB8F36B),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (features.isEmpty()) {
                            Text(
                                "No golf features were mapped near this course. " +
                                    "We won't invent missing course details.",
                                color = Color(0xFFB6C1B9)
                            )
                        }
                    }
                }
            }
        }

        Button(
            onClick = onStartRound,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB8F36B), contentColor = Color(0xFF101713))
        ) {
            Icon(Icons.Filled.SportsGolf, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("START ROUND WITH CADDY")
        }

        Button(
            onClick = onViewOnMap,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFB8F36B),
                contentColor = Color(0xFF101713)
            )
        ) {
            Icon(Icons.Filled.Map, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("View on Satellite Map")
        }
    }
}
