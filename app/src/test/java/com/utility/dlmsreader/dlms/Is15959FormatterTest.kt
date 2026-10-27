package com.utility.dlmsreader.dlms

import org.junit.Assert.assertEquals
import org.junit.Test

class Is15959FormatterTest {
    @Test
    fun `billing reset status values have field labels`() {
        assertEquals(
            "65 (Scheduled)",
            Is15959Formatter.formatValue(65, obis = "1.0.128.8.38.255", includeUnit = false)
        )
        assertEquals(
            "255 (Current)",
            Is15959Formatter.formatValue(255, obis = "0.128.171.128.128.255", includeUnit = false)
        )
    }

    @Test
    fun `vendor temperature label carries Celsius unit`() {
        val descriptor = Is15959Formatter.getObisLabelAndUnit("1.0.128.8.98.255")
        assertEquals("Meter Temp / Vendor (1.0.128.8.98.255)", descriptor.label)
        assertEquals("°C", descriptor.unit)
    }
}
