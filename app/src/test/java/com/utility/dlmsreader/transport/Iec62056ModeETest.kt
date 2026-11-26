package com.utility.dlmsreader.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iec62056ModeETest {

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    @Test
    fun `acknowledgement requests HDLC binary mode, not data readout`() {
        // ACK '2' Z '2' CR LF. The old code sent ACK '0' '5' '0' (Mode C readout).
        assertArrayEquals(
            byteArrayOf(0x06, 0x32, 0x35, 0x32, 0x0D, 0x0A),
            Iec62056ModeE.modeEAcknowledgement('5')
        )
        assertArrayEquals(
            byteArrayOf(0x06, 0x32, 0x36, 0x32, 0x0D, 0x0A),
            Iec62056ModeE.modeEAcknowledgement('6')
        )
    }

    @Test
    fun `sign on request is slash question bang CR LF`() {
        assertArrayEquals(ascii("/?!\r\n"), Iec62056ModeE.SIGN_ON_REQUEST)
    }

    @Test
    fun `identification yields manufacturer, baud and Mode E marker`() {
        val id = Iec62056ModeE.parseIdentification(ascii("/LGZ5\\2ZMD3104407.B32\r\n"))!!
        assertEquals("LGZ", id.manufacturer)
        assertEquals('5', id.baudChar)
        assertEquals(9600, id.baudRate)
        assertTrue(id.supportsModeE)
    }

    @Test
    fun `baud character follows the meter, not a fixed 9600`() {
        val id = Iec62056ModeE.parseIdentification(ascii("/ABC6\\2METER\r\n"))!!
        assertEquals(19200, id.baudRate)
        assertEquals(4800, Iec62056ModeE.parseIdentification(ascii("/ABC4XYZ\r\n"))!!.baudRate)
    }

    @Test
    fun `identification without Mode E marker is still parsed`() {
        val id = Iec62056ModeE.parseIdentification(ascii("/ISK5MT174-0001\r\n"))!!
        assertFalse(id.supportsModeE)
    }

    @Test
    fun `parity bit and leading noise are ignored`() {
        // 7E1 bytes read as 8N1 can carry the parity bit in bit 7.
        val withParity = ascii("/LGZ5\\2X\r\n").map { (it.toInt() or 0x80).toByte() }.toByteArray()
        val noisy = byteArrayOf(0x00, 0x7F) + withParity
        assertEquals("LGZ", Iec62056ModeE.parseIdentification(noisy)!!.manufacturer)
    }

    @Test
    fun `partial or malformed replies are rejected`() {
        assertNull(Iec62056ModeE.parseIdentification(ascii("/LGZ5\\2ZMD")))      // no CR LF yet
        assertNull(Iec62056ModeE.parseIdentification(ascii("LGZ5\r\n")))         // no '/'
        assertNull(Iec62056ModeE.parseIdentification(ascii("/LGZ9X\r\n")))       // bad baud char
        assertNull(Iec62056ModeE.parseIdentification(ascii("/L1Z5X\r\n")))       // bad manufacturer
        assertFalse(Iec62056ModeE.isIdentificationComplete(ascii("/LGZ5\\2ZMD")))
        assertTrue(Iec62056ModeE.isIdentificationComplete(ascii("/LGZ5\\2ZMD\r\n")))
    }
}
