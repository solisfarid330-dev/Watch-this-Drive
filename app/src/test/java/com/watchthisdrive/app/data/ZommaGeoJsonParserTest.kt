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
