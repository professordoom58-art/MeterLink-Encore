package com.utility.dlmsreader.dlms

import com.utility.dlmsreader.storage.MeterReportSerializer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeterFileImportPipelineTest {

    private val taggedCsv = """
        Name plate details
        0.0.42.0.0.255,Logical Device Name,LDN-0001
        0.0.96.1.0.255,Meter Serial Number,92717295
        0.0.96.1.1.255,Manufacturer Name,Example Energy
        ,END
        Programmable datas
        0.0.94.91.12.255,Current Rating,10
        ,END
        Instant data OBIS
        0100010800FF,0100200700FF,01001F0700FF
        ,END
        Instant data value
        123456,23045,1234
        ,END
        Instant scalar value
        -3|kWh,-2|V,-2|A
        ,END
        Billing data OBIS
        0000010200FF,0100010800FF
        ,END
        Billing data value
        01-10-26 00:00,12000,01-09-26 00:00,11000
        ,END
        Billing scalar value
        0|,-3|kWh
        ,END
        Daily load data OBIS
        0000010200FF,0100012900FF
        ,END
        Daily load data value
        01-10-26 00:00,100,01-10-26 00:15,110
        ,END
        Daily load scalar value
        0|,-3|kWh
        ,END
    """.trimIndent()

    @Test
    fun `tagged csv converts hex obis, scales values and chunks profiles`() {
        val report = ObisProfileCsvParser.parse(taggedCsv)
        assertNotNull(report)

        assertEquals("92717295", report!!.identity?.rows?.first { it.obisCode == "0.0.96.1.0.255" }?.formattedValue)
        assertEquals("0.0.42.0.0.255", report.identity?.rows?.first { it.parameterName == "Logical Device Name" }?.obisCode)

        val instant = report.instantaneous!!
        assertEquals(
            listOf("1.0.1.8.0.255", "1.0.32.7.0.255", "1.0.31.7.0.255"),
            instant.columns.map { it.obisCode }
        )
        assertEquals(listOf("123.456", "230.45", "12.34"), instant.rows.single())
        assertEquals("kWh", instant.columns[0].unit)

        assertEquals(2, report.billing!!.rows.size)
        assertEquals("01-10-26 00:00", report.billing!!.rows[0][0])
        assertEquals("12", report.billing!!.rows[0][1])
        assertEquals(2, report.dailyLoad!!.rows.size)

        assertEquals(MeterDataReport.SOURCE_IMPORTED, report.sourceLabel)
        assertTrue(report.cumulativeEnergies.any { it.obisCode == "1.0.1.8.0.255" })
    }

    @Test
    fun `router imports json reports and relabels the source`() {
        val original = MeterDataReport(
            identity = IdentitySection(
                rows = listOf(ParameterRow("Meter Serial Number", "0.0.96.1.0.255", "12345"))
            )
        )
        val bytes = MeterReportSerializer.toJson(original).toString().toByteArray()
        val result = MeterFileImportRouter.route(bytes, "reading.json", "application/json")

        assertEquals(MeterFileFormat.JSON, result.format)
        assertEquals(MeterDataReport.SOURCE_IMPORTED, result.report.sourceLabel)
        assertEquals("12345", result.report.identity?.rows?.single()?.formattedValue)
    }

    @Test
    fun `framed binary dump extracts ascii nameplate and ieee754 registers`() {
        val text = "S\$SModel=ML-3P,Serial=123456,Manufacturer=Example,Phase=3," +
            "Category=CT,Current Rating=10A"
        val doubles = ByteArrayOutputStream().apply {
            write(doubleBytes(12345.678))
            write(doubleBytes(230.45))
        }.toByteArray()
        val dump = text.toByteArray(Charsets.ISO_8859_1) + doubles

        assertTrue(FramedBinaryDumpParser.containsFrameHeader(dump))
        val report = FramedBinaryDumpParser.parse(dump)
        assertNotNull(report)
        assertEquals(
            "123456",
            report!!.identity?.rows?.first { it.obisCode == "0.0.96.1.0.255" }?.formattedValue
        )
        assertEquals(
            "Example",
            report.identity?.rows?.first { it.obisCode == "0.0.96.1.1.255" }?.formattedValue
        )
        val readings = report.cumulativeEnergies.map { it.reading }
        assertTrue("registers were $readings", readings.any { it == "12345.678" })
        assertTrue("registers were $readings", readings.any { it == "230.45" })
        assertEquals(MeterDataReport.SOURCE_IMPORTED, report.sourceLabel)
    }

    @Test
    fun `raw byte sniffing wins over a misleading extension`() {
        val dump = "S\$SSerial=1".toByteArray(Charsets.ISO_8859_1) + doubleBytes(42.5)
        assertEquals(
            MeterFileFormat.FRAMED_BINARY,
            MeterFileImportRouter.detectFormat("labels.csv", "text/csv", dump)
        )
    }

    private fun doubleBytes(value: Double): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(value).array()
}
