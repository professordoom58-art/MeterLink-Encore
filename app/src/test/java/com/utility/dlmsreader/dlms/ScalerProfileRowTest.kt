package com.utility.dlmsreader.dlms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins how a scaler profile row (e.g. billing 1.0.94.91.6.255) is paired with
 * its capture objects. A wrong pairing puts one column's scaler and unit on
 * another column. That only shows in the wide profiles (billing) and gives
 * values that are off by powers of ten or carry the wrong unit.
 */
class ScalerProfileRowTest {

    private val kwh = "1.0.1.8.0.255"
    private val kvah = "1.0.9.8.0.255"
    private val mdKw = "1.0.1.6.0.255"

    @Test
    fun `aligned row maps each scaler to its own obis`() {
        val parsed = parseScalerProfileRow(
            listOf(kwh, kvah, mdKw),
            listOf(listOf<Any?>(0.toByte(), 30), listOf<Any?>(1.toByte(), 31), listOf<Any?>((-2).toByte(), 27))
        )!!
        assertEquals(ScalerEntry(0, 30), parsed[kwh])
        assertEquals(ScalerEntry(1, 31), parsed[kvah])
        assertEquals(ScalerEntry(-2, 27), parsed[mdKw])
    }

    @Test
    fun `row length mismatch is rejected instead of shifting scalers`() {
        // One cell short: index pairing would give kVAh the MD scaler.
        assertNull(
            parseScalerProfileRow(
                listOf(kwh, kvah, mdKw),
                listOf(listOf<Any?>(0.toByte(), 30), listOf<Any?>((-2).toByte(), 27))
            )
        )
        // One cell extra.
        assertNull(
            parseScalerProfileRow(
                listOf(kwh),
                listOf(listOf<Any?>(0.toByte(), 30), listOf<Any?>(0.toByte(), 31))
            )
        )
    }

    @Test
    fun `unsigned8 exponent is read as signed integer8`() {
        val parsed = parseScalerProfileRow(listOf(kvah), listOf(listOf<Any?>(253.toShort(), 31)))!!
        assertEquals(-3, parsed.getValue(kvah).exponent)
    }

    @Test
    fun `out of range or missing exponent is dropped for direct read`() {
        val parsed = parseScalerProfileRow(
            listOf(kwh, kvah, mdKw),
            listOf(listOf<Any?>(42, 30), listOf<Any?>(null, 31), listOf<Any?>(0.toByte(), 27))
        )!!
        assertFalse(kwh in parsed)
        assertFalse(kvah in parsed)
        assertEquals(ScalerEntry(0, 27), parsed[mdKw])
    }

    @Test
    fun `empty profile parses to empty map`() {
        assertEquals(emptyMap<String, ScalerEntry>(), parseScalerProfileRow(emptyList(), emptyList()))
    }
}
