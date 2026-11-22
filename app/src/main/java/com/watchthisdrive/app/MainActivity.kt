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
import com.watchthisdrive.app.ui.GolfTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GolfTheme { WatchThisDriveApp() } }
    }
}

private enum class Tab(val label: String) {
    HOME("Home"), MAP("Course"), SCORE("Scorecard"), CADDY("Caddy")
}

private fun courseGeometryHandoffKey(course: GolfCourse): String =
    course.elementType.lowercase(Locale.ROOT) + ":" + course.id

@Composable
private fun WatchThisDriveApp() {
    var selected by remember { mutableStateOf(Tab.HOME) }
    var showNearbyCourses by remember { mutableStateOf(false) }
    var selectedCourse by remember { mutableStateOf<GolfCourse?>(null) }
    var activeCourse by remember { mutableStateOf<GolfCourse?>(null) }
    var loadedGeometryCourseKey by remember { mutableStateOf<String?>(null) }
    var loadedCourseGeometry by remember { mutableStateOf<CourseGeometry?>(null) }
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
                            Tab.MAP -> Icons.Filled.Map
                            Tab.SCORE -> Icons.Filled.Scoreboard
                            Tab.CADDY -> Icons.Filled.SportsGolf
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
                        onGeometryLoaded = { loadedCourse, loaded ->
                            loadedGeometryCourseKey = courseGeometryHandoffKey(loadedCourse)
                            loadedCourseGeometry = loaded
                        },
                        onStartRound = {
                            activeCourse = selectedCourse
                            selectedCourse = null
                            showNearbyCourses = false
                            selected = Tab.CADDY
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
                            selected = Tab.MAP
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

                    Tab.MAP -> GolfMap(
                        location = mapTarget ?: location,
                        course = activeCourse,
                        onLocate = { mapTarget = null; locate() }
                    )

                    Tab.SCORE -> PlaceholderScreen(
                        "Scorecard",
                        "Your round and player scores will live here.",
                        Icons.Filled.Scoreboard
                    )

                    Tab.CADDY -> CaddyScreen(
                        course = activeCourse,
                        initialGeometry = activeCourse?.let { course ->
                            if (loadedGeometryCourseKey == courseGeometryHandoffKey(course)) loadedCourseGeometry else null
                        },
                        location = location,
                        onLocate = { locate() },
                        onFindCourse = {
                            selectedCourse = null
                            showNearbyCourses = true
                        }
                    )
                }
            }
        }
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
        Modifier.fillMaxSize().padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "WATCH THIS DRIVE",
                    color = Color(0xFFB8F36B),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Your golf companion.",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Icon(
                Icons.Filled.SportsGolf,
                null,
                tint = Color(0xFFB8F36B),
                modifier = Modifier.size(42.dp)
            )
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1D2A22)),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(
                Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "READY FOR THE COURSE?",
                    color = Color(0xFFB8F36B),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Find a golf course near you.",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "We'll search nearby mapped golf courses using your current location.",
                    color = Color(0xFFB6C1B9),
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = onFindCourses,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFB8F36B),
                        contentColor = Color(0xFF101713)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Search, null)
                    Spacer(Modifier.width(8.dp))
                    Text("FIND NEARBY COURSES", fontWeight = FontWeight.Bold)
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF18231C)),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.MyLocation,
                        null,
                        tint = Color(0xFFB8F36B)
                    )
                    Spacer(Modifier.width(9.dp))
                    Text("GPS STATUS", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Text(message, color = Color(0xFFB6C1B9))

                if (location != null) {
                    Text(
                        "${"%.5f".format(Locale.US, location.latitude)}, ${
                            "%.5f".format(Locale.US, location.longitude)
                        }",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                OutlinedButton(
                    onClick = onLocate,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFB8F36B)
                    )
                ) {
                    Text("CHECK MY LOCATION")
                }
            }
        }

        Text(
            "YOUR ROUND, YOUR GAME",
            color = Color(0xFFB8F36B),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FeatureTile("GPS distances", Icons.Filled.NearMe, Modifier.weight(1f))
            FeatureTile("Smart caddy", Icons.Filled.AutoAwesome, Modifier.weight(1f))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FeatureTile("Scorekeeping", Icons.Filled.Scoreboard, Modifier.weight(1f))
            FeatureTile("Round stats", Icons.Filled.QueryStats, Modifier.weight(1f))
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
    onGeometryLoaded: (GolfCourse, CourseGeometry) -> Unit,
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

    LaunchedEffect(course.elementType, course.id) {
        CourseGeometryLoader.load(course) { result ->
            result.fold(
                onSuccess = {
                    geometry = it
                    onGeometryLoaded(course, it)
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
