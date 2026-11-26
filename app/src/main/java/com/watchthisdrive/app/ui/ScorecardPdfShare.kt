package com.watchthisdrive.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.widget.Toast
import androidx.core.content.FileProvider
import com.watchthisdrive.app.data.RoundScorecard
import java.io.File
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date

object ScorecardPdfShare {
    fun share(context: Context, round: RoundScorecard, golferName: String = "") {
        try {
            val directory = File(context.cacheDir, "shared_scorecards").apply { mkdirs() }
            val safeName = round.courseName.replace(Regex("[^A-Za-z0-9_-]"), "_").take(36)
            val file = File(directory, "WTD_${safeName}_${round.startedAt}.pdf")
            val displayName = golferName.trim().takeIf { it.isNotEmpty() }
            val exportRound = if (displayName == null) round else round.copy(players = round.players.map { player ->
                if (player.id == round.userPlayerId) player.copy(name = displayName) else player
            })
            createPdf(file, exportRound)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Watch This Drive scorecard — ${round.courseName}")
                putExtra(Intent.EXTRA_TEXT, "Scorecard from Watch This Drive at ${round.courseName}.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Share scorecard"))
        } catch (problem: Exception) {
            Toast.makeText(context, "Couldn't prepare the scorecard PDF: ${problem.localizedMessage ?: "please try again"}", Toast.LENGTH_LONG).show()
        }
    }

    fun clear(context: Context) {
        runCatching { File(context.cacheDir, "shared_scorecards").deleteRecursively() }
    }

    private fun createPdf(file: File, round: RoundScorecard) {
        val pdf = PdfDocument()
        val pageWidth = 612
        val pageHeight = 792
        val dark = Color.rgb(16, 23, 19)
        val green = Color.rgb(184, 243, 107)
        val muted = Color.rgb(94, 111, 99)
        val holes = round.holePars.keys.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
        val players = round.players.ifEmpty { emptyList() }
        try {
            players.forEachIndexed { playerIndex, player ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, playerIndex + 1).create())
                val canvas = page.canvas
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.color = dark
                canvas.drawColor(Color.WHITE)

                paint.color = green
                paint.strokeWidth = 2.4f
                canvas.drawCircle(25f, 48f, 7f, paint)
                canvas.drawLine(25f, 55f, 25f, 70f, paint)
                canvas.drawLine(20f, 70f, 30f, 70f, paint)
                paint.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                paint.textSize = 25f
                canvas.drawText("WATCH THIS", 42f, 54f, paint)
                paint.color = green
                canvas.drawText("DRIVE", 42f, 82f, paint)
                paint.color = muted
                paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                paint.textSize = 8f
                canvas.drawText("GOLF COMPANION  •  OFFICIAL SCORECARD EXPORT", 43f, 99f, paint)

                paint.color = dark
                paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                paint.textSize = 19f
                canvas.drawText(round.courseName.take(48), 42f, 143f, paint)
                paint.textSize = 11f
                paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(round.startedAt))
                canvas.drawText("Golfer: ${player.name}     Date: $date", 42f, 165f, paint)

                val top = 202f
                val rowHeight = if (holes.size > 9) 24f else 30f
                val columns = listOf(42f, 125f, 270f, 420f)
                paint.color = dark
                canvas.drawRect(42f, top, 570f, top + rowHeight, paint)
                paint.color = Color.WHITE
                paint.typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
                paint.textSize = 10f
                canvas.drawText("HOLE", columns[0] + 8, top + 17, paint)
                canvas.drawText("PAR", columns[1] + 8, top + 17, paint)
                canvas.drawText("SCORE", columns[2] + 8, top + 17, paint)
                canvas.drawText("TO PAR", columns[3] + 8, top + 17, paint)

                var y = top + rowHeight
                var parTotal = 0
                var scoreTotal = 0
                var scoredCount = 0
                holes.forEachIndexed { index, hole ->
                    val par = round.holePars[hole]
                    val score = player.scores[hole]
                    if (par != null) parTotal += par
                    if (score != null && score > 0) { scoreTotal += score; scoredCount++ }
                    paint.color = if (index % 2 == 0) Color.rgb(242, 246, 242) else Color.WHITE
                    canvas.drawRect(42f, y, 570f, y + rowHeight, paint)
                    paint.color = dark
                    paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    paint.textSize = 11f
                    canvas.drawText(hole, columns[0] + 8, y + rowHeight * .68f, paint)
                    canvas.drawText(par?.toString() ?: "—", columns[1] + 8, y + rowHeight * .68f, paint)
                    canvas.drawText(score?.takeIf { it > 0 }?.toString() ?: "—", columns[2] + 8, y + rowHeight * .68f, paint)
                    val relative = if (score != null && par != null) score - par else null
                    canvas.drawText(relative?.let { if (it > 0) "+$it" else it.toString() } ?: "—", columns[3] + 8, y + rowHeight * .68f, paint)
                    y += rowHeight
                }

                y += 24f
                paint.color = dark
                paint.typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
                paint.textSize = 12f
                canvas.drawText("TOTAL", 50f, y, paint)
                canvas.drawText("PAR  $parTotal", 170f, y, paint)
                canvas.drawText("SCORE  ${if (scoredCount == 0) "—" else scoreTotal}", 310f, y, paint)
                val relativeTotal = if (scoredCount > 0) player.scores.filterKeys { round.holePars[it] != null }.entries.sumOf { it.value - (round.holePars[it.key] ?: 0) } else null
                canvas.drawText("TO PAR  ${relativeTotal?.let { if (it > 0) "+$it" else it.toString() } ?: "—"}", 440f, y, paint)

                paint.color = muted
                paint.textSize = 8f
                paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                canvas.drawText("Shared with Watch This Drive  •  Scores are recorded as entered by the golfer.", 42f, 750f, paint)
                canvas.drawText("watchthisdrive", 490f, 750f, paint)
                pdf.finishPage(page)
            }
            FileOutputStream(file).use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }
}
