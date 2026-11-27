package com.utility.dlmsreader.dlms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExportRetentionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun touch(dir: File, name: String, modifiedMs: Long): File =
        File(dir, name).apply { writeText("x"); setLastModified(modifiedMs) }

    @Test
    fun `keeps only the newest files of the given type`() {
        val dir = temp.newFolder()
        (1..55).forEach { i ->
            touch(dir, "MeterLink_Report_SN_202611%02d_000000.pdf".format(i % 28 + 1) + "_$i.pdf", 1_000_000L * i)
        }
        val deleted = ExportRetention.prune(dir, ExportRetention.REPORT_PREFIX, ".pdf", keep = 50)
        assertEquals(5, deleted)
        val left = dir.listFiles()!!.map { it.lastModified() }.sorted()
        assertEquals(50, left.size)
        assertEquals(6_000_000L, left.first()) // files 1..5 (oldest) are gone
    }

    @Test
    fun `other types, JSON backups and unrelated files are never touched`() {
        val dir = temp.newFolder()
        (1..5).forEach { i -> touch(dir, "MeterLink_Report_A_$i.pdf", 1_000L * i) }
        val xlsx = touch(dir, "MeterLink_Report_A_1.xlsx", 1L)
        val hex = touch(dir, "MeterLink_HexLog_A_1.txt", 1L)
        val json = touch(dir, "Meterlink-A-20261127.json", 1L)
        val other = touch(dir, "notes.pdf", 1L)

        assertEquals(3, ExportRetention.prune(dir, ExportRetention.REPORT_PREFIX, ".pdf", keep = 2))
        listOf(xlsx, hex, json, other).forEach { assertTrue(it.name, it.exists()) }
        assertEquals(
            setOf("MeterLink_Report_A_4.pdf", "MeterLink_Report_A_5.pdf"),
            dir.listFiles()!!.map { it.name }.filter { it.endsWith(".pdf") && it.startsWith("MeterLink") }.toSet()
        )
    }

    @Test
    fun `under the cap or missing folder deletes nothing`() {
        val dir = temp.newFolder()
        touch(dir, "MeterLink_HexLog_A_1.txt", 1L)
        assertEquals(0, ExportRetention.prune(dir, ExportRetention.HEX_LOG_PREFIX, ".txt"))
        assertEquals(0, ExportRetention.prune(File(dir, "missing"), ExportRetention.HEX_LOG_PREFIX, ".txt"))
    }
}
