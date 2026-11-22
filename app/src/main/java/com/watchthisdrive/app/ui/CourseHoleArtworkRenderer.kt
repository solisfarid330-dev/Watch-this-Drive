package com.watchthisdrive.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.watchthisdrive.app.data.GeoPoint
import com.watchthisdrive.app.data.ReconstructedHole
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.*

/** Rich tee-bottom/green-top artwork, fitted to the complete reconstructed hole. */
internal object CourseHoleArtworkRenderer {
    private const val VERSION = 4
    private const val WIDTH = 1200
    private const val HEIGHT = 1800
    private const val LEFT = 48.0
    private const val RIGHT = WIDTH - 48.0
    private const val TOP = 158.0
    private const val BOTTOM = HEIGHT - 112.0
    private data class XY(val x: Double, val y: Double)

    fun render(hole: ReconstructedHole, cacheDirectory: File, cacheKey: String): File {
        val folder = File(cacheDirectory, "generated-course-holes").apply { mkdirs() }
        val signature = buildString {
            append("renderer=").append(VERSION).append('|').append(cacheKey).append('|').append(hole.number)
            append('|').append(hole.tee).append('|').append(hole.green).append('|').append(hole.greenBoundary)
            append('|').append(hole.fairwayEstimate).append('|').append(hole.lengthMeters)
            hole.mappedFairways.forEach { append("|f:").append(it.id).append(it.points) }
            hole.bunkers.forEach { append("|b:").append(it.id).append(it.points) }
            hole.water.forEach { append("|w:").append(it.id).append(it.points) }
            hole.mappedCenterline?.let { append("|c:").append(it.id).append(it.points) }
            (hole.rough + hole.woodland + hole.paths + hole.trees).forEach { append("|s:").append(it.featureType).append(it.id).append(it.points) }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        val output = File(folder, digest + ".png")
        if (output.exists() && output.length() > 0L) return output

        val geographic = buildList {
            add(hole.tee); add(hole.green); addAll(hole.greenBoundary); addAll(hole.fairwayEstimate)
            hole.mappedFairways.forEach { addAll(it.points) }
            (hole.bunkers + hole.water).forEach { addAll(it.points) }
            hole.mappedCenterline?.let { addAll(it.points) }
        }.distinct()
        val midLat = Math.toRadians((hole.tee.latitude + hole.green.latitude) / 2.0)
        val lonScale = 111320.0 * cos(midLat)
        val latScale = 111320.0
        val east = (hole.green.longitude - hole.tee.longitude) * lonScale
        val north = (hole.green.latitude - hole.tee.latitude) * latScale
        val length = hypot(east, north).coerceAtLeast(1.0)
        val ux = east / length; val uy = north / length
        val vx = -uy; val vy = ux
        fun local(p: GeoPoint): XY {
            val e = (p.longitude - hole.tee.longitude) * lonScale
            val n = (p.latitude - hole.tee.latitude) * latScale
            return XY(e * vx + n * vy, e * ux + n * uy)
        }
        val projected = geographic.map(::local)
        // Use min AND max on both axes; include the tee, green, hazards and dogleg.
        val minX = min(0.0, projected.minOfOrNull { it.x } ?: 0.0)
        val maxX = max(0.0, projected.maxOfOrNull { it.x } ?: 0.0)
        val minY = min(0.0, projected.minOfOrNull { it.y } ?: 0.0)
        val maxY = max(length, projected.maxOfOrNull { it.y } ?: length)
        val rangeX = (maxX - minX).coerceAtLeast(30.0)
        val rangeY = (maxY - minY).coerceAtLeast(50.0)
        val scale = min((RIGHT - LEFT) / rangeX, (BOTTOM - TOP) / rangeY) * 0.975
        val ox = (WIDTH - rangeX * scale) / 2.0 - minX * scale
        val oy = (TOP + BOTTOM - rangeY * scale) / 2.0 + maxY * scale
        fun xy(p: GeoPoint): Pair<Float, Float> {
            val q = local(p)
            return (ox + q.x * scale).toFloat() to (oy - q.y * scale).toFloat()
        }

        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val full = RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat())

        // Deep green terrain with soft gradients and contour-like decorative texture.
        paint.shader = LinearGradient(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(),
            intArrayOf(0xFF183B2B.toInt(), 0xFF294D32.toInt(), 0xFF193A2A.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRect(full, paint); paint.shader = null
        paint.style = Paint.Style.STROKE
        for (i in 0..18) {
            val y = 195f + i * 86f
            paint.color = if (i % 2 == 0) 0x1D78A05D else 0x145F8D50
            paint.strokeWidth = if (i % 4 == 0) 3f else 1.5f
            val contour = Path().apply {
                moveTo(-40f, y)
                cubicTo(220f, y - 45f, 420f, y + 55f, 650f, y + 8f)
                cubicTo(870f, y - 42f, 1040f, y + 55f, 1240f, y - 4f)
            }
            canvas.drawPath(contour, paint)
        }
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(WIDTH / 2f, HEIGHT / 2f, HEIGHT * .72f,
            intArrayOf(0x00000000, 0x1A06170F), null, Shader.TileMode.CLAMP)
        canvas.drawRect(full, paint); paint.shader = null

        fun shape(points: List<GeoPoint>): Path? {
            if (points.size < 3) return null
            val p = points.map(::xy)
            return Path().apply {
                moveTo((p.first().first + p.last().first) / 2f, (p.first().second + p.last().second) / 2f)
                for (i in p.indices) {
                    val a = p[i]; val b = p[(i + 1) % p.size]
                    quadTo(a.first, a.second, (a.first + b.first) / 2f, (a.second + b.second) / 2f)
                }
                close()
            }
        }
        fun polygon(points: List<GeoPoint>, fill: Int, edge: Int, width: Float = 6f): Path? {
            val path = shape(points) ?: return null
            paint.setShadowLayer(13f, 0f, 9f, 0x88000000.toInt())
            paint.color = 0x44301E12
            canvas.drawPath(path, paint)
            paint.clearShadowLayer()
            paint.style = Paint.Style.FILL; paint.color = fill; canvas.drawPath(path, paint)
            paint.style = Paint.Style.STROKE; paint.color = edge; paint.strokeWidth = width
            paint.strokeJoin = Paint.Join.ROUND; canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            return path
        }
        fun drawLine(points: List<GeoPoint>, color: Int, width: Float, dashed: Boolean = false) {
            if (points.size < 2) return
            val path = Path()
            points.forEachIndexed { i, point ->
                val q = xy(point)
                if (i == 0) path.moveTo(q.first, q.second) else path.lineTo(q.first, q.second)
            }
            paint.style = Paint.Style.STROKE; paint.color = color; paint.strokeWidth = width
            paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
            if (dashed) paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 12f), 0f)
            canvas.drawPath(path, paint)
            paint.pathEffect = null; paint.style = Paint.Style.FILL
        }
        fun text(value: String, x: Float, y: Float, color: Int, size: Float, align: Paint.Align = Paint.Align.CENTER) {
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            paint.textSize = size; paint.textAlign = align
            paint.color = 0xAA10251A.toInt(); canvas.drawText(value, x + 1.5f, y + 2f, paint)
            paint.color = color; canvas.drawText(value, x, y, paint)
            paint.textAlign = Paint.Align.LEFT
        }

        // Mapped woodland and rough establish the surrounding course landscape.
        hole.woodland.forEach { feature ->
            val path = polygon(feature.points, 0xFF24432D.toInt(), 0xFF587A48.toInt(), 5f)
            if (path != null) {
                canvas.save(); canvas.clipPath(path)
                for (row in 0..12) for (col in 0..8) {
                    val x = 42f + col * 145f + (row % 2) * 48f
                    val y = 190f + row * 126f
                    paint.color = if ((row + col) % 3 == 0) 0x447FA15A else 0x33617F48
                    canvas.drawCircle(x, y, 19f + ((row * 7 + col * 3) % 11), paint)
                    paint.color = 0x226F9856
                    canvas.drawCircle(x - 5f, y - 6f, 9f, paint)
                }
                canvas.restore()
            }
        }
        hole.rough.forEach { feature ->
            polygon(feature.points, 0xFF4E7843.toInt(), 0x887FA36A.toInt(), 3f)
        }

        // Individual mapped trees use layered crowns and small cast shadows.
        hole.trees.forEachIndexed { index, feature ->
            feature.points.forEach { point ->
                val q = xy(point)
                val radius = 11f + (index % 4) * 2.5f
                paint.color = 0x55301D13
                canvas.drawCircle(q.first + 4f, q.second + 6f, radius + 2f, paint)
                paint.color = 0xFF183A29.toInt()
                canvas.drawCircle(q.first, q.second, radius, paint)
                paint.color = 0xFF315C39.toInt()
                canvas.drawCircle(q.first - radius * .22f, q.second - radius * .28f, radius * .67f, paint)
                paint.color = 0xFF527A46.toInt()
                canvas.drawCircle(q.first - radius * .32f, q.second - radius * .42f, radius * .31f, paint)
            }
        }

        // Water bodies, with soft shore outlines and ripples.
        hole.water.forEach { feature ->
            val path = polygon(feature.points, 0xFF247D9E.toInt(), 0xFF91D5DF.toInt(), 8f)
            if (path != null) {
                canvas.save(); canvas.clipPath(path)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = 0x347DE0E8
                for (i in 0..12) {
                    val y = 240f + i * 112f
                    canvas.drawArc(-120f, y, 1320f, y + 90f, 0f, 180f, false, paint)
                }
                paint.style = Paint.Style.FILL; canvas.restore()
            } else feature.points.forEach { point ->
                val q = xy(point); paint.color = 0xFF247D9E.toInt(); canvas.drawCircle(q.first, q.second, 22f, paint)
                paint.color = 0xFF91D5DF.toInt(); paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4f; canvas.drawCircle(q.first, q.second, 22f, paint); paint.style = Paint.Style.FILL
            }
        }

        // Mapped fairway polygons retain their exact footprint; inferred corridor is softer.
        val fairways = if (hole.usesMappedFairway) hole.mappedFairways.map { it.points } else listOf(hole.fairwayEstimate)
        fairways.forEach { points ->
            val path = polygon(points, if (hole.usesMappedFairway) 0xFF689C50.toInt() else 0xCC779A60.toInt(),
                0xFFD7E5B5.toInt(), 10f)
            if (path != null) {
                canvas.save(); canvas.clipPath(path)
                paint.shader = LinearGradient(0f, 0f, WIDTH.toFloat(), 0f,
                    intArrayOf(0x286BE078, 0x006BE078, 0x286BE078), null, Shader.TileMode.CLAMP)
                canvas.drawRect(full, paint); paint.shader = null
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 20f
                for (i in -2..20) {
                    paint.color = if (i % 2 == 0) 0x286EA85B else 0x16618E4E
                    val y = 180f + i * 94f
                    canvas.drawLine(0f, y, WIDTH.toFloat(), y + 24f, paint)
                }
                paint.style = Paint.Style.FILL; canvas.restore()
                paint.style = Paint.Style.STROKE; paint.color = 0x66F0F4C9
                paint.strokeWidth = 2f; canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
            }
        }

        // Green is distinct from fairway, with subtle contours and a soft highlight.
        val green = polygon(hole.greenBoundary, 0xFF9DBF65.toInt(), 0xFFF0F4C8.toInt(), 11f)
        if (green != null) {
            canvas.save(); canvas.clipPath(green)
            paint.shader = RadialGradient(WIDTH * .5f, TOP.toFloat() + 130f, 420f,
                intArrayOf(0x66E1F5A4, 0x229AC65B, 0x009AC65B), null, Shader.TileMode.CLAMP)
            canvas.drawRect(full, paint); paint.shader = null
            paint.style = Paint.Style.STROKE; paint.color = 0x32718E3D; paint.strokeWidth = 2f
            for (i in 1..5) canvas.drawOval(WIDTH * .5f - i * 30f, TOP.toFloat() + 90f - i * 14f,
                WIDTH * .5f + i * 30f, TOP.toFloat() + 90f + i * 14f, paint)
            paint.style = Paint.Style.FILL; canvas.restore()
        }

        // Bunkers: warm sand, sculpted lip and fine rake texture.
        hole.bunkers.forEach { feature ->
            val path = polygon(feature.points, 0xFFD9C18E.toInt(), 0xFFFFEAC0.toInt(), 7f)
            if (path != null) {
                canvas.save(); canvas.clipPath(path)
                paint.style = Paint.Style.STROKE; paint.color = 0x397F6941; paint.strokeWidth = 2f
                for (i in 0..10) {
                    val y = 220f + i * 96f
                    canvas.drawLine(0f, y, WIDTH.toFloat(), y + 36f, paint)
                }
                paint.style = Paint.Style.FILL; canvas.restore()
            } else feature.points.forEach { point ->
                val q = xy(point); paint.color = 0xFFD9C18E.toInt(); canvas.drawCircle(q.first, q.second, 19f, paint)
                paint.color = 0xFFFFEAC0.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f
                canvas.drawCircle(q.first, q.second, 19f, paint); paint.style = Paint.Style.FILL
            }
        }

        // Cart and walking paths use a pale edge with a warm center.
        hole.paths.forEach { feature ->
            drawLine(feature.points, 0xBFE6DCC2.toInt(), 10f)
            drawLine(feature.points, 0xFFE0D3AE.toInt(), 5f)
        }

        // Centerline is an unobtrusive routing hint, not a mapped fairway edge.
        hole.mappedCenterline?.let { drawLine(it.points, 0x99F8F0D8.toInt(), 4f, true) }

        // Tee pad and pin flag establish orientation at a glance.
        val tee = xy(hole.tee)
        paint.color = 0xFF263B2D.toInt()
        canvas.drawRoundRect(tee.first - 35f, tee.second - 19f, tee.first + 35f, tee.second + 19f, 9f, 9f, paint)
        paint.style = Paint.Style.STROKE; paint.color = 0xFFF4F0DA.toInt(); paint.strokeWidth = 4f
        canvas.drawRoundRect(tee.first - 35f, tee.second - 19f, tee.first + 35f, tee.second + 19f, 9f, 9f, paint)
        paint.style = Paint.Style.FILL
        val pin = xy(hole.green)
        paint.color = 0xFFF7F2E5.toInt(); paint.strokeWidth = 5f
        canvas.drawLine(pin.first, pin.second + 20f, pin.first, pin.second - 55f, paint)
        val flag = Path().apply {
            moveTo(pin.first, pin.second - 54f); lineTo(pin.first + 44f, pin.second - 40f)
            lineTo(pin.first, pin.second - 26f); close()
        }
        paint.color = 0xFFE65E4E.toInt(); canvas.drawPath(flag, paint)
        paint.color = 0xFFF7F2E5.toInt(); canvas.drawCircle(pin.first, pin.second + 20f, 8f, paint)

        // Header/footer are kept outside the fitted course viewport.
        paint.shader = LinearGradient(0f, 0f, 0f, 158f, 0xE610251D.toInt(), 0x0010251D, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), 160f, paint); paint.shader = null
        text("HOLE " + hole.number, 56f, 73f, 0xFFF7F4E9.toInt(), 48f, Paint.Align.LEFT)
        text((hole.lengthMeters * 1.0936133).roundToInt().toString() + " YDS", 56f, 124f, 0xFFD1E3C7.toInt(), 29f, Paint.Align.LEFT)
        text("TEE  →  GREEN", WIDTH - 56f, 76f, 0xFFB8D4B4.toInt(), 22f, Paint.Align.RIGHT)
        val scaleMeters = niceScaleMeters(150.0 / scale)
        val scalePixels = (scaleMeters * scale).toFloat()
        val sx = WIDTH - 66f; val sy = HEIGHT - 68f
        paint.color = 0xFFEAF0DF.toInt(); paint.strokeWidth = 5f
        canvas.drawLine(sx - scalePixels, sy, sx, sy, paint)
        canvas.drawLine(sx - scalePixels, sy - 9f, sx - scalePixels, sy + 9f, paint)
        canvas.drawLine(sx, sy - 9f, sx, sy + 9f, paint)
        text(scaleMeters.roundToInt().toString() + " m", sx - scalePixels / 2f, sy - 16f, 0xFFEAF0DF.toInt(), 20f)
        paint.shader = LinearGradient(0f, HEIGHT - 110f, 0f, HEIGHT.toFloat(),
            0x0010251D, 0xE610251D.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, HEIGHT - 112f, WIDTH.toFloat(), HEIGHT.toFloat(), paint); paint.shader = null
        text(if (hole.usesMappedFairway) "MAPPED FAIRWAY GEOMETRY" else "ESTIMATED FAIRWAY · NOT SURVEYED",
            56f, HEIGHT - 40f, 0xFFD4E1D0.toInt(), 20f, Paint.Align.LEFT)

        val temporary = File(folder, digest + "-" + Thread.currentThread().id + ".tmp")
        FileOutputStream(temporary).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        if (!temporary.renameTo(output)) {
            if (!output.exists()) temporary.copyTo(output, overwrite = true)
            temporary.delete()
        }
        return output
    }

    private fun niceScaleMeters(raw: Double): Double {
        val safe = raw.coerceAtLeast(1.0)
        val power = 10.0.pow(floor(log10(safe)))
        val normalized = safe / power
        val step = when { normalized < 1.5 -> 1.0; normalized < 3.5 -> 2.0; normalized < 7.5 -> 5.0; else -> 10.0 }
        return step * power
    }
}
