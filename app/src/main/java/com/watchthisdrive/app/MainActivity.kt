package com.watchthisdrive.app

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import com.watchthisdrive.app.ui.GolfMap
import com.watchthisdrive.app.ui.GolfTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GolfTheme { WatchThisDriveApp() } }
    }
}

private enum class Tab(val label: String) { HOME("Home"), MAP("Course"), SCORE("Scorecard"), CADDY("Caddy") }

@Composable
private fun WatchThisDriveApp() {
    var selected by remember { mutableStateOf(Tab.HOME) }
    var location by remember { mutableStateOf<Location?>(null) }
    var locationMessage by remember { mutableStateOf("Location not checked yet") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val fused = remember { LocationServices.getFusedLocationProviderClient(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            try {
                fused.lastLocation.addOnSuccessListener { found ->
                    location = found
                    locationMessage = if (found != null) "GPS fix received" else "Waiting for a GPS fix — try outdoors"
                }
            } catch (_: SecurityException) { locationMessage = "Location permission unavailable" }
        } else locationMessage = "Location permission was not granted"
    }

    fun locate() {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else {
            try {
                fused.lastLocation.addOnSuccessListener { found ->
                    location = found
                    locationMessage = if (found != null) "GPS fix received" else "No recent location yet — try outdoors"
                }
            } catch (_: SecurityException) { locationMessage = "Location permission unavailable" }
        }
    }

    Scaffold(
        containerColor = Color(0xFF101713),
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF18231C), contentColor = Color.White) {
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
                            selectedIconColor = Color(0xFF101713), selectedTextColor = Color(0xFFB8F36B),
                            indicatorColor = Color(0xFFB8F36B), unselectedIconColor = Color(0xFFB6C1B9),
                            unselectedTextColor = Color(0xFFB6C1B9)
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (selected) {
                Tab.HOME -> HomeScreen(locationMessage, location, onLocate = { locate() }, onOpenMap = { selected = Tab.MAP })
                Tab.MAP -> GolfMap(location = location, onLocate = { locate() })
                Tab.SCORE -> PlaceholderScreen("Scorecard", "Your round and player scores will live here.", Icons.Filled.Scoreboard)
                Tab.CADDY -> PlaceholderScreen("Your caddy", "Default club distances will be ready from day one. The caddy will learn and adapt as you record shots.", Icons.Filled.SportsGolf)
            }
        }
    }
}

@Composable
private fun HomeScreen(message: String, location: Location?, onLocate: () -> Unit, onOpenMap: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("WATCH THIS DRIVE", color = Color(0xFFB8F36B), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text("Your golf companion.", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            Icon(Icons.Filled.SportsGolf, null, tint = Color(0xFFB8F36B), modifier = Modifier.size(42.dp))
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1D2A22)), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("READY FOR THE COURSE?", color = Color(0xFFB8F36B), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text("Find your course and get your round started.", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Course detection and hole-by-hole mapping are coming online in the next build stages.", color = Color(0xFFB6C1B9), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onOpenMap, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB8F36B), contentColor = Color(0xFF101713)), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Map, null); Spacer(Modifier.width(8.dp)); Text("OPEN COURSE MAP", fontWeight = FontWeight.Bold)
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF18231C)), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.MyLocation, null, tint = Color(0xFFB8F36B))
                    Spacer(Modifier.width(9.dp))
                    Text("GPS STATUS", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Text(message, color = Color(0xFFB6C1B9))
                if (location != null) Text("${"%.5f".format(location.latitude)}, ${"%.5f".format(location.longitude)}", color = Color.White, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onLocate, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB8F36B))) {
                    Text("CHECK MY LOCATION")
                }
            }
        }
        Text("YOUR ROUND, YOUR GAME", color = Color(0xFFB8F36B), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
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
private fun FeatureTile(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFF18231C)), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color(0xFFB8F36B), modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Color(0xFFB8F36B), modifier = Modifier.size(54.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(description, color = Color(0xFFB6C1B9), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(18.dp))
        Text("COMING IN A FUTURE BUILD", color = Color(0xFFB8F36B), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}
