package com.watchthisdrive.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class RoundPlayer(
    val id: String,
    val name: String,
    val scores: Map<String, Int> = emptyMap()
)

data class RoundScorecard(
    val courseName: String,
    val holePars: Map<String, Int?>,
    val players: List<RoundPlayer>,
    val userPlayerId: String,
    val startedAt: Long = System.currentTimeMillis(),
    val activeHoleNumber: String = "1"
) {
    val user: RoundPlayer get() = players.first { it.id == userPlayerId }
    fun totalFor(player: RoundPlayer): Int = player.scores.values.sum()
    fun relativeToPar(player: RoundPlayer): Int? {
        val scoredHoles = player.scores.filterKeys { holePars[it] != null }
        if (scoredHoles.isEmpty()) return null
        return scoredHoles.entries.sumOf { (hole, strokes) -> strokes - (holePars[hole] ?: 0) }
    }

    companion object {
        fun new(courseName: String, holePars: Map<String, Int?>): RoundScorecard {
            val player = RoundPlayer(UUID.randomUUID().toString(), "You")
            return RoundScorecard(courseName, holePars, listOf(player), player.id)
        }
    }
}

object RoundScorecardStore {
    private const val PREFS = "round_scorecards"
    private const val ACTIVE = "active_round"
    private const val COMPLETED = "completed_rounds"

    fun loadActive(context: Context): RoundScorecard? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ACTIVE, null)
            ?: return null
        return runCatching { decode(JSONObject(raw)) }.getOrNull()
    }

    fun saveActive(context: Context, round: RoundScorecard?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (round == null) remove(ACTIVE) else putString(ACTIVE, encode(round).toString())
        }.apply()
    }

    fun saveCompleted(context: Context, round: RoundScorecard) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val history = runCatching { JSONArray(prefs.getString(COMPLETED, "[]")) }
            .getOrDefault(JSONArray())
        history.put(encode(round).put("completedAt", System.currentTimeMillis()))
        prefs.edit().putString(COMPLETED, history.toString()).remove(ACTIVE).apply()
    }

    private fun encode(round: RoundScorecard): JSONObject = JSONObject().apply {
        put("courseName", round.courseName)
        put("startedAt", round.startedAt)
        put("activeHoleNumber", round.activeHoleNumber)
        put("userPlayerId", round.userPlayerId)
        put("holePars", JSONObject().apply {
            round.holePars.forEach { (hole, par) -> put(hole.toString(), par ?: JSONObject.NULL) }
        })
        put("players", JSONArray().apply {
            round.players.forEach { player ->
                put(JSONObject().apply {
                    put("id", player.id)
                    put("name", player.name)
                    put("scores", JSONObject().apply {
                        player.scores.forEach { (hole, strokes) -> put(hole.toString(), strokes) }
                    })
                })
            }
        })
    }

    private fun decode(json: JSONObject): RoundScorecard {
        val parsJson = json.getJSONObject("holePars")
        val pars = buildMap {
            val keys = parsJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, if (parsJson.isNull(key)) null else parsJson.getInt(key))
            }
        }
        val playersJson = json.getJSONArray("players")
        val players = (0 until playersJson.length()).map { index ->
            val item = playersJson.getJSONObject(index)
            val scoresJson = item.getJSONObject("scores")
            val scores = buildMap {
                val keys = scoresJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, scoresJson.getInt(key))
                }
            }
            RoundPlayer(item.getString("id"), item.getString("name"), scores)
        }
        return RoundScorecard(
            courseName = json.getString("courseName"),
            holePars = pars,
            players = players,
            userPlayerId = json.getString("userPlayerId"),
            startedAt = json.optLong("startedAt", System.currentTimeMillis()),
            activeHoleNumber = json.optString("activeHoleNumber", "1")
        )
    }
}
