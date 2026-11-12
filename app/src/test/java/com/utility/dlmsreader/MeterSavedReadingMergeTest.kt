package com.utility.dlmsreader

import com.utility.dlmsreader.dlms.IdentitySection
import com.utility.dlmsreader.dlms.MeterDataReport
import com.utility.dlmsreader.dlms.ParameterRow
import com.utility.dlmsreader.dlms.ProfileColumn
import com.utility.dlmsreader.dlms.ProfileSection
import com.utility.dlmsreader.storage.instantaneousParameterCount
import com.utility.dlmsreader.storage.mergeSavedReport
import com.utility.dlmsreader.storage.summaryStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeterSavedReadingMergeTest {

    private companion object {
        const val SERIAL = "92717295"
        const val SERIAL_OBIS = "0.0.96.1.0.255"
        const val BLOCK_ROW_COUNT = 3_602
        const val INSTANT_PARAMETERS = 18
    }

    private fun identity(serial: String = SERIAL): IdentitySection = IdentitySection(
        rows = listOf(
            ParameterRow("Meter Serial Number", SERIAL_OBIS, serial),
            ParameterRow("Manufacturer Name", "0.0.96.1.1.255", "LARSEN AND TOUBRO LIMITED")
        )
    )

    private fun blockLoadReport(): MeterDataReport = MeterDataReport(
        identity = identity(),
        blockLoad = ProfileSection(
            title = "Block Load Survey",
            profileObis = "1.0.99.1.0.255",
            columns = listOf(
                ProfileColumn("Capture Time", "0.0.1.0.0.255", attributeIndex = 5),
                ProfileColumn("Block Energy - Active Import", "1.0.1.29.0.255", "kWh"),
                ProfileColumn("Average Voltage", "1.0.12.27.0.255", "V")
            ),
            rows = (1..BLOCK_ROW_COUNT).map { index ->
                listOf("12-11-26 ${index % 24}:00", "$index.000", "230.1")
            },
            totalRows = BLOCK_ROW_COUNT,
            selectionLabel = "FULL_BUFFER",
            windowNote = "Full buffer"
        )
    )

    private fun instantaneousReport(serial: String = SERIAL): MeterDataReport = MeterDataReport(
        identity = identity(serial),
        instantaneous = ProfileSection(
            title = "Instantaneous Parameters Snapshot",
            profileObis = "1.0.94.91.0.255",
            columns = (1..INSTANT_PARAMETERS).map { index ->
                ProfileColumn("Parameter $index", "1.0.$index.7.0.255", "V")
            },
            // One snapshot row carrying every parameter as a column.
            rows = listOf((1..INSTANT_PARAMETERS).map { index -> "${200 + index}.5" })
        )
    )

    @Test
    fun `partial instantaneous read keeps the stored block load rows`() {
        val merged = mergeSavedReport(blockLoadReport(), instantaneousReport())

        assertEquals(BLOCK_ROW_COUNT, merged.blockLoad?.rows?.size)
        assertEquals(BLOCK_ROW_COUNT, merged.blockLoad?.totalRows)
        assertNotNull("instantaneous section was dropped", merged.instantaneous)
    }

    @Test
    fun `summary counts instantaneous parameters not snapshot rows`() {
        val merged = mergeSavedReport(blockLoadReport(), instantaneousReport())

        assertEquals(1, merged.instantaneous?.rows?.size)
        assertEquals(INSTANT_PARAMETERS, merged.instantaneousParameterCount())
        assertEquals("Block: 3602 rows | 18 params", merged.summaryStats())
        assertFalse(
            "row count leaked into the parameter count",
            merged.summaryStats().contains("1 params")
        )
    }

    @Test
    fun `instantaneous only report no longer reports no profile rows`() {
        val summary = mergeSavedReport(null, instantaneousReport()).summaryStats()

        assertEquals("18 params", summary)
        assertFalse(summary.contains("No profile rows"))
    }

    @Test
    fun `reports from a different serial number are never merged`() {
        val otherMeter = instantaneousReport(serial = "11111111")

        val merged = mergeSavedReport(blockLoadReport(), otherMeter)

        assertNull("another meter's block load survived the merge", merged.blockLoad)
        assertEquals("11111111", merged.identity?.rows?.first()?.formattedValue)
    }

    @Test
    fun `newly read sections win over the stored copy`() {
        val fresh = MeterDataReport(
            identity = identity(),
            blockLoad = ProfileSection(
                title = "Block Load Survey",
                profileObis = "1.0.99.1.0.255",
                columns = listOf(ProfileColumn("Capture Time", "0.0.1.0.0.255", attributeIndex = 5)),
                rows = listOf(listOf("12-11-26 23:45")),
                totalRows = 1
            )
        )

        val merged = mergeSavedReport(blockLoadReport(), fresh)

        assertEquals(1, merged.blockLoad?.rows?.size)
        assertTrue(merged.errors.isEmpty())
    }
}
