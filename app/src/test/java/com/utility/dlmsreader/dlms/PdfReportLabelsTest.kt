package com.utility.dlmsreader.dlms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfReportLabelsTest {

    private fun label(name: String, obis: String, unit: String = "") =
        PdfReportLabels.resolve(name, obis, unit)

    @Test
    fun `unmapped export demand codes read as names`() {
        assertEquals(
            "MD Active Export - ToD Zone 2 (kW)",
            label("OBIS 1.0.2.6.2.255", "1.0.2.6.2.255", "kW").text
        )
        assertEquals(
            "MD Apparent Export - ToD Zone 8 (kVA)",
            label("OBIS 1.0.10.6.8.255", "1.0.10.6.8.255", "kVA").text
        )
        assertEquals(
            "Cum. MD Active Export (kW)",
            label("OBIS 1.0.2.2.0.255", "1.0.2.2.0.255").text
        )
        // Some CSV imports carry the fallback name but no separate code.
        assertEquals(
            "MD Active Export - ToD Zone 2 (kW)",
            label("OBIS 1.0.2.6.2.255", "", "kW").text
        )
    }

    @Test
    fun `known catalogue and plain labels are preserved`() {
        assertEquals(
            "Cumulative Active Energy - Import (kWh)",
            label(
                "Cumulative Active Energy - Import (kWh)",
                "1.0.1.8.0.255",
                "kWh"
            ).text
        )
        assertEquals("Timestamp", label("Timestamp", "1.0.2.6.1.255", "kW").text)
    }

    @Test
    fun `unknown vendor registers derive a readable unique label`() {
        val labels = PdfReportLabels.allocate(
            listOf(
                label("OBIS 1.0.128.7.65.255", "1.0.128.7.65.255", "kWh"),
                label("OBIS 1.0.128.7.68.255", "1.0.128.7.68.255", "kVAh"),
                label("OBIS 1.0.128.7.66.255", "1.0.128.7.66.255", "kVArh"),
                label("OBIS 1.0.128.7.67.255", "1.0.128.7.67.255", "kVArh")
            )
        )

        assertEquals(
            listOf(
                "Cumulative Active Energy (kWh)",
                "Cumulative Apparent Energy (kVAh)",
                "Cumulative Reactive Energy (kVArh)",
                "Cumulative Reactive Energy (kVArh) 2"
            ),
            labels
        )
        assertTrue(labels.none(PdfReportLabels::containsObis))
    }

    @Test
    fun `scrub replaces wrapped and bare addresses`() {
        val text = PdfReportLabels.scrub(
            "Read failed for OBIS 1.0.2.6.2.255 and 1.0.128.7.65.255"
        )

        assertFalse(PdfReportLabels.containsObis(text))
        assertTrue(text.contains("MD Active Export - ToD Zone 2 (kW)"))
        assertTrue(text.contains("Meter Reading"))
    }

    @Test
    fun `phase power codes resolve to phase names`() {
        assertEquals(
            "R-Phase Active Power (kW)",
            label("OBIS 1.0.36.7.0.255", "1.0.36.7.0.255", "kW").text
        )
        assertEquals(
            "Y-Phase Active Power (kW)",
            label("OBIS 1.0.56.7.0.255", "1.0.56.7.0.255", "kW").text
        )
        assertEquals(
            "B-Phase Active Power (kW)",
            label("OBIS 1.0.76.7.0.255", "1.0.76.7.0.255", "kW").text
        )
    }
}
