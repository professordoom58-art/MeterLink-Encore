package com.utility.dlmsreader

import com.utility.dlmsreader.dlms.ExtractionProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the percentage-only extraction banner contract. */
class ExtractionProgressTest {

    @Test
    fun `section labels are exactly the user facing names`() {
        assertEquals("Identity 0%", ExtractionProgress.format(ExtractionProgress.IDENTITY, 0))
        assertEquals("Energy & MD 50%", ExtractionProgress.format(ExtractionProgress.ENERGY, 50))
        assertEquals(
            "Block Load Survey 99%",
            ExtractionProgress.format(ExtractionProgress.BLOCK_LOAD, 99)
        )
        assertEquals("Daily Load Survey 100%", ExtractionProgress.format(ExtractionProgress.DAILY_LOAD, 100))
        assertEquals("Tamper Logs 14%", ExtractionProgress.format(ExtractionProgress.TAMPER, 14))
    }

    @Test
    fun `identity steps run from zero to one hundred`() {
        assertEquals("Identity 0%", ExtractionProgress.step(ExtractionProgress.IDENTITY, 0, 7))
        assertEquals("Identity 14%", ExtractionProgress.step(ExtractionProgress.IDENTITY, 1, 7))
        assertEquals("Identity 57%", ExtractionProgress.step(ExtractionProgress.IDENTITY, 4, 7))
        assertEquals("Identity 100%", ExtractionProgress.step(ExtractionProgress.IDENTITY, 7, 7))
    }

    @Test
    fun `block load percentages are clamped and never complete early`() {
        assertEquals("Block Load Survey 0%", ExtractionProgress.step(ExtractionProgress.BLOCK_LOAD, 0, 3605))
        assertEquals(
            "Block Load Survey 37%",
            ExtractionProgress.format(ExtractionProgress.BLOCK_LOAD, 37)
        )
        // Streaming frames are clamped to 99% by the reader; the formatter
        // itself saturates at the valid 0..100 range.
        assertEquals(
            "Block Load Survey 99%",
            ExtractionProgress.format(ExtractionProgress.BLOCK_LOAD, 99)
        )
        assertEquals(
            "Block Load Survey 100%",
            ExtractionProgress.format(ExtractionProgress.BLOCK_LOAD, 140)
        )
    }

    @Test
    fun `trailing percent is parsed for the determinate bar`() {
        assertEquals(0, ExtractionProgress.parsePercent("Identity 0%"))
        assertEquals(37, ExtractionProgress.parsePercent("Block Load Survey 37%"))
        assertEquals(100, ExtractionProgress.parsePercent("Tamper Logs 100%"))
        assertNull(ExtractionProgress.parsePercent("Connecting to meter..."))
        assertNull(ExtractionProgress.parsePercent(null))
    }

    @Test
    fun `frame diagnostics are collapsed to a bare percentage`() {
        val noisy = "Block Load Survey (2 Days): Frame 315 " +
            "(3605 entries, ~37%, ~1342 received, 38.0 KB)"
        val section = "Block Load Survey"
        val percent = ExtractionProgress.parseEmbeddedPercent(noisy)
        val banner = ExtractionProgress.format(section, percent ?: 0)

        assertEquals(37, percent)
        assertEquals("Block Load Survey 37%", banner)
        assertFalse(banner.contains("Frame"))
        assertFalse(banner.contains("entries"))
        assertFalse(banner.contains("KB"))
        assertTrue(banner.endsWith("%"))
    }
}
