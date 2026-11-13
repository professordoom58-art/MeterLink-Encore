package com.utility.dlmsreader

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the SCAN USB dot colour against the transport's real probe statuses. */
class ScanUsbDotColorTest {

    private val red = Color(0xFFEF4444)
    private val green = Color(0xFF10B981)

    @Test
    fun `initial no probe status is red`() {
        assertEquals(red, scanUsbDotColor("No Optical Probe Connected"))
    }

    @Test
    fun `missing usb probe is red despite the Detected word`() {
        assertEquals(red, scanUsbDotColor("No USB Optical Probe Detected"))
        assertEquals(red, scanUsbDotColor("No USB-Serial optical probe found."))
    }

    @Test
    fun `permission and disconnect errors are red`() {
        assertEquals(red, scanUsbDotColor("USB permission denied"))
        assertEquals(red, scanUsbDotColor("Disconnected"))
        assertEquals(red, scanUsbDotColor("Probe error: read failed"))
    }

    @Test
    fun `no meter response is red despite Cable and Connected`() {
        assertEquals(
            red,
            scanUsbDotColor("Cable Connected, but No Meter Response (Check alignment)")
        )
    }

    @Test
    fun `a detected usb probe turns the dot green`() {
        assertEquals(green, scanUsbDotColor("USB Cable Detected"))
    }

    @Test
    fun `established link is green`() {
        assertEquals(green, scanUsbDotColor("Meter Link Established Successfully!"))
        assertEquals(green, scanUsbDotColor("Ready"))
    }

    @Test
    fun `empty or unknown status falls back to red`() {
        assertEquals(red, scanUsbDotColor(""))
        assertEquals(red, scanUsbDotColor("Scanning"))
    }
}
