package com.watchthisdrive.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStreamReader
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.math.cos

/**
 * Imports the verified OpenGolfAPI US release into an indexed on-device SQLite
 * catalog. The bundled file is a course directory only; its Point geometry is
 * never used as hole geometry.
 */
internal object OfflineCourseCatalog {
    private const val DATABASE_VERSION = 1
    private const val DATABASE_NAME = "watch_this_drive_courses.db"
    private const val ASSET_NAME = "opengolfapi-us.geojson.gz"
    private const val DATASET_VERSION = "v2.1.0"
    private const val RADIUS_METERS = 15_000.0

    private class CatalogDb(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        init { setWriteAheadLoggingEnabled(true) }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE courses (
                    id TEXT PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    latitude REAL NOT NULL,
                    longitude REAL NOT NULL,
                    country TEXT,
                    state TEXT,
                    city TEXT,
                    course_type TEXT,
                    holes INTEGER,
                    par INTEGER,
                    phone TEXT,
                    website TEXT,
                    address TEXT,
                    postal_code TEXT,
                    total_yardage INTEGER,
                    osm_id INTEGER,
                    scorecard TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_course_country_state ON courses(country, state)")
            db.execSQL("CREATE INDEX idx_course_country_lat_lon ON courses(country, latitude, longitude)")
            db.execSQL("CREATE INDEX idx_course_name ON courses(name)")
            db.execSQL(
                "CREATE TABLE catalog_meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS courses")
            db.execSQL("DROP TABLE IF EXISTS catalog_meta")
            onCreate(db)
        }
    }

    @Synchronized
    private fun ensureImported(context: Context, helper: CatalogDb): Boolean {
        val db = helper.writableDatabase
        val existing = db.rawQuery("SELECT COUNT(*) FROM courses", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }
        if (existing > 0L) return true

        return runCatching {
            context.assets.open(ASSET_NAME).use { compressed ->
                JsonReader(InputStreamReader(GZIPInputStream(compressed), Charsets.UTF_8)).use { reader ->
                    db.beginTransaction()
                    try {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "features" -> {
                                    reader.beginArray()
                                    while (reader.hasNext()) readFeature(reader, db)
                                    reader.endArray()
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        db.execSQL(
                            "INSERT OR REPLACE INTO catalog_meta(key, value) VALUES('dataset_version', ?)",
                            arrayOf(DATASET_VERSION)
                        )
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                }
            }
            val count = db.rawQuery("SELECT COUNT(*) FROM courses", null).use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }
            count > 0L
        }.getOrDefault(false)
    }

    private data class Properties(
        val id: String? = null,
        val name: String? = null,
        val country: String? = null,
        val state: String? = null,
        val city: String? = null,
        val type: String? = null,
        val holes: Int? = null,
        val par: Int? = null,
        val phone: String? = null,
        val website: String? = null,
        val address: String? = null,
        val postalCode: String? = null,
        val totalYardage: Int? = null,
        val osmId: Long? = null,
        val scorecard: String = "[]"
    )

    private fun readFeature(reader: JsonReader, db: SQLiteDatabase) {
        var latitude: Double? = null
        var longitude: Double? = null
        var properties: Properties? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "geometry" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "coordinates" -> {
                                reader.beginArray()
                                if (reader.hasNext()) longitude = reader.nextDouble()
                                if (reader.hasNext()) latitude = reader.nextDouble()
                                while (reader.hasNext()) reader.skipValue()
                                reader.endArray()
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                "properties" -> properties = readProperties(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val p = properties ?: return
        val id = p.id ?: return
        val name = p.name?.trim()?.takeIf { it.isNotBlank() } ?: return
        val lat = latitude?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return
        val lon = longitude?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return

        val values = ContentValues().apply {
            put("id", id)
            put("name", name)
            put("latitude", lat)
            put("longitude", lon)
            put("country", p.country)
            put("state", p.state)
            put("city", p.city)
            put("course_type", p.type)
            p.holes?.let { put("holes", it) }
            p.par?.let { put("par", it) }
            put("phone", p.phone)
            put("website", p.website)
            put("address", p.address)
            put("postal_code", p.postalCode)
            p.totalYardage?.let { put("total_yardage", it) }
            p.osmId?.let { put("osm_id", it) }
            put("scorecard", p.scorecard)
        }
        db.insertWithOnConflict("courses", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun readProperties(reader: JsonReader): Properties {
        var id: String? = null
        var name: String? = null
        var country: String? = null
        var state: String? = null
        var city: String? = null
        var type: String? = null
        var holes: Int? = null
        var par: Int? = null
        var phone: String? = null
        var website: String? = null
        var address: String? = null
        var postalCode: String? = null
        var totalYardage: Int? = null
        var osmId: Long? = null
        var scorecard = "[]"

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = readString(reader)
                "name" -> name = readString(reader)
                "country" -> country = readString(reader)
                "state" -> state = readString(reader)
                "city" -> city = readString(reader)
                "type" -> type = readString(reader)
                "holes" -> holes = readInt(reader)
                "par" -> par = readInt(reader)
                "phone" -> phone = readString(reader)
                "website" -> website = readString(reader)
                "address" -> address = readString(reader)
                "postal_code" -> postalCode = readString(reader)
                "total_yardage" -> totalYardage = readInt(reader)
                "osm_id" -> osmId = readLong(reader)
                "scorecard" -> scorecard = readScorecard(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return Properties(
            id, name, country, state, city, type, holes, par, phone, website,
            address, postalCode, totalYardage, osmId, scorecard
        )
    }

    private fun readScorecard(reader: JsonReader): String {
        val entries = org.json.JSONArray()
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return entries.toString()
        }
        reader.beginArray()
        while (reader.hasNext()) {
            var hole: Int? = null
            var par: Int? = null
            var handicap: Int? = null
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "hole" -> hole = readInt(reader)
                    "par" -> par = readInt(reader)
                    "handicap_index" -> handicap = readInt(reader)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (hole != null) {
                entries.put(org.json.JSONObject().apply {
                    put("hole", hole)
                    par?.let { put("par", it) }
                    handicap?.let { put("handicap_index", it) }
                })
            }
        }
        reader.endArray()
        return entries.toString()
    }

    private fun readString(reader: JsonReader): String? =
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            null
        } else reader.nextString().takeIf { it.isNotBlank() && it != "null" }

    private fun readInt(reader: JsonReader): Int? =
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            null
        } else reader.nextString().toIntOrNull()

    private fun readLong(reader: JsonReader): Long? =
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            null
        } else reader.nextString().toLongOrNull()

    fun searchNearby(
        context: Context,
        latitude: Double,
        longitude: Double
    ): List<GolfCourse> {
        val helper = CatalogDb(context.applicationContext)
        return try {
            if (!ensureImported(context.applicationContext, helper)) return emptyList()
            val latDelta = RADIUS_METERS / 111_000.0
            val lonDelta = RADIUS_METERS /
                (111_000.0 * cos(Math.toRadians(latitude)).coerceAtLeast(0.05))
            val cursor = helper.readableDatabase.rawQuery(
                """
                SELECT id, name, latitude, longitude, country, state, city, course_type,
                       holes, par, phone, website, address, postal_code, scorecard
                FROM courses
                WHERE country = 'US'
                  AND latitude BETWEEN ? AND ?
                  AND longitude BETWEEN ? AND ?
                """.trimIndent(),
                arrayOf(
                    (latitude - latDelta).toString(),
                    (latitude + latDelta).toString(),
                    (longitude - lonDelta).toString(),
                    (longitude + lonDelta).toString()
                )
            )
            cursor.use {
                val results = mutableListOf<GolfCourse>()
                while (it.moveToNext()) {
                    val lat = it.getDouble(2)
                    val lon = it.getDouble(3)
                    val distance = FloatArray(1)
                    android.location.Location.distanceBetween(latitude, longitude, lat, lon, distance)
                    if (distance[0] > RADIUS_METERS) continue
                    val scorecard = runCatching {
                        val array = org.json.JSONArray(it.getString(14))
                        (0 until array.length()).mapNotNull { index ->
                            val hole = array.optJSONObject(index) ?: return@mapNotNull null
                            val number = hole.optInt("hole", 0)
                            if (number !in 1..18) return@mapNotNull null
                            CourseHoleInfo(
                                number = number,
                                par = hole.optInt("par", 0).takeIf { value -> value in 3..6 },
                                handicap = hole.optInt("handicap_index", 0).takeIf { value -> value in 1..18 },
                                yardage = null
                            )
                        }
                    }.getOrDefault(emptyList())
                    val id = it.getString(0)
                    results += GolfCourse(
                        id = stableCatalogId(id),
                        elementType = "node",
                        name = it.getString(1),
                        latitude = lat,
                        longitude = lon,
                        distanceMeters = distance[0],
                        city = it.getStringOrNull(6),
                        region = it.getStringOrNull(5),
                        countryCode = it.getStringOrNull(4),
                        address = it.getStringOrNull(12),
                        postalCode = it.getStringOrNull(13),
                        holes = it.getIntOrNull(8),
                        par = it.getIntOrNull(9),
                        courseType = it.getStringOrNull(7),
                        website = it.getStringOrNull(11),
                        phone = it.getStringOrNull(10),
                        openGolfId = id,
                        scorecard = scorecard,
                        sourceNames = setOf("OpenGolfAPI offline dataset $DATASET_VERSION")
                    )
                }
                results.sortedBy { course -> course.distanceMeters }
            }
        } finally {
            helper.close()
        }
    }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun android.database.Cursor.getIntOrNull(index: Int): Int? =
        if (isNull(index)) null else getInt(index)

    private fun stableCatalogId(id: String): Long {
        val key = "opengolf:$id"
        return -((key.hashCode().toLong() and 0x7fffffffL) + 1L)
    }
}
