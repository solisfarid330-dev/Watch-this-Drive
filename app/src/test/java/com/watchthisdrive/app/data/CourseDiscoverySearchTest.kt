package com.watchthisdrive.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseDiscoverySearchTest {

    @Test
    fun fiveDigitZipUsesStructuredUsPostalCodeLookup() {
        val url = CourseDiscovery.buildGeocodingUrl("75001")

        assertTrue(url.contains("postalcode=75001"))
        assertTrue(url.contains("country=United%20States"))
        assertTrue(url.contains("countrycodes=us"))
        assertFalse(url.contains("?q=75001"))
    }

    @Test
    fun zipPlusFourUsesFiveDigitPostalArea() {
        val url = CourseDiscovery.buildGeocodingUrl("75001-1234")

        assertTrue(url.contains("postalcode=75001"))
        assertFalse(url.contains("1234"))
    }

    @Test
    fun courseAndCitySearchesRemainFreeTextQueries() {
        val url = CourseDiscovery.buildGeocodingUrl("Lake Arlington Golf Course")

        assertTrue(url.contains("q=Lake+Arlington+Golf+Course"))
        assertFalse(url.contains("postalcode="))
    }
}
