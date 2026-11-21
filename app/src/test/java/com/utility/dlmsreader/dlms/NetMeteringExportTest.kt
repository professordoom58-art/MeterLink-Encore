package com.utility.dlmsreader.dlms

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Verifies the exported workbook, not the stored report: Section 2 must carry
 * the export and net rows of a net meter, and imported base units must be
 * printed in kWh / kVAh / kW / kVA with three decimals.
 */
class NetMeteringExportTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun report(): MeterDataReport = MeterDataReport(
        identity = IdentitySection(
            rows = listOf(
                ParameterRow("Meter Serial Number", "0.0.96.1.0.255", "92717295"),
                ParameterRow("Maximum Demand - Active Export", "1.0.2.6.0.255", "1850", "W")
            )
        ),
        cumulativeEnergies = listOf(
            RegisterReading("Cumulative Active Energy - Import", "1.0.1.8.0.255", "7443000", "WH"),
            RegisterReading("Cumulative Apparent Energy - Import", "1.0.9.8.0.255", "9077800", "VAH"),
            RegisterReading("Cumulative Active Energy - Export", "1.0.2.8.0.255", "2100500", "WH"),
            RegisterReading("Cumulative Apparent Energy - Export", "1.0.10.8.0.255", "2650000", "VAH"),
            RegisterReading("Maximum Demand - Active Export", "1.0.2.6.0.255", "1850", "W", "28-10-26 00:00"),
            RegisterReading("Maximum Demand - Apparent Export", "1.0.10.6.0.255", "3680", "VA", "28-10-26 00:00")
        ),
        billing = ProfileSection(
            title = "Billing History Profile",
            profileObis = DlmsCosemCatalog.OBIS_BILLING_PROFILE,
            columns = listOf(
                ProfileColumn("Billing Date", "0.0.0.1.2.255", attributeIndex = 5),
                ProfileColumn("Cumulative Active Energy - Export", "1.0.2.8.0.255", "WH")
            ),
            rows = listOf(listOf("01-10-26 00:00", "2100500"))
        )
    )

    private fun buildWorkbook(): ZipFile {
        val file = temporaryFolder.newFile("MeterLink_NetMeter_TEST.xlsx")
        file.outputStream().use { out -> MeterLinkXlsxWriter.write(report(), out) }
        return ZipFile(file)
    }

    private fun ZipFile.sheetText(index: Int): String =
        getInputStream(getEntry("xl/worksheets/sheet$index.xml"))
            .bufferedReader()
            .readText()

    @Test
    fun `section 2 carries export and net rows in kWh and kVAh`() {
        buildWorkbook().use { zip ->
            val energy = zip.sheetText(2)
            assertTrue("active export missing", energy.contains("Cumulative Active Energy - Export"))
            assertTrue("apparent export missing", energy.contains("Cumulative Apparent Energy - Export"))
            assertTrue("export MD missing", energy.contains("Maximum Demand - Active Export"))
            assertTrue("export apparent MD missing", energy.contains("Maximum Demand - Apparent Export"))
            assertTrue("net active missing", energy.contains("Net Active Energy (kWh)"))
            assertTrue("net apparent missing", energy.contains("Net Apparent Energy (kVAh)"))
            assertTrue("net value wrong", energy.contains("5,342.500"))
            assertTrue("net apparent value wrong", energy.contains("6,427.800"))
        }
    }

    @Test
    fun `base units are normalized once in the workbook`() {
        buildWorkbook().use { zip ->
            val energy = zip.sheetText(2)
            assertTrue("active import not normalized", energy.contains("7,443.000"))
            assertTrue("apparent import not normalized", energy.contains("9,077.800"))
            assertTrue("export demand not normalized", energy.contains("1.850"))
            assertTrue("apparent export demand not normalized", energy.contains("3.680"))
            assertTrue("kWh unit missing", energy.contains("kWh"))
            assertTrue("kVAh unit missing", energy.contains("kVAh"))
            // No raw base-unit token may survive in the register table.
            assertTrue("raw WH leaked", !energy.contains("7443000"))
            assertTrue("raw VAH leaked", !energy.contains("9077800"))
        }
    }

    @Test
    fun `billing history normalizes wh to kWh`() {
        buildWorkbook().use { zip ->
            val billing = zip.sheetText(4)
            assertTrue("billing value not normalized", billing.contains("2,100.500"))
            assertTrue("raw billing value leaked", !billing.contains("2100500"))
        }
    }

    @Test
    fun `workbook flags the net meter in the identity sheet`() {
        buildWorkbook().use { zip ->
            val identity = zip.sheetText(1)
            assertTrue(identity.contains("Net Metering (Bi-Directional)"))
        }
    }

    @Test
    fun `json backup normalizes base units and carries the net rows`() {
        val json = com.utility.dlmsreader.storage.ExternalReadingBackup.toJson(report())

        assertTrue("active import not normalized", json.contains("7,443.000"))
        assertTrue("export energy not normalized", json.contains("2,100.500"))
        assertTrue("export MD not normalized", json.contains("1.850"))
        assertTrue("net active missing", json.contains("Net Active Energy (kWh)"))
        assertTrue("net value wrong", json.contains("5,342.500"))
        assertTrue("raw WH leaked", !json.contains("7443000"))
        assertTrue("raw W leaked", !json.contains("\"1850\""))
    }
}
