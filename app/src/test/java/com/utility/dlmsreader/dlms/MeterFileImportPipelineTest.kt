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
    fun `raw DLMS headers win over a misleading extension`() {
        val hdlc = byteArrayOf(0x7E, 0xA0.toByte()) + "Serial=1".toByteArray()
        assertEquals(
            MeterFileFormat.FRAMED_BINARY,
            MeterFileImportRouter.detectFormat("labels.csv", "text/csv", hdlc)
        )

        val wrapper = byteArrayOf(0x00, 0x01, 0x00) + "Serial=1".toByteArray()
        assertEquals(
            MeterFileFormat.FRAMED_BINARY,
            MeterFileImportRouter.detectFormat("labels.csv", "text/csv", wrapper)
        )
    }

    @Test
    fun `text dat without a DLMS header falls back to tagged csv`() {
        val csv = """
            Name plate details
            0.0.96.1.0.255,Meter Serial Number,123456
            ,END
        """.trimIndent()

        assertEquals(
            MeterFileFormat.TAGGED_OBIS_CSV,
            MeterFileImportRouter.detectFormat(
                "export.dat",
                "application/octet-stream",
                csv.toByteArray()
            )
        )
    }

    @Test
    fun `out extension is treated as tagged csv`() {
        val csv = """
            Name plate details
            0.0.96.1.0.255,Meter Serial Number,123456
            ,END
        """.trimIndent()

        assertEquals(
            MeterFileFormat.TAGGED_OBIS_CSV,
            MeterFileImportRouter.detectFormat("dump.OUT", null, csv.toByteArray())
        )
    }

    @Test
    fun `binary header is detected before text sampling on large dumps`() {
        val header = byteArrayOf(0x7E, 0xA0.toByte())
        val dump = header + ByteArray(200_000) + "obis".toByteArray()

        assertEquals(
            MeterFileFormat.FRAMED_BINARY,
            MeterFileImportRouter.detectFormat("huge.out", null, dump)
        )
    }

    @Test
    fun `text sniffing only reads the first 64 KB`() {
        // OBIS vocabulary past the sample window must not force a full decode;
        // the unknown text extension falls through to its binary fallback.
        val bytes = ("x".repeat(70_000) + "\nobis\n").toByteArray()

        assertEquals(
            MeterFileFormat.FRAMED_BINARY,
            MeterFileImportRouter.detectFormat("export.bin", null, bytes)
        )
    }

    @Test
    fun `plain extensions map to their formats`() {
        val plain = "value".toByteArray()

        assertEquals(MeterFileFormat.JSON, MeterFileImportRouter.detectFormat("export.json", null, plain))
        assertEquals(MeterFileFormat.TAGGED_OBIS_CSV, MeterFileImportRouter.detectFormat("export.csv", null, plain))
        assertEquals(MeterFileFormat.TAGGED_OBIS_CSV, MeterFileImportRouter.detectFormat("export.txt", null, plain))
        assertEquals(MeterFileFormat.TAGGED_OBIS_CSV, MeterFileImportRouter.detectFormat("export.out", null, plain))
        assertEquals(MeterFileFormat.FRAMED_BINARY, MeterFileImportRouter.detectFormat("export.dat", null, plain))
    }

    @Test
    fun `router skips leading timestamp garbage before the frame marker`() {
        val prefix = "Exported 2026-01-01T00:00:00\n"
        val text = "S\$SModel=ML-3P,Serial=987654,Manufacturer=Example,Phase=3"
        val dump = (prefix + text).toByteArray(Charsets.ISO_8859_1) + doubleBytes(77.5)

        val result = MeterFileImportRouter.route(dump, "export.dat", "application/octet-stream")

        assertEquals(MeterFileFormat.FRAMED_BINARY, result.format)
        assertEquals(
            "987654",
            result.report.identity?.rows?.first { it.obisCode == "0.0.96.1.0.255" }?.formattedValue
        )
    }

    @Test
    fun `uppercase nameplate headers and multi line profiles are stitched together`() {
        val csv = """
            NAMEPLATE DETAILS
            0.0.96.1.0.255,Meter Serial Number,555444
            END
            INSTANT DATA OBIS
            0100010800FF,0100200700FF
            END
            INSTANT DATA VALUE
            10,20,
            30,40
            END
            INSTANT SCALAR VALUE
            -1|kWh,-1|V
            END
        """.trimIndent()

        val report = ObisProfileCsvParser.parse(csv)
        assertNotNull(report)
        assertEquals(
            "555444",
            report!!.identity?.rows?.first { it.obisCode == "0.0.96.1.0.255" }?.formattedValue
        )
        assertEquals(
            listOf(listOf("1", "2"), listOf("3", "4")),
            report.instantaneous?.rows
        )
    }

    @Test
    fun `scale engine keeps zero compact and maps null registers to dash`() {
        val csv = """
            Instant data OBIS
            0100010800FF,0100200700FF,01001F0700FF
            END
            Instant data value
            0,65535,4294967295
            END
            Instant scalar value
            -3|kWh,-3|V,-3|A
            END
        """.trimIndent()

        val report = ObisProfileCsvParser.parse(csv)
        assertNotNull(report)
        assertEquals(listOf("0", "-", "-"), report!!.instantaneous?.rows?.single())
    }

    @Test
    fun `scale engine clamps runaway fractional padding`() {
        val csv = """
            Instant data OBIS
            0100010800FF
            END
            Instant data value
            123456789012345678901234567890
            END
            Instant scalar value
            -30|kWh
            END
        """.trimIndent()

        val report = ObisProfileCsvParser.parse(csv)
        assertNotNull(report)
        assertEquals("0.123", report!!.instantaneous?.rows?.single()?.single())
    }

    @Test
    fun `lnt positional nameplate resolves identity and clock`() {
        val csv = """
            Name plate details,LNTB2XXXXXXXC5,        92717295,LARSEN AND TOUBRO LIMITED,TD00.02,5,0,0,C3,10-60A  ,2019,0,END
            Programmable datas,28/12/2023 23:25:47,1800,1800,END
            Instant data OBIS,0100010800FF,0100090800FF,END
            Instant data value,7443000,9077800,END
            Instant scalar OBIS,0100010800FF,0100090800FF,END
            Instant scalar value,-3|kWh,-3|kVAh,END
        """.trimIndent()

        val report = ObisProfileCsvParser.parse(csv)
        assertNotNull(report)

        fun identity(obis: String) =
            report!!.identity?.rows?.first { it.obisCode == obis }?.formattedValue

        assertEquals("LNTB2XXXXXXXC5", identity("0.0.42.0.0.255"))
        assertEquals("92717295", identity("0.0.96.1.0.255"))
        assertEquals("LARSEN AND TOUBRO LIMITED", identity("0.0.96.1.1.255"))
        assertEquals("TD00.02", identity("1.0.0.2.0.255"))
        assertEquals("10-60A", identity("0.0.94.91.12.255"))
        assertEquals("2019", identity("0.0.96.1.4.255"))
        assertEquals("28/12/2023 23:25:47", identity("0.0.1.0.0.255"))
        assertEquals(listOf("7443", "9077.8"), report!!.instantaneous?.rows?.single())
    }

    @Test
    fun `vendor positional nameplates with separators keep identity`() {
        val records = listOf(
            "Name plate details,AMEERIS106_10,0722899,AVON METERS PVT LTD,AA1.00,5,1,1,C3,10-60A,2016,0,END"
                to listOf("AMEERIS106_10", "0722899", "AVON METERS PVT LTD", "10-60A", "2016"),
            "Name plate details,GOESPHWCN-S,     6799485,GENUS POWER INFRASTRUCTURES LTD,G36B7.160001,5,1,1,C3,10-60A,2017,0,END"
                to listOf("GOESPHWCN-S", "6799485", "GENUS POWER INFRASTRUCTURES LTD", "10-60A", "2017"),
            "Name plate details,LGZ-LGC110E150,  1234567,SCHNEIDER ELECTRIC INDIA PRIVATE LIMITED,TD00.02,5,1,1,C3,10-60A,2019,0,END"
                to listOf("LGZ-LGC110E150", "1234567", "SCHNEIDER ELECTRIC INDIA PRIVATE LIMITED", "10-60A", "2019")
        )

        records.forEach { (line, expected) ->
            val report = ObisProfileCsvParser.parse(line)
            assertNotNull(report)
            val rows = report!!.identity?.rows.orEmpty()
            fun identity(obis: String) = rows.firstOrNull { it.obisCode == obis }?.formattedValue
            val missing = rows.joinToString(" | ") { it.obisCode }
            assertEquals("LDN for $line (rows: $missing)", expected[0], identity("0.0.42.0.0.255"))
            assertEquals("serial for $line", expected[1], identity("0.0.96.1.0.255"))
            assertEquals("manufacturer for $line", expected[2], identity("0.0.96.1.1.255"))
            assertEquals("current rating for $line", expected[3], identity("0.0.94.91.12.255"))
            assertEquals("year for $line", expected[4], identity("0.0.96.1.4.255"))
        }
    }

    @Test
    fun `unset positional meter clock is not recorded as a real time`() {
        val csv = """
            Name plate details,LNTB2XXXXXXXC5,        A5806062,LARSEN AND TOUBRO LIMITED,TD00.02,5,0,0,C3,10-60A  ,2023,0,END
            Programmable datas,00/00/0000 00:00:04,1800,1800,END
        """.trimIndent()

        val report = ObisProfileCsvParser.parse(csv)
        assertNotNull(report)
        assertEquals("A5806062", report!!.identity?.rows?.first { it.obisCode == "0.0.96.1.0.255" }?.formattedValue)
        assertTrue(
            report.identity?.rows.orEmpty().none { it.obisCode == "0.0.1.0.0.255" }
        )
    }

    @Test
    fun `record dump decodes identity, captures and register buffer`() {
        val serial = record(
            classId = 1,
            obis = intArrayOf(0x00, 0x00, 0x60, 0x01, 0x00, 0xFF),
            attribute = 2,
            payload = byteArrayOf(0x09, 0x06) + "123456".toByteArray()
        )
        val captureObjects = record(
            classId = 7,
            obis = intArrayOf(0x01, 0x00, 0x5E, 0x5B, 0x00, 0xFF),
            attribute = 3,
            payload = byteArrayOf(
                0x01, 0x02,
                0x02, 0x04, 0x12, 0x00, 0x08,
                0x09, 0x06, 0x00, 0x00, 0x01, 0x00, 0x00, 0xFF.toByte(),
                0x0F, 0x02, 0x12, 0x00, 0x00,
                0x02, 0x04, 0x12, 0x00, 0x03,
                0x09, 0x06, 0x01, 0x00, 0x01, 0x08, 0x00, 0xFF.toByte(),
                0x0F, 0x02, 0x12, 0x00, 0x00
            )
        )
        val buffer = record(
            classId = 7,
            obis = intArrayOf(0x01, 0x00, 0x5E, 0x5B, 0x00, 0xFF),
            attribute = 2,
            payload = byteArrayOf(
                0x01, 0x01,
                0x02, 0x02,
                0x19, 0x0C,
                0x07, 0xE8.toByte(), 0x0B, 0x11, 0x02, 0x0C, 0x2C, 0x25,
                0xFF.toByte(), 0x01, 0x4A, 0x00,
                0x06, 0x00, 0x00, 0x00, 0x64
            )
        )

        val result = MeterFileImportRouter.route(
            serial + captureObjects + buffer,
            "record-dump.dat",
            "application/octet-stream"
        )

        assertEquals(MeterFileFormat.FRAMED_BINARY, result.format)
        assertEquals(
            "123456",
            result.report.identity?.rows?.first { it.obisCode == "0.0.96.1.0.255" }?.formattedValue
        )
        assertEquals("0.1", result.report.instantaneous?.rows?.single()?.last())
        assertEquals(
            "0.1",
            result.report.cumulativeEnergies.first { it.obisCode == "1.0.1.8.0.255" }.reading
        )
    }

    @Test
    fun `record dump decodes long64 billing energy counters`() {
        val captureObjects = record(
            classId = 7,
            obis = intArrayOf(0x01, 0x00, 0x62, 0x01, 0x00, 0xFF),
            attribute = 3,
            payload = byteArrayOf(
                0x01, 0x02,
                0x02, 0x04, 0x12, 0x00, 0x00,
                0x09, 0x06, 0x01, 0x00, 0x01, 0x08, 0x00, 0xFF.toByte(),
                0x0F, 0x02, 0x12, 0x00, 0x00,
                0x02, 0x04, 0x12, 0x00, 0x00,
                0x09, 0x06, 0x01, 0x00, 0x09, 0x08, 0x00, 0xFF.toByte(),
                0x0F, 0x02, 0x12, 0x00, 0x00
            )
        )
        val buffer = record(
            classId = 7,
            obis = intArrayOf(0x01, 0x00, 0x62, 0x01, 0x00, 0xFF),
            attribute = 2,
            payload = byteArrayOf(
                0x01, 0x01,
                0x02, 0x02,
                0x15, 0x00, 0x00, 0x00, 0x00, 0x00, 0x12, 0xDC.toByte(), 0x8F.toByte(),
                0x15, 0x00, 0x00, 0x00, 0x00, 0x00, 0x8A.toByte(), 0x84.toByte(), 0x28
            )
        )

        val result = MeterFileImportRouter.route(
            captureObjects + buffer,
            "record-dump.dat",
            "application/octet-stream"
        )

        assertEquals(MeterFileFormat.FRAMED_BINARY, result.format)
        val billing = result.report.billing
        assertNotNull(billing)
        assertEquals(2, billing!!.columns.size)
        assertEquals(listOf("1236.111", "9077.8"), billing.rows.single())
        assertEquals(
            "1236.111",
            result.report.cumulativeEnergies.first { it.obisCode == "1.0.1.8.0.255" }.reading
        )
    }

    private fun record(
        classId: Int,
        obis: IntArray,
        attribute: Int,
        payload: ByteArray
    ): ByteArray = ByteArrayOutputStream().apply {
        write("S\$S".toByteArray(Charsets.ISO_8859_1))
        write((classId shr 8) and 0xFF)
        write(classId and 0xFF)
        obis.forEach { write(it and 0xFF) }
        write(attribute)
        write(0x00)
        write(0x2D)
        write(payload)
    }.toByteArray()

    private fun doubleBytes(value: Double): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(value).array()
}
