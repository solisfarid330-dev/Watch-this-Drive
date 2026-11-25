package com.watchthisdrive.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.SportsGolf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.watchthisdrive.app.data.ClubCarryDistances
import com.watchthisdrive.app.data.CourseCacheSummary
import java.text.DateFormat
import java.util.Date

@Composable
fun CaddySetupScreen(
    context: Context,
    carryDistances: Map<String, Int>,
    onChange: (Map<String, Int>) -> Unit,
    savedRoundSummaries: List<String>,
    cachedCourses: List<CourseCacheSummary>
) {
    var showCarries by remember { mutableStateOf(false) }
    var showLocation by remember { mutableStateOf(false) }
    var showData by remember { mutableStateOf(false) }
    val hasFineLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val hasCoarseLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("SETTINGS", color = Color(0xFFB8F36B), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text("Make WTD yours.", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Manage your golf setup and see what Watch This Drive keeps on this device.", color = Color(0xFFB6C1B9), style = MaterialTheme.typography.bodyMedium)

        SettingsActionCard(
            title = "Club Distances",
            subtitle = "${carryDistances.size} clubs configured · carry in yards",
            icon = { Icon(Icons.Filled.SportsGolf, null, tint = Color(0xFFB8F36B)) },
            expanded = showCarries,
            onClick = { showCarries = !showCarries }
        )
        if (showCarries) {
            Text("Carry distance means yards through the air to first landing, before bounce and roll.", color = Color(0xFFB6C1B9), style = MaterialTheme.typography.bodySmall)
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                ClubCarryDistances.clubNames.forEach { club ->
                    var value by remember(club, carryDistances[club]) { mutableStateOf(carryDistances[club]?.toString().orEmpty()) }
                    Surface(color = Color(0xFF1B2520), shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(club, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            OutlinedTextField(
                                value = value,
                                onValueChange = { raw ->
                                    value = raw.filter(Char::isDigit).take(3)
                                    val yards = value.toIntOrNull()?.takeIf { it in 1..400 }
                                    val updated = carryDistances.toMutableMap().apply {
                                        if (yards == null) remove(club) else put(club, yards)
                                    }
                                    ClubCarryDistances.save(context, club, yards)
                                    onChange(updated)
                                },
                                modifier = Modifier.width(104.dp),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                placeholder = { Text("yd", color = Color(0xFFB6C1B9)) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFFB8F36B),
                                    unfocusedBorderColor = Color(0xFF526257),
                                    cursorColor = Color(0xFFB8F36B)
                                )
                            )
                        }
                    }
                }
            }
        }

        SettingsActionCard(
            title = "Location",
            subtitle = if (hasFineLocation) "Precise location permission enabled" else if (hasCoarseLocation) "Approximate location permission enabled" else "Location permission not enabled",
            icon = { Icon(Icons.Filled.GpsFixed, null, tint = Color(0xFFB8F36B)) },
            expanded = showLocation,
            onClick = { showLocation = !showLocation }
        )
        if (showLocation) {
            SettingsDetail {
                Text("WTD uses location to find nearby golf courses and calculate your distance to the pin or a map target during an active game.", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Text(
                    when {
                        hasFineLocation -> "Android currently allows precise location."
                        hasCoarseLocation -> "Android currently allows approximate location. Pin distances may be less accurate."
                        else -> "Location access is currently off. You can enable it in Android Settings → Apps → Watch This Drive → Permissions → Location."
                    },
                    color = Color(0xFFB6C1B9),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        SettingsActionCard(
            title = "On-Device Data",
            subtitle = "${cachedCourses.size} saved course maps · ${savedRoundSummaries.size} saved scorecards",
            icon = { Icon(Icons.Filled.Folder, null, tint = Color(0xFFB8F36B)) },
            expanded = showData,
            onClick = { showData = !showData }
        )
        if (showData) {
            SettingsDetail {
                Text("SAVED COURSE MAPS", color = Color(0xFFB8F36B), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                if (cachedCourses.isEmpty()) {
                    Text("No course maps have been saved on this device yet.", color = Color.White, style = MaterialTheme.typography.bodySmall)
                } else cachedCourses.forEach { course ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(course.courseName, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("${course.featureCount} mapped features · ${String.format(java.util.Locale.US, "%.1f", course.fileSizeBytes / 1_048_576.0)} MB · saved ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(course.lastModified))}", color = Color(0xFFB6C1B9), style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider(color = Color(0xFF344238))
                Text("SAVED SCORECARDS", color = Color(0xFFB8F36B), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                if (savedRoundSummaries.isEmpty()) {
                    Text("No completed scorecards have been saved yet.", color = Color.White, style = MaterialTheme.typography.bodySmall)
                } else savedRoundSummaries.forEach { summary ->
                    Text(summary, color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider(color = Color(0xFF344238))
                Text("WHERE THE DATA LIVES", color = Color(0xFFB8F36B), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Text("Course map files: ${context.filesDir.absolutePath}/mapped_courses/", color = Color.White, style = MaterialTheme.typography.bodySmall)
                Text("Scorecards and settings are kept in Watch This Drive's private app storage. Android does not normally expose these private files in the phone's Files app.", color = Color(0xFFB6C1B9), style = MaterialTheme.typography.bodySmall)
                Text("Clearing app storage or uninstalling WTD removes this local data. It is not synced to a cloud account.", color = Color(0xFFB6C1B9), style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SettingsActionCard(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2520)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Color(0xFFB6C1B9), style = MaterialTheme.typography.bodySmall)
            }
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Color(0xFFB8F36B))
        }
    }
}

@Composable
private fun SettingsDetail(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF141E18)), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
    }
}
