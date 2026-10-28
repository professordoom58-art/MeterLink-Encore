package com.utility.dlmsreader.dlms

import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MeterLinkExcelExporterTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun sampleReport(): MeterDataReport {
        val identity = IdentitySection(
            rows = listOf(
                ParameterRow("Logical Device Name", "0.0.42.0.0.255", "MH12E1234567"),
                ParameterRow("Meter Serial Number", "0.0.96.1.0.255", "00712345"),
                ParameterRow("Manufacturer Name", "0.0.96.1.1.255", "L&T & Co <Grid>"),
                ParameterRow("Real-Time Clock", "0.0.1.0.0.255", "28-Oct-2026 14:37:22")
            ),
            nameplate = ProfileSection(
                title = "Nameplate Profile",
                profileObis = "0.0.94.91.10.255",
                columns = listOf(
                    ProfileColumn("Meter Type", "0.0.94.91.9.255"),
                    ProfileColumn("Current Rating", "0.0.94.91.12.255", "A")
                ),
                rows = listOf(listOf("5 (1P-2W)", "10"))
            )
        )
        val instantaneous = ProfileSection(
            title = "Instantaneous Parameters Snapshot",
            profileObis = "1.0.94.91.0.255",
            columns = listOf(
                ProfileColumn("R-Phase Voltage", "1.0.32.7.0.255", "V"),
                ProfileColumn("Signed Power Factor", "1.0.13.7.0.255", "PF")
            ),
            rows = listOf(listOf("230.45", "0.98"), listOf("229.10", "1.00"))
        )
        val loadRows = (1..120).map { index ->
            listOf("28-10-26 0${index % 10}:00", "${index * 10}", "${index}.5")
        }
        val loadColumns = listOf(
            ProfileColumn("Capture Time", "0.0.1.0.0.255", attributeIndex = 5),
            ProfileColumn("Block Energy - Active Import", "1.0.1.29.0.255", "kWh"),
            ProfileColumn("Average Voltage", "1.0.12.27.0.255", "V")
        )
        return MeterDataReport(
            identity = identity,
            cumulativeEnergies = listOf(
                RegisterReading("Cumulative Active Energy - Import", "1.0.1.8.0.255", "12345.678", "kWh"),
                RegisterReading("Maximum Demand - Active", "1.0.1.6.0.255", "4.2", "kW", "28-Oct-2026 00:00:00")
            ),
            instantaneous = instantaneous,
            billing = ProfileSection(
                title = "Billing History Profile",
                profileObis = "1.0.98.1.0.255",
                columns = listOf(
                    ProfileColumn("Billing Date", "0.0.0.1.2.255", attributeIndex = 5),
                    ProfileColumn("Cumulative Active Energy - Import", "1.0.1.8.0.255", "kWh")
                ),
                rows = listOf(listOf("01-10-26 00:00", "12000.100"))
            ),
            dailyLoad = ProfileSection(
                title = "Daily Load Survey",
                profileObis = "1.0.99.2.0.255",
                columns = loadColumns,
                rows = loadRows,
                totalRows = 400
            ),
            blockLoad = ProfileSection(
                title = "Block Load Survey",
                profileObis = "1.0.99.1.0.255",
                columns = loadColumns,
                rows = loadRows.take(48),
                totalRows = 48
            ),
            tamperEvents = listOf(
                TamperEventRow(
                    compartment = "Voltage Events",
                    dateTime = "28-10-26 03:15",
                    eventCode = "7",
                    eventDescription = "Over Voltage in Any Phase - Occurrence",
                    voltage = "265.4"
                ),
                TamperEventRow(
                    compartment = "Other Events",
                    dateTime = "27-10-26 22:04",
                    eventCode = "251",
                    eventDescription = "Meter Cover Opening - Occurrence"
                )
            ),
            eventLogStatus = listOf(
                "Voltage Events" to "2 entries",
                "Current Events" to "not supported by this meter"
            ),
            errors = listOf("Billing profile unavailable: read denied")
        )
    }

    private fun buildWorkbook(): File {
        val file = temporaryFolder.newFile("MeterLink_Report_TEST_20261028_143722.xlsx")
        file.outputStream().use { out ->
            MeterLinkXlsxWriter.write(sampleReport(), out)
        }
        return file
    }

    @Test
    fun `workbook contains every required part and all seven worksheets`() {
        val file = buildWorkbook()
        assertTrue("workbook was not written", file.length() > 0)

        ZipFile(file).use { zip ->
            val required = listOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels",
                "xl/styles.xml"
            ) + (1..7).map { "xl/worksheets/sheet$it.xml" }

            required.forEach { name ->
                val entry = zip.getEntry(name)
                assertTrue("missing part: $name", entry != null)
                val body = zip.getInputStream(entry).bufferedReader().readText()
                assertTrue("empty part: $name", body.isNotEmpty())
                assertTrue("part is not xml: $name", body.trimStart().startsWith("<?xml"))
            }
        }
    }

    @Test
    fun `every part parses as well formed xml`() {
        val file = buildWorkbook()
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        ZipFile(file).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.endsWith(".xml") || it.name.endsWith(".rels") }
                .forEach { entry ->
                    // Throws SAXException if the part is malformed.
                    factory.newDocumentBuilder().parse(zip.getInputStream(entry))
                }
        }
    }

    @Test
    fun `workbook declares the seven expected tab names in order`() {
        val file = buildWorkbook()
        ZipFile(file).use { zip ->
            val workbook = zip.getInputStream(zip.getEntry("xl/workbook.xml"))
                .bufferedReader()
                .readText()
            val expected = listOf(
                "Identity_Nameplate",
                "Energy_MD",
                "Instantaneous",
                "Billing_History",
                "Daily_Load",
                "Block_Load",
                "Tamper_Events"
            )
            expected.forEach { name ->
                assertTrue("tab missing: $name", workbook.contains("name=\"$name\""))
            }
            // Tab order must follow the sheet part order.
            val positions = expected.map { workbook.indexOf("name=\"$it\"") }
            assertEquals(positions.sorted(), positions)
        }
    }

    @Test
    fun `every worksheet part is a well formed worksheet root`() {
        val file = buildWorkbook()
        ZipFile(file).use { zip ->
            (1..7).forEach { index ->
                val body = zip.getInputStream(zip.getEntry("xl/worksheets/sheet$index.xml"))
                    .bufferedReader()
                    .readText()
                assertTrue("sheet$index missing sheetData", body.contains("<sheetData>"))
                assertTrue("sheet$index not closed", body.trimEnd().endsWith("</worksheet>"))
            }
        }
    }

    @Test
    fun `xml metacharacters in meter data are escaped`() {
        val file = buildWorkbook()
        ZipFile(file).use { zip ->
            val identity = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml"))
                .bufferedReader()
                .readText()
            assertTrue("ampersand was not escaped", identity.contains("L&amp;T &amp; Co &lt;Grid&gt;"))
            assertFalse("raw ampersand leaked", identity.contains("L&T & Co"))
            assertFalse("raw angle bracket leaked", identity.contains("<Grid>"))
        }
    }

    @Test
    fun `load survey sheets keep every row rather than a truncated window`() {
        val file = buildWorkbook()
        ZipFile(file).use { zip ->
            val daily = zip.getInputStream(zip.getEntry("xl/worksheets/sheet5.xml"))
                .bufferedReader()
                .readText()
            // 120 data rows plus title, header and truncation note.
            val rowCount = Regex("<row r=\"").findAll(daily).count()
            assertTrue("daily load rows were truncated: $rowCount", rowCount >= 121)
            assertTrue(
                "truncation note missing",
                daily.contains("Showing 120 of 400 entries")
            )
        }
    }

    @Test
    fun `numeric cells are written as numbers and identifiers stay text`() {
        val file = buildWorkbook()
        ZipFile(file).use { zip ->
            val energy = zip.getInputStream(zip.getEntry("xl/worksheets/sheet2.xml"))
                .bufferedReader()
                .readText()
            assertTrue("energy reading was not numeric", energy.contains("<v>12345.678</v>"))

            val identity = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml"))
                .bufferedReader()
                .readText()
            // Serial "00712345" must keep its leading zeros, so it stays a string.
            assertTrue("leading-zero serial became numeric", identity.contains("00712345"))
            assertFalse("leading-zero serial was numeric", identity.contains("<v>712345</v>"))
        }
    }

    @Test
    fun `column letters advance past Z`() {
        assertEquals("A", MeterLinkXlsxWriter.colLetter(0))
        assertEquals("Z", MeterLinkXlsxWriter.colLetter(25))
        assertEquals("AA", MeterLinkXlsxWriter.colLetter(26))
        assertEquals("AB", MeterLinkXlsxWriter.colLetter(27))
        assertEquals("AZ", MeterLinkXlsxWriter.colLetter(51))
        assertEquals("BA", MeterLinkXlsxWriter.colLetter(52))
    }

    @Test
    fun `numeric detection rejects identifiers and unit suffixed values`() {
        assertTrue(MeterLinkXlsxWriter.isNumeric("12345.678"))
        assertTrue(MeterLinkXlsxWriter.isNumeric("-42"))
        assertFalse("leading zero", MeterLinkXlsxWriter.isNumeric("007"))
        assertFalse("unit suffix", MeterLinkXlsxWriter.isNumeric("230.45 V"))
        assertFalse("obis code", MeterLinkXlsxWriter.isNumeric("1.0.1.8.0.255"))
        assertFalse("empty", MeterLinkXlsxWriter.isNumeric(""))
        assertFalse("text", MeterLinkXlsxWriter.isNumeric("N/A"))
    }

    @Test
    fun `style markers never reach the workbook text`() {
        assertEquals("Parameter", MeterLinkXlsxWriter.displayText("\u0001H\u0001Parameter"))
        assertEquals("Section 1", MeterLinkXlsxWriter.displayText("\u0001T\u0001Section 1"))
        assertEquals("plain", MeterLinkXlsxWriter.displayText("plain"))
    }
}
