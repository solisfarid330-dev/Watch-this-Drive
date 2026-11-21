package com.watchthisdrive.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

private data class ClubDistance(
    val name: String,
    val carryYards: String
)

private val defaultClubs = listOf(
    ClubDistance("Driver", "220"),
    ClubDistance("3 Wood", "200"),
    ClubDistance("5 Wood", "185"),
    ClubDistance("4 Iron", "170"),
    ClubDistance("5 Iron", "160"),
    ClubDistance("6 Iron", "150"),
    ClubDistance("7 Iron", "140"),
    ClubDistance("8 Iron", "130"),
    ClubDistance("9 Iron", "120"),
    ClubDistance("Pitching Wedge", "105"),
    ClubDistance("Gap Wedge", "90"),
    ClubDistance("Sand Wedge", "75"),
    ClubDistance("Lob Wedge", "60")
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
            // Keep existing user carry values when upgrading from the earlier two-distance version.
            ClubDistance(
                club.name,
                preferences.getString("carry_${club.name}", club.carry) ?: club.carry
            )
        })
    }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "CLUB MANAGEMENT",
                    color = caddyGreen,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Your club distances",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Set the distance each club normally carries the ball.",
                    color = caddyMuted,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = caddyCard),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "CARRY DISTANCE (YD)",
                        color = caddyGreen,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Distance the ball travels through the air to its first landing. Bounce and roll are not included. The caddy uses this number for club recommendations and hazard planning.",
                        color = caddyMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        items(clubs, key = { it.name }) { club ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF18231C)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = club.carryYards,
                    onValueChange = { value ->
                        val cleaned = value.filter(Char::isDigit).take(3)
                        clubs = clubs.map { current ->
                            if (current.name == club.name) current.copy(carryYards = cleaned) else current
                        }
                        savedMessage = null
                    },
                    label = { Text("${club.name} — carry yards") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(10.dp)
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        preferences.edit().apply {
                            clubs.forEach { club ->
                                putString("carry_${club.name}", club.carryYards)
                                // Remove obsolete total values from the previous screen design.
                                remove("total_${club.name}")
                            }
                        }.apply()
                        savedMessage = "Club distances saved."
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("SAVE CLUB DISTANCES")
                }
                savedMessage?.let {
                    Text(it, color = caddyGreen, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "You can change these estimates whenever you like. Shot learning will update them as you play.",
                    color = caddyMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
