package com.watchthisdrive.app.data

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

data class GeoPoint(val latitude: Double, val longitude: Double)

enum class CourseGeometryType { POINT, LINE, POLYGON, MULTI_POLYGON, UNKNOWN }

data class CourseFeature(
    val id: Long,
    val elementType: String,
    val featureType: String,
    val holeNumber: String?,
    val name: String?,
    // Kept for compatibility with the existing course summary UI.
    // For polygons this is the outer ring; for lines the full line; for points one coordinate.
    val points: List<GeoPoint>,
    val geometryType: CourseGeometryType = CourseGeometryType.UNKNOWN,
    // Polygon rings: first ring is outer, following rings are holes.
    val rings: List<List<GeoPoint>> = emptyList(),
    // Each polygon has its own rings. Used for OSM multipolygon relations.
    val polygons: List<List<List<GeoPoint>>> = emptyList(),
    val geometryStatus: String = "unverified",
    val par: Int? = null
) {
    val hasUsableGeometry: Boolean
        get() = !geometryStatus.startsWith("invalid_or_incomplete") && when (geometryType) {
            CourseGeometryType.POINT -> points.size == 1
            CourseGeometryType.LINE -> points.size >= 2
            CourseGeometryType.POLYGON -> rings.isNotEmpty() && rings.first().size >= 4
            CourseGeometryType.MULTI_POLYGON -> polygons.isNotEmpty()
            CourseGeometryType.UNKNOWN -> false
        }

    val vertexCount: Int
        get() = when (geometryType) {
            CourseGeometryType.POINT, CourseGeometryType.LINE -> points.size
            CourseGeometryType.POLYGON -> rings.sumOf { it.size }
            CourseGeometryType.MULTI_POLYGON -> polygons.sumOf { polygon -> polygon.sumOf { it.size } }
            CourseGeometryType.UNKNOWN -> 0
        }
}

data class CourseGeometry(
    val courseId: Long,
    val courseName: String,
    val features: List<CourseFeature>,
    val incompleteRelations: List<String> = emptyList()
) {
    fun count(featureType: String): Int = features.count { it.featureType == featureType }
    fun countWithUsableGeometry(featureType: String): Int =
        features.count { it.featureType == featureType && it.hasUsableGeometry }
}

object CourseGeometryLoader {
    private val OVERPASS_SERVERS = listOf(
        "https://ovp.dresel.systems/api-na/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass-api.de/api/interpreter"
    )

    fun load(course: GolfCourse, onResult: (Result<CourseGeometry>) -> Unit) =
        loadWithQuery(course, buildCourseQuery(course), onResult)

    /**
     * Recovery scan: repeat the course-area search with additional OSM tagging
     * and direct relation-member coverage. This can recover features omitted by
     * the normal tagged-area query without substituting course-specific data.
     */
    fun loadRecovery(course: GolfCourse, onResult: (Result<CourseGeometry>) -> Unit) =
        loadWithQuery(course, buildRecoveryQuery(course), onResult)

    private fun loadWithQuery(
        course: GolfCourse,
        query: String,
        onResult: (Result<CourseGeometry>) -> Unit
    ) {
        thread {
            val result = runCatching {
                var lastError: Exception? = null
                for (server in OVERPASS_SERVERS) {
                    try {
                        return@runCatching fetchGeometry(server, query, course)
                    } catch (e: Exception) { lastError = e }
                }
                throw Exception("All geometry servers failed. " + (lastError?.message ?: ""))
            }
            Handler(Looper.getMainLooper()).post { onResult(result) }
        }
    }

    internal fun buildRecoveryQuery(course: GolfCourse): String {
        val element = when (course.elementType.lowercase()) {
            "relation" -> "relation(${course.id});"
            "way" -> "way(${course.id});"
            else -> throw Exception("This course is represented by an OSM node, which does not provide a course boundary.")
        }
        val relationMembers = if (course.elementType.equals("relation", ignoreCase = true)) {
            "way(r.course);"
        } else ""
        return """
            [out:json][timeout:90];
            $element -> .course;
            .course map_to_area -> .courseArea;
            (
              nwr(area.courseArea)["golf"];
              nwr(area.courseArea)["golf:hole"];
              $relationMembers
            );
            out body geom;
        """.trimIndent()
    }

    internal fun buildCourseQuery(course: GolfCourse): String {
        val element = when (course.elementType.lowercase()) {
            "relation" -> "relation(${course.id});"
            "way" -> "way(${course.id});"
            else -> throw Exception("This course is represented by an OSM node, which does not provide a course boundary.")
        }
        // 'out body geom' is intentional: relation member ways and their coordinates
        // are required to reconstruct multipolygon outer and inner rings.
        return """
            [out:json][timeout:60];
            $element
            map_to_area -> .courseArea;
            (
              nwr(area.courseArea)["golf"];
            );
            out body geom;
        """.trimIndent()
    }

    private fun fetchGeometry(server: String, query: String, course: GolfCourse): CourseGeometry {
        val connection = URL(server).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 70000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "WatchThisDrive/0.2 Android Golf App")
            val body = "data=" + URLEncoder.encode(query, "UTF-8")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) throw Exception("Geometry server returned HTTP $code")
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            return parseGeometry(response, course)
        } finally { connection.disconnect() }
    }

    internal fun parseGeometry(response: String, course: GolfCourse): CourseGeometry {
        val elements = JSONObject(response).getJSONArray("elements")
        val features = mutableListOf<CourseFeature>()
        val relationErrors = mutableListOf<String>()
        for (i in 0 until elements.length()) {
            val element = elements.getJSONObject(i)
            val tags = element.optJSONObject("tags") ?: continue
            val golfType = tags.optString("golf").trim().ifBlank {
                if (tags.has("golf:hole")) "hole" else ""
            }
            if (golfType.isBlank()) continue
            val id = element.optLong("id")
            val elementType = element.optString("type")
            val hole = tags.optString("ref").takeIf { it.isNotBlank() }
                ?: tags.optString("golf:hole").takeIf { it.isNotBlank() }
            val name = tags.optString("name").takeIf { it.isNotBlank() }
            val members = element.optJSONArray("members")
            val parsed = if (elementType == "relation" && members != null) {
                val relationType = tags.optString("type")
                when {
                    relationType == "multipolygon" || relationType == "boundary" ||
                        tags.optString("area") == "yes" -> parseRelation(members)
                    golfType in setOf("hole", "cartpath") -> parseLinearRelation(members)
                    else -> Parsed(error = "unsupported non-area golf relation type: ${relationType.ifBlank { "unspecified" }}")
                }
            } else {
                parseDirectGeometry(element)
            }
            if (parsed.geometryType == CourseGeometryType.UNKNOWN) {
                val detail = parsed.error ?: "no usable geometry"
                relationErrors.add("$elementType/$id: $detail")
                continue
            }
            features.add(CourseFeature(
                id = id,
                elementType = elementType,
                featureType = golfType,
                holeNumber = hole,
                name = name,
                points = parsed.points,
                geometryType = parsed.geometryType,
                rings = parsed.rings,
                polygons = parsed.polygons,
                geometryStatus = when {
                    parsed.error != null -> "invalid_or_incomplete: ${parsed.error}"
                    parsed.geometryType == CourseGeometryType.POLYGON ||
                        parsed.geometryType == CourseGeometryType.MULTI_POLYGON ->
                        "ring_closure_area_self_intersection_and_inner_containment_checked"
                    else -> "source_geometry"
                },
                par = tags.optString("par").toIntOrNull()?.takeIf { it in 3..6 }
            ))
            if (parsed.error != null) relationErrors.add("$elementType/$id: ${parsed.error}")
        }
        return CourseGeometry(course.id, course.name, features, relationErrors)
    }

    private data class Parsed(
        val geometryType: CourseGeometryType = CourseGeometryType.UNKNOWN,
        val points: List<GeoPoint> = emptyList(),
        val rings: List<List<GeoPoint>> = emptyList(),
        val polygons: List<List<List<GeoPoint>>> = emptyList(),
        val error: String? = null
    )

    private fun parseDirectGeometry(element: JSONObject): Parsed {
        val tags = element.optJSONObject("tags")
        val golfType = tags?.optString("golf").orEmpty()
        val geometry = element.optJSONArray("geometry")
        if (geometry != null) {
            val coords = readCoordinates(geometry)
            // A closed OSM way is not automatically an area. In particular, golf=hole
            // is a centerline and must remain a line even if the source way is closed.
            val explicitlyNotArea = tags?.optString("area") == "no"
            val linearGolfFeature = golfType in setOf("hole", "cartpath")
            if (!explicitlyNotArea && !linearGolfFeature &&
                coords.size >= 4 && coords.first() == coords.last()) {
                val problem = ringProblem(coords)
                if (problem != null) return Parsed(error = "invalid polygon ring: $problem")
                return Parsed(CourseGeometryType.POLYGON, coords, listOf(coords))
            }
            if (coords.size >= 2) return Parsed(CourseGeometryType.LINE, coords)
        }
        val center = element.optJSONObject("center")
        val lat = if (center != null) center.optDouble("lat", Double.NaN) else element.optDouble("lat", Double.NaN)
        val lon = if (center != null) center.optDouble("lon", Double.NaN) else element.optDouble("lon", Double.NaN)
        if (lat.isFinite() && lon.isFinite()) return Parsed(CourseGeometryType.POINT, listOf(GeoPoint(lat, lon)))
        return Parsed()
    }

    private fun List<GeoPoint>.firstRing(): List<GeoPoint> = this

    // Route-like golf relations (notably golf=hole) are linework, not polygon rings.
    // Join their member ways only when there is one unambiguous continuous chain.
    private fun parseLinearRelation(members: JSONArray): Parsed {
        val remaining = mutableListOf<List<GeoPoint>>()
        for (i in 0 until members.length()) {
            val member = members.optJSONObject(i) ?: continue
            if (member.optString("type") != "way") continue
            val geometry = member.optJSONArray("geometry") ?: continue
            val points = readCoordinates(geometry)
            if (points.size >= 2) remaining.add(points)
        }
        if (remaining.isEmpty()) return Parsed(error = "linear relation has no usable way members")
        val line = remaining.removeAt(0).toMutableList()
        while (remaining.isNotEmpty()) {
            val appendMatches = remaining.indices.filter { index ->
                remaining[index].first() == line.last() || remaining[index].last() == line.last()
            }
            val prependMatches = remaining.indices.filter { index ->
                remaining[index].first() == line.first() || remaining[index].last() == line.first()
            }
            if (appendMatches.size > 1 || prependMatches.size > 1) {
                return Parsed(error = "linear relation has ambiguous member-way junctions")
            }
            val index = appendMatches.firstOrNull() ?: prependMatches.firstOrNull()
                ?: return Parsed(error = "linear relation contains disconnected member ways")
            val segment = remaining.removeAt(index)
            when {
                segment.first() == line.last() -> line.addAll(segment.drop(1))
                segment.last() == line.last() -> line.addAll(segment.asReversed().drop(1))
                segment.last() == line.first() -> line.addAll(0, segment.dropLast(1))
                else -> line.addAll(0, segment.asReversed().dropLast(1))
            }
        }
        return Parsed(CourseGeometryType.LINE, line)
    }

    private fun parseRelation(members: JSONArray): Parsed {
        val outerSegments = mutableListOf<List<GeoPoint>>()
        val innerSegments = mutableListOf<List<GeoPoint>>()
        for (i in 0 until members.length()) {
            val member = members.optJSONObject(i) ?: continue
            if (member.optString("type") != "way") continue
            val geometry = member.optJSONArray("geometry") ?: continue
            val coords = readCoordinates(geometry)
            if (coords.size < 2) continue
            when (member.optString("role")) {
                "outer" -> outerSegments.add(coords)
                "inner" -> innerSegments.add(coords)
            }
        }
        val errors = mutableListOf<String>()
        val stitchedOuters = stitchRings(outerSegments, errors)
        val stitchedInners = stitchRings(innerSegments, errors)
        val outers = stitchedOuters.filter { ring ->
            val problem = ringProblem(ring)
            if (problem != null) errors.add("invalid outer ring: $problem")
            problem == null
        }
        val inners = stitchedInners.filter { ring ->
            val problem = ringProblem(ring)
            if (problem != null) errors.add("invalid inner ring: $problem")
            problem == null
        }
        if (outers.isEmpty()) return Parsed(error = errors.joinToString("; ").ifBlank { "missing valid outer ring" })
        val polygons = outers.map { outer -> mutableListOf(outer) }
        for (inner in inners) {
            // Checking every inner vertex prevents a ring whose first point happens
            // to lie inside an outer from being accepted when the rest lies outside.
            val container = polygons.firstOrNull { polygon ->
                inner.dropLast(1).all { pointInRing(it, polygon.first()) } &&
                    inner.dropLast(1).none { pointOnRingBoundary(it, polygon.first()) } &&
                    !ringsIntersect(inner, polygon.first())
            }
            if (container == null) {
                errors.add("inner ring is not fully contained by any outer ring")
            } else if (container.drop(1).any { existing -> ringsOverlap(existing, inner) }) {
                errors.add("inner rings overlap, touch, or are nested")
            } else {
                container.add(inner)
            }
        }
        // OSM multipolygon outer components must not overlap or contain one another.
        for (i in polygons.indices) for (j in i + 1 until polygons.size) {
            if (ringsOverlap(polygons[i].first(), polygons[j].first())) {
                errors.add("outer rings overlap or touch")
            }
        }
        val immutable = polygons.map { it.toList() }
        val firstOuter = immutable.firstOrNull()?.firstOrNull().orEmpty()
        return if (immutable.size == 1) {
            Parsed(CourseGeometryType.POLYGON, firstOuter, immutable.first(), error = errors.joinToString("; ").ifBlank { null })
        } else {
            Parsed(CourseGeometryType.MULTI_POLYGON, firstOuter, polygons = immutable, error = errors.joinToString("; ").ifBlank { null })
        }
    }

    private fun readCoordinates(array: JSONArray): List<GeoPoint> {
        val points = ArrayList<GeoPoint>(array.length())
        for (i in 0 until array.length()) {
            val point = array.optJSONObject(i) ?: continue
            val lat = point.optDouble("lat", Double.NaN)
            val lon = point.optDouble("lon", Double.NaN)
            if (lat.isFinite() && lon.isFinite()) points.add(GeoPoint(lat, lon))
        }
        return points
    }

    private fun stitchRings(segments: List<List<GeoPoint>>, errors: MutableList<String>): List<List<GeoPoint>> {
        val remaining = segments.toMutableList()
        val rings = mutableListOf<List<GeoPoint>>()
        while (remaining.isNotEmpty()) {
            val ring = remaining.removeAt(0).toMutableList()
            var guard = 0
            while (ring.first() != ring.last() && guard++ <= segments.size) {
                val appendMatches = remaining.indices.filter { index ->
                    remaining[index].first() == ring.last() || remaining[index].last() == ring.last()
                }
                val prependMatches = remaining.indices.filter { index ->
                    remaining[index].first() == ring.first() || remaining[index].last() == ring.first()
                }
                if (appendMatches.size > 1 || prependMatches.size > 1) {
                    errors.add("ambiguous ring junction: multiple member ways share an endpoint")
                    break
                }
                val appendIndex = appendMatches.firstOrNull()
                val prependIndex = prependMatches.firstOrNull()
                val index = appendIndex ?: prependIndex ?: break
                val segment = remaining.removeAt(index)
                when {
                    segment.first() == ring.last() -> ring.addAll(segment.drop(1))
                    segment.last() == ring.last() -> ring.addAll(segment.asReversed().drop(1))
                    segment.last() == ring.first() -> ring.addAll(0, segment.dropLast(1))
                    segment.first() == ring.first() -> ring.addAll(0, segment.asReversed().dropLast(1))
                }
            }
            if (ring.size >= 4 && ring.first() == ring.last()) rings.add(ring)
            else errors.add("could not close a polygon ring")
        }
        return rings
    }

    // Reject zero-area and self-intersecting rings before exposing them as usable areas.
    private fun ringProblem(ring: List<GeoPoint>): String? {
        if (ring.size < 4 || ring.first() != ring.last()) return "ring is not closed"
        var twiceArea = 0.0
        for (i in 0 until ring.lastIndex) {
            twiceArea += ring[i].longitude * ring[i + 1].latitude -
                ring[i + 1].longitude * ring[i].latitude
        }
        if (kotlin.math.abs(twiceArea) < 1e-14) return "ring has zero area"
        val edgeCount = ring.size - 1
        for (i in 0 until edgeCount) {
            for (j in i + 1 until edgeCount) {
                if (j == i + 1 || (i == 0 && j == edgeCount - 1)) continue
                if (segmentsIntersect(ring[i], ring[i + 1], ring[j], ring[j + 1])) {
                    return "ring self-intersects"
                }
            }
        }
        return null
    }

    private fun ringsIntersect(a: List<GeoPoint>, b: List<GeoPoint>): Boolean {
        for (i in 0 until a.lastIndex) for (j in 0 until b.lastIndex) {
            if (segmentsIntersect(a[i], a[i + 1], b[j], b[j + 1])) return true
        }
        return false
    }

    private fun ringsOverlap(a: List<GeoPoint>, b: List<GeoPoint>): Boolean =
        ringsIntersect(a, b) || pointInRing(a.first(), b) || pointInRing(b.first(), a)

    private fun segmentsIntersect(a: GeoPoint, b: GeoPoint, c: GeoPoint, d: GeoPoint): Boolean {
        fun orient(p: GeoPoint, q: GeoPoint, r: GeoPoint): Double =
            (q.longitude - p.longitude) * (r.latitude - p.latitude) -
                (q.latitude - p.latitude) * (r.longitude - p.longitude)
        fun onSegment(p: GeoPoint, q: GeoPoint, r: GeoPoint): Boolean =
            kotlin.math.abs(orient(p, q, r)) <= 1e-12 &&
                r.longitude >= minOf(p.longitude, q.longitude) - 1e-12 &&
                r.longitude <= maxOf(p.longitude, q.longitude) + 1e-12 &&
                r.latitude >= minOf(p.latitude, q.latitude) - 1e-12 &&
                r.latitude <= maxOf(p.latitude, q.latitude) + 1e-12
        val o1 = orient(a, b, c)
        val o2 = orient(a, b, d)
        val o3 = orient(c, d, a)
        val o4 = orient(c, d, b)
        if (((o1 > 1e-12 && o2 < -1e-12) || (o1 < -1e-12 && o2 > 1e-12)) &&
            ((o3 > 1e-12 && o4 < -1e-12) || (o3 < -1e-12 && o4 > 1e-12))) return true
        return onSegment(a, b, c) || onSegment(a, b, d) ||
            onSegment(c, d, a) || onSegment(c, d, b)
    }

    private fun pointOnRingBoundary(point: GeoPoint, ring: List<GeoPoint>): Boolean {
        for (i in 0 until ring.lastIndex) {
            val a = ring[i]
            val b = ring[i + 1]
            val cross = (point.longitude - a.longitude) * (b.latitude - a.latitude) -
                (point.latitude - a.latitude) * (b.longitude - a.longitude)
            if (kotlin.math.abs(cross) < 1e-10 &&
                point.longitude >= minOf(a.longitude, b.longitude) - 1e-10 &&
                point.longitude <= maxOf(a.longitude, b.longitude) + 1e-10 &&
                point.latitude >= minOf(a.latitude, b.latitude) - 1e-10 &&
                point.latitude <= maxOf(a.latitude, b.latitude) + 1e-10) return true
        }
        return false
    }

    private fun pointInRing(point: GeoPoint, ring: List<GeoPoint>): Boolean {
        var inside = false
        for (i in 0 until ring.lastIndex) {
            val a = ring[i]
            val b = ring[i + 1]
            if ((a.latitude > point.latitude) != (b.latitude > point.latitude)) {
                val crossing = (b.longitude - a.longitude) *
                    (point.latitude - a.latitude) / (b.latitude - a.latitude) + a.longitude
                if (point.longitude < crossing) inside = !inside
            }
        }
        return inside
    }
}
