package com.utility.dlmsreader.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UiFormattersTest {

    @Test
    fun `wh readings scale down to the kWh heading`() {
        assertEquals("21239.7", energyAmount("21239700 WH", "kWh"))
    }

    @Test
    fun `vah readings scale down to the kVAh heading`() {
        assertEquals("24506.7", energyAmount("24506700 VAH", "kVAh"))
    }

    @Test
    fun `readings already in the heading unit keep their value`() {
        assertEquals("7,443", energyAmount("7,443.000 kWh", "kWh"))
        assertEquals("9,077", energyAmount("9,077.800 kVAh", "kVAh"))
    }

    @Test
    fun `unitless and placeholder readings are left alone`() {
        assertEquals("825", energyAmount("825", "kWh"))
        assertEquals("—", energyAmount("—", "kWh"))
        assertEquals("N/A", energyAmount("N/A", "kWh"))
    }

    @Test
    fun `different energy families are not converted`() {
        assertEquals("1234", energyAmount("1234 WH", "kVAh"))
    }
}