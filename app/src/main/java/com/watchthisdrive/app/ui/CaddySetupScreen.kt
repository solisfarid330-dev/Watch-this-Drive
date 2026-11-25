package com.watchthisdrive.app.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.watchthisdrive.app.data.ClubCarryDistances

@Composable
fun CaddySetupScreen(context: Context, carryDistances: Map<String, Int>, onChange: (Map<String, Int>) -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("SETTINGS", color = Color(0xFFB8F36B), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text("Club carry distances", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Starter carry estimates are prefilled. Adjust each club to your own distance through the air to first landing; do not include bounce or roll.", color = Color(0xFFB6C1B9), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ClubCarryDistances.clubNames) { club ->
                var value by remember(club, carryDistances[club]) { mutableStateOf(carryDistances[club]?.toString().orEmpty()) }
                Surface(color = Color(0xFF1B2520), shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
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
                            modifier = Modifier.width(112.dp),
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
}
