package com.utility.dlmsreader.dlms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfReportOrderingTest {

    private fun column(
        name: String,
        obis: String,
        unit: String = "",
        attribute: Int = 2
    ) = ProfileColumn(
        parameterName = name,
        obisCode = obis,
        unit = unit,
        attributeIndex = attribute
    )

    @Test
    fun `billing rows sort newest first`() {
        val section = ProfileSection(
            title = "Billing History Profile",
            profileObis = DlmsCosemCatalog.OBIS_BILLING_PROFILE,
            columns = listOf(
                column("Billing Date", "0.0.0.1.2.255", attribute = 5),
                column("Active Energy", "1.0.1.8.0.255", "kWh")
            ),
            rows = listOf(
                listOf("01-02-22 00:00", "100"),
                listOf("01-12-23 00:00", "300"),
                listOf("01-03-22 00:00", "200")
            )
        )

        val sorted = PdfReportOrdering.sortBillingRowsDescending(section)

        assertEquals(listOf("300", "200", "100"), sorted.map { it[1] })
        assertEquals("01-12-23 00:00", sorted.first()[0])
        assertEquals("01-02-22 00:00", sorted.last()[0])
    }

    @Test
    fun `billing date is found when it is not the first column`() {
        val section = ProfileSection(
            title = "Billing History Profile",
            profileObis = DlmsCosemCatalog.OBIS_BILLING_PROFILE,
            columns = listOf(
                column("Active Energy", "1.0.1.8.0.255", "kWh"),
                column("Billing Date", "0.0.0.1.2.255", attribute = 5)
            ),
            rows = listOf(
                listOf("100", "01-02-22 00:00"),
                listOf("200", "01-03-22 00:00")
            )
        )

        val sorted = PdfReportOrdering.sortBillingRowsDescending(section)

        assertEquals(listOf("200", "100"), sorted.map { it[0] })
    }

    @Test
    fun `unparseable billing rows stay at the bottom in original order`() {
        val section = ProfileSection(
            title = "Billing History Profile",
            profileObis = DlmsCosemCatalog.OBIS_BILLING_PROFILE,
            columns = listOf(
                column("Billing Date", "0.0.0.1.2.255", attribute = 5),
                column("Active Energy", "1.0.1.8.0.255", "kWh")
            ),
            rows = listOf(
                listOf("not a date", "invalid-1"),
                listOf("01-01-22 00:00", "old"),
                listOf("also invalid", "invalid-2"),
                listOf("01-02-22 00:00", "new")
            )
        )

        val sorted = PdfReportOrdering.sortBillingRowsDescending(section)

        assertEquals(
            listOf("new", "old", "invalid-1", "invalid-2"),
            sorted.map { it[1] }
        )
    }

    @Test
    fun `phase columns group timestamp voltage current and power factor`() {
        val columns = listOf(
            column("Active Power (kW)", "1.0.1.7.0.255", "kW"),
            column("R-Phase Power Factor", "1.0.33.7.0.255", "PF"),
            column("Y-Phase Current", "1.0.51.7.0.255", "A"),
            column("Real-Time Clock", "0.0.1.0.0.255"),
            column("B-Phase Voltage", "1.0.72.7.0.255", "V"),
            column("Frequency (Hz)", "1.0.14.7.0.255", "Hz"),
            column("Neutral Current", "1.0.91.7.0.255", "A"),
            column("R-Phase Voltage", "1.0.12.7.0.255", "V"),
            column("R-Phase Power Factor", "1.0.33.7.0.255", "PF")
        )

        val ordered = PdfReportOrdering.groupPhaseColumns(columns.withIndex().toList())

        assertEquals(
            listOf(
                "Real-Time Clock",
                "B-Phase Voltage",
                "R-Phase Voltage",
                "Y-Phase Current",
                "Neutral Current",
                "R-Phase Power Factor",
                "R-Phase Power Factor",
                "Active Power (kW)",
                "Frequency (Hz)"
            ),
            ordered.map { it.value.parameterName }
        )
    }

    @Test
    fun `numeric cells clamp to three decimals and leave text untouched`() {
        assertEquals("123.457", PdfReportOrdering.clampNumericText("123.4567"))
        assertEquals("1,234.568", PdfReportOrdering.clampNumericText("1,234.5678"))
        assertEquals("-0.123", PdfReportOrdering.clampNumericText("-0.12345"))
        assertEquals("12.345", PdfReportOrdering.clampNumericText("12.345"))
        assertEquals("7443000", PdfReportOrdering.clampNumericText("7443000"))
        assertEquals("01-02-22 00:00", PdfReportOrdering.clampNumericText("01-02-22 00:00"))
        assertEquals("N/A", PdfReportOrdering.clampNumericText("N/A"))
    }

    @Test
    fun `billing date at the last index is promoted to column zero and sorted`() {
        val section = ProfileSection(
            title = "Billing History Profile",
            profileObis = DlmsCosemCatalog.OBIS_BILLING_PROFILE,
            columns = listOf(
                column("Cumulative Active Energy - Import", "1.0.1.8.0.255", "kWh"),
                column("Cumulative Active Energy - Export", "1.0.2.8.0.255", "kWh"),
                column("Maximum Demand - Active Import", "1.0.1.6.0.255", "kW"),
                column("Billing Date", "0.0.0.1.2.255", attribute = 5)
            ),
            rows = listOf(
                listOf("100", "10", "2", "01-02-22 00:00"),
                listOf("300", "30", "4", "01-12-23 00:00"),
                listOf("200", "20", "3", "01-03-22 00:00")
            )
        )

        val promoted = PdfReportOrdering.promoteTimestampColumn(section)

        // The date becomes column 0 and is not duplicated at the old index.
        assertEquals(PdfReportOrdering.BILLING_DATE_OBIS, promoted.columns.first().obisCode)
        assertEquals(section.columns.size, promoted.columns.size)
        assertEquals(
            1,
            promoted.columns.count { it.obisCode == PdfReportOrdering.BILLING_DATE_OBIS }
        )
        // Headers and cells move in lockstep.
        assertEquals(
            listOf("01-02-22 00:00", "100", "10", "2"),
            promoted.rows.first()
        )
        assertEquals("1.0.1.8.0.255", promoted.columns[1].obisCode)

        val sorted = PdfReportOrdering.sortBillingRowsDescending(promoted)
        assertEquals(listOf("300", "200", "100"), sorted.map { it[1] })
        assertEquals("01-12-23 00:00", sorted.first().first())
    }

    @Test
    fun `block load average quantities resolve to their own phase labels`() {
        // BIS IS 15959 Part 2 capture objects: 32/52/72 are the average
        // phase-neutral voltages and 31/51/71 the average phase currents.
        val expected = mapOf(
            "1.0.32.27.0.255" to "Average Voltage R-N",
            "1.0.52.27.0.255" to "Average Voltage Y-N",
            "1.0.72.27.0.255" to "Average Voltage B-N",
            "1.0.31.27.0.255" to "Average Current R",
            "1.0.51.27.0.255" to "Average Current Y",
            "1.0.71.27.0.255" to "Average Current B"
        )

        expected.forEach { (obis, label) ->
            assertEquals(label, DlmsCosemFormatter.getObisLabelAndUnit(obis).label)
        }
        assertEquals("V", DlmsCosemFormatter.getObisLabelAndUnit("1.0.52.27.0.255").unit)
        assertEquals("A", DlmsCosemFormatter.getObisLabelAndUnit("1.0.51.27.0.255").unit)
    }

    @Test
    fun `promoting block load columns keeps every header paired with its value`() {
        val section = ProfileSection(
            title = "Block Load Survey",
            profileObis = DlmsCosemCatalog.OBIS_BLOCK_LOAD_PROFILE,
            columns = listOf(
                column("Voltage R-N", "1.0.32.7.0.255", "V"),
                column("Current B", "1.0.71.7.0.255", "A"),
                column("Voltage Y-N", "1.0.52.7.0.255", "V"),
                column("Real-Time Clock", "0.0.1.0.0.255", attribute = 5),
                column("Current R", "1.0.31.7.0.255", "A"),
                column("Voltage B-N", "1.0.72.7.0.255", "V")
            ),
            rows = listOf(
                listOf("6528", "0.007", "6536", "12-12-23 00:00", "0.008", "6506")
            )
        )

        val promoted = PdfReportOrdering.promoteTimestampColumn(section)
        val row = promoted.rows.first()
        val paired = promoted.columns.indices.associate { index ->
            promoted.columns[index].obisCode to row[index]
        }

        assertEquals("12-12-23 00:00", row.first())
        assertEquals("0.0.1.0.0.255", promoted.columns.first().obisCode)
        assertEquals("6528", paired["1.0.32.7.0.255"])
        assertEquals("6536", paired["1.0.52.7.0.255"])
        assertEquals("6506", paired["1.0.72.7.0.255"])
        assertEquals("0.007", paired["1.0.71.7.0.255"])
        assertEquals("0.008", paired["1.0.31.7.0.255"])
    }

    @Test
    fun `split parts pin the promoted billing date in every part exactly once`() {
        val section = ProfileSection(
            title = "Billing History Profile",
            profileObis = DlmsCosemCatalog.OBIS_BILLING_PROFILE,
            columns = listOf(
                column("Cumulative Active Energy - Import", "1.0.1.8.0.255", "kWh"),
                column("Cumulative Active Energy - Export", "1.0.2.8.0.255", "kWh"),
                column("Cumulative Apparent Energy - Import", "1.0.9.8.0.255", "kVAh"),
                column("Cumulative Apparent Energy - Export", "1.0.10.8.0.255", "kVAh"),
                column("Maximum Demand - Active Import", "1.0.1.6.0.255", "kW"),
                column("Maximum Demand - Active Export", "1.0.2.6.0.255", "kW"),
                column("Maximum Demand - Apparent Import", "1.0.9.6.0.255", "kVA"),
                column("Reactive Energy Q1", "1.0.5.8.0.255", "kVArh"),
                column("Reactive Energy Q2", "1.0.6.8.0.255", "kVArh"),
                column("Billing Date", "0.0.0.1.2.255", attribute = 5)
            ),
            rows = listOf(
                (1..9).map { it.toString() } + "01-12-23 00:00",
                (11..19).map { it.toString() } + "01-11-23 00:00"
            )
        )

        val promoted = PdfReportOrdering.promoteTimestampColumn(section)
        val parts = PdfReportOrdering.splitProfileParts(
            promoted.columns,
            promoted.columns.indices.toList(),
            maxColumnsPerPart = 6
        )

        assertTrue(parts.size > 1)
        parts.forEach { part ->
            assertEquals("timestamp first", 0, part.first())
            assertEquals(
                "timestamp pinned once per part",
                1,
                part.count { it == 0 }
            )
        }
        assertEquals(parts.size, parts.sumOf { part -> part.count { it == 0 } })

        // Every non-pinned column appears in exactly one part.
        val nonPinned = parts.flatMap { part -> part.filter { it != 0 } }
        assertEquals(promoted.columns.size - 1, nonPinned.size)
        assertEquals(nonPinned.size, nonPinned.distinct().size)

        // Rendering a part by index keeps headers and values paired.
        val part = parts.first()
        val headers = part.map { promoted.columns[it].obisCode }
        val values = part.map { index -> promoted.rows.first()[index] }
        assertEquals(PdfReportOrdering.BILLING_DATE_OBIS, headers.first())
        assertEquals("01-12-23 00:00", values.first())
        assertEquals(
            "1",
            values[headers.indexOf("1.0.1.8.0.255")]
        )
    }

    @Test
    fun `negative zero formats without a minus sign`() {
        assertEquals("0", PdfReportOrdering.clampNumericText("-0"))
        assertEquals("0.000", PdfReportOrdering.clampNumericText("-0.000"))
        assertEquals("0.0", UnitNormalization.cleanNegativeZero("-0.0"))
        assertEquals("0.000", UnitNormalization.format(java.math.BigDecimal("-0.000")))
        assertEquals("0.000", UnitNormalization.normalize("-0.000", "kWh").value)
        assertEquals("-0.123", PdfReportOrdering.clampNumericText("-0.123"))
    }
}
