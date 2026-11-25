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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.watchthisdrive.app.data.RoundPlayer
import com.watchthisdrive.app.data.RoundScorecard

@Composable
fun GlobalScorecardScreen(round: RoundScorecard, onScoreChange: (String, String, Int) -> Unit, onAddPlayer: (String) -> Unit, onRemovePlayer: (String) -> Unit, onFinishRound: () -> Unit) {
    var newPlayerName by remember { mutableStateOf("") }
    var showFinishDialog by remember { mutableStateOf(false) }
    val holes = round.holePars.keys.sortedWith(compareBy<String> { it.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it })
    val userComplete = holes.isNotEmpty() && holes.all { (round.user.scores[it] ?: 0) > 0 }
    val scoredHoleCount = holes.count { (round.user.scores[it] ?: 0) > 0 }
    Column(Modifier.fillMaxSize().background(Color(0xFF101713)).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("GLOBAL SCORECARD", color = Color(0xFFB8F36B), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text(round.courseName, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Scores entered here update the live game automatically.", color = Color(0xFFB6C1B9))
        round.players.forEach { player ->
            PlayerScorecard(player, player.id == round.userPlayerId, holes, round.holePars,
                { hole, strokes -> onScoreChange(player.id, hole, strokes) },
                { onRemovePlayer(player.id) })
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2921)), shape = RoundedCornerShape(16.dp)) {
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
        Button(onClick = { showFinishDialog = true }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB8F36B), contentColor = Color(0xFF101713))) {
            Text(if (userComplete) "FINISH ROUND & SAVE" else "FINISH EARLY & SAVE", fontWeight = FontWeight.Bold)
        }
        if (!userComplete) Text("$scoredHoleCount of ${holes.size} holes scored. You can finish now; unplayed holes will remain blank in your saved scorecard.", color = Color(0xFFB6C1B9), style = MaterialTheme.typography.bodySmall)
    }
    if (showFinishDialog) AlertDialog(onDismissRequest = { showFinishDialog = false }, title = { Text(if (userComplete) "Save completed round?" else "Finish this round early?") },
        text = { Text(if (userComplete) "Your scorecard will be saved on this device and the active game will close." else "Your current scores will be saved on this device. Unplayed holes will remain blank, and the active game will close.") },
        confirmButton = { TextButton(onClick = { showFinishDialog = false; onFinishRound() }) { Text("SAVE ROUND", color = Color(0xFFB8F36B)) } },
        dismissButton = { TextButton(onClick = { showFinishDialog = false }) { Text("KEEP PLAYING") } })
}

@Composable
private fun PlayerScorecard(player: RoundPlayer, isUser: Boolean, holes: List<String>, pars: Map<String, Int?>, onScoreChange: (String, Int) -> Unit, onRemove: () -> Unit) {
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
                if (!isUser) TextButton(onClick = onRemove) { Text("Remove", color = Color(0xFFFFB4AB)) }
            }
            holes.forEach { hole ->
                val strokes = player.scores[hole] ?: 0
                Row(Modifier.fillMaxWidth().background(Color(0xFF111A15), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Hole $hole", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Par ${pars[hole] ?: "—"}", color = Color(0xFFB6C1B9), style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { onScoreChange(hole, (strokes - 1).coerceAtLeast(0)) }, enabled = strokes > 0) { Icon(Icons.Filled.Remove, contentDescription = "Remove stroke", tint = Color.White) }
                    Text(if (strokes == 0) "—" else strokes.toString(), color = Color.White, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { onScoreChange(hole, strokes + 1) }) { Icon(Icons.Filled.Add, contentDescription = "Add stroke", tint = Color(0xFFB8F36B)) }
                }
            }
        }
    }
}
private fun scoreLabel(relative: Int): String = when { relative == 0 -> "Even"; relative > 0 -> "+$relative"; else -> relative.toString() }
