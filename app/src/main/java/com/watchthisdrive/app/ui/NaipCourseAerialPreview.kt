package com.watchthisdrive.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.watchthisdrive.app.data.CourseGeometry
import com.watchthisdrive.app.data.GolfCourse
import com.watchthisdrive.app.data.NaipImageryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun NaipCourseAerialPreview(course: GolfCourse, geometry: CourseGeometry?) {
    val context = LocalContext.current
    var bitmap by remember(course.elementType, course.id) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(course.elementType, course.id) { mutableStateOf(false) }

    LaunchedEffect(course.elementType, course.id, geometry) {
        // Wait for the selected course outline so the request is clipped to the
        // course instead of downloading an arbitrary rectangle around its point.
        if (geometry == null) return@LaunchedEffect
        loading = true
        bitmap = null
        try {
            val file = withContext(Dispatchers.IO) {
                NaipImageryRepository.loadOrFetch(context, course, geometry)
            }
            bitmap = withContext(Dispatchers.IO) {
                file?.let { BitmapFactory.decodeFile(it.absolutePath) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            bitmap = null
        } finally {
            loading = false
        }
    }

    if (loading || bitmap != null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2520))
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "NAIP AERIAL REFERENCE",
                    color = Color(0xFFB8F36B),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.fillMaxWidth()
                )
                if (loading) {
                    CircularProgressIndicator(color = Color(0xFFB8F36B))
                } else {
                    bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "USDA NAIP aerial reference for ${course.name}",
                            modifier = Modifier.fillMaxWidth().aspectRatio(1.2f).clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                Text(
                    "USDA NAIP imagery via USGS The National Map. Aerial reference only; capture dates vary by area and this image is not a surveyed course map.",
                    color = Color(0xFFB6C1B9),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
