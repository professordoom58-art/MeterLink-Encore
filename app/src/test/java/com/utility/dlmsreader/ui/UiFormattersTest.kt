package com.utility.dlmsreader.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UiFormattersTest {

    @Test
    fun `wh readings scale down to the kWh heading`() {
        assertEquals("21,239.7", energyAmount("21239700 WH", "kWh"))
    }

    @Test
    fun `vah readings scale down to the kVAh heading`() {
        assertEquals("24,506.7", energyAmount("24506700 VAH", "kVAh"))
    }

    @Test
    fun `readings already in the heading unit keep their value`() {
        assertEquals("7,443", energyAmount("7,443.000 kWh", "kWh"))
        assertEquals("9,077", energyAmount("9,077.800 kVAh", "kVAh"))
    }

    @Test
    fun `unitless and placeholder readings are left alone`() {
        assertEquals("825", energyAmount("825", "kWh"))
        assertEquals("—", energyAmount("—", "kWh"))
        assertEquals("N/A", energyAmount("N/A", "kWh"))
    }

    @Test
    fun `different energy families are not converted`() {
        assertEquals("1,234", energyAmount("1234 WH", "kVAh"))
    }

    @Test
    fun `whole unit preview groups thousands consistently`() {
        assertEquals("7,443", "7443".toPreviewWholeUnits())
        assertEquals("5,342", "5342.000".toPreviewWholeUnits())
        assertEquals("21,239", "21239.7".toPreviewWholeUnits())
        assertEquals("825", "825.900".toPreviewWholeUnits())
        assertEquals("7,443", "7,443".toPreviewWholeUnits())
        assertEquals("-1,234", "-1234.5".toPreviewWholeUnits())
        // Non-numeric preview text is never regrouped.
        assertEquals("29/01/2025 10:38:42", "29/01/2025 10:38:42".toPreviewWholeUnits())
        assertEquals("N/A", "N/A".toPreviewWholeUnits())
        assertEquals("—", "—".toPreviewWholeUnits())
    }

    @Test
    fun `net meter card previews whole units from imported base units`() {
        val report = com.utility.dlmsreader.dlms.MeterDataReport(
            cumulativeEnergies = listOf(
                com.utility.dlmsreader.dlms.RegisterReading(
                    "Cumulative Active Energy - Import",
                    "1.0.1.8.0.255",
                    "7443000",
                    "WH"
                ),
                com.utility.dlmsreader.dlms.RegisterReading(
                    "Cumulative Active Energy - Export",
                    "1.0.2.8.0.255",
                    "2100500",
                    "WH"
                )
            )
        )

        assertEquals("7,443", report.energyReading("1.0.1.8.0.255", "kWh"))
        assertEquals("2,100", report.energyReading("1.0.2.8.0.255", "kWh"))
        assertEquals("5,342", report.netActiveEnergyReading())
        assertEquals("—", report.netApparentEnergyReading())
    }
}