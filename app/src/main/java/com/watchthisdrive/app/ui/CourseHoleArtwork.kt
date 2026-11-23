package com.watchthisdrive.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.watchthisdrive.app.data.GolfCourse
import com.watchthisdrive.app.data.CourseGeometry
import com.watchthisdrive.app.data.HoleReconstructor
import com.watchthisdrive.app.data.ReconstructedHole
import com.watchthisdrive.app.data.GeoPoint
import com.watchthisdrive.app.data.NaipImageryRepository
import java.io.File
import org.json.JSONObject

private data class HoleArtwork(val number: Int, val label: String, val assetPath: String)
private data class CourseArtworkPackage(val holes: List<HoleArtwork>)

private data class AerialReference(
    val bitmap: Bitmap,
    val bounds: NaipImageryRepository.Bounds,
    val cacheToken: String
)

private suspend fun loadAerialReference(
    context: android.content.Context,
    course: GolfCourse,
    geometry: CourseGeometry?,
    hole: ReconstructedHole? = null
): AerialReference? = withContext(Dispatchers.IO) {
    runCatching {
        val file = if (hole != null) {
            NaipImageryRepository.loadOrFetchForHole(context, course, hole)
        } else {
            NaipImageryRepository.loadOrFetch(context, course, geometry)
        } ?: return@runCatching null
        val bounds = if (hole != null) {
            NaipImageryRepository.boundsForHole(hole)
        } else {
            NaipImageryRepository.boundsFor(course, geometry)
        } ?: return@runCatching null
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@runCatching null
        AerialReference(bitmap, bounds, file.name + ":" + file.lastModified() + ":" + file.length())
    }.getOrNull()
}

/** Generic asset contract: course_holes/<OSM-type>-<OSM-id>/manifest.json plus its PNGs. */
private object CourseHoleArtworkRepository {
    private fun findAssetRoot(context: android.content.Context, course: GolfCourse): String? {
        val exact = "course_holes/" + course.elementType.lowercase() + "-" + course.id
        return exact.takeIf { root ->
            runCatching { context.assets.open(root + "/manifest.json").close() }.isSuccess
        }
    }
    fun load(context: android.content.Context, course: GolfCourse): CourseArtworkPackage? {
        val root = findAssetRoot(context, course) ?: return null
        val text = runCatching {
            context.assets.open(root + "/manifest.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return null
        return runCatching {
            val entries = JSONObject(text).optJSONArray("generated") ?: return null
            val holes = mutableListOf<HoleArtwork>()
            for (i in 0 until entries.length()) {
                val item = entries.optJSONObject(i) ?: continue
                val label = item.optString("hole_label").trim()
                val number = label.toIntOrNull() ?: item.optInt("candidate_index", i + 1)
                val image = item.optString("image").substringAfterLast('/').trim()
                if (number <= 0 || image.isBlank()) continue
                val asset = root + "/" + image
                if (runCatching { context.assets.open(asset).close() }.isSuccess) {
                    holes.add(HoleArtwork(number, label.ifBlank { number.toString() }, asset))
                }
            }
            CourseArtworkPackage(holes.sortedBy { it.number })
        }.getOrNull()
    }

    fun bitmap(context: android.content.Context, path: String): Bitmap? =
        runCatching { context.assets.open(path).use { BitmapFactory.decodeStream(it) } }.getOrNull()
}

@Composable
fun CourseHoleArtworkPanel(
    course: GolfCourse,
    geometry: CourseGeometry?,
    geometryError: String? = null
) {
    val context = LocalContext.current
    var artwork by remember(course.elementType, course.id) { mutableStateOf<CourseArtworkPackage?>(null) }
    var loading by remember(course.elementType, course.id) { mutableStateOf(true) }
    var selected by remember(course.elementType, course.id) { mutableStateOf<Int?>(null) }
    var image by remember(course.elementType, course.id) { mutableStateOf<Bitmap?>(null) }
    var generatedImages by remember(course.elementType, course.id) { mutableStateOf<Map<Int, File>>(emptyMap()) }
    var generating by remember(course.elementType, course.id) { mutableStateOf(false) }
    var renderError by remember(course.elementType, course.id) { mutableStateOf<String?>(null) }
    var aerialReference by remember(course.elementType, course.id) { mutableStateOf<AerialReference?>(null) }

    // Hole reconstruction can be CPU-heavy on large OSM courses. Keep it
    // off the main thread and share the result with the panel's render effect.
    var reconstructedHoles by remember(course.elementType, course.id) {
        mutableStateOf<List<ReconstructedHole>>(emptyList())
    }
    LaunchedEffect(course.elementType, course.id, geometry, selected, reconstructedHoles) {
        val hole = reconstructedHoles.firstOrNull { it.number == selected }
        aerialReference = loadAerialReference(context, course, geometry, hole)
    }
    LaunchedEffect(geometry, course.scorecard) {
        reconstructedHoles = emptyList()
        generatedImages = emptyMap()
        renderError = null
        val geometrySnapshot = geometry ?: return@LaunchedEffect
        val baseHoles = withContext(Dispatchers.Default) {
            HoleReconstructor.reconstruct(geometrySnapshot)
        }
        reconstructedHoles = baseHoles.map { hole ->
            val catalogHole = course.scorecard.firstOrNull { it.number == hole.number }
            hole.copy(
                par = hole.par ?: catalogHole?.par,
                handicap = hole.handicap ?: catalogHole?.handicap
            )
        }
    }

    LaunchedEffect(course.elementType, course.id) {
        loading = true
        try {
            val loaded = withContext(Dispatchers.IO) {
                CourseHoleArtworkRepository.load(context, course)
            }
            artwork = loaded
            selected = loaded?.holes?.firstOrNull()?.number
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            artwork = null
        } finally {
            loading = false
        }
    }
    LaunchedEffect(course.elementType, course.id, geometry, reconstructedHoles) {
        if (selected == null || reconstructedHoles.none { it.number == selected }) {
            selected = artwork?.holes?.firstOrNull()?.number ?: reconstructedHoles.firstOrNull()?.number
        }
    }

    // Render only the hole the golfer is viewing. Rendering an entire course at
    // panel-open time caused a long burst of CPU and bitmap allocation on phones.
    // The renderer's disk cache keeps revisited holes inexpensive.
    LaunchedEffect(course.elementType, course.id, geometry, selected, reconstructedHoles, aerialReference) {
        val hole = reconstructedHoles.firstOrNull { it.number == selected }
        if (hole != null && selected !in generatedImages) {
            generating = true
            try {
                val rendered = withContext(Dispatchers.Default) {
                    CourseHoleArtworkRenderer.render(
                        hole, context.cacheDir, course.elementType + "-" + course.id,
                        aerialReference?.bitmap, aerialReference?.bounds, aerialReference?.cacheToken.orEmpty()
                    )
                }
                generatedImages = generatedImages + (hole.number to rendered)
                renderError = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                renderError = error.localizedMessage
                    ?: "The hole illustration could not be generated."
            } finally {
                generating = false
            }
        }
    }

    LaunchedEffect(artwork, generatedImages, selected) {
        val hole = artwork?.holes?.firstOrNull { it.number == selected }
        val generated = selected?.let { generatedImages[it] }
        image = withContext(Dispatchers.IO) {
            when {
                generated != null -> BitmapFactory.decodeFile(generated.absolutePath)
                hole != null -> CourseHoleArtworkRepository.bitmap(context, hole.assetPath)
                else -> null
            }
        }
    }

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2520))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Illustrated holes", color = Color.White, style = MaterialTheme.typography.titleLarge)
            when {
                loading || generating -> CircularProgressIndicator(color = Color(0xFFB8F36B))
                artwork?.holes.isNullOrEmpty() && generatedImages.isEmpty() -> Text(
                    if (geometry == null && geometryError != null) {
                        "Course mapping is unavailable, so a hole illustration cannot be generated yet. $geometryError"
                    } else if (geometry == null) {
                        "Course geometry is loading. Hole illustrations will be generated when it arrives."
                    } else renderError
                        ?: "There is not enough mapped tee/green geometry to reconstruct holes for this course yet. Satellite maps and mapped course details remain available.",
                    color = if (renderError != null) Color(0xFFFFB4AB) else Color(0xFFB6C1B9))
                else -> {
                    val availableNumbers = (
                        artwork?.holes?.map { it.number }.orEmpty() +
                            reconstructedHoles.map { it.number } +
                            generatedImages.keys
                        ).distinct().sorted()
                    Text("${availableNumbers.size} hole views · ${generatedImages.size} generated on this device",
                        color = Color(0xFFB8F36B), style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        availableNumbers.forEach { number ->
                            val label = artwork?.holes?.firstOrNull { it.number == number }?.label ?: number.toString()
                            FilterChip(selected = selected == number,
                                onClick = { selected = number },
                                label = { Text("Hole " + label) })
                        }
                    }
                    val selectedHole = reconstructedHoles.firstOrNull { it.number == selected }
                    Text(
                        "HOLE ${selected ?: "—"} · ${selectedHole?.par?.let { "PAR $it" } ?: "PAR UNKNOWN"}" +
                            (selectedHole?.handicap?.let { " · HCP $it" } ?: ""),
                        color = Color.White, style = MaterialTheme.typography.titleMedium
                    )
                    if (image != null) {
                        Image(bitmap = image!!.asImageBitmap(),
                            contentDescription = "Illustrated view of hole " + selected,
                            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)),
                            contentScale = ContentScale.FillBounds)
                        Text("Tee at bottom · Green at top",
                            color = Color(0xFFB6C1B9), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("This hole's image couldn't be loaded. Choose another hole or try again later.",
                            color = Color(0xFFFFB4AB))
                    }
                }
            }
        }
    }
}


/**
 * Shows the generated illustration for the requested hole when one is available.
 * The caller supplies the existing live/reconstructed map as a safe fallback,
 * so Caddy never loses its location-aware view for courses without artwork.
 */
@Composable
fun CourseHoleArtworkForHole(
    course: GolfCourse,
    holeNumber: Int,
    reconstructedHole: ReconstructedHole? = null,
    geometry: CourseGeometry? = null,
    playerLocation: Location? = null,
    fallback: @Composable () -> Unit
) {
    val context = LocalContext.current
    var aerialReference by remember(course.elementType, course.id) { mutableStateOf<AerialReference?>(null) }
    LaunchedEffect(course.elementType, course.id, geometry, reconstructedHole) {
        aerialReference = loadAerialReference(context, course, geometry, reconstructedHole)
    }
    val playerMarker = remember(playerLocation?.latitude, playerLocation?.longitude, reconstructedHole) {
        if (playerLocation != null && reconstructedHole != null) {
            CourseHoleArtworkRenderer.project(
                reconstructedHole,
                GeoPoint(playerLocation.latitude, playerLocation.longitude)
            )
        } else null
    }
    var loading by remember(course.elementType, course.id, holeNumber, reconstructedHole) { mutableStateOf(true) }
    var bitmap by remember(course.elementType, course.id, holeNumber, reconstructedHole) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(course.elementType, course.id, holeNumber, reconstructedHole, aerialReference) {
        loading = true
        bitmap = null
        try {
            val pack = withContext(Dispatchers.IO) {
                CourseHoleArtworkRepository.load(context, course)
            }
            val entry = pack?.holes?.firstOrNull { it.number == holeNumber }
            val generatedFile = reconstructedHole?.let { hole ->
                withContext(Dispatchers.Default) {
                    CourseHoleArtworkRenderer.render(
                        hole, context.cacheDir, course.elementType + "-" + course.id,
                        aerialReference?.bitmap, aerialReference?.bounds, aerialReference?.cacheToken.orEmpty()
                    )
                }
            }
            bitmap = withContext(Dispatchers.IO) {
                generatedFile?.let { BitmapFactory.decodeFile(it.absolutePath) }
                    ?: entry?.let { CourseHoleArtworkRepository.bitmap(context, it.assetPath) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the existing GPS-aware fallback visible if rendering or
            // image decoding fails; never leave Play stuck on a spinner.
            bitmap = null
        } finally {
            loading = false
        }
    }

    when {
        loading -> Box(
            Modifier.fillMaxWidth().height(180.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            CircularProgressIndicator(color = Color(0xFFB8F36B))
        }
        bitmap != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "GENERATED HOLE ARTWORK",
                color = Color(0xFFB8F36B),
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                "HOLE $holeNumber · ${reconstructedHole?.par?.let { "PAR $it" } ?: "PAR UNKNOWN"}" +
                    (reconstructedHole?.handicap?.let { " · HCP $it" } ?: ""),
                color = Color.White, style = MaterialTheme.typography.titleMedium
            )
            Box(
                Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp))
            ) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "Illustrated view of hole $holeNumber",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds
                )
                playerMarker?.let { marker ->
                    Canvas(Modifier.fillMaxSize()) {
                        val center = Offset(marker.first * size.width, marker.second * size.height)
                        drawCircle(Color.White, radius = 14.dp.toPx(), center = center)
                        drawCircle(Color(0xFFB8F36B), radius = 8.dp.toPx(), center = center)
                        drawCircle(Color(0xFF173D2B), radius = 3.dp.toPx(), center = center)
                    }
                }
            }
            if (playerLocation != null && playerMarker == null) {
                Text("Your GPS position is outside this hole's illustrated view.", color = Color(0xFFB6C1B9), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Tee at bottom · Green at top",
                color = Color(0xFFB6C1B9),
                style = MaterialTheme.typography.bodySmall
            )
        }
        else -> fallback()
    }
}
