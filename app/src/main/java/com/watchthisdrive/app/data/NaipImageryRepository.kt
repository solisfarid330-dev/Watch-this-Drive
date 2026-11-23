package com.watchthisdrive.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.max

/**
 * Fetches georeferenced USDA NAIP imagery and a matching NDVI product for
 * course-hole analysis. The service is four-band (RGB + NIR); the NDVI raster
 * is generated from the source bands, not from the rendered RGB photograph.
 */
object NaipImageryRepository {
    private const val IMAGE_SERVER =
        "https://imagery.nationalmap.gov/arcgis/rest/services/USGSNAIPImagery/ImageServer"
    private const val CACHE_MAX_AGE_MS = 180L * 24L * 60L * 60L * 1000L
    private const val MAX_EXPORT_DIMENSION = 4_000
    private const val MAX_IMAGE_BYTES = 24 * 1024 * 1024
    private const val MAX_METADATA_BYTES = 512 * 1024

    data class Bounds(
        val west: Double,
        val south: Double,
        val east: Double,
        val north: Double
    )

    data class ImagerySelection(
        val rasterIds: List<Long>,
        val year: Int?,
        val acquisitionDateEpochMs: Long?,
        val resolutionMeters: Double?,
        val bandCount: Int
    ) {
        fun mosaicRule(): String? = rasterIds.takeIf { it.isNotEmpty() }?.let { ids ->
            JSONObject()
                .put("mosaicMethod", "esriMosaicLockRaster")
                .put("lockRasterIds", JSONArray(ids))
                .put("mosaicOperation", "MT_FIRST")
                .toString()
        }
    }

    private enum class Product(val suffix: String, val format: String, val rasterFunction: String) {
        NATURAL_COLOR(".jpg", "jpg", "NaturalColor"),
        NDVI(".ndvi.png", "png", "NDVI_Color")
    }

    private val selectionCache = ConcurrentHashMap<String, ImagerySelection>()
    internal data class RasterRecord(
        val id: Long,
        val year: Int?,
        val acquisitionDateEpochMs: Long?,
        val resolutionValue: Double?,
        val resolutionUnits: String?,
        val bandCount: Int
    )

    /** Newest acquisition wins; within that vintage, prefer finest native GSD. */
    internal fun selectBestRasters(records: List<RasterRecord>): ImagerySelection? {
        val valid = records.filter { it.id > 0 && it.bandCount >= 4 }
        if (valid.isEmpty()) return null
        val latestYear = valid.mapNotNull { it.year }.maxOrNull()
        val vintage = if (latestYear != null) valid.filter { it.year == latestYear }
            else {
                val latestDate = valid.mapNotNull { it.acquisitionDateEpochMs }.maxOrNull()
                if (latestDate != null) valid.filter { it.acquisitionDateEpochMs == latestDate } else valid
            }
        fun meters(record: RasterRecord): Double? {
            val value = record.resolutionValue?.takeIf { it.isFinite() && it > 0 } ?: return null
            return when (record.resolutionUnits?.lowercase(Locale.US)) {
                "m", "meter", "meters" -> value
                "ft", "feet", "foot" -> value * 0.3048
                else -> value
            }
        }
        val bestGsd = vintage.mapNotNull(::meters).minOrNull()
        val selected = if (bestGsd == null) vintage else vintage.filter {
            meters(it)?.let { gsd -> gsd <= bestGsd * 1.05 } == true
        }
        return ImagerySelection(
            rasterIds = selected.map { it.id }.distinct(),
            year = latestYear,
            acquisitionDateEpochMs = selected.mapNotNull { it.acquisitionDateEpochMs }.maxOrNull(),
            resolutionMeters = selected.mapNotNull(::meters).minOrNull(),
            bandCount = selected.minOf { it.bandCount }
        )
    }


    fun loadOrFetch(
        context: Context,
        course: GolfCourse,
        geometry: CourseGeometry?
    ): File? {
        val bounds = boundsFor(course, geometry) ?: return null
        return loadOrFetchBounds(context, course, bounds, "course", Product.NATURAL_COLOR)
    }

    /** Fetch a georeferenced natural-colour aerial reference for one hole. */
    fun loadOrFetchForHole(
        context: Context,
        course: GolfCourse,
        hole: ReconstructedHole
    ): File? {
        val bounds = boundsForHole(hole) ?: return null
        return loadOrFetchBounds(context, course, bounds, "hole-${hole.number}", Product.NATURAL_COLOR)
    }

    /**
     * Fetches an NDVI colour composite from the same locked NAIP source rasters
     * as the natural-colour image. It helps distinguish vigorous vegetation
     * from sparse vegetation/bare surfaces; it is not a semantic golf map.
     */
    fun loadOrFetchNdviForHole(
        context: Context,
        course: GolfCourse,
        hole: ReconstructedHole
    ): File? {
        val bounds = boundsForHole(hole) ?: return null
        return loadOrFetchBounds(context, course, bounds, "hole-${hole.number}", Product.NDVI)
    }

    fun boundsForHole(hole: ReconstructedHole): Bounds? {
        val points = buildList {
            add(hole.tee); add(hole.green); addAll(hole.greenBoundary)
            addAll(hole.fairwayEstimate)
            hole.mappedFairways.forEach { addAll(it.points) }
            hole.mappedCenterline?.let { addAll(it.points) }
            (hole.bunkers + hole.water + hole.rough + hole.woodland + hole.paths + hole.trees)
                .forEach { addAll(it.points) }
        }.filter { it.latitude.isFinite() && it.longitude.isFinite() &&
            it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
        if (points.size < 2) return null
        val west = points.minOf { it.longitude }; val east = points.maxOf { it.longitude }
        val south = points.minOf { it.latitude }; val north = points.maxOf { it.latitude }
        val midLat = Math.toRadians((south + north) / 2.0)
        val widthMeters = (east - west) * 111_320.0 * cos(midLat).coerceAtLeast(0.1)
        val heightMeters = (north - south) * 111_320.0
        if (widthMeters > 1_500.0 || heightMeters > 1_500.0) return null
        val latPad = max(0.0005, max(55.0, heightMeters * 0.14) / 111_320.0)
        val lonPad = max(0.0005, max(55.0, widthMeters * 0.14) /
            (111_320.0 * cos(midLat).coerceAtLeast(0.1)))
        return Bounds(
            west = (west - lonPad).coerceAtLeast(-180.0),
            south = (south - latPad).coerceAtLeast(-90.0),
            east = (east + lonPad).coerceAtMost(180.0),
            north = (north + latPad).coerceAtMost(90.0)
        )
    }

    private fun loadOrFetchBounds(
        context: Context,
        course: GolfCourse,
        bounds: Bounds,
        scope: String,
        product: Product
    ): File? {
        if (!isWithinConus(course.latitude, course.longitude)) return null
        val key = listOf(
            "naip4b-v3-$MAX_EXPORT_DIMENSION", course.elementType, course.id, scope,
            "%.5f".format(Locale.US, bounds.west),
            "%.5f".format(Locale.US, bounds.south),
            "%.5f".format(Locale.US, bounds.east),
            "%.5f".format(Locale.US, bounds.north)
        ).joinToString("_").hashCode().toUInt().toString(16)
        val directory = File(context.cacheDir, "naip").apply { mkdirs() }
        val imageFile = File(directory, "course_$key${product.suffix}")
        val metadataFile = File(directory, "course_$key.json")
        if (imageFile.exists() && imageFile.length() > 0 &&
            System.currentTimeMillis() - imageFile.lastModified() < CACHE_MAX_AGE_MS) return imageFile

        val widthMeters = (bounds.east - bounds.west) * 111_320.0 *
            cos(Math.toRadians(course.latitude)).coerceAtLeast(0.1)
        val heightMeters = (bounds.north - bounds.south) * 111_320.0
        if (widthMeters <= 0.0 || heightMeters <= 0.0) return null
        val aspect = widthMeters / heightMeters
        val width = if (aspect >= 1.0) MAX_EXPORT_DIMENSION else
            (MAX_EXPORT_DIMENSION * aspect).toInt().coerceIn(256, MAX_EXPORT_DIMENSION)
        val height = if (aspect >= 1.0)
            (MAX_EXPORT_DIMENSION / aspect).toInt().coerceIn(256, MAX_EXPORT_DIMENSION)
        else MAX_EXPORT_DIMENSION

        val selection = selectionFor(bounds, key, metadataFile)
        val params = linkedMapOf(
            "bbox" to "${bounds.west},${bounds.south},${bounds.east},${bounds.north}",
            "bboxSR" to "4326",
            "imageSR" to "4326",
            "size" to "$width,$height",
            "format" to product.format,
            "pixelType" to "U8",
            "compressionQuality" to "95",
            "renderingRule" to JSONObject().put("rasterFunction", product.rasterFunction).toString(),
            "f" to "image"
        )
        selection.mosaicRule()?.let { params["mosaicRule"] = it }
        val requestUrl = IMAGE_SERVER + "/exportImage?" + params.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }
        val temporary = File(directory, imageFile.name + ".tmp")
        val connection = URL(requestUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 25_000
            connection.setRequestProperty("Accept", if (product == Product.NDVI) "image/png" else "image/jpeg")
            connection.setRequestProperty("User-Agent", "WatchThisDrive/0.4 (USDA NAIP public imagery)")
            val code = connection.responseCode
            if (code !in 200..299) return null
            if (!connection.contentType.orEmpty().lowercase(Locale.US).startsWith("image/")) return null
            var total = 0
            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_IMAGE_BYTES) {
                            temporary.delete()
                            return null
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (total == 0) { temporary.delete(); return null }
            if (!temporary.renameTo(imageFile)) {
                temporary.copyTo(imageFile, overwrite = true)
                temporary.delete()
            }
            if (!metadataFile.exists() || product == Product.NATURAL_COLOR) {
                val metadata = JSONObject()
                    .put("source", IMAGE_SERVER)
                    .put("provider", "USDA NAIP via USGS The National Map")
                    .put("imageryProduct", "USDA NAIP four-band aerial imagery")
                    .put("bandCount", selection.bandCount)
                    .put("acquisitionYear", selection.year ?: JSONObject.NULL)
                    .put("acquisitionDateEpochMs", selection.acquisitionDateEpochMs ?: JSONObject.NULL)
                    .put("resolutionMeters", selection.resolutionMeters ?: JSONObject.NULL)
                    .put("rasterIds", JSONArray(selection.rasterIds))
                    .put("maxExportDimension", MAX_EXPORT_DIMENSION)
                    .put("compressionQuality", 95)
                    .put("rgbRenderingRule", "NaturalColor")
                    .put("analysisRenderingRule", "NDVI_Color")
                    .put("attribution", "USDA Farm Production and Conservation Business Center, Geospatial Enterprise Operations")
                    .put("license", "Public domain; source credit requested")
                    .put("retrievedAtEpochMs", System.currentTimeMillis())
                    .put("scope", scope)
                    .put("bboxWgs84", JSONArray(listOf(bounds.west, bounds.south, bounds.east, bounds.north)))
                    .put("width", width).put("height", height)
                metadataFile.writeText(metadata.toString())
            }
            return imageFile
        } catch (_: Exception) {
            temporary.delete()
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun selectionFor(bounds: Bounds, key: String, metadataFile: File): ImagerySelection {
        selectionCache[key]?.let { return it }
        if (metadataFile.exists() && metadataFile.length() <= MAX_METADATA_BYTES) {
            runCatching {
                val json = JSONObject(metadataFile.readText())
                val ids = json.optJSONArray("rasterIds") ?: JSONArray()
                val parsedIds = (0 until ids.length()).mapNotNull { ids.optLong(it, -1L).takeIf { id -> id > 0 } }
                if (parsedIds.isNotEmpty()) {
                    val restored = ImagerySelection(
                        rasterIds = parsedIds,
                        year = json.optInt("acquisitionYear").takeIf { it > 0 },
                        acquisitionDateEpochMs = json.optLong("acquisitionDateEpochMs").takeIf { it > 0 },
                        resolutionMeters = json.optDouble("resolutionMeters").takeIf { it.isFinite() && it > 0 },
                        bandCount = json.optInt("bandCount", 4)
                    )
                    selectionCache[key] = restored
                    return restored
                }
            }
        }
        val queried = queryBestImagery(bounds) ?: ImagerySelection(emptyList(), null, null, null, 4)
        selectionCache[key] = queried
        return queried
    }

    /**
     * Ask the ImageServer which source rasters intersect this hole. Prefer the
     * newest available NAIP acquisition, then the finest native resolution.
     * Locking those raster IDs makes RGB and NDVI exports use the same inputs.
     */
    private fun queryBestImagery(bounds: Bounds): ImagerySelection? {
        val queryParams = linkedMapOf(
            "where" to "band_count >= 4",
            "geometry" to "${bounds.west},${bounds.south},${bounds.east},${bounds.north}",
            "geometryType" to "esriGeometryEnvelope",
            "inSR" to "4326",
            "spatialRel" to "esriSpatialRelIntersects",
            "outFields" to "OBJECTID,Year,acquisition_date,resolution_value,resolution_units,band_count,agency",
            "returnGeometry" to "false",
            "orderByFields" to "Year DESC,resolution_value ASC",
            "resultRecordCount" to "50",
            "f" to "json"
        )
        val url = IMAGE_SERVER + "/query?" + queryParams.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "WatchThisDrive/0.4 (USDA NAIP source selection)")
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            if (body.length > MAX_METADATA_BYTES) return null
            val features = JSONObject(body).optJSONArray("features") ?: return null
            val records = (0 until features.length()).mapNotNull { index ->
                val attrs = features.optJSONObject(index)?.optJSONObject("attributes") ?: return@mapNotNull null
                val id = value(attrs, "OBJECTID", "objectid")?.toLongOrNull() ?: return@mapNotNull null
                val bands = value(attrs, "band_count", "Band_Count")?.toIntOrNull() ?: return@mapNotNull null
                RasterRecord(
                    id = id,
                    year = value(attrs, "Year", "year")?.toIntOrNull(),
                    acquisitionDateEpochMs = value(attrs, "acquisition_date", "Acquisition_Date")?.toLongOrNull(),
                    resolutionValue = value(attrs, "resolution_value", "Resolution_Value")?.toDoubleOrNull(),
                    resolutionUnits = value(attrs, "resolution_units", "Resolution_Units"),
                    bandCount = bands
                )
            }
            selectBestRasters(records)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun value(json: JSONObject, vararg names: String): String? {
        val entries = json.keys().asSequence().associateBy { it.lowercase(Locale.US) }
        return names.firstNotNullOfOrNull { name ->
            val key = entries[name.lowercase(Locale.US)] ?: return@firstNotNullOfOrNull null
            val value = json.opt(key)
            when {
                value == null || value == JSONObject.NULL -> null
                value is Number -> value.toString()
                else -> value.toString().takeIf { it.isNotBlank() && it != "null" }
            }
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    fun boundsFor(course: GolfCourse, geometry: CourseGeometry?): Bounds? {
        val boundaryPoints = geometry?.features
            ?.filter { it.featureType == "course_boundary" }
            ?.flatMap { it.points }
            .orEmpty()
        val points = boundaryPoints.ifEmpty {
            val latPad = 0.009
            val lonPad = 0.009 / cos(Math.toRadians(course.latitude)).coerceAtLeast(0.2)
            return Bounds(
                west = course.longitude - lonPad,
                south = course.latitude - latPad,
                east = course.longitude + lonPad,
                north = course.latitude + latPad
            )
        }
        val west = points.minOf { it.longitude }
        val east = points.maxOf { it.longitude }
        val south = points.minOf { it.latitude }
        val north = points.maxOf { it.latitude }
        val latPad = max(0.0007, (north - south) * 0.05)
        val lonPad = max(0.0007, (east - west) * 0.05)
        val result = Bounds(west - lonPad, south - latPad, east + lonPad, north + latPad)
        if (result.east - result.west > 0.12 || result.north - result.south > 0.12) return null
        return result
    }

    private fun isWithinConus(latitude: Double, longitude: Double): Boolean =
        latitude in 24.0..50.0 && longitude in -125.0..-66.0
}
