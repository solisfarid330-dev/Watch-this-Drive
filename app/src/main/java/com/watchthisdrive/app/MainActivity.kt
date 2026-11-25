package com.watchthisdrive.app

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.watchthisdrive.app.data.CourseDiscovery
import com.watchthisdrive.app.data.OpenGolfApiCourseLoader
import com.watchthisdrive.app.data.ExternalCourseMetadata
import com.watchthisdrive.app.data.NaipImageryLoader
import com.watchthisdrive.app.data.enrichGeometryWithScorecard
import com.watchthisdrive.app.data.ClubCarryDistances
import com.watchthisdrive.app.data.RoundPlayer
import com.watchthisdrive.app.data.RoundScorecard
import com.watchthisdrive.app.data.RoundScorecardStore
import com.watchthisdrive.app.data.CourseGeometry
import com.watchthisdrive.app.data.CourseGeometryLoader
import com.watchthisdrive.app.data.CourseFeatureInference
import com.watchthisdrive.app.data.CourseHoleInference
import com.watchthisdrive.app.data.CourseMappingQuality
import com.watchthisdrive.app.data.MissingCourseFeature
import com.watchthisdrive.app.data.CourseReconstruction
import com.watchthisdrive.app.data.CourseReconstructionEngine
import com.watchthisdrive.app.data.GolfCourse
import com.watchthisdrive.app.ui.CourseGeometryPreview
import com.watchthisdrive.app.ui.GolfMap
import com.watchthisdrive.app.ui.ActiveHoleScreen
import com.watchthisdrive.app.ui.CaddySetupScreen
import com.watchthisdrive.app.ui.GlobalScorecardScreen
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
    val context = androidx.compose.ui.platform.LocalContext.current
    var selected by remember { mutableStateOf(Tab.HOME) }
    var showNearbyCourses by remember { mutableStateOf(false) }
    var selectedCourse by remember { mutableStateOf<GolfCourse?>(null) }
    var location by remember { mutableStateOf<Location?>(null) }
    var mapTarget by remember { mutableStateOf<Location?>(null) }
    var mapGeometry by remember { mutableStateOf<CourseGeometry?>(null) }
    var mapReconstruction by remember { mutableStateOf<CourseReconstruction?>(null) }
    var roundScorecard by remember { mutableStateOf(RoundScorecardStore.loadActive(context)) }
    var activeRound by remember { mutableStateOf(roundScorecard != null) }
    var activeHoleIndex by remember { mutableIntStateOf(0) }
    var locationMessage by remember { mutableStateOf("Location not checked yet") }

    val fused = remember { LocationServices.getFusedLocationProviderClient(context) }
    var clubCarries by remember { mutableStateOf(ClubCarryDistances.load(context)) }

    fun updateRoundScore(playerId: String, holeNumber: Int, strokes: Int) {
        val current = roundScorecard ?: return
        val updated = current.copy(players = current.players.map { player ->
            if (player.id != playerId) player else player.copy(scores =
                if (strokes <= 0) player.scores - holeNumber else player.scores + (holeNumber to strokes.coerceAtMost(20)))
        })
        roundScorecard = updated
        RoundScorecardStore.saveActive(context, updated)
    }

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
                    val visibleTabs = if (activeRound) listOf(Tab.MAP, Tab.SCORE, Tab.CADDY) else Tab.values().toList()
                    visibleTabs.forEach { tab ->
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
                            label = { Text(if (activeRound && tab == Tab.MAP) "Active Game" else tab.label) },
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
                        onViewOnMap = { loadedGeometry, loadedReconstruction ->
                            mapGeometry = loadedGeometry
                            mapReconstruction = loadedReconstruction
                            mapTarget = Location("selected-course").apply {
                                latitude = selectedCourse!!.latitude
                                longitude = selectedCourse!!.longitude
                            }
                            selectedCourse = null
                            showNearbyCourses = false
                            activeRound = loadedReconstruction?.holes?.isNotEmpty() == true
                            activeHoleIndex = 0
                            val holes = loadedReconstruction?.holes.orEmpty()
                            if (holes.isNotEmpty()) {
                                roundScorecard = RoundScorecard.new(selectedCourse!!.name, holes.associate { it.number to it.par })
                                RoundScorecardStore.saveActive(context, roundScorecard)
                            } else {
                                roundScorecard = null
                                RoundScorecardStore.saveActive(context, null)
                            }
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

                    Tab.MAP -> {
                        val holes = mapReconstruction?.holes.orEmpty()
                        if (activeRound && holes.isNotEmpty()) {
                            val safeIndex = activeHoleIndex.coerceIn(0, holes.lastIndex)
                            val hole = holes[safeIndex]
                            ActiveHoleScreen(
                                courseName = mapGeometry?.courseName ?: "Golf course",
                                hole = hole,
                                holeIndex = safeIndex,
                                holeCount = holes.size,
                                strokes = roundScorecard?.user?.scores?.get(hole.number) ?: 0,
                                overallStrokes = roundScorecard?.user?.scores?.values?.sum() ?: 0,
                                overallRelativeToPar = roundScorecard?.let { round ->
                                    val scoredHoles = round.user.scores.keys
                                    if (scoredHoles.any { round.holePars[it] != null }) round.user.scores.entries.sumOf { (number, value) -> value - (round.holePars[number] ?: 0) } else null
                                },
                                carryDistances = clubCarries,
                                geometry = mapGeometry,
                                reconstruction = mapReconstruction,
                                onStrokesChange = { value -> roundScorecard?.let { updateRoundScore(it.userPlayerId, hole.number, value) } },
                                onPreviousHole = { if (safeIndex > 0) activeHoleIndex = safeIndex - 1 },
                                onNextHole = { if (safeIndex < holes.lastIndex) activeHoleIndex = safeIndex + 1 },
                                onExitRound = { selected = Tab.SCORE }
                            )
                        } else {
                            GolfMap(location = mapTarget ?: location, onLocate = { mapTarget = null; locate() }, courseGeometry = mapGeometry, reconstruction = mapReconstruction)
                        }
                    }

                    Tab.SCORE -> {
                        val round = roundScorecard
                        if (activeRound && round != null) {
                            GlobalScorecardScreen(
                                round = round,
                                onScoreChange = { playerId, holeNumber, strokes -> updateRoundScore(playerId, holeNumber, strokes) },
                                onAddPlayer = { name ->
                                    val current = roundScorecard
                                    if (current != null && name.isNotBlank()) {
                                        val updated = current.copy(players = current.players + RoundPlayer(java.util.UUID.randomUUID().toString(), name.trim()))
                                        roundScorecard = updated
                                        RoundScorecardStore.saveActive(context, updated)
                                    }
                                },
                                onRemovePlayer = { playerId ->
                                    val current = roundScorecard
                                    if (current != null && playerId != current.userPlayerId) {
                                        val updated = current.copy(players = current.players.filterNot { it.id == playerId })
                                        roundScorecard = updated
                                        RoundScorecardStore.saveActive(context, updated)
                                    }
                                },
                                onFinishRound = {
                                    roundScorecard?.let { RoundScorecardStore.saveCompleted(context, it) }
                                    roundScorecard = null
                                    activeRound = false
                                    selected = Tab.MAP
                                }
                            )
                        } else PlaceholderScreen("Scorecard", "Start a game to keep score for every hole and player.", Icons.Filled.Scoreboard)
                    }

                    Tab.CADDY -> CaddySetupScreen(
                        context = context,
                        carryDistances = clubCarries,
                        onChange = { clubCarries = it }
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
    onViewOnMap: (CourseGeometry?, CourseReconstruction?) -> Unit
) {
    var geometry by remember(course.id) {
        mutableStateOf<CourseGeometry?>(null)
    }
    var reconstruction by remember(course.id) {
        mutableStateOf<CourseReconstruction?>(null)
    }
    var loading by remember(course.id) {
        mutableStateOf(true)
    }
    var error by remember(course.id) {
        mutableStateOf<String?>(null)
    }
    val appContext = androidx.compose.ui.platform.LocalContext.current
    var externalMetadata by remember(course.id) { mutableStateOf<ExternalCourseMetadata?>(null) }
    var externalMetadataMessage by remember(course.id) { mutableStateOf("Checking OpenGolfAPI course records…") }
    var naipBitmap by remember(course.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var naipMessage by remember(course.id) { mutableStateOf("Loading public aerial imagery…") }
    var recoveryLoading by remember(course.id) { mutableStateOf(false) }
    var recoveryMessage by remember(course.id) { mutableStateOf<String?>(null) }
    var showMissingFeatureDialog by remember(course.id) { mutableStateOf(false) }
    var reportedFeatures by remember(course.id) {
        mutableStateOf(
            appContext.getSharedPreferences("course_mapping_reports", android.content.Context.MODE_PRIVATE)
                .getStringSet("course_${course.elementType}_${course.id}", emptySet())
                .orEmpty()
        )
    }

    val mappingPreferences = remember(course.id) {
        appContext.getSharedPreferences("course_mapping_preferences", android.content.Context.MODE_PRIVATE)
    }
    val expectedHolePreferenceKey = "expected_holes_${course.elementType}_${course.id}"
    var selectedExpectedHoleCount by remember(course.id) {
        mutableStateOf(
            mappingPreferences.getInt(expectedHolePreferenceKey, course.expectedHoleCount ?: 0)
                .takeIf { it == 9 || it == 18 }
        )
    }

    fun setExpectedHoleCount(count: Int?) {
        val editor = mappingPreferences.edit()
        if (count == null) editor.remove(expectedHolePreferenceKey) else editor.putInt(expectedHolePreferenceKey, count)
        editor.apply()
        selectedExpectedHoleCount = count ?: course.expectedHoleCount
        geometry?.let { current ->
            val updated = current.copy(expectedHoleCount = selectedExpectedHoleCount)
            val enriched = CourseFeatureInference.addInferredFeatures(CourseHoleInference.addInferredHoles(updated))
            geometry = enriched
            reconstruction = CourseReconstructionEngine.reconstruct(enriched)
            recoveryMessage = "Rechecked the course against the ${selectedExpectedHoleCount?.let { "$it-hole" } ?: "automatic"} hole target."
        }
    }

    fun improveMapping(featureType: String? = null) {
        if (recoveryLoading) return
        recoveryLoading = true
        // Run local reconstruction first. It must remain useful even when every
        // Overpass mirror is unavailable.
        geometry?.let { current ->
            val locallyEnriched = CourseFeatureInference.addInferredFeatures(
                CourseHoleInference.addInferredHoles(current)
            )
            geometry = locallyEnriched
            reconstruction = CourseReconstructionEngine.reconstruct(locallyEnriched)
        }
        recoveryMessage = "Checking local reconstruction and searching for additional mapped features…"
        CourseGeometryLoader.loadRecovery(course, featureType) { result ->
            result.onSuccess { recovered ->
                val previous = geometry
                val mergedFeatures = (previous?.features.orEmpty() + recovered.features)
                    .groupBy { it.elementType to it.id }
                    .values.map { candidates ->
                        candidates.firstOrNull { it.hasUsableGeometry } ?: candidates.first()
                    }
                val merged = CourseFeatureInference.addInferredFeatures(CourseHoleInference.addInferredHoles(CourseGeometry(
                    courseId = course.id,
                    courseName = course.name,
                    features = mergedFeatures,
                    incompleteRelations = (previous?.incompleteRelations.orEmpty() + recovered.incompleteRelations).distinct(),
                    expectedHoleCount = selectedExpectedHoleCount ?: previous?.expectedHoleCount ?: recovered.expectedHoleCount ?: course.expectedHoleCount
                )))
                val improved = CourseReconstructionEngine.reconstruct(merged)
                val previousSourceKeys = previous?.features.orEmpty()
                    .filter { it.origin == com.watchthisdrive.app.data.CourseFeatureOrigin.SOURCE }
                    .map { it.elementType to it.id }.toSet()
                val newSourceFeatureCount = recovered.features.count {
                    it.origin == com.watchthisdrive.app.data.CourseFeatureOrigin.SOURCE &&
                        (it.elementType to it.id) !in previousSourceKeys
                }
                val oldCount = reconstruction?.holes?.size ?: 0
                geometry = merged
                reconstruction = improved
                recoveryMessage = buildString {
                    append("Wider search returned ${recovered.features.size} mapped features; ")
                    append("$newSourceFeatureCount were new. ")
                    append("${improved.holes.size} holes are currently reconstructed.")
                    if (newSourceFeatureCount == 0 && improved.holes.size <= oldCount) {
                        append(" No additional geometry was recovered from this search.")
                    }
                }
            }
            result.onFailure { problem ->
                recoveryMessage = if (geometry != null) {
                    "The map search was unavailable. Existing course data was kept and local reconstruction was still applied. " +
                        (problem.message ?: "Please try again later.")
                } else {
                    "Could not complete the improvement search: ${problem.message ?: "Please try again."}"
                }
            }
            recoveryLoading = false
        }
    }

    fun reportMissingFeature(feature: MissingCourseFeature) {
        val updated = reportedFeatures + feature.name
        reportedFeatures = updated
        appContext.getSharedPreferences("course_mapping_reports", android.content.Context.MODE_PRIVATE)
            .edit()
            .putStringSet("course_${course.elementType}_${course.id}", updated)
            .apply()
        recoveryMessage = "${feature.label} marked as missing. Checking for additional mapped data now…"
        showMissingFeatureDialog = false
        improveMapping(feature.osmTag)
    }

    LaunchedEffect(course.id) {
        OpenGolfApiCourseLoader.load(course) { result ->
            result.onSuccess { metadata ->
                externalMetadata = metadata
                externalMetadataMessage = if (metadata == null) {
                    "No sufficiently close OpenGolfAPI match. OSM geometry remains unchanged."
                } else {
                    "Matched by exact course name and geographic proximity."
                }
                val externalHoleCount = metadata?.holeCount?.takeIf { it == 9 || it == 18 }
                if (selectedExpectedHoleCount == null && externalHoleCount != null) {
                    selectedExpectedHoleCount = externalHoleCount
                }
                metadata?.let { matchedMetadata ->
                    geometry?.let { current ->
                        val enriched = enrichGeometryWithScorecard(current, matchedMetadata, selectedExpectedHoleCount)
                        val inferred = CourseFeatureInference.addInferredFeatures(
                            CourseHoleInference.addInferredHoles(enriched)
                        )
                        geometry = inferred
                        reconstruction = CourseReconstructionEngine.reconstruct(inferred)
                    }
                }
            }.onFailure {
                externalMetadataMessage = "OpenGolfAPI unavailable; continuing with OSM data."
            }
        }
        NaipImageryLoader.load(course) { result ->
            result.onSuccess {
                naipBitmap = it
                naipMessage = "USGS / USDA NAIP · public-domain aerial reference"
            }.onFailure {
                naipMessage = "Aerial imagery unavailable at this location."
            }
        }
    }

    LaunchedEffect(course.id) {
        CourseGeometryLoader.load(course) { result ->
            result.fold(
                onSuccess = {
                    val withScorecard = externalMetadata?.let { metadata -> enrichGeometryWithScorecard(it, metadata, selectedExpectedHoleCount) }
                        ?: it.copy(expectedHoleCount = selectedExpectedHoleCount ?: it.expectedHoleCount)
                    val enriched = CourseFeatureInference.addInferredFeatures(CourseHoleInference.addInferredHoles(withScorecard))
                    geometry = enriched
                    reconstruction = CourseReconstructionEngine.reconstruct(enriched)
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
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2520)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Additional course evidence", color = Color.White, fontWeight = FontWeight.Bold)
                Text("OpenGolfAPI", color = Color(0xFFB8F36B), fontWeight = FontWeight.SemiBold)
                Text(externalMetadataMessage, color = Color(0xFFB6C1B9), style = MaterialTheme.typography.bodySmall)
                externalMetadata?.let { metadata ->
                    Text(
                        metadata.name + " · " +
                            (metadata.holeCount?.toString() ?: "hole count unknown") + " · " +
                            (metadata.totalPar?.let { "Par " + it } ?: "Par unavailable") + " · " +
                            (metadata.totalYardage?.let { it.toString() + " yd" } ?: "yardage unavailable"),
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )
                    metadata.tees.forEach { tee ->
                        Text(
                            tee.name + ": " + (tee.totalYardage?.let { it.toString() + " yd" } ?: "yardage unavailable") +
                                (tee.courseRating?.let { " · Rating " + "%.1f".format(Locale.US, it) } ?: "") +
                                (tee.slopeRating?.let { " · Slope " + it } ?: ""),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    metadata.holes.take(18).forEach { hole ->
                        Text(
                            "Hole " + hole.number +
                                (hole.par?.let { " · Par " + it } ?: "") +
                                (hole.yardage?.let { " · " + it + " yd" } ?: "") +
                                (hole.handicap?.let { " · HCP " + it } ?: ""),
                            color = Color(0xFFB6C1B9),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(metadata.attribution, color = Color(0xFFB6C1B9), style = MaterialTheme.typography.labelSmall)
                }
                Divider(color = Color(0xFF344238))
                Text("NAIP aerial imagery", color = Color(0xFFB8F36B), fontWeight = FontWeight.SemiBold)
                Text(naipMessage, color = Color(0xFFB6C1B9), style = MaterialTheme.typography.bodySmall)
                naipBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Public NAIP aerial image centered on the selected golf course",
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        contentScale = ContentScale.Crop
                    )
                }
                Text(
                    "Ariad is retained as a reference-only cross-check. Its published atlas describes maps as interpretations of published course geometry and identifies OSM and OpenGolfAPI among its data sources, so it is not treated as independent geometry.",
                    color = Color(0xFFB6C1B9),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

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
                        val sourceFeatures = features.filter { it.origin == com.watchthisdrive.app.data.CourseFeatureOrigin.SOURCE }
                        val currentGeometry = geometry
                        val currentReconstruction = reconstruction
                        val quality = if (currentGeometry != null && currentReconstruction != null) {
                            CourseMappingQuality.assess(currentGeometry, currentReconstruction)
                        } else null

                        quality?.let { report ->
                            Divider(color = Color(0xFF344238))
                            Text("Expected course length", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Use mapped course metadata when available, or set the known length here.",
                                color = Color(0xFFB6C1B9),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = { setExpectedHoleCount(9) }) {
                                    Text(if (selectedExpectedHoleCount == 9) "✓ 9 holes" else "9 holes")
                                }
                                TextButton(onClick = { setExpectedHoleCount(18) }) {
                                    Text(if (selectedExpectedHoleCount == 18) "✓ 18 holes" else "18 holes")
                                }
                                TextButton(onClick = { setExpectedHoleCount(null) }) {
                                    Text("Auto")
                                }
                            }
                            Text(
                                if (report.needsAttention) "Mapping needs attention" else "Mapping data loaded",
                                color = if (report.needsAttention) Color(0xFFFFC857) else Color(0xFFB8F36B),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${report.holeCenterlineCount} hole lines · ${report.reconstructedHoleCount} reconstructed · " +
                                    "${report.teeCount} tees · ${report.greenCount} greens · " +
                                    "${report.fairwayCount} fairways · ${report.bunkerCount} bunkers · ${report.waterCount} water features",
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall
                            )
                            report.expectedHoleCount?.let { expected ->
                                Text(
                                    "${report.reconstructedHoleCount}/$expected expected holes reconstructed",
                                    color = if (report.reconstructedHoleCount >= expected) Color(0xFFB8F36B) else Color(0xFFFFC857),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (report.inferredHoleCount > 0) {
                                Text(
                                    "${report.inferredHoleCount} missing hole(s) reconstructed from available tee, green and course-layout evidence (inferred; not source-mapped)",
                                    color = Color(0xFFFFC857),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (report.inferredFairwayCount > 0) {
                                Text(
                                    "${report.inferredFairwayCount} fairway corridors generated from hole lines (approximate, not source-mapped)",
                                    color = Color(0xFFFFC857),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (report.missingNumberedHoles.isNotEmpty()) {
                                Text(
                                    "Numbered hole gaps in source data: ${report.missingNumberedHoles.joinToString()}",
                                    color = Color(0xFFFFC857),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (report.unresolvedNumberedHoles.isNotEmpty()) {
                                Text(
                                    "Still unresolved after reconstruction: ${report.unresolvedNumberedHoles.joinToString()}",
                                    color = Color(0xFFFF8A80),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (report.holesWithoutGreen > 0) {
                                Text(
                                    "${report.holesWithoutGreen} reconstructed holes have no confidently matched green.",
                                    color = Color(0xFFFFC857),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(
                                "These counts describe available map data, not a verified inventory of the physical course.",
                                color = Color(0xFFB6C1B9),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        geometry?.takeIf { it.features.any { feature -> feature.hasUsableGeometry } }?.let {
                            Text("Decoded course layout", color = Color.White, fontWeight = FontWeight.SemiBold)
                            CourseGeometryPreview(it, Modifier.fillMaxWidth(), reconstruction)
                            Text(
                                "Geographic feature preview only — not satellite imagery or verified hole assignments.",
                                color = Color(0xFFB6C1B9),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        reconstruction?.let { reconstructed ->
                            Text(
                                "${reconstructed.holes.size} holes reconstructed · " +
                                    "${reconstructed.mappedPinCount} mapped pins · " +
                                    "${reconstructed.estimatedPinCount} estimated green-center pins",
                                color = Color(0xFFB8F36B),
                                fontWeight = FontWeight.SemiBold
                            )
                            reconstructed.holes.forEach { hole ->
                                Text(
                                    "Hole ${hole.number}: " +
                                        (hole.pinSource?.let { if (it == com.watchthisdrive.app.data.PinSource.MAPPED) "mapped pin" else "estimated pin" } ?: "pin unavailable") +
                                        " · ${hole.fairways.size} fairways · ${hole.bunkers.size} bunkers · ${hole.waterHazards.size} water features",
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        Text(
                            "${sourceFeatures.size} source-mapped features found",
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
                            val count = sourceFeatures.count { it.featureType == type }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(label, color = Color.White)
                                Text(count.toString(), color = Color(0xFFB8F36B), fontWeight = FontWeight.Bold)
                            }
                        }

                        val polygonCount = sourceFeatures.count {
                            it.geometryType == com.watchthisdrive.app.data.CourseGeometryType.POLYGON ||
                                it.geometryType == com.watchthisdrive.app.data.CourseGeometryType.MULTI_POLYGON
                        }
                        val lineCount = sourceFeatures.count {
                            it.geometryType == com.watchthisdrive.app.data.CourseGeometryType.LINE
                        }
                        val pointCount = sourceFeatures.count {
                            it.geometryType == com.watchthisdrive.app.data.CourseGeometryType.POINT
                        }
                        Divider(color = Color(0xFF344238))
                        Text("Geometry successfully decoded", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(
                            "$polygonCount area features · $lineCount lines · $pointCount points",
                            color = Color(0xFFB8F36B)
                        )
                        Text(
                            "Multipolygon inner boundaries are retained where the source provides them.",
                            color = Color(0xFFB6C1B9),
                            style = MaterialTheme.typography.bodySmall
                        )
                        geometry?.incompleteRelations?.takeIf { it.isNotEmpty() }?.let { issues ->
                            Text(
                                "${issues.size} course geometries need review; incomplete or invalid areas were not silently filled.",
                                color = Color(0xFFFFCC80),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        if (features.isEmpty()) {
                            Text(
                                "No golf features were mapped near this course. " +
                                    "We won't invent missing course details.",
                                color = Color(0xFFB6C1B9)
                            )
                        }

                        Button(
                            onClick = { improveMapping() },
                            enabled = !recoveryLoading,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFB8F36B),
                                contentColor = Color(0xFF101713)
                            )
                        ) {
                            if (recoveryLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFF101713)
                                )
                                Spacer(Modifier.width(8.dp))
                            } else {
                                Icon(Icons.Filled.Refresh, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (recoveryLoading) "IMPROVING MAPPING…" else "IMPROVE COURSE MAPPING")
                        }
                        OutlinedButton(
                            onClick = { showMissingFeatureDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB8F36B))
                        ) {
                            Icon(Icons.Filled.Flag, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("REPORT A MISSING FEATURE")
                        }
                        recoveryMessage?.let {
                            Text(it, color = Color(0xFFB6C1B9), style = MaterialTheme.typography.bodySmall)
                        }
                        if (reportedFeatures.isNotEmpty()) {
                            Text(
                                "Reported missing on this device: " +
                                    reportedFeatures.mapNotNull { name ->
                                        MissingCourseFeature.entries.firstOrNull { it.name == name }?.label
                                    }.joinToString(),
                                color = Color(0xFFFFC857),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }

        if (showMissingFeatureDialog) {
            AlertDialog(
                onDismissRequest = { showMissingFeatureDialog = false },
                title = { Text("What is missing?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        MissingCourseFeature.entries.forEach { feature ->
                            TextButton(
                                onClick = { reportMissingFeature(feature) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(feature.label, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showMissingFeatureDialog = false }) { Text("Cancel") }
                }
            )
        }

        Button(
            onClick = { onViewOnMap(geometry, reconstruction) },
            enabled = !loading && geometry != null,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFB8F36B),
                contentColor = Color(0xFF101713)
            )
        ) {
            Icon(Icons.Filled.Map, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (reconstruction?.holes?.isNotEmpty() == true) "Start Round" else "View Course Map")
        }
    }
}

private fun Int?.orZero(): Int = this ?: 0
