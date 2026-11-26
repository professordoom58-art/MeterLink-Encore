package com.utility.dlmsreader.dlms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Imported MRI `.OUT` exports (DHBVN SANDS-style, Avon 1P) declare billing
 * scalers in base units (`1|VAH`, `1|WH`). Before the fix the import router
 * kept those base units in the report, so the in-app billing table showed
 * values like `78532710` under a "(kVAh)" label. Only the PDF/Excel exporters
 * normalized. Unused billing slots (billing date `00/00/0000 00:00:00`) also
 * surfaced uninitialized memory as impossible kVAh readings.
 *
 * Values are synthetic. The line layout matches the field files.
 */
class ImportedBillingUnitsTest {

    private val outFile = listOf(
        "Name plate details,EXAMPLE_LDN,0000001,EXAMPLE METERS,AA1.00,5,1,1,C3,10-60A,2016,0,END",
        "Billing data value," +
            "01/05/2025 00:00:00,799,103399,45144,26185,164,02/04/2025 10:30:00," +
            "01/06/2025 00:00:00,774,112595,45609,27195,186,02/05/2025 11:00:00," +
            "00/00/0000 00:00:00,0,0,579796992,-1585309184,0,00/00/0000 00:00:00,END",
        "Billing data OBIS,0000000102FF,01000D0000FF,0100010800FF,0100090800FF,0100090801FF,0100010600FF,0100010600FF,END",
        "Billing scalar value,-3|NA,1|WH,1|VAH,1|VAH,1|W,END",
        "Billing scalar OBIS,01000D0000FF,0100010800FF,0100090800FF,0100090801FF,0100010600FF,END"
    ).joinToString("\r\n")

    private fun billing(): ProfileSection =
        MeterFileImportRouter.route(outFile.toByteArray(), "sample.OUT", null).report.billing!!

    private fun cell(section: ProfileSection, row: Int, obis: String): String =
        section.rows[row][section.columns.indexOfFirst { it.obisCode == obis }]

    @Test
    fun `imported billing energy columns are reported in kWh and kVAh`() {
        val section = billing()
        val units = section.columns.associate { it.obisCode to it.unit }
        assertEquals("kWh", units["1.0.1.8.0.255"])
        assertEquals("kVAh", units["1.0.9.8.0.255"])
        assertEquals("kVAh", units["1.0.9.8.1.255"])

        // 103399 x 10 Wh = 1,033.990 kWh (matches the MRI vendor report).
        assertEquals("1,033.990", cell(section, 0, "1.0.1.8.0.255"))
        // 45144 x 10 VAh = 451.440 kVAh, not 451440.
        assertEquals("451.440", cell(section, 0, "1.0.9.8.0.255"))
        assertEquals("261.850", cell(section, 0, "1.0.9.8.1.255"))
    }

    @Test
    fun `unwritten billing slots with a zero billing date are dropped`() {
        val section = billing()
        assertEquals(2, section.rows.size)
        assertTrue(section.rows.none { it.first().startsWith("00/00/0000") })
        assertEquals(2, section.totalRows)
    }

    @Test
    fun `normalizing an already normalized import changes nothing`() {
        val section = billing()
        assertEquals(section, UnitNormalization.normalize(section))
    }
}
