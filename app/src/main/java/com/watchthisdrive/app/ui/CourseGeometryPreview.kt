package com.watchthisdrive.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.watchthisdrive.app.data.CourseFeature
import com.watchthisdrive.app.data.CourseGeometry
import com.watchthisdrive.app.data.CourseGeometryType
import com.watchthisdrive.app.data.GeoPoint

@Composable
fun CourseGeometryPreview(geometry: CourseGeometry, modifier: Modifier = Modifier) {
    val allPoints = geometry.features.flatMap { feature ->
        when (feature.geometryType) {
            CourseGeometryType.POINT, CourseGeometryType.LINE -> feature.points
            CourseGeometryType.POLYGON -> feature.rings.flatten()
            CourseGeometryType.MULTI_POLYGON -> feature.polygons.flatten().flatten()
            CourseGeometryType.UNKNOWN -> emptyList()
        }
    }
    if (allPoints.isEmpty()) return
    val minLat = allPoints.minOf { it.latitude }
    val maxLat = allPoints.maxOf { it.latitude }
    val minLon = allPoints.minOf { it.longitude }
    val maxLon = allPoints.maxOf { it.longitude }
    val latSpan = (maxLat - minLat).coerceAtLeast(0.00001)
    val lonSpan = (maxLon - minLon).coerceAtLeast(0.00001)
    val midLat = (minLat + maxLat) / 2.0
    val aspectCorrectedLonSpan = lonSpan * kotlin.math.cos(Math.toRadians(midLat)).coerceAtLeast(0.1)
    val bounds = Bounds(minLat, maxLat, minLon, maxLon, latSpan, lonSpan, aspectCorrectedLonSpan)

    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF14221A))) {
        Canvas(Modifier.fillMaxWidth().height(250.dp)) {
            val pad = 18.dp.toPx()
            val usableWidth = (size.width - 2 * pad).coerceAtLeast(1f)
            val usableHeight = (size.height - 2 * pad).coerceAtLeast(1f)
            val worldWidth = bounds.aspectCorrectedLonSpan
            val worldHeight = bounds.latSpan
            val scale = minOf(usableWidth / worldWidth.toFloat(), usableHeight / worldHeight.toFloat())
            val drawnWidth = worldWidth.toFloat() * scale
            val drawnHeight = worldHeight.toFloat() * scale
            val left = (size.width - drawnWidth) / 2f
            val top = (size.height - drawnHeight) / 2f

            fun screen(point: GeoPoint): Offset {
                val xWorld = (point.longitude - bounds.minLon) *
                    kotlin.math.cos(Math.toRadians(bounds.midLat)).coerceAtLeast(0.1)
                val yWorld = bounds.maxLat - point.latitude
                return Offset(left + xWorld.toFloat() * scale, top + yWorld.toFloat() * scale)
            }

            fun pathFor(rings: List<List<GeoPoint>>): Path {
                val path = Path().apply { fillType = PathFillType.EvenOdd }
                rings.forEach { ring ->
                    if (ring.isNotEmpty()) {
                        val first = screen(ring.first())
                        path.moveTo(first.x, first.y)
                        ring.drop(1).forEach { p -> screen(p).let { path.lineTo(it.x, it.y) } }
                        path.close()
                    }
                }
                return path
            }

            val drawOrder = listOf("rough", "water_hazard", "fairway", "bunker", "green", "tee")
            drawOrder.forEach { type ->
                geometry.features.filter { it.featureType == type }.forEach { feature ->
                    val color = featureColor(type)
                    when (feature.geometryType) {
                        CourseGeometryType.POLYGON -> {
                            val p = pathFor(feature.rings)
                            drawPath(p, color.copy(alpha = 0.85f))
                            drawPath(p, color.copy(alpha = 0.95f), style = Stroke(width = 1.2.dp.toPx()))
                        }
                        CourseGeometryType.MULTI_POLYGON -> feature.polygons.forEach { polygon ->
                            val p = pathFor(polygon)
                            drawPath(p, color.copy(alpha = 0.85f))
                            drawPath(p, color.copy(alpha = 0.95f), style = Stroke(width = 1.2.dp.toPx()))
                        }
                        else -> Unit
                    }
                }
            }

            geometry.features.filter { it.featureType == "hole" }.forEach { feature ->
                if (feature.geometryType == CourseGeometryType.LINE && feature.points.size >= 2) {
                    val path = Path()
                    feature.points.forEachIndexed { index, point ->
                        val p = screen(point)
                        if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    drawPath(path, Color.White.copy(alpha = 0.9f), style = Stroke(width = 2.dp.toPx()))
                }
            }

            geometry.features.filter { it.geometryType == CourseGeometryType.POINT }.forEach { feature ->
                val color = featureColor(feature.featureType)
                feature.points.firstOrNull()?.let { drawCircle(color, radius = 3.dp.toPx(), center = screen(it)) }
            }
        }
    }
}

private data class Bounds(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    val latSpan: Double,
    val lonSpan: Double,
    val aspectCorrectedLonSpan: Double
)

private fun featureColor(type: String): Color = when (type) {
    "rough" -> Color(0xFF426B43)
    "water_hazard" -> Color(0xFF368CB5)
    "fairway" -> Color(0xFF8DBB65)
    "bunker" -> Color(0xFFD8C28B)
    "green" -> Color(0xFFB8F36B)
    "tee" -> Color(0xFFE5EAD7)
    "pin" -> Color(0xFFFF6B6B)
    else -> Color(0xFFB6C1B9)
}
