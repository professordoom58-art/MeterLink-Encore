package com.utility.dlmsreader

import com.utility.dlmsreader.dlms.IdentitySection
import com.utility.dlmsreader.dlms.MeterDataReport
import com.utility.dlmsreader.dlms.ParameterRow
import com.utility.dlmsreader.dlms.ProfileColumn
import com.utility.dlmsreader.dlms.ProfileSection
import com.utility.dlmsreader.dlms.RegisterReading
import com.utility.dlmsreader.storage.ExternalReadingBackup
import com.utility.dlmsreader.storage.identityValue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises the `Documents/MeterLink` JSON backup directly on the JVM: file
 * naming, readable JSON, auto-scan restore and cross-install deduplication.
 */
class ExternalReadingBackupTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun timestamp(text: String): Long =
        SimpleDateFormat(ExternalReadingBackup.TIMESTAMP_PATTERN, Locale.US)
            .parse(text)!!.time

    private fun report(serial: String = "92717295"): MeterDataReport = MeterDataReport(
        identity = IdentitySection(
            rows = listOf(
                ParameterRow("Meter Serial Number", "0.0.96.1.0.255", serial),
                ParameterRow("Manufacturer Name", "0.0.96.1.1.255", "LARSEN AND TOUBRO LIMITED")
            ),
            nameplate = ProfileSection(
                title = "Nameplate Profile",
                profileObis = "0.0.94.91.0.255",
                columns = listOf(ProfileColumn("Rated Voltage", "1.0.12.27.0.255", "V")),
                rows = listOf(listOf("230.0"))
            )
        ),
        cumulativeEnergies = listOf(
            RegisterReading("Active Energy", "1.0.1.8.0.255", "7,443.000", "kWh"),
            RegisterReading("Apparent Energy", "1.0.9.8.0.255", "9,077.800", "kVAh")
        ),
        instantaneous = ProfileSection(
            title = "Instantaneous Parameters Snapshot",
            profileObis = "1.0.94.91.0.255",
            columns = listOf(ProfileColumn("Voltage R", "1.0.12.27.0.255", "V")),
            rows = listOf(listOf("230.1"))
        ),
        blockLoad = ProfileSection(
            title = "Block Load Survey",
            profileObis = "1.0.99.1.0.255",
            columns = listOf(ProfileColumn("Capture Time", "0.0.1.0.0.255")),
            rows = listOf(listOf("13-11-26 00:15"), listOf("13-11-26 00:30"))
        )
    )

    @Test
    fun `backup file uses the Meterlink serial and scan timestamp`() {
        val directory = temp.newFolder("MeterLink")
        val scannedAt = timestamp("13-Nov-2026_13-23-21")
        val file = ExternalReadingBackup.write(directory, report(), scannedAt)

        assertEquals("Meterlink-92717295-13-Nov-2026_13-23-21.json", file.name)
        assertTrue(file.exists())
        assertNotNull(ExternalReadingBackup.fromJson(file.readText()))
        assertEquals(scannedAt, ExternalReadingBackup.parseScanTimestamp(file.name))
    }

    @Test
    fun `a new install rescans the folder and restores every reading`() {
        val directory = temp.newFolder("MeterLink")
        ExternalReadingBackup.write(directory, report(), timestamp("13-Nov-2026_13-23-21"))

        // Simulate a fresh SQLite database: only the shared folder survives.
        val restored = ExternalReadingBackup.scan(directory)
            .mapNotNull { file -> ExternalReadingBackup.fromJson(file.readText()) }

        assertEquals(1, restored.size)
        assertEquals("92717295", restored.single().identity?.rows?.first()?.formattedValue)
        assertEquals(2, restored.single().blockLoad?.rows?.size)
    }

    @Test
    fun `scan accepts current and legacy names and rejects unrelated files`() {
        val directory = temp.newFolder("MeterLink")
        ExternalReadingBackup.write(directory, report(), timestamp("13-Nov-2026_13-23-21"))
        File(
            directory,
            ExternalReadingBackup.legacyFileName("92717295", 42)
        ).writeText(ExternalReadingBackup.toJson(report()))
        File(directory, "notes.json").writeText("{}")
        File(directory, "MeterLink_Reading_92717295_42.txt").writeText("ignored")

        val files = ExternalReadingBackup.scan(directory)
        assertEquals(2, files.size)

        val legacy = files.first {
            it.name.startsWith(ExternalReadingBackup.LEGACY_FILE_PREFIX)
        }
        assertEquals(42L, ExternalReadingBackup.parseId(legacy.name))
        assertEquals(
            1234L,
            ExternalReadingBackup.parseScanTimestamp(legacy.name, fallbackMs = 1234L)
        )
    }

    @Test
    fun `filename filter recognises both MeterLink conventions`() {
        assertTrue(
            ExternalReadingBackup.isMeterLinkJsonFile(
                "Meterlink-92717295-13-Nov-2026_13-23-21.json"
            )
        )
        assertTrue(
            ExternalReadingBackup.isMeterLinkJsonFile("MeterLink_Reading_92717295_1.json")
        )
        assertTrue(ExternalReadingBackup.isMeterLinkJsonFile("meterlink-123.json"))
        assertFalse(ExternalReadingBackup.isMeterLinkJsonFile("notes.json"))
        assertFalse(ExternalReadingBackup.isMeterLinkJsonFile("MeterLink_Reading_1.txt"))
    }

    @Test
    fun `scan timestamp falls back for legacy and malformed names`() {
        assertEquals(
            999L,
            ExternalReadingBackup.parseScanTimestamp(
                "MeterLink_Reading_92717295_1.json",
                fallbackMs = 999L
            )
        )
        assertEquals(
            999L,
            ExternalReadingBackup.parseScanTimestamp(
                "Meterlink-92717295-broken.json",
                fallbackMs = 999L
            )
        )
    }

    @Test
    fun `fingerprint is stable across restore and changes with content`() {
        val original = report()
        val restored = ExternalReadingBackup.fromJson(
            ExternalReadingBackup.toJson(original)
        )

        assertNotNull(restored)
        assertEquals(
            ExternalReadingBackup.fingerprint(original),
            ExternalReadingBackup.fingerprint(restored!!)
        )
        assertNotEquals(
            ExternalReadingBackup.fingerprint(original),
            ExternalReadingBackup.fingerprint(
                original.copy(blockLoad = original.blockLoad?.copy(rows = emptyList()))
            )
        )
    }

    @Test
    fun `a pushed backup fixture deserializes into a full report`() {
        val text = checkNotNull(javaClass.classLoader?.getResource("restore_sample.json"))
            .readText()
        val report = ExternalReadingBackup.fromJson(text)

        assertNotNull(report)
        assertEquals("TEST12345", report!!.identityValue("0.0.96.1.0.255"))
        assertEquals(2, report.blockLoad?.rows?.size)
        assertEquals(2, report.dailyLoad?.rows?.size)
        assertEquals(1, report.tamperEvents.size)
    }

    @Test
    fun `serial numbers are sanitised for file names`() {
        assertEquals("Unknown", ExternalReadingBackup.sanitizeSerial("   "))
        assertEquals("MH_12_AB", ExternalReadingBackup.sanitizeSerial("MH 12/AB"))
        assertEquals("12345", ExternalReadingBackup.sanitizeSerial("..12345.."))
    }
}
