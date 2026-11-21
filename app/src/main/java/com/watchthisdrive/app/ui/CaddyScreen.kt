package com.watchthisdrive.app.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsGolf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

private data class ClubDistance(
    val name: String,
    val carry: String,
    val total: String
)

private val defaultClubs = listOf(
    ClubDistance("Driver", "220", ""),
    ClubDistance("3 Wood", "200", ""),
    ClubDistance("5 Wood", "185", ""),
    ClubDistance("4 Iron", "170", ""),
    ClubDistance("5 Iron", "160", ""),
    ClubDistance("6 Iron", "150", ""),
    ClubDistance("7 Iron", "140", ""),
    ClubDistance("8 Iron", "130", ""),
    ClubDistance("9 Iron", "120", ""),
    ClubDistance("Pitching Wedge", "105", ""),
    ClubDistance("Gap Wedge", "90", ""),
    ClubDistance("Sand Wedge", "75", ""),
    ClubDistance("Lob Wedge", "60", "")
)

private val caddyGreen = Color(0xFFB8F36B)
private val caddyCard = Color(0xFF1D2A22)
private val caddyMuted = Color(0xFFB6C1B9)

@Composable
fun CaddyScreen() {
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences("watch_this_drive_caddy", Context.MODE_PRIVATE)
    }
    var clubs by remember {
        mutableStateOf(defaultClubs.map { club ->
            ClubDistance(
                club.name,
                preferences.getString("carry_${club.name}", club.carry) ?: club.carry,
                preferences.getString("total_${club.name}", club.total) ?: club.total
            )
        })
    }
    var targetDistance by remember { mutableStateOf("") }
    var selectedClub by remember { mutableStateOf(defaultClubs.first().name) }
    var carryEntry by remember { mutableStateOf("") }
    var totalEntry by remember { mutableStateOf("") }
    var shotMessage by remember { mutableStateOf<String?>(null) }
    var showClubPicker by remember { mutableStateOf(false) }

    fun saveClubs(updated: List<ClubDistance>) {
        clubs = updated
        preferences.edit().apply {
            updated.forEach { club ->
                putString("carry_${club.name}", club.carry)
                putString("total_${club.name}", club.total)
            }
        }.apply()
    }

    val recommendation = targetDistance.toIntOrNull()?.let { yards ->
        clubs.mapNotNull { club ->
            val carry = club.carry.toIntOrNull() ?: return@mapNotNull null
            club to carry
        }.minByOrNull { (_, carry) -> kotlin.math.abs(carry - yards) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "YOUR CADDY",
                    color = caddyGreen,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Simple, personal club recommendations.",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = caddyCard),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("CLUB RECOMMENDATION", color = caddyGreen, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = targetDistance,
                        onValueChange = { targetDistance = it.filter(Char::isDigit).take(3) },
                        label = { Text("Distance to target (yards)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    when {
                        recommendation != null -> {
                            Text(
                                recommendation.first.name,
                                color = Color.White,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Closest carry: approximately ${recommendation.second} yards.",
                                color = caddyMuted
                            )
                        }
                        else -> Text(
                            "Enter the distance to the target. Your caddy compares it with your saved carry distances.",
                            color = caddyMuted
                        )
                    }
                    Text(
                        "Target distance is manual for now. GPS-to-pin distance will be added when the selected hole's pin location is available.",
                        color = Color(0xFF8F9D93),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = caddyCard),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("RECORD A SHOT", color = caddyGreen, fontWeight = FontWeight.Bold)
                    Box {
                        OutlinedButton(
                            onClick = { showClubPicker = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.SportsGolf, null)
                            Spacer(Modifier.width(8.dp))
                            Text(selectedClub)
                        }
                        DropdownMenu(
                            expanded = showClubPicker,
                            onDismissRequest = { showClubPicker = false }
                        ) {
                            clubs.forEach { club ->
                                DropdownMenuItem(
                                    text = { Text(club.name) },
                                    onClick = {
                                        selectedClub = club.name
                                        showClubPicker = false
                                    }
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        OutlinedTextField(
                            value = carryEntry,
                            onValueChange = { carryEntry = it.filter(Char::isDigit).take(3) },
                            label = { Text("Carry yd") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = totalEntry,
                            onValueChange = { totalEntry = it.filter(Char::isDigit).take(3) },
                            label = { Text("Total yd") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Button(
                        onClick = {
                            val carry = carryEntry.toIntOrNull()
                            val total = totalEntry.toIntOrNull()
                            if (carry != null && total != null && carry > 0 && total >= carry) {
                                val previous = clubs.first { it.name == selectedClub }
                                val oldCarry = previous.carry.toIntOrNull()
                                val oldTotal = previous.total.toIntOrNull()
                                val updated = clubs.map { club ->
                                    if (club.name == selectedClub) {
                                        val nextCarry = if (oldCarry == null) carry else (oldCarry + carry) / 2
                                        val nextTotal = if (oldTotal == null) total else (oldTotal + total) / 2
                                        club.copy(carry = nextCarry.toString(), total = nextTotal.toString())
                                    } else club
                                }
                                saveClubs(updated)
                                carryEntry = ""
                                totalEntry = ""
                                shotMessage = "Saved. $selectedClub averages updated."
                            } else {
                                shotMessage = "Enter carry and total; total must be at least carry."
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("SAVE SHOT & UPDATE CLUB AVERAGES")
                    }
                    shotMessage?.let {
                        Text(it, color = caddyGreen, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "Enter the measured carry and total for this shot. The caddy updates that club's estimate. GPS shot tracking is not connected yet.",
                        color = Color(0xFF8F9D93),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("MY CLUB DISTANCES", color = caddyGreen, fontWeight = FontWeight.Bold)
                Text(
                    "Edit your carry and total distances at any time. Recommendations use carry.",
                    color = caddyMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        items(clubs, key = { it.name }) { club ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF18231C)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Text(club.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        OutlinedTextField(
                            value = club.carry,
                            onValueChange = { value ->
                                saveClubs(clubs.map {
                                    if (it.name == club.name) it.copy(carry = value.filter(Char::isDigit).take(3)) else it
                                })
                            },
                            label = { Text("Carry yd") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = club.total,
                            onValueChange = { value ->
                                saveClubs(clubs.map {
                                    if (it.name == club.name) it.copy(total = value.filter(Char::isDigit).take(3)) else it
                                })
                            },
                            label = { Text("Total yd") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
