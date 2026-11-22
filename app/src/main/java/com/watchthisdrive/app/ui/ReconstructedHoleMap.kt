package com.watchthisdrive.app.ui

import android.location.Location
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.watchthisdrive.app.data.GeoPoint
import com.watchthisdrive.app.data.ReconstructedHole
import kotlin.math.*

/**
 * Clean schematic rendering of one reconstructed hole. Generated fairway
 * boundaries are explicitly labeled as estimates; mapped hazards retain a
 * separate visual treatment. GPS is projected into the same local coordinate
 * frame as the reconstructed geometry.
 */
@Composable
fun ReconstructedHoleMap(
    hole: ReconstructedHole,
    location: Location?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF142019))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("HOLE ${hole.number}", color = Color(0xFFB8F36B), style = MaterialTheme.typography.labelLarge)
        Text(
            "${hole.lengthMeters.roundToInt()} m · Reconstructed layout",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            "Fairway shape is an estimate · GPS is shown when near this hole",
            color = Color(0xFFCBD5CC),
            style = MaterialTheme.typography.bodySmall
        )
        HoleDrawing(
            hole = hole,
            location = location,
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendDot(Color(0xFF79B95B), "Estimated fairway")
            LegendDot(Color(0xFFB8F36B), "Green")
            LegendDot(Color(0xFFE6C77A), "Bunker")
            LegendDot(Color(0xFF43B9E8), "Water")
            LegendDot(Color(0xFF42A5F5), "You")
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(9.dp)) { drawCircle(color) }
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun HoleDrawing(
    hole: ReconstructedHole,
    location: Location?,
    modifier: Modifier = Modifier
) {
    val allPoints = buildList {
        add(hole.tee)
        add(hole.green)
        addAll(hole.greenBoundary)
        addAll(hole.fairwayEstimate)
        hole.bunkers.forEach { addAll(it.points) }
        hole.water.forEach { addAll(it.points) }
    }
    Canvas(modifier.background(Color(0xFF24432D), RoundedCornerShape(20.dp))) {
        if (allPoints.isEmpty()) return@Canvas
        val minLat = allPoints.minOf { it.latitude }
        val maxLat = allPoints.maxOf { it.latitude }
        val minLon = allPoints.minOf { it.longitude }
        val maxLon = allPoints.maxOf { it.longitude }
        val midLat = Math.toRadians((minLat + maxLat) / 2.0)
        val xRange = ((maxLon - minLon) * cos(midLat)).coerceAtLeast(0.00001)
        val yRange = (maxLat - minLat).coerceAtLeast(0.00001)
        val scale = min(size.width / xRange, size.height / yRange) * 0.82f
        val xOffset = (size.width - xRange * scale) / 2f
        val yOffset = (size.height - yRange * scale) / 2f
        fun project(point: GeoPoint): Offset {
            val x = (point.longitude - minLon) * cos(midLat) * scale + xOffset
            val y = (maxLat - point.latitude) * scale + yOffset
            return Offset(x.toFloat(), y.toFloat())
        }
        fun polygon(points: List<GeoPoint>, color: Color, outline: Color) {
            if (points.size < 3) return
            val path = Path().apply {
                moveTo(project(points.first()).x, project(points.first()).y)
                points.drop(1).forEach { val p = project(it); lineTo(p.x, p.y) }
                close()
            }
            drawPath(path, color)
            drawPath(path, outline, style = Stroke(width = 2.dp.toPx()))
        }

        // Decorative course background texture.
        for (i in 1..7) {
            val y = size.height * i / 8f
            drawLine(Color(0x14202F22), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        }
        polygon(hole.fairwayEstimate, Color(0xFF79B95B), Color(0xFFB5D99B))
        polygon(hole.greenBoundary, Color(0xFFB8F36B), Color(0xFFF0FFD8))

        (hole.bunkers + hole.water).forEach { hazard ->
            val water = hazard in hole.water
            val color = if (water) Color(0xFF43B9E8) else Color(0xFFE6C77A)
            val outline = if (water) Color(0xFFB8EFFF) else Color(0xFFFFF0C2)
            if (hazard.points.size >= 3) {
                polygon(hazard.points, color, outline)
            } else {
                hazard.points.forEach { point ->
                    drawCircle(color, radius = 7.dp.toPx(), center = project(point))
                    drawCircle(outline, radius = 7.dp.toPx(), center = project(point), style = Stroke(1.5.dp.toPx()))
                }
            }
        }

        // Tee anchor marker.
        val tee = project(hole.tee)
        drawCircle(Color.White, 8.dp.toPx(), tee)
        drawCircle(Color(0xFF24432D), 4.dp.toPx(), tee)

        // Current GPS marker, with an accuracy halo when available.
        if (location != null) {
            val gps = GeoPoint(location.latitude, location.longitude)
            // A distant GPS fix must never expand the bounds and shrink the hole.
            val latitudeMargin = max((maxLat - minLat) * 0.12, 0.00015)
            val longitudeMargin = max((maxLon - minLon) * 0.12, 0.00015)
            val nearHole = gps.latitude in (minLat - latitudeMargin)..(maxLat + latitudeMargin) &&
                gps.longitude in (minLon - longitudeMargin)..(maxLon + longitudeMargin)
            if (nearHole) {
                val raw = project(gps)
                val inset = 12.dp.toPx()
                val you = Offset(
                    raw.x.coerceIn(inset, size.width - inset),
                    raw.y.coerceIn(inset, size.height - inset)
                )
                val accuracyRadius = (location.accuracy / 2f).coerceIn(8.dp.toPx(), 34.dp.toPx())
                drawCircle(Color(0x3342A5F5), accuracyRadius, you)
                drawCircle(Color(0xFF42A5F5), 7.dp.toPx(), you)
                drawCircle(Color.White, 7.dp.toPx(), you, style = Stroke(2.dp.toPx()))
            }
        }
    }
}
