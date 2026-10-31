package com.utility.dlmsreader.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused tests for [containsHdlcUaFrame], the gate that decides whether an
 * optical-probe read actually contains an HDLC Unnumbered Acknowledgment.
 *
 * The point of the validator is that ambient IR noise, a partial byte, or a
 * non-UA HDLC frame must never be mistaken for an established meter link.
 */
class HdlcUaFrameValidatorTest {

    /**
     * Real capture from the L&T meter over the optical probe.
     *
     * 7E A0 1E 21 03 73 C3 7A 81 80 12 05 01 80 06 01 80 07 04 00 00 00 01
     * 08 04 00 00 00 01 53 3B 7E
     *
     * Format 0xA0, length 0x1E (30 = body + 2-byte FCS), destination 0x21,
     * source 0x03, control 0x73 (UA with poll/final), then an information
     * field, then FCS 0x3B53 (little-endian 53 3B).
     */
    private val realLtUaFrame = byteArrayOf(
        0x7E,
        0xA0.toByte(), 0x1E, 0x21, 0x03, 0x73,
        0xC3.toByte(), 0x7A,
        0x81.toByte(), 0x80.toByte(), 0x12, 0x05, 0x01,
        0x80.toByte(), 0x06, 0x01,
        0x80.toByte(), 0x07, 0x04, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x04, 0x00, 0x00, 0x00, 0x01,
        0x53, 0x3B,
        0x7E
    )

    // ---------------------------------------------------------------- real frame

    @Test
    fun `real captured L&T UA frame is accepted`() {
        assertTrue(
            "the real captured L&T UA frame must be recognised as a UA",
            containsHdlcUaFrame(realLtUaFrame)
        )
    }

    @Test
    fun `real captured L&T UA frame is accepted behind leading optical noise`() {
        val noisy = byteArrayOf(
            0xFF.toByte(), 0x00, 0xFF.toByte(), 0x00, 0x81.toByte(), 0x3C
        ) + realLtUaFrame

        assertTrue(
            "leading optical noise must not hide a valid UA frame",
            containsHdlcUaFrame(noisy)
        )
    }

    @Test
    fun `real captured L&T UA frame is accepted with trailing noise`() {
        val noisy = realLtUaFrame + byteArrayOf(0x00, 0xFF.toByte(), 0x00)

        assertTrue(
            "trailing optical noise must not hide a valid UA frame",
            containsHdlcUaFrame(noisy)
        )
    }

    // ------------------------------------------------------------- ambient noise

    @Test
    fun `single byte ambient IR noise is rejected`() {
        assertFalse("0xFF alone is not a UA frame", containsHdlcUaFrame(byteArrayOf(0xFF.toByte())))
        assertFalse("0x00 alone is not a UA frame", containsHdlcUaFrame(byteArrayOf(0x00)))
        assertFalse("0x7E alone is not a UA frame", containsHdlcUaFrame(byteArrayOf(0x7E)))
    }

    @Test
    fun `multi byte ambient IR noise is rejected`() {
        assertFalse(
            "a flag pair with garbage between is not a UA frame",
            containsHdlcUaFrame(byteArrayOf(0x7E, 0xFF.toByte(), 0x7E))
        )
        assertFalse(
            "back-to-back flags are not a UA frame",
            containsHdlcUaFrame(byteArrayOf(0x7E, 0x7E))
        )
        assertFalse(
            "a run of 0xFF is not a UA frame",
            containsHdlcUaFrame(ByteArray(32) { 0xFF.toByte() })
        )
        assertFalse(
            "a run of 0x00 is not a UA frame",
            containsHdlcUaFrame(ByteArray(32) { 0x00 })
        )
        assertFalse(
            "an empty read is not a UA frame",
            containsHdlcUaFrame(ByteArray(0))
        )
    }

    // -------------------------------------------------------------- corrupted FCS

    @Test
    fun `valid frame with a corrupted FCS byte is rejected`() {
        // Flip the low FCS byte: 0x53 -> 0x54. Everything else stays valid.
        val corrupted = realLtUaFrame.copyOf()
        corrupted[corrupted.size - 3] = 0x54

        assertFalse(
            "a frame whose FCS does not match must be rejected",
            containsHdlcUaFrame(corrupted)
        )
    }

    @Test
    fun `valid frame with a corrupted high FCS byte is rejected`() {
        val corrupted = realLtUaFrame.copyOf()
        corrupted[corrupted.size - 2] = 0x3C

        assertFalse(
            "a frame whose FCS does not match must be rejected",
            containsHdlcUaFrame(corrupted)
        )
    }

    @Test
    fun `valid frame with a corrupted payload byte is rejected`() {
        // Payload corruption breaks the FCS even though the header is intact.
        val corrupted = realLtUaFrame.copyOf()
        corrupted[10] = 0x13

        assertFalse(
            "payload corruption must invalidate the frame",
            containsHdlcUaFrame(corrupted)
        )
    }

    // ------------------------------------------------------------- non-UA frames

    @Test
    fun `DM response is rejected`() {
        // 7E A0 07 21 03 1F 6B E9 7E - control 0x1F is Disconnected Mode.
        val dm = byteArrayOf(
            0x7E, 0xA0.toByte(), 0x07, 0x21, 0x03, 0x1F,
            0x6B, 0xE9.toByte(), 0x7E
        )

        assertFalse("a DM frame is not a UA frame", containsHdlcUaFrame(dm))
    }

    @Test
    fun `AARE information frame is rejected`() {
        // 7E A0 12 21 03 10 0E 61 63 00 00 00 00 00 00 00 00 1E 6D 7E
        // control 0x10 is an I-frame, not a UA.
        val aare = byteArrayOf(
            0x7E, 0xA0.toByte(), 0x12, 0x21, 0x03, 0x10, 0x0E,
            0x61, 0x63, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x1E, 0x6D, 0x7E
        )

        assertFalse("an I-frame carrying AARE is not a UA frame", containsHdlcUaFrame(aare))
    }

    @Test
    fun `SNRM request frame is rejected`() {
        // 7E A0 07 21 03 93 0F A7 7E - control 0x93 is SNRM with poll/final, not UA.
        val snrm = byteArrayOf(
            0x7E, 0xA0.toByte(), 0x07, 0x21, 0x03, 0x93.toByte(),
            0x0F, 0xA7.toByte(), 0x7E
        )

        assertFalse("an SNRM frame is not a UA frame", containsHdlcUaFrame(snrm))
    }

    // ------------------------------------------------------------ minimal UA forms

    @Test
    fun `minimal UA frame with poll final bit is accepted`() {
        // 7E A0 07 21 03 73 01 40 7E - header-only UA, control 0x73.
        val ua = byteArrayOf(
            0x7E, 0xA0.toByte(), 0x07, 0x21, 0x03, 0x73,
            0x01, 0x40, 0x7E
        )

        assertTrue("a header-only UA frame is valid", containsHdlcUaFrame(ua))
    }

    @Test
    fun `minimal UA frame without poll final bit is accepted`() {
        // 7E A0 07 21 03 63 80 50 7E - control 0x63.
        val ua = byteArrayOf(
            0x7E, 0xA0.toByte(), 0x07, 0x21, 0x03, 0x63,
            0x80.toByte(), 0x50, 0x7E
        )

        assertTrue("a UA frame without the poll/final bit is valid", containsHdlcUaFrame(ua))
    }

    // ---------------------------------------------------------------- malformed

    @Test
    fun `truncated frame is rejected`() {
        val truncated = realLtUaFrame.copyOf(realLtUaFrame.size - 1)

        assertFalse(
            "a frame missing its closing flag must be rejected",
            containsHdlcUaFrame(truncated)
        )
    }

    @Test
    fun `frame with mismatched length byte is rejected`() {
        val bad = realLtUaFrame.copyOf()
        bad[2] = 0x1D

        assertFalse(
            "a length byte that disagrees with the frame size must be rejected",
            containsHdlcUaFrame(bad)
        )
    }

    @Test
    fun `frame with a non-A format byte is rejected`() {
        val bad = realLtUaFrame.copyOf()
        bad[1] = 0xA8.toByte()

        assertFalse(
            "a format byte outside the A0 nibble must be rejected",
            containsHdlcUaFrame(bad)
        )
    }
}
