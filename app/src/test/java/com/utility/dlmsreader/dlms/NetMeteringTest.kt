package com.utility.dlmsreader.dlms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Net metering (bi-directional / rooftop solar) detection and the
 * `import − export = net` calculation, with three-decimal precision.
 */
class NetMeteringTest {

    private val importActive =
        RegisterReading("Cumulative Active Energy - Import", "1.0.1.8.0.255", "7,443.000", "kWh")
    private val importApparent =
        RegisterReading("Cumulative Apparent Energy - Import", "1.0.9.8.0.255", "9,077.800", "kVAh")
    private val exportActive =
        RegisterReading("Cumulative Active Energy - Export", "1.0.2.8.0.255", "2,100.500", "kWh")
    private val exportApparent =
        RegisterReading("Cumulative Apparent Energy - Export", "1.0.10.8.0.255", "2,650.000", "kVAh")

    @Test
    fun `metering mode 1 marks a net meter`() {
        assertTrue(NetMetering.isNetMeter(1, emptyList()))
        assertFalse(NetMetering.isNetMeter(0, emptyList()))
        assertFalse(NetMetering.isNetMeter(null, emptyList()))
    }

    @Test
    fun `an export energy register marks a net meter without metering mode`() {
        assertTrue(NetMetering.isNetMeter(null, listOf(importActive, exportActive)))
        assertTrue(NetMetering.isNetMeter(null, listOf(importApparent, exportApparent)))
        // ToD export zone energy is export energy as well.
        assertTrue(
            NetMetering.isNetMeter(
                null,
                listOf(RegisterReading("kWh ToD Zone 1 - Export", "1.0.2.8.1.255", "10", "kWh"))
            )
        )
        assertFalse(NetMetering.isNetMeter(null, listOf(importActive, importApparent)))
    }

    @Test
    fun `report exposes isNetMeter from the mode and from export registers`() {
        assertTrue(MeterDataReport(meteringMode = 1).isNetMeter)
        assertTrue(MeterDataReport(cumulativeEnergies = listOf(exportActive)).isNetMeter)
        assertFalse(MeterDataReport(cumulativeEnergies = listOf(importActive)).isNetMeter)
        assertFalse(MeterDataReport(meteringMode = 0).isNetMeter)
    }

    @Test
    fun `net active energy is import minus export with three decimals`() {
        assertEquals("5,342.500", NetMetering.netText(listOf(importActive, exportActive), "1.0.1.8.0.255", "1.0.2.8.0.255"))
    }

    @Test
    fun `net apparent energy is import minus export with three decimals`() {
        assertEquals(
            "6,427.800",
            NetMetering.netText(listOf(importApparent, exportApparent), "1.0.9.8.0.255", "1.0.10.8.0.255")
        )
    }

    @Test
    fun `net energy is negative when export exceeds import`() {
        val importLow = RegisterReading("Import", "1.0.1.8.0.255", "100.000", "kWh")
        val exportHigh = RegisterReading("Export", "1.0.2.8.0.255", "1,100.000", "kWh")
        assertEquals("-1,000.000", NetMetering.netText(listOf(importLow, exportHigh), "1.0.1.8.0.255", "1.0.2.8.0.255"))
    }

    @Test
    fun `net energy mixes base and kilo units safely`() {
        val importWh = RegisterReading("Import", "1.0.1.8.0.255", "7443000", "WH")
        val exportKwh = RegisterReading("Export", "1.0.2.8.0.255", "2,100.500", "kWh")
        assertEquals(
            "5,342.500",
            NetMetering.netText(listOf(importWh, exportKwh), "1.0.1.8.0.255", "1.0.2.8.0.255")
        )
    }

    @Test
    fun `net needs both sides`() {
        assertEquals("", NetMetering.netText(listOf(importActive), "1.0.1.8.0.255", "1.0.2.8.0.255"))
        assertEquals("", NetMetering.netText(emptyList(), "1.0.1.8.0.255", "1.0.2.8.0.255"))
        val placeholder = RegisterReading("Export", "1.0.2.8.0.255", "N/A", "kWh")
        assertEquals(
            "",
            NetMetering.netText(listOf(importActive, placeholder), "1.0.1.8.0.255", "1.0.2.8.0.255")
        )
    }

    @Test
    fun `withNetMetering appends export and net rows exactly once`() {
        val report = MeterDataReport(
            cumulativeEnergies = listOf(importActive, importApparent, exportActive, exportApparent)
        ).withNetMetering()

        assertTrue(report.cumulativeEnergies.any { it.parameterName == NetMetering.LABEL_NET_ACTIVE })
        assertTrue(report.cumulativeEnergies.any { it.parameterName == NetMetering.LABEL_NET_APPARENT })
        val netActive = report.cumulativeEnergies.first { it.parameterName == NetMetering.LABEL_NET_ACTIVE }
        assertEquals("5,342.500", netActive.reading)
        assertEquals("kWh", netActive.unit)
        val netApparent = report.cumulativeEnergies.first { it.parameterName == NetMetering.LABEL_NET_APPARENT }
        assertEquals("6,427.800", netApparent.reading)
        assertEquals("kVAh", netApparent.unit)

        assertEquals(report, report.withNetMetering())
    }

    @Test
    fun `withNetMetering keeps an export MD row that the meter returned`() {
        val mdExport = RegisterReading(
            "Maximum Demand - Active Export",
            "1.0.2.6.0.255",
            "4.250",
            "kW",
            "28-10-26 00:00"
        )
        val report = MeterDataReport(cumulativeEnergies = listOf(importActive, exportActive, mdExport))
            .withNetMetering()

        assertEquals(1, report.cumulativeEnergies.count { it.obisCode == "1.0.2.6.0.255" })
        assertTrue(report.isNetMeter)
    }

    @Test
    fun `withNetMetering adds export placeholders when the meter denied them`() {
        val report = MeterDataReport(meteringMode = 1, cumulativeEnergies = listOf(importActive))
            .withNetMetering()

        val export = report.cumulativeEnergies.first { it.obisCode == "1.0.2.8.0.255" }
        assertEquals("N/A", export.reading)
        // No export value means no net row can be computed.
        assertFalse(report.cumulativeEnergies.any { it.parameterName == NetMetering.LABEL_NET_ACTIVE })
    }

    @Test
    fun `uni directional report is left completely untouched`() {
        val report = MeterDataReport(cumulativeEnergies = listOf(importActive, importApparent))
        assertFalse(report.isNetMeter)
        assertEquals(report, report.withNetMetering())
    }

    @Test
    fun `tagged csv import with export obis is flagged and nets correctly`() {
        val csv = """
            Instant data OBIS
            0100010800FF,0100090800FF,0100020800FF,01000A0800FF
            END
            Instant data value
            7443000,9077800,2100500,2650000
            END
            Instant scalar value
            0|WH,0|VAH,0|WH,0|VAH
            END
        """.trimIndent()

        val imported = ObisProfileCsvParser.parse(csv)!!
        assertTrue(imported.isNetMeter)

        val report = imported.copy(sourceLabel = MeterDataReport.SOURCE_IMPORTED).withNetMetering()
        val netActive = report.cumulativeEnergies.first { it.parameterName == NetMetering.LABEL_NET_ACTIVE }
        assertEquals("5,342.500", netActive.reading)
        assertEquals("kWh", netActive.unit)

        // The exported report keeps 3-decimal precision after normalization.
        val normalized = UnitNormalization.normalize(report)
        val exportedActive = normalized.cumulativeEnergies.first { it.obisCode == "1.0.2.8.0.255" }
        assertEquals("2,100.500", exportedActive.reading)
        assertEquals("kWh", exportedActive.unit)
    }

    @Test
    fun `export labels cover the standard bi-directional objects`() {
        val pf = DlmsCosemFormatter.getObisLabelAndUnit(NetMetering.OBIS_EXPORT_BILLING_PF)
        assertEquals("Average Billing Power Factor - Export", pf.label)
        assertEquals("PF", pf.unit)
        assertFalse(pf.label.contains("XML"))

        assertEquals(
            "MD Active Export - ToD Zone 5 (kW)",
            DlmsCosemFormatter.getObisLabelAndUnit("1.0.2.6.5.255").label
        )
        assertEquals(
            "MD Apparent Export - ToD Zone 8 (kVA)",
            DlmsCosemFormatter.getObisLabelAndUnit("1.0.10.6.8.255").label
        )
        assertEquals(
            "Block Energy - Active Export (kWh)",
            DlmsCosemFormatter.getObisLabelAndUnit(NetMetering.OBIS_BLOCK_ACTIVE_EXPORT).label
        )
    }

    @Test
    fun `demand register addresses are recognised for extended probes`() {
        listOf(
            "1.0.1.6.0.255",
            "1.0.2.6.0.255",
            "1.0.10.6.0.255",
            "1.0.2.6.1.255",
            "1.0.10.6.8.255"
        ).forEach { assertTrue(it, NetMetering.isDemandRegisterObis(it)) }
        listOf(
            NetMetering.OBIS_ACTIVE_ENERGY_EXPORT,
            NetMetering.OBIS_EXPORT_BILLING_PF,
            "1.0.2.8.1.255"
        ).forEach { assertFalse(it, NetMetering.isDemandRegisterObis(it)) }
    }

    @Test
    fun `a vendor maximum-demand object alone is not a net meter`() {
        // `1.0.16.8.0.255` is not a standard export selector; net energy is
        // computed from import − export, never from that object.
        val report = MeterDataReport(
            cumulativeEnergies = listOf(
                importActive,
                RegisterReading("Cumulative Maximum Demand", "1.0.16.8.0.255", "6.200", "kW")
            )
        )
        assertFalse(report.isNetMeter)
        assertTrue(NetMetering.netText(report.cumulativeEnergies, "1.0.1.8.0.255", "1.0.2.8.0.255").isEmpty())
    }

    @Test
    fun `key value dump with export energy is imported as a net meter`() {
        val dump = (
            "S\$S\n" +
                "activeenergyimport=7443000 WH\n" +
                "apparentenergyimport=9077800 VAH\n" +
                "activeenergyexport=2100500 WH\n" +
                "apparentenergyexport=2650000 VAH\n" +
                "averagebillingpfexport=0.998\n"
            ).toByteArray()

        val report = FramedBinaryDumpParser.parse(dump)!!
        assertTrue(report.isNetMeter)

        val pf = report.cumulativeEnergies.first { it.obisCode == NetMetering.OBIS_EXPORT_BILLING_PF }
        assertEquals("Average Billing Power Factor - Export", pf.parameterName)
        assertEquals("PF", pf.unit)

        val netActive = NetMetering.netText(
            report.cumulativeEnergies,
            NetMetering.OBIS_ACTIVE_ENERGY_IMPORT,
            NetMetering.OBIS_ACTIVE_ENERGY_EXPORT
        )
        assertEquals("5,342.500", netActive)
    }

    @Test
    fun `tagged csv export power factor is mapped to the standard object`() {
        val csv = """
            Instant data OBIS
            0100010800FF,0100020800FF,0100540000FF
            END
            Instant data value
            7443000,2100500,0.998
            END
            Instant scalar value
            0|WH,0|WH,0|PF
            END
        """.trimIndent()

        val imported = ObisProfileCsvParser.parse(csv)!!
        assertTrue(imported.isNetMeter)
        val pf = imported.cumulativeEnergies
            .first { it.obisCode == NetMetering.OBIS_EXPORT_BILLING_PF }
        assertEquals("Average Billing Power Factor - Export", pf.parameterName)
        assertEquals("PF", pf.unit)
        assertEquals("0.998", pf.reading)
    }
}
