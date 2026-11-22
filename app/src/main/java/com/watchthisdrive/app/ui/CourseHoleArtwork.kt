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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.watchthisdrive.app.data.GolfCourse
import com.watchthisdrive.app.data.CourseGeometry
import com.watchthisdrive.app.data.HoleReconstructor
import com.watchthisdrive.app.data.ReconstructedHole
import java.io.File
import org.json.JSONObject

private data class HoleArtwork(val number: Int, val label: String, val assetPath: String)
private data class CourseArtworkPackage(val holes: List<HoleArtwork>)

/** Generic asset contract: course_holes/<OSM-type>-<OSM-id>/manifest.json plus its PNGs. */
private object CourseHoleArtworkRepository {
    private fun normalizedName(value: String): String =
        value.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]"), "")

    private fun findAssetRoot(context: android.content.Context, course: GolfCourse): String? {
        val exact = "course_holes/" + course.elementType.lowercase() + "-" + course.id
        if (runCatching { context.assets.open(exact + "/manifest.json").close() }.isSuccess) {
            return exact
        }

        // Search generated course packages by normalized name as a fallback.
        // Course discovery can return a different OSM way/relation for the same
        // named facility than the boundary element used when artwork was built.
        val folders = runCatching { context.assets.list("course_holes").orEmpty() }
            .getOrDefault(emptyArray())
        val matching = folders.mapNotNull { folder ->
            val root = "course_holes/" + folder
            val manifest = runCatching {
                context.assets.open(root + "/manifest.json").bufferedReader().use { it.readText() }
            }.getOrNull() ?: return@mapNotNull null
            val name = runCatching { JSONObject(manifest).optString("course") }.getOrNull()
            if (name != null && normalizedName(name) == normalizedName(course.name)) root else null
        }
        // Do not guess if multiple courses share the same name.
        return matching.singleOrNull()
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
fun CourseHoleArtworkPanel(course: GolfCourse, geometry: CourseGeometry?) {
    val context = LocalContext.current
    var artwork by remember(course.elementType, course.id) { mutableStateOf<CourseArtworkPackage?>(null) }
    var loading by remember(course.elementType, course.id) { mutableStateOf(true) }
    var selected by remember(course.elementType, course.id) { mutableStateOf<Int?>(null) }
    var image by remember(course.elementType, course.id) { mutableStateOf<Bitmap?>(null) }
    var generatedImages by remember(course.elementType, course.id) { mutableStateOf<Map<Int, File>>(emptyMap()) }
    var generating by remember(course.elementType, course.id) { mutableStateOf(false) }

    LaunchedEffect(course.elementType, course.id) {
        loading = true
        val loaded = withContext(Dispatchers.IO) {
            CourseHoleArtworkRepository.load(context, course)
        }
        artwork = loaded
        selected = loaded?.holes?.firstOrNull()?.number
        loading = false
    }
    LaunchedEffect(course.elementType, course.id, geometry, artwork) {
        val sourceGeometry = geometry
        if (sourceGeometry != null && artwork?.holes.isNullOrEmpty()) {
            generating = true
            generatedImages = withContext(Dispatchers.Default) {
                HoleReconstructor.reconstruct(sourceGeometry).associate { hole ->
                    hole.number to CourseHoleArtworkRenderer.render(hole, context.cacheDir, course.elementType + "-" + course.id)
                }
            }
            if (selected == null) selected = generatedImages.keys.minOrNull()
            generating = false
        }
    }

    LaunchedEffect(artwork, generatedImages, selected) {
        val hole = artwork?.holes?.firstOrNull { it.number == selected }
        val generated = selected?.let { generatedImages[it] }
        image = withContext(Dispatchers.IO) {
            when {
                hole != null -> CourseHoleArtworkRepository.bitmap(context, hole.assetPath)
                generated != null -> BitmapFactory.decodeFile(generated.absolutePath)
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
                    if (geometry == null) "Course geometry is loading. Hole illustrations will be generated when it arrives."
                    else "There is not enough mapped tee/green geometry to reconstruct holes for this course yet. Satellite maps and mapped course details remain available.",
                    color = Color(0xFFB6C1B9))
                else -> {
                    val availableNumbers = (artwork?.holes?.map { it.number }.orEmpty() + generatedImages.keys).distinct().sorted()
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
                    if (image != null) {
                        Image(bitmap = image!!.asImageBitmap(),
                            contentDescription = "Illustrated view of hole " + selected,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 520.dp),
                            contentScale = ContentScale.Fit)
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
    fallback: @Composable () -> Unit
) {
    val context = LocalContext.current
    var loading by remember(course.elementType, course.id, holeNumber, reconstructedHole) { mutableStateOf(true) }
    var bitmap by remember(course.elementType, course.id, holeNumber, reconstructedHole) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(course.elementType, course.id, holeNumber, reconstructedHole) {
        loading = true
        bitmap = withContext(Dispatchers.IO) {
            val pack = CourseHoleArtworkRepository.load(context, course)
            val entry = pack?.holes?.firstOrNull { it.number == holeNumber }
            val packaged = entry?.let { CourseHoleArtworkRepository.bitmap(context, it.assetPath) }
            if (packaged != null) packaged else reconstructedHole?.let { hole ->
                val file = CourseHoleArtworkRenderer.render(hole, context.cacheDir, course.elementType + "-" + course.id)
                BitmapFactory.decodeFile(file.absolutePath)
            }
        }
        loading = false
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
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "Illustrated view of hole $holeNumber",
                modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 520.dp),
                contentScale = ContentScale.Fit
            )
            Text(
                "Tee at bottom · Green at top",
                color = Color(0xFFB6C1B9),
                style = MaterialTheme.typography.bodySmall
            )
        }
        else -> fallback()
    }
}
