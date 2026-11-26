package com.watchthisdrive.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.watchthisdrive.app.data.CourseFeature
import com.watchthisdrive.app.data.CourseFeatureOrigin
import com.watchthisdrive.app.data.CourseGeometry
import com.watchthisdrive.app.data.CourseGeometryType
import com.watchthisdrive.app.data.CourseReconstruction
import com.watchthisdrive.app.data.GeoPoint
import com.watchthisdrive.app.data.ReconstructedHole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

internal data class ArtworkBounds(
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double
)

internal data class ArtworkFeature(
    val id: Long,
    val featureType: String,
    val points: List<GeoPoint>,
    val source: String,
    val confidence: Double?,
    val isPredicted: Boolean
)

internal data class ArtworkHole(
    val number: Int,
    val tee: GeoPoint,
    val green: GeoPoint,
    val greenBoundary: List<GeoPoint>,
    val fairwayEstimate: List<GeoPoint>,
    val bunkers: List<ArtworkFeature>,
    val water: List<ArtworkFeature>,
    val teeSource: ArtworkFeature,
    val greenSource: ArtworkFeature,
    val lengthMeters: Double,
    val mappedFairways: List<ArtworkFeature> = emptyList(),
    val mappedCenterline: ArtworkFeature? = null,
    val rough: List<ArtworkFeature> = emptyList(),
    val woodland: List<ArtworkFeature> = emptyList(),
    val paths: List<ArtworkFeature> = emptyList(),
    val trees: List<ArtworkFeature> = emptyList(),
    val par: Int? = null,
    val handicap: Int? = null
) {
    val usesMappedFairway: Boolean get() = mappedFairways.isNotEmpty()
}

private fun CourseFeature.artworkPoints(): List<GeoPoint> =
    when (geometryType) {
        CourseGeometryType.POLYGON -> rings.firstOrNull() ?: points
        CourseGeometryType.MULTI_POLYGON -> polygons.firstOrNull()?.firstOrNull() ?: points
        else -> points
    }

private fun polygonCentroid(points: List<GeoPoint>): GeoPoint? {
    if (points.size < 3) return null
    val ring = if (points.first() == points.last()) points.dropLast(1) else points
    if (ring.size < 3) return null
    var twiceArea = 0.0
    var latitudeSum = 0.0
    var longitudeSum = 0.0
    ring.indices.forEach { index ->
        val current = ring[index]
        val next = ring[(index + 1) % ring.size]
        val cross = current.longitude * next.latitude - next.longitude * current.latitude
        twiceArea += cross
        longitudeSum += (current.longitude + next.longitude) * cross
        latitudeSum += (current.latitude + next.latitude) * cross
    }
    if (kotlin.math.abs(twiceArea) < 1e-12) return null
    return GeoPoint(latitudeSum / (3.0 * twiceArea), longitudeSum / (3.0 * twiceArea))
}

private fun CourseFeature.artworkCenter(): GeoPoint? {
    val points = artworkPoints()
    if (points.isEmpty()) return null
    if (geometryType == CourseGeometryType.POLYGON ||
        geometryType == CourseGeometryType.MULTI_POLYGON
    ) {
        polygonCentroid(points)?.let { return it }
    }
    return GeoPoint(points.map { it.latitude }.average(), points.map { it.longitude }.average())
}

private fun CourseFeature.toArtworkFeature(): ArtworkFeature = ArtworkFeature(
    id = id,
    featureType = featureType,
    points = artworkPoints(),
    source = if (origin == CourseFeatureOrigin.SOURCE) "Mapped course data" else "Inferred geometry",
    confidence = confidence,
    isPredicted = origin == CourseFeatureOrigin.INFERRED
)

private fun metersBetween(a: GeoPoint, b: GeoPoint): Double {
    val lat = Math.toRadians((a.latitude + b.latitude) / 2.0)
    val dx = (b.longitude - a.longitude) * 111320.0 * cos(lat)
    val dy = (b.latitude - a.latitude) * 111320.0
    return hypot(dx, dy)
}

private fun distanceToRouteMeters(point: GeoPoint, route: List<GeoPoint>): Double {
    if (route.size < 2) return route.firstOrNull()?.let { metersBetween(point, it) } ?: Double.POSITIVE_INFINITY
    return route.zipWithNext().minOf { (a, b) ->
        val latitude = Math.toRadians((point.latitude + a.latitude + b.latitude) / 3.0)
        val scaleX = 111320.0 * cos(latitude)
        val px = (point.longitude - a.longitude) * scaleX
        val py = (point.latitude - a.latitude) * 111320.0
        val bx = (b.longitude - a.longitude) * scaleX
        val by = (b.latitude - a.latitude) * 111320.0
        val lengthSquared = bx * bx + by * by
        val t = if (lengthSquared <= 1e-9) 0.0 else ((px * bx + py * by) / lengthSquared).coerceIn(0.0, 1.0)
        hypot(px - t * bx, py - t * by)
    }
}

private fun CourseFeature.isNearHoleRoute(route: List<GeoPoint>): Boolean {
    val points = artworkPoints()
    if (points.isEmpty() || route.size < 2) return false
    val sample = points + artworkCenter().orEmptyPoint()
    return sample.any { distanceToRouteMeters(it, route) <= 85.0 }
}

private fun GeoPoint?.orEmptyPoint(): List<GeoPoint> = this?.let(::listOf).orEmpty()


private fun ReconstructedHole.toArtworkHole(geometry: CourseGeometry): ArtworkHole? {
    val teeFeature = tee ?: return null
    val greenFeature = green ?: return null
    val teePoint = teeFeature.artworkCenter() ?: return null
    val greenPoint = greenFeature.artworkCenter() ?: return null
    val fairwayFeatures = fairways.map { it.toArtworkFeature() }
    val mappedFairways = fairwayFeatures.filterNot { it.isPredicted }
    val inferredFairway = fairwayFeatures.firstOrNull { it.isPredicted }?.points.orEmpty()
    val centerlineFeature = geometry.features.firstOrNull {
        it.featureType == "hole" && it.holeNumber == number && it.geometryType == CourseGeometryType.LINE
    }?.toArtworkFeature() ?: centerline.takeIf { it.size >= 2 }?.let {
        ArtworkFeature(number.toLong(), "hole", it, "Mapped hole line", null, false)
    }
    val length = centerline.zipWithNext().sumOf { (a, b) -> metersBetween(a, b) }
        .takeIf { it > 0.0 } ?: metersBetween(teePoint, greenPoint)
    val mappedLandscape = geometry.features.filter { feature ->
        feature.origin == CourseFeatureOrigin.SOURCE &&
            feature.featureType.lowercase() in setOf("rough", "woodland", "forest", "path", "cart_path", "tree", "trees") &&
            feature.isNearHoleRoute(centerline.ifEmpty { listOf(teePoint, greenPoint) })
    }.map { it.toArtworkFeature() }
    return ArtworkHole(
        number = number.toIntOrNull() ?: return null,
        tee = teePoint,
        green = greenPoint,
        greenBoundary = greenFeature.artworkPoints(),
        fairwayEstimate = inferredFairway,
        bunkers = bunkers.map { it.toArtworkFeature() },
        water = waterHazards.map { it.toArtworkFeature() },
        teeSource = teeFeature.toArtworkFeature(),
        greenSource = greenFeature.toArtworkFeature(),
        lengthMeters = length,
        mappedFairways = mappedFairways,
        mappedCenterline = centerlineFeature,
        rough = mappedLandscape.filter { it.featureType.equals("rough", true) },
        woodland = mappedLandscape.filter { it.featureType.equals("woodland", true) || it.featureType.equals("forest", true) },
        paths = mappedLandscape.filter { it.featureType.equals("path", true) || it.featureType.equals("cart_path", true) },
        trees = mappedLandscape.filter { it.featureType.equals("tree", true) || it.featureType.equals("trees", true) },
        par = par
    )
}

@Composable
private fun CourseHoleArtworkImage(
    hole: ReconstructedHole,
    geometry: CourseGeometry,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val artworkHole = remember(hole, geometry) { hole.toArtworkHole(geometry) }
    var bitmap by remember(geometry.courseId, hole.number) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(geometry.courseId, hole.number) { mutableStateOf(false) }
    LaunchedEffect(artworkHole, geometry.courseId) {
        bitmap = null
        failed = false
        if (artworkHole == null) {
            failed = true
            return@LaunchedEffect
        }
        try {
            val file = withContext(Dispatchers.Default) {
                CourseHoleArtworkRenderer.render(
                    artworkHole,
                    context.cacheDir,
                    "course-${geometry.courseId}"
                )
            }
            bitmap = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(file.absolutePath)
            }
            failed = bitmap == null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    when {
        bitmap != null -> Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "Illustrated view of hole ${hole.number}",
            modifier = modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)),
            contentScale = ContentScale.FillBounds
        )
        failed -> Text(
            "There isn't enough reliable tee and green geometry to illustrate this hole yet. The mapped course view remains available.",
            color = Color(0xFFFFC857),
            style = MaterialTheme.typography.bodySmall
        )
        else -> Box(
            modifier.fillMaxWidth().aspectRatio(2f / 3f),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) { CircularProgressIndicator(color = Color(0xFFB8F36B)) }
    }
}

/** Dynamically renders every reconstructable hole from the selected course's current map data. */
@Composable
fun CourseHoleArtworkPanel(
    geometry: CourseGeometry?,
    reconstruction: CourseReconstruction?
) {
    val holes = reconstruction?.holes.orEmpty()
    var selectedNumber by remember(geometry?.courseId) {
        mutableStateOf(holes.firstOrNull()?.number)
    }
    LaunchedEffect(holes) {
        if (selectedNumber == null || holes.none { it.number == selectedNumber }) {
            selectedNumber = holes.firstOrNull()?.number
        }
    }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2520))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Illustrated holes", color = Color.White, style = MaterialTheme.typography.titleLarge)
            when {
                geometry == null -> Text(
                    "Hole illustrations will be generated when course geometry is ready.",
                    color = Color(0xFFB6C1B9)
                )
                holes.isEmpty() -> Text(
                    "There isn't enough reliable tee and green geometry to illustrate this course yet. WTD won't invent missing hole layouts.",
                    color = Color(0xFFB6C1B9)
                )
                else -> {
                    Text(
                        "${holes.size} reconstructed hole views · generated on this device",
                        color = Color(0xFFB8F36B),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        holes.forEach { hole ->
                            FilterChip(
                                selected = selectedNumber == hole.number,
                                onClick = { selectedNumber = hole.number },
                                label = { Text("Hole ${hole.number}") }
                            )
                        }
                    }
                    val selected = holes.firstOrNull { it.number == selectedNumber }
                    if (selected != null) {
                        Text(
                            "HOLE ${selected.number} · ${selected.par?.let { "PAR $it" } ?: "PAR UNKNOWN"}",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium
                        )
                        CourseHoleArtworkImage(selected, geometry)
                        Text(
                            "Tee at bottom · Green at top. Mapped features retain their source positions; inferred geometry is shown as approximate.",
                            color = Color(0xFFB6C1B9),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

/** Opens the same cached illustration from the in-game Caddy panel without changing its layout. */
@Composable
fun CourseHoleArtworkDialog(
    hole: ReconstructedHole,
    geometry: CourseGeometry?,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Color(0xFF18231C),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("Hole ${hole.number} illustration", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text("CLOSE", color = Color(0xFFB8F36B)) }
                }
                if (geometry != null) CourseHoleArtworkImage(hole, geometry)
                else Text("Course geometry is unavailable for this hole.", color = Color(0xFFB6C1B9))
            }
        }
    }
}
