package com.watchthisdrive.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.watchthisdrive.app.data.GeoPoint
import com.watchthisdrive.app.data.ReconstructedHole
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.*

/**
 * Creates a standalone, tee-bottom/green-top hole illustration on the device.
 * Only mapped geometry is drawn as mapped; synthetic fairway corridors are
 * visibly softer and are never presented as surveyed course data.
 */
internal object CourseHoleArtworkRenderer {
    private const val WIDTH = 1200
    private const val HEIGHT = 1800

    fun render(hole: ReconstructedHole, cacheDirectory: File, cacheKey: String): File {
        val folder = File(cacheDirectory, "generated-course-holes").apply { mkdirs() }
        val signature = buildString {
            append(cacheKey).append('|').append(hole.number)
            append('|').append(hole.tee).append('|').append(hole.green)
            hole.mappedFairways.forEach { append('|').append(it.elementType).append(':').append(it.id) }
            hole.bunkers.forEach { append('|').append("b:").append(it.id) }
            hole.water.forEach { append('|').append("w:").append(it.id) }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        val output = File(folder, "$digest.png")
        if (output.exists() && output.length() > 0L) return output

        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF173323.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        val points = buildList {
            add(hole.tee); add(hole.green); addAll(hole.greenBoundary)
            addAll(hole.fairwayEstimate)
            hole.mappedFairways.forEach { addAll(it.points) }
            (hole.bunkers + hole.water).forEach { addAll(it.points) }
        }
        val latScale = 111320.0 * cos(Math.toRadians((hole.tee.latitude + hole.green.latitude) / 2))
        val lonScale = 111320.0
        val dx = (hole.green.longitude - hole.tee.longitude) * latScale
        val dy = (hole.green.latitude - hole.tee.latitude) * lonScale
        val length = hypot(dx, dy).coerceAtLeast(1.0)
        val ux = dx / length
        val uy = dy / length
        val vx = -uy
        val vy = ux
        val projected = points.map { point ->
            val east = (point.longitude - hole.tee.longitude) * latScale
            val north = (point.latitude - hole.tee.latitude) * lonScale
            (east * vx + north * vy) to (east * ux + north * uy)
        }
        val maxCross = max(80.0, projected.maxOfOrNull { abs(it.first) } ?: 80.0)
        val maxAlong = max(100.0, projected.maxOfOrNull { it.second } ?: 100.0)
        val scale = min((WIDTH - 180.0) / (2.0 * maxCross), (HEIGHT - 260.0) / maxAlong)
        val centerX = WIDTH / 2.0
        val bottomY = HEIGHT - 125.0
        fun xy(point: GeoPoint): Pair<Float, Float> {
            val east = (point.longitude - hole.tee.longitude) * latScale
            val north = (point.latitude - hole.tee.latitude) * lonScale
            val cross = east * vx + north * vy
            val along = east * ux + north * uy
            return (centerX + cross * scale).toFloat() to (bottomY - along * scale).toFloat()
        }
        fun drawPolygon(poly: List<GeoPoint>, fill: Int, stroke: Int, strokeWidth: Float = 5f) {
            if (poly.size < 3) return
            val path = Path()
            poly.forEachIndexed { index, point ->
                val (x, y) = xy(point)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            paint.color = fill; canvas.drawPath(path, paint)
            paint.color = stroke; paint.style = Paint.Style.STROKE; paint.strokeWidth = strokeWidth
            canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
        }
        fun marker(point: GeoPoint, color: Int, radius: Float) {
            val (x, y) = xy(point)
            paint.color = color; canvas.drawCircle(x, y, radius, paint)
            paint.color = 0xFFF6F3E8.toInt(); paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5f; canvas.drawCircle(x, y, radius, paint)
            paint.style = Paint.Style.FILL
        }
        // Subtle background bands keep the generated illustration readable.
        paint.color = 0xFF1D3B2B.toInt()
        for (i in 0..11) canvas.drawRect(0f, i * 155f, WIDTH.toFloat(), i * 155f + 2f, paint)

        if (hole.usesMappedFairway) {
            hole.mappedFairways.forEach { drawPolygon(it.points, 0xFF5D9E50.toInt(), 0xFFB5D99B.toInt(), 7f) }
        } else {
            drawPolygon(hole.fairwayEstimate, 0xCC6E9D58.toInt(), 0xFFD0DDB2.toInt(), 5f)
        }
        drawPolygon(hole.greenBoundary, 0xFF9BCB59.toInt(), 0xFFE4F2B9.toInt(), 8f)
        hole.water.forEach { feature ->
            if (feature.points.size >= 3) drawPolygon(feature.points, 0xFF287FA7.toInt(), 0xFF9ADCF0.toInt())
            else feature.points.forEach { marker(it, 0xFF287FA7.toInt(), 18f) }
        }
        hole.bunkers.forEach { feature ->
            if (feature.points.size >= 3) drawPolygon(feature.points, 0xFFD6B879.toInt(), 0xFFFFE9B5.toInt())
            else feature.points.forEach { marker(it, 0xFFD6B879.toInt(), 16f) }
        }
        marker(hole.tee, 0xFFF7F4E8.toInt(), 20f)
        marker(hole.green, 0xFFB8F36B.toInt(), 20f)

        paint.color = 0xFFF4F2E9.toInt()
        paint.textSize = 46f
        paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        canvas.drawText("HOLE ${hole.number}", 64f, 82f, paint)
        paint.textSize = 30f
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        canvas.drawText("${hole.lengthMeters.roundToInt()} m · tee to green", 64f, 130f, paint)
        paint.textSize = 26f
        paint.color = 0xFFBFD0C2.toInt()
        canvas.drawText(if (hole.usesMappedFairway) "Mapped fairway geometry" else "Estimated fairway corridor", 64f, HEIGHT - 42f, paint)

        FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return output
    }
}
