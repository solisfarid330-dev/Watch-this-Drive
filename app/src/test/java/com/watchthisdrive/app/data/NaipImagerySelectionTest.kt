package com.watchthisdrive.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NaipImagerySelectionTest {
    @Test
    fun newestAcquisitionTakesPriorityOverOlderHigherResolution() {
        val selected = NaipImageryRepository.selectBestRasters(
            listOf(
                NaipImageryRepository.RasterRecord(1, 2023, 1_690_000_000_000, 0.3, "m", 4),
                NaipImageryRepository.RasterRecord(2, 2025, 1_750_000_000_000, 0.6, "m", 4),
                NaipImageryRepository.RasterRecord(3, 2025, 1_751_000_000_000, 0.3, "m", 4)
            )
        )
        assertEquals(2025, selected?.year)
        assertEquals(listOf(3L), selected?.rasterIds)
        assertEquals(0.3, selected?.resolutionMeters ?: 0.0, 0.000001)
    }

    @Test
    fun retainsAllMatchingTilesAtTheFinestResolutionInTheNewestVintage() {
        val selected = NaipImageryRepository.selectBestRasters(
            listOf(
                NaipImageryRepository.RasterRecord(10, 2025, 1_750_000_000_000, 0.3, "m", 4),
                NaipImageryRepository.RasterRecord(11, 2025, 1_750_100_000_000, 0.31, "m", 4),
                NaipImageryRepository.RasterRecord(12, 2025, 1_750_200_000_000, 0.6, "m", 4),
                NaipImageryRepository.RasterRecord(13, 2025, 1_750_300_000_000, 0.3, "m", 3)
            )
        )
        assertEquals(listOf(10L, 11L), selected?.rasterIds)
        assertEquals(4, selected?.bandCount)
    }

    @Test
    fun normalizesFeetToMetersWhenComparingResolution() {
        val selected = NaipImageryRepository.selectBestRasters(
            listOf(
                NaipImageryRepository.RasterRecord(20, 2024, null, 2.0, "ft", 4),
                NaipImageryRepository.RasterRecord(21, 2024, null, 0.5, "m", 4)
            )
        )
        assertEquals(listOf(21L), selected?.rasterIds)
        assertEquals(0.5, selected?.resolutionMeters ?: 0.0, 0.000001)
    }

    @Test
    fun rejectsImageryWithoutFourBands() {
        val selected = NaipImageryRepository.selectBestRasters(
            listOf(NaipImageryRepository.RasterRecord(30, 2025, null, 0.3, "m", 3))
        )
        assertNull(selected)
    }
}
