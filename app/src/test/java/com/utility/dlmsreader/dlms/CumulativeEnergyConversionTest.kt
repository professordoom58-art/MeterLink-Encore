package com.utility.dlmsreader.dlms

import gurux.dlms.enums.Unit as DlmsUnit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the cumulative-energy conversion against the live field log for L&T
 * meter 92717295:
 *
 *  - OBIS 1.0.1.8.0.255: 0x000122BE = 74,430 * 10^2 Wh = 7,443.000 kWh
 *  - OBIS 1.0.9.8.0.255: 0x0001629A = 90,778 * 10^2 VAh = 9,077.800 kVAh
 *
 * Gurux applies attribute 3's scaler (10^2) while reading attribute 2, so the
 * reader hands [Is15959Formatter.formatValue] the already-scaled value with
 * scaler = 1.0; the formatter then converts Wh/VAh to kWh/kVAh.
 */
class CumulativeEnergyConversionTest {

    @Test
    fun `field log raw registers decode to the expected scaled values`() {
        val activeRaw = 0x000122BE
        val apparentRaw = 0x0001629A

        assertEquals(74_430, activeRaw)
        assertEquals(90_778, apparentRaw)
        assertEquals(7_443_000.0, activeRaw * 100.0, 0.0)
        assertEquals(9_077_800.0, apparentRaw * 100.0, 0.0)
    }

    @Test
    fun `active energy renders as 7443 kWh`() {
        assertEquals(
            "7,443.000",
            Is15959Formatter.formatValue(
                raw = 7_443_000.0,
                scaler = 1.0,
                unit = DlmsUnit.ACTIVE_ENERGY,
                obis = "1.0.1.8.0.255",
                includeUnit = false
            )
        )
        assertEquals("kWh", Is15959Formatter.unitSymbol(DlmsUnit.ACTIVE_ENERGY))
    }

    @Test
    fun `apparent energy renders as 9077 point 8 kVAh`() {
        assertEquals(
            "9,077.800",
            Is15959Formatter.formatValue(
                raw = 9_077_800.0,
                scaler = 1.0,
                unit = DlmsUnit.APPARENT_ENERGY,
                obis = "1.0.9.8.0.255",
                includeUnit = false
            )
        )
        assertEquals("kVAh", Is15959Formatter.unitSymbol(DlmsUnit.APPARENT_ENERGY))
    }
}
