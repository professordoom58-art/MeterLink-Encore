package com.utility.dlmsreader

import com.utility.dlmsreader.dlms.IdentitySection
import com.utility.dlmsreader.dlms.MeterDataReport
import com.utility.dlmsreader.dlms.MeterLinkExcelExporter
import com.utility.dlmsreader.dlms.ParameterRow
import com.utility.dlmsreader.dlms.ProfileColumn
import com.utility.dlmsreader.dlms.ProfileSection
import com.utility.dlmsreader.dlms.RegisterReading
import com.utility.dlmsreader.dlms.TamperEventRow
import com.utility.dlmsreader.storage.MeterReportSerializer
import com.utility.dlmsreader.storage.toReadingMetadata
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MeterReportSerializerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun loadColumns(): List<ProfileColumn> = listOf(
        ProfileColumn("Capture Time", "0.0.1.0.0.255", attributeIndex = 5),
        ProfileColumn("Block Energy - Active Import", "1.0.1.29.0.255", "kWh"),
        ProfileColumn("Average Voltage", "1.0.12.27.0.255", "V")
    )

    private fun completeReport(): MeterDataReport {
        val identity = IdentitySection(
            rows = listOf(
                ParameterRow("Logical Device Name", "0.0.42.0.0.255", "LNTB2XXXXXXXC5"),
                ParameterRow("Meter Serial Number", "0.0.96.1.0.255", "92717295"),
                ParameterRow("Manufacturer Name", "0.0.96.1.1.255", "LARSEN AND TOUBRO LIMITED"),
                ParameterRow("Firmware Version", "0.0.96.1.4.255", "V1.2.3"),
                ParameterRow("Real-Time Clock", "0.0.1.0.0.255", "29-Dec-2023 00:34:19")
            ),
            nameplate = ProfileSection(
                title = "Nameplate Profile",
                profileObis = "0.0.94.91.10.255",
                columns = listOf(
                    ProfileColumn("Meter Type", "0.0.94.91.9.255"),
                    ProfileColumn("Current Rating", "0.0.94.91.12.255", "A")
                ),
                rows = listOf(listOf("5 (1P-2W)", "10")),
                totalRows = 1,
                fullRows = listOf(listOf("5 (1P-2W)", "10")),
                fullTotalRows = 1,
                fullBufferCached = true
            )
        )

        val blockFullRows = listOf(
            listOf("28-Dec-23 23:45", "7443.000", "229.4"),
            listOf("28-Dec-23 00:00", "7442.875", "230.1"),
            listOf("12-Dec-23 12:30", "7400.500", "228.7"),
            listOf("12-Dec-23 12:15", "7400.375", "229.0")
        )
        val dailyFullRows = listOf(
            listOf("29-Dec-23 00:00", "7443.000", "230.0"),
            listOf("28-Dec-23 00:00", "7442.000", "229.5"),
            listOf("27-Dec-23 00:00", "7441.000", "230.2"),
            listOf("26-Dec-23 00:00", "7440.000", "229.9"),
            listOf("25-Dec-23 00:00", "7439.000", "230.4")
        )

        return MeterDataReport(
            identity = identity,
            cumulativeEnergies = listOf(
                RegisterReading(
                    "Cumulative Active Energy - Import",
                    "1.0.1.8.0.255",
                    "7,443.000",
                    "kWh",
                    "29-Dec-2023 00:34:19"
                ),
                RegisterReading(
                    "Cumulative Reactive Energy - Import",
                    "1.0.9.8.0.255",
                    "1,234.567",
                    "kVAh",
                    "29-Dec-2023 00:34:19"
                ),
                RegisterReading(
                    "Maximum Demand - Active",
                    "1.0.1.6.0.255",
                    "4.2",
                    "kW",
                    "01-Dec-2023 00:00:00"
                )
            ),
            instantaneous = ProfileSection(
                title = "Instantaneous Parameters Snapshot",
                profileObis = "1.0.94.91.0.255",
                columns = listOf(
                    ProfileColumn("R-Phase Voltage", "1.0.32.7.0.255", "V"),
                    ProfileColumn("Signed Power Factor", "1.0.13.7.0.255", "PF")
                ),
                rows = listOf(listOf("230.45", "0.98"), listOf("229.10", "1.00")),
                totalRows = 2,
                fullRows = listOf(listOf("230.45", "0.98"), listOf("229.10", "1.00")),
                fullTotalRows = 2,
                fullBufferCached = true
            ),
            billing = ProfileSection(
                title = "Billing History Profile",
                profileObis = "1.0.98.1.0.255",
                columns = listOf(
                    ProfileColumn("Billing Date", "0.0.0.1.2.255", attributeIndex = 5),
                    ProfileColumn("Cumulative Active Energy - Import", "1.0.1.8.0.255", "kWh")
                ),
                rows = listOf(
                    listOf("01-Dec-23 00:00", "7200.100"),
                    listOf("01-Nov-23 00:00", "7000.500")
                ),
                totalRows = 13
            ),
            blockLoad = ProfileSection(
                title = "Block Load Survey",
                profileObis = "1.0.99.1.0.255",
                columns = loadColumns(),
                rows = blockFullRows.take(2),
                totalRows = 3_601,
                fullRows = blockFullRows,
                fullTotalRows = 3_601,
                fullBufferCached = true,
                selectionLabel = "2 Days",
                windowNote = "Showing 2 of 3601 entries across 2 distinct days " +
                    "(power-off gap fallback: latest 2 distinct days; oldest shown: 12-Dec-23 12:15)"
            ),
            dailyLoad = ProfileSection(
                title = "Daily Load Survey",
                profileObis = "1.0.99.2.0.255",
                columns = loadColumns(),
                rows = dailyFullRows.take(2),
                totalRows = 181,
                fullRows = dailyFullRows,
                fullTotalRows = 181,
                fullBufferCached = true,
                selectionLabel = "30 Days",
                windowNote = "Showing 2 of 181 entries across 2 distinct days " +
                    "(calendar window: latest 30 days; oldest shown: 28-Dec-23 00:00)"
            ),
            tamperEvents = listOf(
                TamperEventRow(
                    compartment = "Voltage Events",
                    dateTime = "29-Dec-23 03:15",
                    eventCode = "7",
                    eventDescription = "Over Voltage in Any Phase - Occurrence",
                    voltage = "265.4",
                    current = "4.2",
                    powerFactor = "0.87",
                    energy = "7443.000",
                    kvah = "1234.567",
                    neutralCurrent = "0.01"
                ),
                TamperEventRow(
                    compartment = "Other Events",
                    dateTime = "27-Dec-23 22:04",
                    eventCode = "251",
                    eventDescription = "Meter Cover Opening - Occurrence"
                )
            ),
            eventLogStatus = listOf(
                "Voltage Events" to "1 entries",
                "Other Events" to "1 entries",
                "Current Events" to "not supported by this meter"
            ),
            errors = listOf("Billing profile unavailable: read denied")
        )
    }

    @Test
    fun `gzip json round trip is lossless`() {
        val report = completeReport()

        val compressed = MeterReportSerializer.toJsonBytesGzip(report)
        val restored = MeterReportSerializer.fromJsonBytesGzip(compressed)

        // Data-class equality covers identity, registers, every section, all
        // rows, cached full buffers, tamper compartments, diagnostics and notes.
        assertEquals(report, restored)

        // Explicit guards for the load-survey cache that saved exports depend on.
        assertEquals(report.blockLoad?.fullRows, restored.blockLoad?.fullRows)
        assertEquals(report.blockLoad?.fullTotalRows, restored.blockLoad?.fullTotalRows)
        assertEquals(report.blockLoad?.fullBufferCached, restored.blockLoad?.fullBufferCached)
        assertEquals(report.blockLoad?.windowNote, restored.blockLoad?.windowNote)
        assertEquals(report.dailyLoad?.fullRows, restored.dailyLoad?.fullRows)
        assertEquals(report.dailyLoad?.fullTotalRows, restored.dailyLoad?.fullTotalRows)
        assertEquals(report.dailyLoad?.fullBufferCached, restored.dailyLoad?.fullBufferCached)
        assertEquals(report.dailyLoad?.selectionLabel, restored.dailyLoad?.selectionLabel)
        assertEquals(report.identity?.nameplate, restored.identity?.nameplate)
        assertEquals(report.tamperEvents, restored.tamperEvents)
        assertEquals(report.eventLogStatus, restored.eventLogStatus)
        assertEquals(report.errors, restored.errors)
    }

    @Test
    fun `deserialized report still exports every worksheet`() {
        val restored = MeterReportSerializer.fromJsonBytesGzip(
            MeterReportSerializer.toJsonBytesGzip(completeReport())
        )
        val file = temporaryFolder.newFile("MeterLink_Report_92717295.xlsx")
        file.outputStream().use { out -> MeterLinkExcelExporter.writeWorkbook(restored, out) }
        assertTrue("workbook was not written", file.length() > 0L)

        ZipFile(file).use { zip ->
            val expectedSheets = listOf(
                "Identity_Nameplate",
                "Energy_MD",
                "Instantaneous",
                "Billing_History",
                "Daily_Load",
                "Block_Load",
                "Tamper_Events"
            )
            val workbook = zip.getInputStream(zip.getEntry("xl/workbook.xml"))
                .bufferedReader()
                .readText()
            expectedSheets.forEach { name ->
                assertTrue("missing worksheet $name", workbook.contains("name=\"$name\""))
            }
            (1..7).forEach { index ->
                val sheet = zip.getInputStream(zip.getEntry("xl/worksheets/sheet$index.xml"))
                    .bufferedReader()
                    .readText()
                assertTrue("empty worksheet $index", sheet.contains("<sheetData>"))
            }

            val identity = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml"))
                .bufferedReader()
                .readText()
            assertTrue("serial number missing from export", identity.contains("92717295"))
            assertTrue("manufacturer missing from export", identity.contains("LARSEN AND TOUBRO LIMITED"))

            val block = zip.getInputStream(zip.getEntry("xl/worksheets/sheet6.xml"))
                .bufferedReader()
                .readText()
            assertTrue("block rows missing from export", block.contains("7443.000"))
            assertTrue("block total missing from export", block.contains("3601"))
            assertTrue("window note missing from export", block.contains("power-off gap fallback"))
        }
    }

    @Test
    fun `saved metadata is derived from the serialized report`() {
        val report = completeReport()
        val restored = MeterReportSerializer.fromJsonBytesGzip(
            MeterReportSerializer.toJsonBytesGzip(report)
        )
        val metadata = restored.toReadingMetadata()

        assertEquals("92717295", metadata.serialNumber)
        assertEquals("LNTB2XXXXXXXC5", metadata.ldn)
        assertEquals("LARSEN AND TOUBRO LIMITED", metadata.manufacturer)
        assertEquals("29-Dec-2023 00:34:19", metadata.meterRtc)
        assertEquals("7,443.000 kWh", metadata.kwhReading)
        assertEquals(
            "Daily: 181 rows | Block: 3601 rows | Tamper: 2",
            metadata.summaryStats
        )
    }
}
