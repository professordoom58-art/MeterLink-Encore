package com.utility.dlmsreader.dlms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the event-code and reactive-energy mappings against the live field log
 * MeterLink_Report_92717295 (L&T 1P-2W, 40 Current Events = 20 pairs of 69/70
 * with phase-vs-neutral current differentials, Cumulative Tamper Count = 20).
 */
class TamperAndReactiveMappingTest {

    @Test
    fun `earth loading codes are not shifted`() {
        assertEquals("Earth Loading - Occurrence", Is15959Formatter.decodeTamperEvent(69))
        assertEquals("Earth Loading - Restoration", Is15959Formatter.decodeTamperEvent(70))
    }

    @Test
    fun `power failure codes are unchanged`() {
        assertEquals("Power Failure - Occurrence", Is15959Formatter.decodeTamperEvent(101))
        assertEquals("Power Failure - Restoration", Is15959Formatter.decodeTamperEvent(102))
    }

    @Test
    fun `current compartment codes follow the SMRS current table`() {
        assertEquals("Load Imbalance - Occurrence", Is15959Formatter.decodeTamperEvent(63))
        assertEquals("Load Imbalance - Restoration", Is15959Formatter.decodeTamperEvent(64))
        assertEquals("CT Short - Occurrence", Is15959Formatter.decodeTamperEvent(65))
        assertEquals("CT Short - Restoration", Is15959Formatter.decodeTamperEvent(66))
        assertEquals("Overload (Current) - Occurrence", Is15959Formatter.decodeTamperEvent(67))
        assertEquals("Overload (Current) - Restoration", Is15959Formatter.decodeTamperEvent(68))
    }

    @Test
    fun `ct reversed and ct open keep their codes`() {
        assertEquals("R-Phase CT Reverse - Occurrence", Is15959Formatter.decodeTamperEvent(51))
        assertEquals("B-Phase CT Reverse - Restoration", Is15959Formatter.decodeTamperEvent(56))
        assertEquals("R-Phase CT Open - Occurrence", Is15959Formatter.decodeTamperEvent(57))
        assertEquals("B-Phase CT Open - Restoration", Is15959Formatter.decodeTamperEvent(62))
    }

    @Test
    fun `single wire operation codes are present`() {
        assertEquals(
            "Single Wire Operation (Neutral Missing) - Occurrence",
            Is15959Formatter.decodeTamperEvent(207)
        )
        assertEquals(
            "Single Wire Operation (Neutral Missing) - Restoration",
            Is15959Formatter.decodeTamperEvent(208)
        )
    }

    @Test
    fun `reactive energy quadrants are labelled distinctly`() {
        val q1 = Is15959Formatter.getObisLabelAndUnit("1.0.5.8.0.255")
        val q2 = Is15959Formatter.getObisLabelAndUnit("1.0.6.8.0.255")
        val q3 = Is15959Formatter.getObisLabelAndUnit("1.0.7.8.0.255")
        val q4 = Is15959Formatter.getObisLabelAndUnit("1.0.8.8.0.255")

        assertEquals("Reactive Energy Q1 - Inductive Import (kVArh)", q1.label)
        assertEquals("Reactive Energy Q2 - Capacitive Import (kVArh)", q2.label)
        assertEquals("Reactive Energy Q3 - Inductive Export (kVArh)", q3.label)
        assertEquals("Reactive Energy Q4 - Capacitive Export (kVArh)", q4.label)
        listOf(q1, q2, q3, q4).forEach { assertEquals("kVArh", it.unit) }

        // No two quadrants may share a label.
        val labels = listOf(q1, q2, q3, q4).map { it.label }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `legacy lag and lead codes survive as fallback labels only`() {
        assertTrue(
            Is15959Formatter.getObisLabelAndUnit("1.0.3.8.0.255").label.contains("fallback")
        )
        assertTrue(
            Is15959Formatter.getObisLabelAndUnit("1.0.4.8.0.255").label.contains("fallback")
        )
    }

    @Test
    fun `tod zones 1 through 8 resolve for import and export`() {
        (1..8).forEach { zone ->
            val import = Is15959Formatter.getObisLabelAndUnit("1.0.1.8.$zone.255")
            val export = Is15959Formatter.getObisLabelAndUnit("1.0.2.8.$zone.255")
            val kvahImport = Is15959Formatter.getObisLabelAndUnit("1.0.9.8.$zone.255")
            val kvahExport = Is15959Formatter.getObisLabelAndUnit("1.0.10.8.$zone.255")

            assertEquals("kWh ToD Zone $zone - Import", import.label)
            assertEquals("kWh ToD Zone $zone - Export", export.label)
            assertEquals("kVAh ToD Zone $zone - Import", kvahImport.label)
            assertEquals("kVAh ToD Zone $zone - Export", kvahExport.label)
        }
    }

    @Test
    fun `export side maximum demand is labelled`() {
        assertEquals("kW", Is15959Formatter.getObisLabelAndUnit("1.0.2.6.0.255").unit)
        assertEquals("kVA", Is15959Formatter.getObisLabelAndUnit("1.0.10.6.0.255").unit)
        assertFalse(
            Is15959Formatter.getObisLabelAndUnit("1.0.1.6.5.255").label.startsWith("OBIS ")
        )
        assertFalse(
            Is15959Formatter.getObisLabelAndUnit("1.0.9.6.8.255").label.startsWith("OBIS ")
        )
    }

    @Test
    fun `report with only errors is not treated as having data`() {
        val errorOnly = MeterDataReport(errors = listOf("Billing profile unavailable: read denied"))
        assertFalse(errorOnly.hasData)

        val withIdentity = MeterDataReport(
            identity = IdentitySection(
                rows = listOf(ParameterRow("Serial", "0.0.96.1.0.255", "92717295"))
            )
        )
        assertTrue(withIdentity.hasData)
    }
}
