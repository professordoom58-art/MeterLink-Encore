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
import org.junit.Assert.assertEquals
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
    fun `backup file uses the MeterLink naming convention`() {
        val directory = temp.newFolder("MeterLink")
        val file = ExternalReadingBackup.write(directory, report(), id = 7)

        assertEquals("MeterLink_Reading_92717295_7.json", file.name)
        assertTrue(file.exists())
        assertNotNull(ExternalReadingBackup.fromJson(file.readText()))
    }

    @Test
    fun `a new install rescans the folder and restores every reading`() {
        val directory = temp.newFolder("MeterLink")
        ExternalReadingBackup.write(directory, report(), id = 1)

        // Simulate a fresh SQLite database: only the shared folder survives.
        val restored = ExternalReadingBackup.scan(directory)
            .mapNotNull { file -> ExternalReadingBackup.fromJson(file.readText()) }

        assertEquals(1, restored.size)
        assertEquals("92717295", restored.single().identity?.rows?.first()?.formattedValue)
        assertEquals(2, restored.single().blockLoad?.rows?.size)
    }

    @Test
    fun `scan ignores unrelated files and parses the row id`() {
        val directory = temp.newFolder("MeterLink")
        ExternalReadingBackup.write(directory, report(), id = 42)
        File(directory, "notes.json").writeText("{}")
        File(directory, "MeterLink_Reading_92717295_42.txt").writeText("ignored")

        val files = ExternalReadingBackup.scan(directory)
        assertEquals(1, files.size)
        assertEquals(42L, ExternalReadingBackup.parseId(files.single().name))
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
