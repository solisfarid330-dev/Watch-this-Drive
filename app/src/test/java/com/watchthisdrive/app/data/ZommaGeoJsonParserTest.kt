package com.watchthisdrive.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZommaGeoJsonParserTest {
    @Test
    fun parsesFeatureCollectionAndMapsGolfProperties() {
        val json = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "id": "fairway-1",
                  "properties": {"golf": "fairway", "hole_number": 3},
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [[
                      [-97.0, 32.0], [-96.999, 32.0],
                      [-96.999, 32.001], [-97.0, 32.0]
                    ]]
                  }
                },
                {
                  "type": "Feature",
                  "id": "green-3",
                  "properties": {"feature_type": "green", "hole": "3", "par": 4},
                  "geometry": {"type": "Point", "coordinates": [-96.998, 32.002]}
                }
              ]
            }
        """.trimIndent()

        val parsed = ZommaGeoJsonParser.parse(json)

        assertEquals(2, parsed.size)
        val fairway = parsed.first { it.featureType == "fairway" }
        assertEquals("3", fairway.holeNumber)
        assertEquals(4, fairway.points.size)
        assertEquals(32.0, fairway.points.first().latitude, 0.000001)
        assertEquals(-97.0, fairway.points.first().longitude, 0.000001)

        val green = parsed.first { it.featureType == "green" }
        assertEquals(4, green.par)
        assertEquals(3, green.holeNumber?.toInt())
        assertEquals(1, green.points.size)
    }

    @Test
    fun preservesPredictedGeometryProvenanceAndNormalizesLayerNames() {
        val json = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "id": "predicted-fairway",
                  "properties": {
                    "layer": "Fairway",
                    "source": "SatlasPretrain model prediction",
                    "confidence": "medium",
                    "predicted": true,
                    "hole": 4
                  },
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [[
                      [-97.0, 32.0], [-96.999, 32.0],
                      [-96.999, 32.001], [-97.0, 32.0]
                    ]]
                  }
                },
                {
                  "type": "Feature",
                  "id": "rough",
                  "properties": {"layer": "Rough / in play"},
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [[
                      [-97.0, 32.0], [-96.999, 32.0],
                      [-96.999, 32.001], [-97.0, 32.0]
                    ]]
                  }
                }
              ]
            }
        """.trimIndent()

        val parsed = ZommaGeoJsonParser.parse(json)
        val fairway = parsed.first { it.id < 0 && it.featureType == "fairway" }
        assertTrue(fairway.isPredicted)
        assertEquals("SatlasPretrain model prediction", fairway.source)
        assertEquals("medium", fairway.confidence)
        assertEquals("4", fairway.holeNumber)
        assertTrue(parsed.any { it.featureType == "rough" })
    }

    @Test
    fun mapsCanonicalOsmNaturalWaterWoodlandAndPathTags() {
        val json = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature", "id": "pond",
                  "properties": {"tags": {"natural": "water", "golf:hole": "4"}},
                  "geometry": {"type": "Polygon", "coordinates": [[
                    [-97.0,32.0],[-96.999,32.0],[-96.999,32.001],[-97.0,32.0]
                  ]]}
                },
                {
                  "type": "Feature", "id": "woods",
                  "properties": {"tags": {"landuse": "forest"}},
                  "geometry": {"type": "Polygon", "coordinates": [[
                    [-97.0,32.0],[-96.999,32.0],[-96.999,32.001],[-97.0,32.0]
                  ]]}
                },
                {
                  "type": "Feature", "id": "cart-path",
                  "properties": {"tags": {"highway": "path", "golf": "cartpath"}},
                  "geometry": {"type": "LineString", "coordinates": [
                    [-97.0,32.0],[-96.999,32.001]
                  ]}
                },
                {
                  "type": "Feature", "id": "river",
                  "properties": {"tags": {"waterway": "river"}},
                  "geometry": {"type": "LineString", "coordinates": [
                    [-97.0,32.0],[-96.999,32.001]
                  ]}
                },
                {
                  "type": "Feature", "id": "course-outline",
                  "properties": {"tags": {"leisure": "golf_course"}},
                  "geometry": {"type": "Polygon", "coordinates": [[
                    [-97.0,32.0],[-96.999,32.0],[-96.999,32.001],[-97.0,32.0]
                  ]]}
                },
                {
                  "type": "Feature", "id": "grass",
                  "properties": {"tags": {"landuse": "grass"}},
                  "geometry": {"type": "Polygon", "coordinates": [[
                    [-97.0,32.0],[-96.999,32.0],[-96.999,32.001],[-97.0,32.0]
                  ]]}
                },
                {
                  "type": "Feature", "id": "scrub",
                  "properties": {"tags": {"natural": "scrub"}},
                  "geometry": {"type": "Polygon", "coordinates": [[
                    [-97.0,32.0],[-96.999,32.0],[-96.999,32.001],[-97.0,32.0]
                  ]]}
                },
                {
                  "type": "Feature", "id": "track",
                  "properties": {"tags": {"highway": "track"}},
                  "geometry": {"type": "LineString", "coordinates": [
                    [-97.0,32.0],[-96.999,32.001]
                  ]}
                }
              ]
            }
        """.trimIndent()

        val parsed = ZommaGeoJsonParser.parse(json)
        assertTrue(parsed.any { it.featureType == "water" && it.holeNumber == "4" })
        assertTrue(parsed.any { it.featureType == "woodland" })
        assertTrue(parsed.any { it.featureType == "path" })
        assertTrue(parsed.any { it.featureType == "water" && it.holeNumber == null })
        assertTrue(parsed.any { it.featureType == "course_boundary" })
        assertTrue(parsed.any { it.featureType == "rough" })
        assertTrue(parsed.any { it.featureType == "woodland" && it.id < 0 })
        assertTrue(parsed.any { it.featureType == "path" && it.id < 0 })
    }

    @Test
    fun directOsmGeometryWinsOverCoincidentZommaPrediction() {
        val point = listOf(GeoPoint(32.0, -97.0), GeoPoint(32.001, -97.0), GeoPoint(32.0, -96.999))
        val prediction = CourseFeature(
            id = -1, elementType = "zomma", featureType = "fairway",
            holeNumber = "2", name = null, points = point, source = "model", isPredicted = true
        )
        val mapped = CourseFeature(
            id = 22, elementType = "way", featureType = "fairway",
            holeNumber = "2", name = null, points = point, source = "OpenStreetMap"
        )

        val merged = CourseGeometryLoader.mergeProviderFeatures(listOf(prediction), listOf(mapped))
        assertEquals(1, merged.size)
        assertEquals("way", merged.single().elementType)
        assertTrue(!merged.single().isPredicted)
    }

    @Test
    fun providerMergeKeepsDistinctNearbyBunkersSeparate() {
        val first = listOf(
            GeoPoint(32.0, -97.0),
            GeoPoint(32.0, -96.999955),
            GeoPoint(32.000045, -96.999955),
            GeoPoint(32.000045, -97.0),
            GeoPoint(32.0, -97.0)
        )
        val second = listOf(
            GeoPoint(32.0, -96.99991),
            GeoPoint(32.0, -96.999865),
            GeoPoint(32.000045, -96.999865),
            GeoPoint(32.000045, -96.99991),
            GeoPoint(32.0, -96.99991)
        )
        val firstBunker = CourseFeature(
            id = -21, elementType = "zomma", featureType = "bunker",
            holeNumber = "2", name = null, points = first, isPredicted = true
        )
        val secondBunker = CourseFeature(
            id = 22, elementType = "way", featureType = "bunker",
            holeNumber = "2", name = null, points = second
        )

        val merged = CourseGeometryLoader.mergeProviderFeatures(listOf(firstBunker), listOf(secondBunker))

        assertEquals(2, merged.size)
        assertEquals(setOf(-21L, 22L), merged.map { it.id }.toSet())
    }

    @Test
    fun providerMergeRecognizesSamePolygonWithReversedRingStartAndDirection() {
        val firstRing = listOf(
            GeoPoint(32.0, -97.0),
            GeoPoint(32.0, -96.9991),
            GeoPoint(32.0009, -96.9991),
            GeoPoint(32.0009, -97.0),
            GeoPoint(32.0, -97.0)
        )
        val reversedRing = listOf(
            GeoPoint(32.0009, -96.9991),
            GeoPoint(32.0, -96.9991),
            GeoPoint(32.0, -97.0),
            GeoPoint(32.0009, -97.0),
            GeoPoint(32.0009, -96.9991)
        )
        val prediction = CourseFeature(
            id = -11, elementType = "zomma", featureType = "fairway",
            holeNumber = "3", name = null, points = firstRing,
            source = "model prediction", isPredicted = true
        )
        val mapped = CourseFeature(
            id = 31, elementType = "way", featureType = "fairway",
            holeNumber = "3", name = null, points = reversedRing
        )

        val merged = CourseGeometryLoader.mergeProviderFeatures(listOf(prediction), listOf(mapped))

        assertEquals(1, merged.size)
        assertEquals("way", merged.single().elementType)
        assertTrue(!merged.single().isPredicted)
    }

    @Test
    fun preservesEveryMultipolygonComponent() {
        val json = """
            {
              "type": "FeatureCollection",
              "features": [{
                "type": "Feature",
                "id": "split-water",
                "properties": {"feature_type": "water", "hole": 6},
                "geometry": {
                  "type": "MultiPolygon",
                  "coordinates": [
                    [[[-97.0,32.0],[-96.999,32.0],[-96.999,32.001],[-97.0,32.0]]],
                    [[[-96.998,32.0],[-96.997,32.0],[-96.997,32.001],[-96.998,32.0]]]
                  ]
                }
              }]
            }
        """.trimIndent()

        val parsed = ZommaGeoJsonParser.parse(json)
        assertEquals(2, parsed.size)
        assertTrue(parsed.all { it.featureType == "water" && it.holeNumber == "6" })
        assertTrue(parsed.all { it.points.size == 4 })
        assertTrue(parsed.map { it.points.first().longitude }.distinct().size == 2)
    }

    @Test
    fun retainsAValidCourseOutlineEvenWhenNoHoleFeaturesAreMapped() {
        val json = """
            {
              "type": "FeatureCollection",
              "features": [{
                "type": "Feature",
                "id": "course-outline",
                "properties": {"tags": {"leisure": "golf_course"}},
                "geometry": {"type": "Polygon", "coordinates": [[
                  [-97.0,32.0],[-96.999,32.0],[-96.999,32.001],[-97.0,32.0]
                ]]}
              }]
            }
        """.trimIndent()
        val parsed = ZommaGeoJsonParser.parse(json)
        assertEquals(1, parsed.size)
        assertEquals("course_boundary", parsed.single().featureType)
    }

    @Test
    fun ignoresUnknownAndInvalidFeaturesWithoutInventingGeometry() {
        val json = """
            {
              "type": "FeatureCollection",
              "features": [
                {"type":"Feature","properties":{"name":"unknown"},
                 "geometry":{"type":"Point","coordinates":[-97.0,32.0]}},
                {"type":"Feature","properties":{"golf":"green"},
                 "geometry":{"type":"Point","coordinates":[400.0,120.0]}}
              ]
            }
        """.trimIndent()

        assertTrue(ZommaGeoJsonParser.parse(json).isEmpty())
    }
}
