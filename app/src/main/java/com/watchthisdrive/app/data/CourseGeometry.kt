package com.watchthisdrive.app.data

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class GeoPoint(
    val latitude: Double,
    val longitude: Double
)

data class CourseFeature(
    val id: Long,
    val elementType: String,
    val featureType: String,
    val holeNumber: String?,
    val name: String?,
    val points: List<GeoPoint>,
    val par: Int? = null,
    val handicap: Int? = null
)

data class CourseGeometry(
    val courseId: Long,
    val courseName: String,
    val features: List<CourseFeature>,
    val courseElementType: String = "unknown"
)

/**
 * Downloads the selected course boundary first, then requests golf and
 * landscape features inside a boundary-derived bounding box. All work runs
 * off the UI thread. Course IDs and coordinates are runtime inputs; no
 * individual course is special-cased.
 */
object CourseGeometryLoader {

    private const val OVERPASS_TIMEOUT_SECONDS = 45
    private const val CONNECT_TIMEOUT_MS = 12000
    private const val READ_TIMEOUT_MS = 55000
    private const val MAX_RESPONSE_CHARS = 12_000_000

    private val OVERPASS_SERVERS = listOf(
        "https://ovp.dresel.systems/api-na/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass-api.de/api/interpreter"
    )

    // Avoid duplicate Overpass downloads when Play, Course Detail, and the map
    // request the same course together. Bound concurrent course downloads.
    private val executor = Executors.newFixedThreadPool(2)
    private val inFlight = mutableMapOf<String, MutableList<(Result<CourseGeometry>) -> Unit>>()

    private data class Bounds(
        val south: Double,
        val west: Double,
        val north: Double,
        val east: Double
    ) {
        fun expanded(): Bounds {
            val latSpan = north - south
            val lonSpan = east - west
            val latPad = max(0.0006, latSpan * 0.12)
            val lonPad = max(0.0006, lonSpan * 0.12)
            return Bounds(
                (south - latPad).coerceAtLeast(-90.0),
                (west - lonPad).coerceAtLeast(-180.0),
                (north + latPad).coerceAtMost(90.0),
                (east + lonPad).coerceAtMost(180.0)
            )
        }

        fun toOverpass(): String = "$south,$west,$north,$east"
    }

    fun load(
        course: GolfCourse,
        onResult: (Result<CourseGeometry>) -> Unit
    ) {
        val key = "${course.elementType.lowercase()}:${course.id}"
        val shouldStart = synchronized(inFlight) {
            val callbacks = inFlight[key]
            if (callbacks != null) {
                callbacks.add(onResult)
                false
            } else {
                inFlight[key] = mutableListOf(onResult)
                true
            }
        }
        if (!shouldStart) return

        executor.execute {
            val result = runCatching { loadBlocking(course) }
            val callbacks = synchronized(inFlight) { inFlight.remove(key).orEmpty() }
            Handler(Looper.getMainLooper()).post {
                callbacks.forEach { callback -> runCatching { callback(result) } }
            }
        }
    }

    private fun loadBlocking(course: GolfCourse): CourseGeometry {
        require(course.latitude.isFinite() && course.longitude.isFinite()) {
            "The selected course has invalid coordinates."
        }
        require(course.latitude in -90.0..90.0 && course.longitude in -180.0..180.0) {
            "The selected course coordinates are outside the valid range."
        }

        // Zomma publishes downloadable GeoJSON for courses it has processed.
        // Treat it as an additional geometry provider: never make it a hard
        // dependency, and retain OSM as the broad-coverage fallback.
        // Start both providers together. Zomma must not add another network
        // round-trip to an already slow Overpass request.
        val zommaFuture = CompletableFuture.supplyAsync {
            runCatching { fetchZommaGeometry(course) }
                .onFailure { android.util.Log.d("CourseGeometry", "Zomma unavailable for ${course.name}: ${it.message}") }
                .getOrDefault(emptyList())
        }
        val osmResult = runCatching { loadOsmGeometry(course) }
        val osm = osmResult.getOrNull()
        val zomma = runCatching { zommaFuture.get(12, TimeUnit.SECONDS) }
            .onFailure {
                zommaFuture.cancel(true)
                android.util.Log.d("CourseGeometry", "Zomma request timed out for ${course.name}")
            }.getOrDefault(emptyList())

        if (zomma.isEmpty() && osm == null) {
            throw osmResult.exceptionOrNull()
                ?: IllegalStateException("No course geometry was available from Zomma or OpenStreetMap.")
        }

        val features = mergeProviderFeatures(zomma, osm?.features.orEmpty())
        if (features.none { it.featureType != "course_boundary" }) {
            throw IllegalStateException(
                "The selected course was found, but neither Zomma nor OpenStreetMap returned usable golf features."
            )
        }
        return CourseGeometry(
            courseId = course.id,
            courseName = course.name,
            features = features,
            courseElementType = course.elementType
        )
    }

    private fun loadOsmGeometry(course: GolfCourse): CourseGeometry {
        val boundary = fetchBoundary(course)
        val bounds = boundary.second ?: fallbackBounds(course)
        val featureElements = fetchFeatures(bounds.expanded())
        val parsed = parseFeatures(featureElements)
        val boundaryRings = stitchBoundaryRings(boundary.first)
        val scoped = if (boundaryRings.isNotEmpty()) {
            parsed.filter { belongsToCourse(it, boundaryRings) }
        } else {
            parsed
        }
        val combined = (boundary.first + scoped)
            .distinctBy { Triple(it.elementType, it.id, it.featureType) }
        if (combined.none { it.featureType != "course_boundary" }) {
            throw IllegalStateException(
                "The selected course was found, but no mapped golf features were returned. " +
                    "This course may have sparse OpenStreetMap coverage."
            )
        }
        return CourseGeometry(course.id, course.name, combined, course.elementType)
    }

    private fun fetchZommaGeometry(course: GolfCourse): List<CourseFeature> {
        val state = stateCode(course.region) ?: return emptyList()
        val slug = course.name.lowercase(java.util.Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-").trim('-')
        if (slug.isBlank()) return emptyList()
        val url = URL("https://zommagolf.com/courses/$state/$slug/geo.json")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 4_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/geo+json, application/json")
            connection.setRequestProperty("User-Agent", "WatchThisDrive/0.3 (Android golf course mapping)")
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_FOUND || status == HttpURLConnection.HTTP_GONE) {
                return emptyList()
            }
            if (status !in 200..299) throw IllegalStateException("Zomma returned HTTP $status")
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            if (text.length > MAX_RESPONSE_CHARS) {
                throw IllegalStateException("Zomma GeoJSON exceeded the safe response-size limit.")
            }
            return ZommaGeoJsonParser.parse(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun stateCode(region: String?): String? {
        val value = region?.trim()?.lowercase(java.util.Locale.US) ?: return null
        if (value.matches(Regex("[a-z]{2}"))) return value
        val states = mapOf(
            "alabama" to "al", "alaska" to "ak", "arizona" to "az", "arkansas" to "ar",
            "california" to "ca", "colorado" to "co", "connecticut" to "ct", "delaware" to "de",
            "florida" to "fl", "georgia" to "ga", "hawaii" to "hi", "idaho" to "id",
            "illinois" to "il", "indiana" to "in", "iowa" to "ia", "kansas" to "ks",
            "kentucky" to "ky", "louisiana" to "la", "maine" to "me", "maryland" to "md",
            "massachusetts" to "ma", "michigan" to "mi", "minnesota" to "mn",
            "mississippi" to "ms", "missouri" to "mo", "montana" to "mt", "nebraska" to "ne",
            "nevada" to "nv", "new hampshire" to "nh", "new jersey" to "nj",
            "new mexico" to "nm", "new york" to "ny", "north carolina" to "nc",
            "north dakota" to "nd", "ohio" to "oh", "oklahoma" to "ok", "oregon" to "or",
            "pennsylvania" to "pa", "rhode island" to "ri", "south carolina" to "sc",
            "south dakota" to "sd", "tennessee" to "tn", "texas" to "tx", "utah" to "ut",
            "vermont" to "vt", "virginia" to "va", "washington" to "wa",
            "west virginia" to "wv", "wisconsin" to "wi", "wyoming" to "wy",
            "district of columbia" to "dc"
        )
        return states[value]
    }

    private fun mergeProviderFeatures(
        preferred: List<CourseFeature>,
        fallback: List<CourseFeature>
    ): List<CourseFeature> {
        val result = preferred.toMutableList()
        fallback.forEach { candidate ->
            val duplicate = result.any { existing ->
                existing.featureType == candidate.featureType &&
                    (existing.holeNumber == null || candidate.holeNumber == null ||
                        existing.holeNumber == candidate.holeNumber) &&
                    featureCentersWithin(existing, candidate, 14.0)
            }
            if (!duplicate) result += candidate
        }
        return result.distinctBy { Triple(it.elementType, it.id, it.featureType) }
    }

    private fun featureCentersWithin(first: CourseFeature, second: CourseFeature, meters: Double): Boolean {
        if (first.points.isEmpty() || second.points.isEmpty()) return false
        val aLat = first.points.map { it.latitude }.average()
        val aLon = first.points.map { it.longitude }.average()
        val bLat = second.points.map { it.latitude }.average()
        val bLon = second.points.map { it.longitude }.average()
        return haversineMeters(aLat, aLon, bLat, bLon) <= meters
    }

    /**
     * Fetch the exact selected OSM way/relation before searching for details.
     * Relations may expose their boundary as outer member ways.
     */
    private fun fetchBoundary(course: GolfCourse): Pair<List<CourseFeature>, Bounds?> {
        if (course.elementType.equals("node", ignoreCase = true)) {
            // A node is only a search marker, not a usable course footprint.
            // Resolve a nearby mapped course boundary rather than treating the
            // node as if it described the whole course.
            val nearby = fetchNearbyCourseBoundary(course)
            if (nearby != null) return nearby
            return emptyList<CourseFeature>() to null
        }

        val selector = when (course.elementType.lowercase()) {
            "way" -> "way(${course.id});"
            "relation" -> "relation(${course.id});"
            else -> return emptyList<CourseFeature>() to null
        }
        val query = """
            [out:json][timeout:30];
            $selector
            out geom;
        """.trimIndent()

        val elements = requestOverpass(query, "course boundary")
        val features = mutableListOf<CourseFeature>()
        val points = mutableListOf<GeoPoint>()

        for (i in 0 until elements.length()) {
            val element = elements.getJSONObject(i)
            val tags = element.optJSONObject("tags") ?: JSONObject()
            val ownGeometry = readGeometry(element.optJSONArray("geometry"))
            if (ownGeometry.isNotEmpty()) {
                points.addAll(ownGeometry)
                features.add(
                    CourseFeature(
                        id = element.optLong("id", course.id),
                        elementType = element.optString("type", course.elementType),
                        featureType = "course_boundary",
                        holeNumber = null,
                        name = tags.optString("name").takeIf { it.isNotBlank() } ?: course.name,
                        points = ownGeometry
                    )
                )
            }

            val members = element.optJSONArray("members") ?: continue
            for (j in 0 until members.length()) {
                val member = members.getJSONObject(j)
                val role = member.optString("role")
                if (role.isNotBlank() && role != "outer") continue
                val memberGeometry = readGeometry(member.optJSONArray("geometry"))
                if (memberGeometry.isEmpty()) continue
                points.addAll(memberGeometry)
                features.add(
                    CourseFeature(
                        id = member.optLong("ref", element.optLong("id", course.id)),
                        elementType = member.optString("type", "way"),
                        featureType = "course_boundary",
                        holeNumber = null,
                        name = course.name + " boundary",
                        points = memberGeometry
                    )
                )
            }
        }

        return features to boundsFrom(points)
    }

    private fun fetchNearbyCourseBoundary(course: GolfCourse): Pair<List<CourseFeature>, Bounds?>? {
        val query = """
            [out:json][timeout:30];
            (
              way(around:2500,${course.latitude},${course.longitude})["leisure"="golf_course"];
              relation(around:2500,${course.latitude},${course.longitude})["leisure"="golf_course"];
            );
            out center geom;
        """.trimIndent()

        val elements = runCatching { requestOverpass(query, "nearby course boundary") }
            .getOrNull() ?: return null
        if (elements.length() == 0) return null

        val candidates = (0 until elements.length()).map { elements.getJSONObject(it) }
            .filter { it.optJSONArray("geometry")?.length()?.let { n -> n >= 4 } == true ||
                it.optJSONArray("members")?.length()?.let { n -> n > 0 } == true }
        // A course marker may sit close to the edge of its own boundary.
        // Rank candidates by distance to their actual outline, not only by the
        // polygon centroid (which can be hundreds of metres away).
        val marker = GeoPoint(course.latitude, course.longitude)
        val normalizedCourseName = course.name.lowercase().replace(Regex("[^a-z0-9]"), "")
        val rankedCandidates = candidates.map { element ->
            val candidateName = element.optJSONObject("tags")?.optString("name").orEmpty()
                .lowercase().replace(Regex("[^a-z0-9]"), "")
            val candidateBoundary = mutableListOf<CourseFeature>()
            val ownGeometry = readGeometry(element.optJSONArray("geometry"))
            if (ownGeometry.isNotEmpty()) candidateBoundary += CourseFeature(
                element.optLong("id"), element.optString("type"), "course_boundary",
                null, course.name, ownGeometry
            )
            element.optJSONArray("members")?.let { members ->
                for (index in 0 until members.length()) {
                    val member = members.optJSONObject(index) ?: continue
                    if (member.optString("role").let { it.isNotBlank() && it != "outer" }) continue
                    val geometry = readGeometry(member.optJSONArray("geometry"))
                    if (geometry.isNotEmpty()) candidateBoundary += CourseFeature(
                        member.optLong("ref"), member.optString("type", "way"),
                        "course_boundary", null, course.name, geometry
                    )
                }
            }
            val outline = candidateBoundary.flatMap { it.points }
            val rings = stitchBoundaryRings(candidateBoundary)
            val distance = when {
                rings.any { pointInRing(marker, it) } -> 0.0
                outline.isNotEmpty() -> outlineDistanceMeters(marker, outline)
                else -> {
                    val center = element.optJSONObject("center")
                    val lat = center?.optDouble("lat", course.latitude) ?: course.latitude
                    val lon = center?.optDouble("lon", course.longitude) ?: course.longitude
                    haversineMeters(course.latitude, course.longitude, lat, lon)
                }
            }
            Triple(element, candidateName == normalizedCourseName && normalizedCourseName.isNotBlank(), distance)
        }
        // Prefer an exact course-name match. If OSM uses a different or missing
        // name, only accept a nearby outline; never attach a distant neighboring
        // course simply because it is the closest polygon in the search radius.
        val chosen = rankedCandidates
            .filter { it.second || it.third <= 1_000.0 }
            .minWithOrNull(
                compareBy<Triple<JSONObject, Boolean, Double>> { if (it.second) 0 else 1 }
                    .thenBy { it.third }
            )?.first ?: return null

        val points = mutableListOf<GeoPoint>()
        val features = mutableListOf<CourseFeature>()
        val own = readGeometry(chosen.optJSONArray("geometry"))
        if (own.isNotEmpty()) {
            points.addAll(own)
            features.add(CourseFeature(
                chosen.optLong("id"), chosen.optString("type"), "course_boundary",
                null, course.name + " boundary", own
            ))
        }
        val members = chosen.optJSONArray("members") ?: JSONArray()
        for (i in 0 until members.length()) {
            val member = members.getJSONObject(i)
            if (member.optString("role").let { it.isNotBlank() && it != "outer" }) continue
            val ring = readGeometry(member.optJSONArray("geometry"))
            if (ring.isEmpty()) continue
            points.addAll(ring)
            features.add(CourseFeature(
                member.optLong("ref"), member.optString("type", "way"),
                "course_boundary", null, course.name + " boundary", ring
            ))
        }
        return if (points.isEmpty()) null else features to boundsFrom(points)
    }

    private fun fetchFeatures(bounds: Bounds): JSONArray {
        val box = bounds.toOverpass()
        val query = """
            [out:json][timeout:$OVERPASS_TIMEOUT_SECONDS];
            (
              nwr["golf"~"^(hole|fairway|green|bunker|tee|water_hazard|rough|driving_range|practice|cartpath)$"]($box);
              nwr["natural"~"^(water|wood|scrub)$"]($box);
              node["natural"="tree"]($box);
              nwr["landuse"~"^(forest|grass|meadow)$"]($box);
              nwr["water"]($box);
              way["highway"~"^(path|track|footway)$"]($box);
              relation["highway"~"^(path|track|footway)$"]($box);
            );
            out center geom;
        """.trimIndent()
        return requestOverpass(query, "course features")
    }

    private fun requestOverpass(query: String, purpose: String): JSONArray {
        val failures = mutableListOf<String>()
        for (server in OVERPASS_SERVERS) {
            try {
                val response = postQuery(server, query)
                val root = JSONObject(response)
                val elements = root.optJSONArray("elements")
                    ?: throw IllegalStateException("Map-data response did not contain an elements list.")
                return elements
            } catch (error: Exception) {
                failures.add("${URL(server).host}: ${error.message ?: "request failed"}")
            }
        }
        throw IllegalStateException(
            "Unable to retrieve $purpose from the available OpenStreetMap servers. " +
                failures.joinToString(" · ")
        )
    }

    private fun postQuery(server: String, query: String): String {
        val connection = URL(server).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8"
            )
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(
                "User-Agent", "WatchThisDrive/0.2 (Android golf course mapping)"
            )

            val body = "data=" + URLEncoder.encode(query, "UTF-8")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (text.length + count > MAX_RESPONSE_CHARS) {
                        throw IllegalStateException("Map-data response exceeded the safe size limit.")
                    }
                    text.append(buffer, 0, count)
                }
                text.toString()
            }.orEmpty()

            if (status !in 200..299) {
                throw IllegalStateException(
                    "HTTP $status" + response.replace(Regex("\\s+"), " ").take(220)
                        .takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                )
            }
            if (!response.trimStart().startsWith("{")) {
                throw IllegalStateException(
                    "Server returned a non-JSON response: " +
                        response.replace(Regex("\\s+"), " ").take(180)
                )
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun parseFeatures(elements: JSONArray): List<CourseFeature> {
        val features = mutableListOf<CourseFeature>()
        for (i in 0 until elements.length()) {
            val element = elements.getJSONObject(i)
            val tags = element.optJSONObject("tags") ?: continue
            val featureType = classify(tags) ?: continue
            // Ways expose their path in element.geometry. Relations often expose
            // the actual golf=hole route only through ordered member geometries.
            // Do not silently reduce a relation centerline to its centroid.
            val memberGeometry = element.optJSONArray("members")?.let { members ->
                val segments = (0 until members.length()).mapNotNull { index ->
                    val member = members.optJSONObject(index) ?: return@mapNotNull null
                    readGeometry(member.optJSONArray("geometry")).takeIf { it.size >= 2 }
                }
                stitchOpenSegments(segments)
            }.orEmpty()
            val points = readGeometry(element.optJSONArray("geometry")).ifEmpty {
                memberGeometry.ifEmpty {
                    val center = element.optJSONObject("center")
                    val lat = if (center != null) center.optDouble("lat", Double.NaN)
                        else element.optDouble("lat", Double.NaN)
                    val lon = if (center != null) center.optDouble("lon", Double.NaN)
                        else element.optDouble("lon", Double.NaN)
                    if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) {
                        listOf(GeoPoint(lat, lon))
                    } else {
                        emptyList()
                    }
                }
            }
            if (points.isEmpty()) continue

            val hole = tags.optString("ref").takeIf { it.isNotBlank() }
                ?: tags.optString("golf:hole").takeIf { it.isNotBlank() }
            val name = tags.optString("name").takeIf { it.isNotBlank() }
            // Preserve explicit hole metadata from OSM. Missing or malformed
            // values remain unknown; downstream UI must never infer a par.
            val par = tags.optString("par").toIntOrNull()?.takeIf { it in 3..6 }
            val handicap = sequenceOf("handicap", "stroke_index", "golf:handicap")
                .mapNotNull { key -> tags.optString(key).toIntOrNull() }
                .firstOrNull()?.takeIf { it in 1..36 }
            features.add(CourseFeature(
                id = element.optLong("id"),
                elementType = element.optString("type"),
                featureType = featureType,
                holeNumber = hole,
                name = name,
                points = points,
                par = par,
                handicap = handicap
            ))
        }
        return features.distinctBy { Triple(it.elementType, it.id, it.featureType) }
    }

    private fun classify(tags: JSONObject): String? {
        val golf = tags.optString("golf").lowercase()
        if (golf in setOf(
                "hole", "fairway", "green", "bunker", "tee", "water_hazard",
                "rough", "driving_range", "practice"
            )
        ) return golf
        if (golf == "cartpath") return "path"
        if (tags.optString("natural") == "water" || tags.has("water")) return "water"
        if (tags.optString("natural") == "tree") return "tree"
        if (tags.optString("natural") in setOf("wood", "scrub")) return "woodland"
        if (tags.optString("landuse") in setOf("forest", "grass", "meadow")) {
            return if (tags.optString("landuse") == "grass") "rough" else "woodland"
        }
        if (tags.optString("highway") in setOf("path", "track", "footway")) return "path"
        return null
    }

    private fun readGeometry(array: JSONArray?): List<GeoPoint> {
        if (array == null) return emptyList()
        val points = mutableListOf<GeoPoint>()
        for (i in 0 until array.length()) {
            val point = array.optJSONObject(i) ?: continue
            val lat = point.optDouble("lat", Double.NaN)
            val lon = point.optDouble("lon", Double.NaN)
            if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) {
                points.add(GeoPoint(lat, lon))
            }
        }
        return points
    }

    /** Joins ordered relation-member ways into one continuous route, reversing
     * a member when its nearest endpoint matches the current route end. */
    private fun stitchOpenSegments(segments: List<List<GeoPoint>>): List<GeoPoint> {
        if (segments.isEmpty()) return emptyList()
        val remaining = segments.filter { it.size >= 2 }.toMutableList()
        if (remaining.isEmpty()) return emptyList()
        val route = remaining.removeAt(0).toMutableList()

        // Relation member order and direction are not guaranteed. Join at either
        // end of the growing route, reversing members when necessary, instead of
        // assuming every next member continues from route.last().
        while (remaining.isNotEmpty()) {
            data class Join(val index: Int, val attachToStart: Boolean, val reverse: Boolean, val distance: Double)
            val joins = remaining.mapIndexedNotNull { index, segment ->
                val appendFirst = haversineMeters(route.last().latitude, route.last().longitude,
                    segment.first().latitude, segment.first().longitude)
                val appendLast = haversineMeters(route.last().latitude, route.last().longitude,
                    segment.last().latitude, segment.last().longitude)
                val prependFirst = haversineMeters(route.first().latitude, route.first().longitude,
                    segment.first().latitude, segment.first().longitude)
                val prependLast = haversineMeters(route.first().latitude, route.first().longitude,
                    segment.last().latitude, segment.last().longitude)
                listOf(
                    Join(index, false, false, appendFirst),
                    Join(index, false, true, appendLast),
                    Join(index, true, true, prependFirst),
                    Join(index, true, false, prependLast)
                ).filter { it.distance <= 12.0 }.minByOrNull { it.distance }
            }
            val join = joins.minByOrNull { it.distance } ?: break
            val segment = remaining.removeAt(join.index)
            when {
                !join.attachToStart && !join.reverse -> route.addAll(segment.drop(1))
                !join.attachToStart && join.reverse -> route.addAll(segment.asReversed().drop(1))
                join.attachToStart && join.reverse -> route.addAll(0, segment.asReversed().dropLast(1))
                else -> route.addAll(0, segment.dropLast(1))
            }
        }
        return route
    }

    /**
     * OSM course relations commonly describe an outline as several outer ways.
     * Stitch their endpoints into closed rings before doing spatial filtering.
     * A small endpoint tolerance handles coordinate rounding in server output.
     */
    private fun stitchBoundaryRings(boundaries: List<CourseFeature>): List<List<GeoPoint>> {
        val remaining = boundaries.map { it.points }.filter { it.size >= 2 }.toMutableList()
        val rings = mutableListOf<List<GeoPoint>>()
        while (remaining.isNotEmpty()) {
            val ring = remaining.removeAt(0).toMutableList()
            while (remaining.isNotEmpty() && haversineMeters(
                    ring.first().latitude, ring.first().longitude,
                    ring.last().latitude, ring.last().longitude
                ) > 5.0
            ) {
                data class Join(val index: Int, val attachToStart: Boolean, val reverse: Boolean, val distance: Double)
                val joins = remaining.mapIndexedNotNull { index, segment ->
                    val appendFirst = haversineMeters(ring.last().latitude, ring.last().longitude,
                        segment.first().latitude, segment.first().longitude)
                    val appendLast = haversineMeters(ring.last().latitude, ring.last().longitude,
                        segment.last().latitude, segment.last().longitude)
                    val prependFirst = haversineMeters(ring.first().latitude, ring.first().longitude,
                        segment.first().latitude, segment.first().longitude)
                    val prependLast = haversineMeters(ring.first().latitude, ring.first().longitude,
                        segment.last().latitude, segment.last().longitude)
                    listOf(
                        Join(index, false, false, appendFirst),
                        Join(index, false, true, appendLast),
                        Join(index, true, true, prependFirst),
                        Join(index, true, false, prependLast)
                    ).filter { it.distance <= 5.0 }.minByOrNull { it.distance }
                }
                val join = joins.minByOrNull { it.distance } ?: break
                val segment = remaining.removeAt(join.index)
                when {
                    !join.attachToStart && !join.reverse -> ring.addAll(segment.drop(1))
                    !join.attachToStart && join.reverse -> ring.addAll(segment.asReversed().drop(1))
                    join.attachToStart && join.reverse -> ring.addAll(0, segment.asReversed().dropLast(1))
                    else -> ring.addAll(0, segment.dropLast(1))
                }
            }
            if (ring.size >= 4 && haversineMeters(
                    ring.first().latitude, ring.first().longitude,
                    ring.last().latitude, ring.last().longitude
                ) <= 5.0
            ) {
                ring[ring.lastIndex] = ring.first()
                rings.add(ring)
            }
        }
        return rings
    }

    private fun belongsToCourse(
        feature: CourseFeature,
        rings: List<List<GeoPoint>>
    ): Boolean {
        if (feature.points.isEmpty()) return false
        val centroid = GeoPoint(
            feature.points.map { it.latitude }.average(),
            feature.points.map { it.longitude }.average()
        )
        val contained = feature.points.any { point -> rings.any { pointInRing(point, it) } } ||
            rings.any { ring -> pointInRing(centroid, ring) } ||
            (feature.points.size >= 4 && rings.any { ring ->
                ring.any { point -> pointInRing(point, feature.points) }
            })
        if (contained) return true

        // Retain mapped features whose geometry crosses the selected course
        // outline even when every sampled vertex and the centroid lie outside.
        return feature.points.zipWithNext().any { (a, b) ->
            rings.any { ring ->
                ring.zipWithNext().any { (c, d) -> segmentsIntersect(a, b, c, d) }
            }
        }
    }

    private fun segmentsIntersect(a: GeoPoint, b: GeoPoint, c: GeoPoint, d: GeoPoint): Boolean {
        fun orient(p: GeoPoint, q: GeoPoint, r: GeoPoint): Double =
            (q.longitude - p.longitude) * (r.latitude - p.latitude) -
                (q.latitude - p.latitude) * (r.longitude - p.longitude)
        fun onSegment(p: GeoPoint, q: GeoPoint, r: GeoPoint): Boolean =
            q.longitude >= min(p.longitude, r.longitude) - 1e-12 &&
                q.longitude <= max(p.longitude, r.longitude) + 1e-12 &&
                q.latitude >= min(p.latitude, r.latitude) - 1e-12 &&
                q.latitude <= max(p.latitude, r.latitude) + 1e-12
        val o1 = orient(a, b, c); val o2 = orient(a, b, d)
        val o3 = orient(c, d, a); val o4 = orient(c, d, b)
        val epsilon = 1e-12
        if (((o1 > epsilon && o2 < -epsilon) || (o1 < -epsilon && o2 > epsilon)) &&
            ((o3 > epsilon && o4 < -epsilon) || (o3 < -epsilon && o4 > epsilon))) return true
        return (kotlin.math.abs(o1) <= epsilon && onSegment(a, c, b)) ||
            (kotlin.math.abs(o2) <= epsilon && onSegment(a, d, b)) ||
            (kotlin.math.abs(o3) <= epsilon && onSegment(c, a, d)) ||
            (kotlin.math.abs(o4) <= epsilon && onSegment(c, b, d))
    }

    private fun pointInRing(point: GeoPoint, ring: List<GeoPoint>): Boolean {
        var inside = false
        var j = ring.lastIndex
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            val crosses = (a.latitude > point.latitude) != (b.latitude > point.latitude) &&
                point.longitude < (b.longitude - a.longitude) *
                (point.latitude - a.latitude) /
                ((b.latitude - a.latitude).takeIf { kotlin.math.abs(it) > 1e-12 } ?: 1e-12) +
                a.longitude
            if (crosses) inside = !inside
            j = i
        }
        return inside
    }

    private fun boundsFrom(points: List<GeoPoint>): Bounds? {
        if (points.isEmpty()) return null
        val south = points.minOf { it.latitude }
        val north = points.maxOf { it.latitude }
        val west = points.minOf { it.longitude }
        val east = points.maxOf { it.longitude }
        // Reject malformed or continent-scale geometry instead of issuing an
        // enormous query. The coordinate fallback remains available.
        if (north - south > 0.20 || east - west > 0.20) return null
        return Bounds(south, west, north, east)
    }

    private fun fallbackBounds(course: GolfCourse): Bounds {
        // Roughly 1.7 km north/south at mid-latitudes. Used only when OSM
        // supplies no usable boundary geometry for the selected course.
        val latPad = 0.008
        val lonPad = 0.008 / kotlin.math.cos(Math.toRadians(course.latitude))
            .coerceAtLeast(0.2)
        return Bounds(
            (course.latitude - latPad).coerceAtLeast(-90.0),
            (course.longitude - lonPad).coerceAtLeast(-180.0),
            (course.latitude + latPad).coerceAtMost(90.0),
            (course.longitude + lonPad).coerceAtMost(180.0)
        )
    }

    private fun outlineDistanceMeters(point: GeoPoint, outline: List<GeoPoint>): Double {
        if (outline.size < 2) return Double.POSITIVE_INFINITY
        if (outline.size >= 4 && pointInRing(point, outline)) return 0.0
        val latitudeScale = 111_320.0
        val longitudeScale = latitudeScale * kotlin.math.cos(Math.toRadians(point.latitude))
        val px = point.longitude * longitudeScale
        val py = point.latitude * latitudeScale
        var best = Double.POSITIVE_INFINITY
        for (i in 0 until outline.lastIndex) {
            val a = outline[i]
            val b = outline[i + 1]
            val ax = a.longitude * longitudeScale
            val ay = a.latitude * latitudeScale
            val bx = b.longitude * longitudeScale
            val by = b.latitude * latitudeScale
            val dx = bx - ax
            val dy = by - ay
            val denominator = dx * dx + dy * dy
            val t = if (denominator <= 1e-9) 0.0 else
                (((px - ax) * dx + (py - ay) * dy) / denominator).coerceIn(0.0, 1.0)
            best = min(best, kotlin.math.hypot(px - (ax + t * dx), py - (ay + t * dy)))
        }
        return best
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val radius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return 2 * radius * kotlin.math.asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
    }
}


/**
 * Defensive GeoJSON reader for Zomma's public course export. Geometry remains
 * ordinary WGS84 data; unknown feature properties are ignored rather than
 * guessed into golf features.
 */
internal object ZommaGeoJsonParser {
    fun parse(json: String): List<CourseFeature> {
        val root = JSONObject(json)
        val features = root.optJSONArray("features") ?: JSONArray()
        val result = mutableListOf<CourseFeature>()
        for (index in 0 until features.length()) {
            val feature = features.optJSONObject(index) ?: continue
            val properties = feature.optJSONObject("properties") ?: JSONObject()
            val tags = properties.optJSONObject("tags") ?: properties
            val kind = firstValue(tags, "golf", "feature_type", "featureType", "kind", "class", "type")
                ?.lowercase()?.replace('-', '_') ?: continue
            val featureType = normalizeType(kind) ?: continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            val points = readCoordinates(geometry) ?: continue
            if (points.isEmpty()) continue
            val hole = firstValue(tags, "hole_number", "holeNumber", "hole", "golf:hole", "ref")
            val par = firstValue(tags, "par")?.toIntOrNull()?.takeIf { it in 3..6 }
            val handicap = firstValue(tags, "handicap", "stroke_index")?.toIntOrNull()?.takeIf { it in 1..36 }
            val rawId = feature.opt("id")?.toString()
                ?: firstValue(properties, "id", "osm_id")
                ?: "zomma-$index-$featureType"
            val id = stableId(rawId, index, featureType)
            result += CourseFeature(
                id = id,
                elementType = "zomma",
                featureType = featureType,
                holeNumber = hole,
                name = firstValue(tags, "name", "label"),
                points = points,
                par = par,
                handicap = handicap
            )
        }
        return result.distinctBy { Triple(it.elementType, it.id, it.featureType) }
    }

    private fun firstValue(json: JSONObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            val value = json.opt(key) ?: return@firstNotNullOfOrNull null
            if (value == JSONObject.NULL) return@firstNotNullOfOrNull null
            when (value) {
                is String -> value.takeIf { it.isNotBlank() && it != "null" }
                is Number -> value.toString()
                else -> null
            }
        }

    private fun normalizeType(value: String): String? = when (value) {
        "tee", "tee_box", "teeing_ground", "start" -> "tee"
        "green", "putting_green" -> "green"
        "fairway", "fair_way" -> "fairway"
        "hole", "hole_line", "hole_centerline", "centerline", "playing_line", "route" -> "hole"
        "bunker", "sand", "sand_trap" -> "bunker"
        "water", "water_hazard", "pond", "lake" -> "water"
        "rough", "semi_rough" -> "rough"
        "wood", "woodland", "forest", "trees" -> "woodland"
        "tree" -> "tree"
        "path", "cart_path", "cartpath" -> "path"
        "course_boundary", "boundary", "outline", "golf_course" -> "course_boundary"
        else -> null
    }

    private fun readCoordinates(geometry: JSONObject): List<GeoPoint>? {
        val type = geometry.optString("type")
        val coordinates = geometry.optJSONArray("coordinates") ?: return null
        val rawPoints = mutableListOf<GeoPoint>()
        fun collect(value: Any?) {
            when (value) {
                is JSONArray -> {
                    if (value.length() >= 2 && value.opt(0) is Number && value.opt(1) is Number) {
                        val lon = value.optDouble(0, Double.NaN)
                        val lat = value.optDouble(1, Double.NaN)
                        if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) {
                            rawPoints += GeoPoint(lat, lon)
                        }
                    } else {
                        for (i in 0 until value.length()) collect(value.opt(i))
                    }
                }
            }
        }
        when (type) {
            "Point", "MultiPoint", "LineString", "MultiLineString", "Polygon", "MultiPolygon",
            "GeometryCollection" -> collect(coordinates)
            else -> return null
        }
        if (type == "Polygon" || type == "MultiPolygon") {
            // GeoJSON polygons begin with the exterior ring. Keep the largest
            // ring when a multi-polygon is represented as a single feature.
            val rings = when (type) {
                "Polygon" -> (0 until coordinates.length()).mapNotNull { ring ->
                    coordinateRing(coordinates.optJSONArray(ring))
                }
                else -> (0 until coordinates.length()).flatMap { polygonIndex ->
                    val polygon = coordinates.optJSONArray(polygonIndex) ?: return@flatMap emptyList()
                    (0 until polygon.length()).mapNotNull { ring -> coordinateRing(polygon.optJSONArray(ring)) }
                }
            }
            return rings.maxByOrNull { it.size }?.takeIf { it.isNotEmpty() }
        }
        return rawPoints
    }

    private fun coordinateRing(array: JSONArray?): List<GeoPoint>? {
        array ?: return null
        val points = mutableListOf<GeoPoint>()
        for (i in 0 until array.length()) {
            val pair = array.optJSONArray(i) ?: continue
            if (pair.length() < 2) continue
            val lon = pair.optDouble(0, Double.NaN)
            val lat = pair.optDouble(1, Double.NaN)
            if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) {
                points += GeoPoint(lat, lon)
            }
        }
        return points.takeIf { it.isNotEmpty() }
    }

    private fun stableId(value: String, index: Int, kind: String): Long {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$value:$index:$kind".toByteArray(Charsets.UTF_8))
        var result = 0L
        for (i in 0 until 8) result = (result shl 8) or (bytes[i].toLong() and 0xffL)
        return -((result and Long.MAX_VALUE).coerceAtLeast(1L))
    }
}
