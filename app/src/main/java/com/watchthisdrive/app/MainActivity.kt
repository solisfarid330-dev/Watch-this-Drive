package com.watchthisdrive.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.watchthisdrive.app.data.GolfCourse
import com.watchthisdrive.app.ui.GolfMap
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

@Composable
private fun WatchThisDriveApp() {
    var selected by remember { mutableStateOf(Tab.HOME) }
    var showNearbyCourses by remember { mutableStateOf(false) }
    var selectedCourse by remember { mutableStateOf<GolfCourse?>(null) }
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
                        onViewOnMap = {
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

                    Tab.MAP -> GolfMap(location = mapTarget ?: location, onLocate = { mapTarget = null; locate() })

                    Tab.SCORE -> PlaceholderScreen(
                        "Scorecard",
                        "Your round and player scores will live here.",
                        Icons.Filled.Scoreboard
                    )

                    Tab.CADDY -> PlaceholderScreen(
                        "Your caddy",
                        "Default club distances will be ready from day one. The caddy will learn and adapt as you record shots.",
                        Icons.Filled.SportsGolf
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

@Composable
private fun NearbyCoursesScreen(
    location: Location?,
    onLocate: () -> Unit,
    onBack: () -> Unit,
    onCourseSelected: (GolfCourse) -> Unit
) {
    var courses by remember { mutableStateOf<List<GolfCourse>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var hasSearched by remember { mutableStateOf(false) }

    fun search() {
        val current = location
        if (current == null) {
            errorMessage = "We need your location before searching. Check GPS and try again."
            return
        }

        loading = true
        errorMessage = null
        hasSearched = true

        CourseDiscovery.searchNearby(
            latitude = current.latitude,
            longitude = current.longitude
        ) { result ->
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
            search()
        }
    }

    Column(
        Modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "NEARBY COURSES",
                    color = Color(0xFFB8F36B),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Golf courses around you",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            IconButton(onClick = { search() }, enabled = !loading) {
                Icon(
                    Icons.Filled.Refresh,
                    "Refresh courses",
                    tint = Color(0xFFB8F36B)
                )
            }
        }

        Text(
            "Searching within 15 km of your current GPS location.",
            color = Color(0xFFB6C1B9),
            style = MaterialTheme.typography.bodyMedium
        )

        when {
            location == null -> {
                InfoCard(
                    message = "Your location isn't available yet. Check your location permission and try again."
                )
                Button(
                    onClick = onLocate,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFB8F36B),
                        contentColor = Color(0xFF101713)
                    )
                ) {
                    Icon(Icons.Filled.MyLocation, null)
                    Spacer(Modifier.width(8.dp))
                    Text("CHECK MY LOCATION")
                }
            }

            loading -> {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(color = Color(0xFFB8F36B))
                    Spacer(Modifier.width(14.dp))
                    Text("Searching for golf courses...", color = Color.White)
                }
            }

            errorMessage != null -> {
                InfoCard(message = errorMessage!!)
                Button(
                    onClick = { search() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFB8F36B),
                        contentColor = Color(0xFF101713)
                    )
                ) {
                    Text("TRY AGAIN")
                }
            }

            courses.isEmpty() && hasSearched -> {
                InfoCard(
                    message = "No mapped golf courses were found within 15 km. You can refresh or try again from another location."
                )
            }

            courses.isNotEmpty() -> {
                Text(
                    "${courses.size} course${if (courses.size == 1) "" else "s"} found",
                    color = Color(0xFFB8F36B),
                    fontWeight = FontWeight.Bold
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(courses) { course ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF1D2A22)
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().clickable { onCourseSelected(course) }
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.GolfCourse,
                                    null,
                                    tint = Color(0xFFB8F36B),
                                    modifier = Modifier.size(30.dp)
                                )
                                Spacer(Modifier.width(13.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        course.name,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        "${"%.1f".format(Locale.US, course.distanceMiles)} miles away",
                                        color = Color(0xFFB6C1B9),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
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
    onViewOnMap: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101713))
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
