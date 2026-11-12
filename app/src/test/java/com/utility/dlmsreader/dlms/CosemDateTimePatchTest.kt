package com.utility.dlmsreader.dlms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CosemDateTimePatchTest {

    /** Raw APDU captured from the live L&T 92717295 Selector 1 request. */
    private val liveRangeApdu = hex(
        "C0 01 C1 00 07 01 00 63 01 00 FF 02 01 01 02 04 02 04 12 00 08 " +
            "09 06 00 00 01 00 00 FF 0F 02 12 00 00 " +
            "09 0C 07 E7 0C 1A 02 00 00 00 00 FE B6 00 " +
            "09 0C 07 E7 0C 1C 04 17 3B 3B 00 FE B6 00 01 00"
    )

    @Test
    fun `selector 1 datetimes match SMRS wildcard format`() {
        val patched = patchProfileRangeDatetimes(arrayOf(liveRangeApdu)).single()
        val first = firstDateTimeOffset(patched)
        val second = nextDateTimeOffset(patched, first + 12)

        // SMRS Send_Read_Query_ld: FF day-of-week, 00 hundredths,
        // 80 00 unspecified deviation, FF clock status.
        assertArrayEquals(
            hex("07 E7 0C 1A FF 00 00 00 00 80 00 FF"),
            patched.copyOfRange(first, first + 12)
        )
        assertArrayEquals(
            hex("07 E7 0C 1C FF 17 3B 3B 00 80 00 FF"),
            patched.copyOfRange(second, second + 12)
        )
        assertEquals(0x80, patched[first + 9].toInt() and 0xFF)
        assertEquals(0x00, patched[first + 10].toInt() and 0xFF)
        assertEquals(0xFF, patched[first + 11].toInt() and 0xFF)
        assertEquals(0x80, patched[second + 9].toInt() and 0xFF)
        assertEquals(0x00, patched[second + 10].toInt() and 0xFF)
        assertEquals(0xFF, patched[second + 11].toInt() and 0xFF)
        assertEquals(0x00, patched[first + 8].toInt() and 0xFF)
        assertEquals(0x00, patched[second + 8].toInt() and 0xFF)
    }

    @Test
    fun `native RTC retry keeps the meter deviation and status`() {
        val patched = patchProfileRangeDatetimes(
            arrayOf(liveRangeApdu),
            format = Selector1DateTimeFormat.NATIVE_RTC,
            nativeDeviationMinutes = 330
        ).single()
        val first = firstDateTimeOffset(patched)
        val second = nextDateTimeOffset(patched, first + 12)

        assertArrayEquals(
            hex("07 E7 0C 1A FF 00 00 00 FF 01 4A 00"),
            patched.copyOfRange(first, first + 12)
        )
        assertArrayEquals(
            hex("07 E7 0C 1C FF 17 3B 3B FF 01 4A 00"),
            patched.copyOfRange(second, second + 12)
        )
    }

    @Test
    fun `deviation encoding keeps the meter native sign`() {
        assertArrayEquals(byteArrayOf(0x01, 0x4A), encodeDeviationMinutes(330))
        assertArrayEquals(byteArrayOf(0xFE.toByte(), 0xB6.toByte()), encodeDeviationMinutes(-330))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x00), encodeDeviationMinutes(0x8000))
    }

    @Test
    fun `hdlc fcs is recalculated after patching a framed request`() {
        val payload = hex(
            "09 0C 07 E7 0C 1A 02 00 00 00 00 FE B6 00 " +
                "09 0C 07 E7 0C 1C 04 17 3B 3B 00 FE B6 00"
        )
        val frame = byteArrayOf(0x7E, 0xA0.toByte(), 0x08, 0x00) +
            payload +
            byteArrayOf(0x00, 0x00, 0x7E)

        val patched = patchProfileRangeDatetimes(arrayOf(frame)).single()
        val expectedFcs = hdlcFcs16(patched, 1, patched.size - 4)

        assertEquals(expectedFcs and 0xFF, patched[patched.size - 3].toInt() and 0xFF)
        assertEquals((expectedFcs ushr 8) and 0xFF, patched[patched.size - 2].toInt() and 0xFF)
    }

    @Test
    fun `known good hdlc frame keeps its fcs`() {
        val frame = hex("7E A8 0D 21 03 30 10 B6 C4 01 C1 00 14 37 7E")
        updateHdlcFcs(frame)

        assertEquals(0x14, frame[frame.size - 3].toInt() and 0xFF)
        assertEquals(0x37, frame[frame.size - 2].toInt() and 0xFF)
        assertEquals(0x3714, hdlcFcs16(frame, 1, frame.size - 4))
    }

    @Test
    fun `non framed apdu is patched without an fcs rewrite`() {
        val patched = patchProfileRangeDatetimes(arrayOf(liveRangeApdu)).single()
        assertTrue(patched.size == liveRangeApdu.size)
        val first = firstDateTimeOffset(patched)
        assertArrayEquals(
            hex("07 E7 0C 1A FF 00 00 00 00 80 00 FF"),
            patched.copyOfRange(first, first + 12)
        )
    }

    private fun firstDateTimeOffset(bytes: ByteArray): Int =
        nextDateTimeOffset(bytes, 0)

    private fun nextDateTimeOffset(bytes: ByteArray, from: Int): Int {
        for (index in from until bytes.size - 1) {
            if ((bytes[index].toInt() and 0xFF) == 0x09 &&
                (bytes[index + 1].toInt() and 0xFF) == 0x0C
            ) {
                return index + 2
            }
        }
        error("No COSEM datetime octet string found")
    }

    private fun hex(value: String): ByteArray =
        value.trim().split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .map { it.toInt(16).toByte() }
            .toByteArray()
}
