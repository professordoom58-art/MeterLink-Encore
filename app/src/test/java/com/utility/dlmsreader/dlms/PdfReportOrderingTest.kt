package com.utility.dlmsreader.dlms

import org.junit.Assert.assertEquals
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
}
