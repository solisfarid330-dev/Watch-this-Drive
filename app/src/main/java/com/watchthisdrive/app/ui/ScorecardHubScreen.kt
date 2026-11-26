package com.watchthisdrive.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.watchthisdrive.app.data.RoundScorecardStore.SavedRound
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ScorecardHubScreen(
    savedRounds: List<SavedRound>,
    onStartScorecardOnly: (String, Int) -> Unit,
    onChooseCourse: (String, Int) -> Unit,
    onOpenSavedRound: (SavedRound) -> Unit,
    onRenameSavedRound: (SavedRound, String) -> Unit = { _, _ -> },
    onDeleteSavedRound: (SavedRound) -> Unit = {}
) {
    var showStartDialog by remember { mutableStateOf(false) }
    var holes by remember { mutableIntStateOf(18) }
    var title by remember { mutableStateOf("") }
    var renamingRound by remember { mutableStateOf<SavedRound?>(null) }
    var renameTitle by remember { mutableStateOf("") }
    var deletingRound by remember { mutableStateOf<SavedRound?>(null) }

    Column(Modifier.fillMaxSize().background(Color(0xFF101713)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("SCORECARD", color = Color(0xFFB8F36B), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text("Keep your round.", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1D2A22)), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("SCORECARD-ONLY GAME", color = Color(0xFFB8F36B), fontWeight = FontWeight.Bold)
                Text("Track scores for yourself and your group with or without a selected course. GPS and course mapping are optional.", color = Color(0xFFB6C1B9))
                Button(onClick = { showStartDialog = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB8F36B), contentColor = Color(0xFF101713))) {
                    Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("START SCORECARD-ONLY GAME", fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SAVED GAMES", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${savedRounds.size} saved", color = Color(0xFFB6C1B9), style = MaterialTheme.typography.labelSmall)
        }
        if (savedRounds.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF18231C)), shape = RoundedCornerShape(14.dp)) {
                Text("Finished rounds will appear here. Your saved scorecards stay on this device.", color = Color(0xFFB6C1B9), modifier = Modifier.padding(16.dp))
            }
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(savedRounds) { saved ->
                val round = saved.round
                val total = round.user.scores.values.sum()
                val relative = round.relativeToPar(round.user)?.let { if (it == 0) "E" else if (it > 0) "+$it" else it.toString() } ?: "—"
                val date = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(saved.completedAt))
                val startTime = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(round.startedAt))
                val endTime = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(saved.completedAt))
                Card(Modifier.fillMaxWidth().clickable { onOpenSavedRound(saved) }, colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2921)), shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(round.scorecardTitle.ifBlank { round.courseName }, color = Color.White, fontWeight = FontWeight.Bold)
                            if (round.scorecardTitle.isNotBlank()) Text(round.courseName, color = Color(0xFFB6C1B9), style = MaterialTheme.typography.bodySmall)
                            Text("$date · Started $startTime · Finished $endTime", color = Color(0xFFB6C1B9), style = MaterialTheme.typography.bodySmall)
                            Text("${round.user.scores.size} holes scored · $total strokes · $relative", color = Color(0xFFB8F36B), style = MaterialTheme.typography.labelMedium)
                        }
                        var menuExpanded by remember(saved.completedAt, round.startedAt) { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Filled.MoreVert, "Saved scorecard options", tint = Color(0xFFB8F36B)) }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(text = { Text("Rename") }, onClick = { menuExpanded = false; renameTitle = round.scorecardTitle.ifBlank { round.courseName }; renamingRound = saved })
                                DropdownMenuItem(text = { Text("Delete") }, onClick = { menuExpanded = false; deletingRound = saved })
                            }
                        }
                    }
                }
            }
        }
    }
    if (showStartDialog) AlertDialog(
        onDismissRequest = { showStartDialog = false },
        title = { Text("Start a scorecard-only game") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it.take(48) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Scorecard title (optional)") }, placeholder = { Text("e.g. Saturday morning round") })
                Text("Choose the number of holes. You can attach a course without enabling GPS.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = holes == 9, onClick = { holes = 9 }, label = { Text("9 holes") })
                    FilterChip(selected = holes == 18, onClick = { holes = 18 }, label = { Text("18 holes") })
                }
            }
        },
        confirmButton = { TextButton(onClick = { showStartDialog = false; onStartScorecardOnly(title.trim(), holes) }, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFB8F36B))) { Text("START WITHOUT COURSE") } },
        dismissButton = {
            Row {
                TextButton(onClick = { showStartDialog = false; onChooseCourse(title.trim(), holes) }, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFB8F36B))) { Text("CHOOSE COURSE") }
                TextButton(onClick = { showStartDialog = false }, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF4B7D22))) { Text("CANCEL") }
            }
        }
    )
    renamingRound?.let { saved ->
        AlertDialog(onDismissRequest = { renamingRound = null }, title = { Text("Rename saved scorecard") },
            text = { OutlinedTextField(value = renameTitle, onValueChange = { renameTitle = it.take(48) }, singleLine = true, label = { Text("Scorecard title") }) },
            confirmButton = { TextButton(onClick = { onRenameSavedRound(saved, renameTitle); renamingRound = null }) { Text("SAVE", color = Color(0xFFB8F36B)) } },
            dismissButton = { TextButton(onClick = { renamingRound = null }) { Text("CANCEL") } })
    }
    deletingRound?.let { saved ->
        AlertDialog(onDismissRequest = { deletingRound = null }, title = { Text("Delete saved scorecard?") },
            text = { Text("This permanently deletes this saved round from this device.") },
            confirmButton = { TextButton(onClick = { onDeleteSavedRound(saved); deletingRound = null }) { Text("DELETE", color = Color(0xFFFFB4AB)) } },
            dismissButton = { TextButton(onClick = { deletingRound = null }) { Text("CANCEL") } })
    }
}
