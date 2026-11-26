package com.watchthisdrive.app.ui
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.watchthisdrive.app.data.RoundPlayer
import com.watchthisdrive.app.data.RoundScorecard

@Composable
fun GlobalScorecardScreen(round: RoundScorecard, onScoreChange: (String, String, Int) -> Unit, onAddPlayer: (String) -> Unit, onRemovePlayer: (String) -> Unit, onFinishRound: () -> Unit, readOnly: Boolean = false, onBack: () -> Unit = {}, onShare: () -> Unit = {}, onTitleChange: (String) -> Unit = {}, onParChange: (String, Int?) -> Unit = { _, _ -> }, onDownload: () -> Unit = {}, courseFactsStatus: String? = null, onRetryCourseFacts: () -> Unit = {}, onQuitRound: () -> Unit = {}) {
    var newPlayerName by remember { mutableStateOf("") }
    var titleDraft by remember(round.startedAt, round.scorecardTitle) { mutableStateOf(round.scorecardTitle) }
    var showFinishDialog by remember { mutableStateOf(false) }
    val holes = round.holePars.keys.sortedWith(compareBy<String> { it.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it })
    val userComplete = holes.isNotEmpty() && holes.all { (round.user.scores[it] ?: 0) > 0 }
    val scoredHoleCount = holes.count { (round.user.scores[it] ?: 0) > 0 }
    var showQuitDialog by remember { mutableStateOf(false) }
    val startDateTime = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(round.startedAt))
    val endDateTime = round.endedAt?.let { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(it)) }
    Column(Modifier.fillMaxSize().background(Color(0xFF101713)).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("GLOBAL SCORECARD", color = Color(0xFFB8F36B), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text(round.courseName, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (!readOnly && round.scorecardOnly && courseFactsStatus != null) {
            Text(courseFactsStatus, color = Color(0xFFB6C1B9), style = MaterialTheme.typography.bodySmall)
            if (!courseFactsStatus.startsWith("Looking up")) {
                TextButton(onClick = onRetryCourseFacts, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                    Text("RETRY COURSE DATA LOOKUP", color = Color(0xFFB8F36B), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (!readOnly) {
            OutlinedTextField(value = titleDraft, onValueChange = { value -> titleDraft = value.take(48); onTitleChange(value.take(48)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Scorecard title (optional)") }, placeholder = { Text("e.g. Saturday morning round") }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color(0xFFB8F36B), unfocusedBorderColor = Color(0xFF526257), cursorColor = Color(0xFFB8F36B)))
        } else if (round.scorecardTitle.isNotBlank()) Text(round.scorecardTitle, color = Color(0xFFB8F36B), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Started: $startDateTime" + (endDateTime?.let { "   ·   Finished: $it" } ?: ""), color = Color(0xFFB6C1B9), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB8F36B))) {
            Icon(Icons.Filled.Share, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("SHARE SCORECARD PDF", fontWeight = FontWeight.Bold)
        }
        OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB8F36B))) {
            Icon(Icons.Filled.Download, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("SAVE PDF TO DEVICE", fontWeight = FontWeight.Bold)
        }
        if (readOnly) TextButton(onClick = onBack) { Text("BACK TO SAVED GAMES", color = Color(0xFFB8F36B)) }
        Text(if (readOnly) "Saved scorecard · read-only" else "Scores entered here update the live game automatically.", color = Color(0xFFB6C1B9))
        round.players.forEach { player ->
            PlayerScorecard(player, player.id == round.userPlayerId, holes, round.holePars, round.holeYardages,
                { hole, strokes -> onScoreChange(player.id, hole, strokes) },
                { onRemovePlayer(player.id) }, readOnly, onParChange)
        }
        if (!readOnly) Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2921)), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("ADD PLAYER", color = Color(0xFFB8F36B), fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newPlayerName, onValueChange = { newPlayerName = it.take(28) }, modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("Player name") },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color(0xFFB8F36B), unfocusedBorderColor = Color(0xFF526257), cursorColor = Color(0xFFB8F36B)))
                    Button(onClick = { onAddPlayer(newPlayerName.trim()); newPlayerName = "" }, enabled = newPlayerName.trim().isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB8F36B), contentColor = Color(0xFF101713))) {
                        Icon(Icons.Filled.PersonAdd, contentDescription = "Add player")
                    }
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2921)), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ROUND TOTALS", color = Color(0xFFB8F36B), fontWeight = FontWeight.Bold)
                round.players.forEach { player ->
                    val total = player.scores.values.sum()
                    val scoredWithPar = player.scores.filterKeys { round.holePars[it] != null }
                    val relative = if (scoredWithPar.isEmpty()) null else scoredWithPar.entries.sumOf { (hole, strokes) -> strokes - (round.holePars[hole] ?: 0) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(player.name, color = Color.White)
                        Text("$total strokes · ${relative?.let { scoreLabel(it) } ?: "—"}", color = if (player.id == round.userPlayerId) Color(0xFFB8F36B) else Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        if (!readOnly) Button(onClick = { showFinishDialog = true }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB8F36B), contentColor = Color(0xFF101713))) {
            Text(if (userComplete) "FINISH ROUND & SAVE" else "FINISH EARLY & SAVE", fontWeight = FontWeight.Bold)
        }
        if (!readOnly && !userComplete) Text("$scoredHoleCount of ${holes.size} holes scored. You can finish now; unplayed holes will remain blank in your saved scorecard.", color = Color(0xFFB6C1B9), style = MaterialTheme.typography.bodySmall)
        if (!readOnly) TextButton(onClick = { showQuitDialog = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFFB4AB))) { Text("QUIT WITHOUT SAVING") }
    }
    if (!readOnly && showFinishDialog) AlertDialog(onDismissRequest = { showFinishDialog = false }, title = { Text(if (userComplete) "Save completed round?" else "Finish this round early?") },
        text = { Text(if (userComplete) "Your scorecard will be saved on this device and the active game will close." else "Your current scores will be saved on this device. Unplayed holes will remain blank, and the active game will close.") },
        confirmButton = { TextButton(onClick = { showFinishDialog = false; onFinishRound() }) { Text("SAVE ROUND", color = Color(0xFFB8F36B)) } },
        dismissButton = { TextButton(onClick = { showFinishDialog = false }) { Text("KEEP PLAYING") } })
    if (!readOnly && showQuitDialog) AlertDialog(onDismissRequest = { showQuitDialog = false }, title = { Text("Quit without saving?") },
        text = { Text("This discards the active round and all scores entered in it. It will not appear in saved games.") },
        confirmButton = { TextButton(onClick = { showQuitDialog = false; onQuitRound() }) { Text("QUIT & DISCARD", color = Color(0xFFFFB4AB)) } },
        dismissButton = { TextButton(onClick = { showQuitDialog = false }) { Text("KEEP PLAYING") } })
}

@Composable
private fun PlayerScorecard(player: RoundPlayer, isUser: Boolean, holes: List<String>, pars: Map<String, Int?>, yardages: Map<String, Int?>, onScoreChange: (String, Int) -> Unit, onRemove: () -> Unit, readOnly: Boolean, onParChange: (String, Int?) -> Unit) {
    val total = player.scores.values.sum()
    val scoredWithPar = player.scores.filterKeys { pars[it] != null }
    val relative = if (scoredWithPar.isEmpty()) null else scoredWithPar.entries.sumOf { (hole, strokes) -> strokes - (pars[hole] ?: 0) }
    Card(colors = CardDefaults.cardColors(containerColor = if (isUser) Color(0xFF24372A) else Color(0xFF1B2520)), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(player.name + if (isUser) " (YOU)" else "", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("$total strokes · ${relative?.let { scoreLabel(it) } ?: "—"}", color = Color(0xFFB8F36B), style = MaterialTheme.typography.labelMedium)
                }
                if (!isUser && !readOnly) TextButton(onClick = onRemove) { Text("Remove", color = Color(0xFFFFB4AB)) }
            }
            holes.forEach { hole ->
                val strokes = player.scores[hole] ?: 0
                Row(Modifier.fillMaxWidth().background(Color(0xFF111A15), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Hole $hole", color = Color.White, fontWeight = FontWeight.SemiBold)
                        if (readOnly) {
                            Text("Par ${pars[hole] ?: "—"}  ·  ${yardages[hole]?.let { "$it yd" } ?: "Yardage —"}", color = Color(0xFFB6C1B9), style = MaterialTheme.typography.labelSmall)
                        } else {
                            OutlinedTextField(
                                value = pars[hole]?.toString().orEmpty(),
                                onValueChange = { raw ->
                                    if (raw.isBlank()) onParChange(hole, null)
                                    else raw.toIntOrNull()?.takeIf { it in 1..10 }?.let { onParChange(hole, it) }
                                },
                                modifier = Modifier.width(76.dp),
                                singleLine = true,
                                label = { Text("Par") },
                                placeholder = { Text("—") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFFB8F36B), unfocusedBorderColor = Color(0xFF526257),
                                    cursorColor = Color(0xFFB8F36B)
                                )
                            )
                            Text(yardages[hole]?.let { "$it yd" } ?: "Yardage unavailable", color = Color(0xFFB6C1B9), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (!readOnly) IconButton(onClick = { onScoreChange(hole, (strokes - 1).coerceAtLeast(0)) }, enabled = strokes > 0) { Icon(Icons.Filled.Remove, contentDescription = "Remove stroke", tint = Color.White) }
                    Text(if (strokes == 0) "—" else strokes.toString(), color = Color.White, fontWeight = FontWeight.Bold)
                    if (!readOnly) IconButton(onClick = { onScoreChange(hole, strokes + 1) }) { Icon(Icons.Filled.Add, contentDescription = "Add stroke", tint = Color(0xFFB8F36B)) }
                }
            }
        }
    }
}
private fun scoreLabel(relative: Int): String = when { relative == 0 -> "Even"; relative > 0 -> "+$relative"; else -> relative.toString() }
