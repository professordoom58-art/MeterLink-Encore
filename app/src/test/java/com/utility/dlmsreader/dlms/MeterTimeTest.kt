package com.utility.dlmsreader.dlms

import gurux.dlms.GXDLMSClient
import gurux.dlms.GXDateTime
import gurux.dlms.enums.DataType
import gurux.dlms.enums.DateTimeSkips
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class MeterTimeTest {
    @Test
    fun `meter wall clock is stable across phone time zones`() {
        val original = TimeZone.getDefault()
        try {
            val vectors = listOf(
                byteArrayOf(0x07, 0xE6.toByte(), 0x07, 0x05, 0xFF.toByte(), 0x00, 0x00, 0x00, 0xFF.toByte(), 0x01, 0x4A, 0x00) to
                    "05-Jul-2022 00:00:00",
                byteArrayOf(0x07, 0xE7.toByte(), 0x0C, 0x1C, 0xFF.toByte(), 0x15, 0x24, 0x33, 0xFF.toByte(), 0x01, 0x4A, 0x00) to
                    "28-Dec-2023 21:36:51",
                byteArrayOf(0x07, 0xE5.toByte(), 0x09, 0x08, 0xFF.toByte(), 0x03, 0x23, 0x00, 0xFF.toByte(), 0x01, 0x4A, 0x00) to
                    "08-Sep-2021 03:35:00",
                byteArrayOf(0x07, 0xE6.toByte(), 0x07, 0x05, 0xFF.toByte(), 0x00, 0x00, 0x00, 0xFF.toByte(), 0xFE.toByte(), 0xB6.toByte(), 0x00) to
                    "05-Jul-2022 00:00:00"
            )
            listOf("Asia/Kolkata", "UTC", "America/New_York").forEach { zone ->
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                vectors.forEach { (bytes, expected) ->
                    val dateTime = GXDLMSClient.changeType(bytes, DataType.DATETIME, false) as GXDateTime
                    assertEquals(expected, MeterTime.format(dateTime))
                    val flippedDateTime = GXDLMSClient.changeType(bytes, DataType.DATETIME, true) as GXDateTime
                    assertEquals(expected, MeterTime.format(flippedDateTime))
                }
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `positive deviation is available for selective access decision`() {
        val bytes = byteArrayOf(
            0x07, 0xE7.toByte(), 0x0C, 0x1C, 0xFF.toByte(), 0x15, 0x24, 0x33,
            0xFF.toByte(), 0x01, 0x4A, 0x00
        )
        val dateTime = GXDLMSClient.changeType(bytes, DataType.DATETIME, false) as GXDateTime
        assertEquals(330, dateTime.deviation)
    }

    @Test
    fun `unspecified deviation is not treated as positive IST`() {
        val bytes = byteArrayOf(
            0x07, 0xE6.toByte(), 0x07, 0x05, 0xFF.toByte(), 0x00, 0x00, 0x00,
            0xFF.toByte(), 0x80.toByte(), 0x00, 0x00
        )
        val dateTime = GXDLMSClient.changeType(bytes, DataType.DATETIME, false) as GXDateTime
        assertEquals(true, dateTime.skip.contains(DateTimeSkips.DEVIATION))
    }
}
