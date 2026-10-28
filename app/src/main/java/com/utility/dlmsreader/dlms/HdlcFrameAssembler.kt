package com.utility.dlmsreader.dlms

import java.io.ByteArrayOutputStream

/**
 * Reassembles HDLC frames received in arbitrary USB serial chunks.
 *
 * Enforces strict IEC 62056-46 HDLC Type-3 framing rules:
 * 1. Discards leading noise bytes before the opening 0x7E flag.
 * 2. Validates minimum HDLC Type-3 frame length (>= 7 payload bytes / 9 total bytes).
 * 3. Computes the exact expected frame length from the Type-3 header (A0..AF).
 * 4. Confirms completion strictly when current[expected - 1] == 0x7E, and automatically
 *    recovers from corrupted/spurious opening flags if the boundary byte is not 0x7E.
 */
class HdlcFrameAssembler {
    private val buffer = ByteArrayOutputStream()

    companion object {
        // Minimum valid HDLC frame payload (Format[2] + Dst[1] + Src[1] + Ctrl[1] + FCS[2] = 7 bytes, +2 flags = 9)
        private const val MIN_HDLC_PAYLOAD_LEN = 7
    }

    val size: Int
        get() = buffer.size()

    val bytes: ByteArray
        get() = buffer.toByteArray()

    fun reset() {
        buffer.reset()
    }

    fun push(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        buffer.write(chunk)
        sanitizeBuffer()
    }

    /**
     * Discards leading noise before 0x7E, strips consecutive idle 0x7E flags,
     * and recovers if a spurious 0x7E header fails boundary validation at [expected - 1].
     */
    private fun sanitizeBuffer() {
        while (true) {
            val current = buffer.toByteArray()
            if (current.isEmpty()) return

            val first7E = current.indexOf(0x7E.toByte())
            if (first7E == -1) {
                buffer.reset()
                return
            }
            if (first7E > 0) {
                buffer.reset()
                buffer.write(current, first7E, current.size - first7E)
                continue
            }

            // current[0] is 0x7E. If followed by another 0x7E, treat as interframe idle fill
            if (current.size >= 2 && current[1] == 0x7E.toByte()) {
                buffer.reset()
                buffer.write(current, 1, current.size - 1)
                continue
            }

            // Check if format byte is HDLC Type 3 (0xA0..0xAF)
            if (current.size >= 2) {
                val formatType = current[1].toInt() and 0xFF
                if ((formatType and 0xF0) != 0xA0) {
                    // Spurious 0x7E flag in noise, drop it and search for next 0x7E
                    buffer.reset()
                    buffer.write(current, 1, current.size - 1)
                    continue
                }
            }

            // Once we have 3 bytes, validate payload length and frame boundary
            if (current.size >= 3) {
                val formatType = current[1].toInt() and 0xFF
                val payloadLen = ((formatType and 0x07) shl 8) or (current[2].toInt() and 0xFF)
                val totalExpected = payloadLen + 2

                if (payloadLen < MIN_HDLC_PAYLOAD_LEN) {
                    // Impossible HDLC frame length -> spurious header, advance past current[0]
                    buffer.reset()
                    buffer.write(current, 1, current.size - 1)
                    continue
                }

                // If enough bytes have arrived to reach the expected closing flag, but it's NOT 0x7E,
                // then current[0] was a false/corrupted frame start. Drop current[0] and resync!
                if (current.size >= totalExpected && current[totalExpected - 1] != 0x7E.toByte()) {
                    buffer.reset()
                    buffer.write(current, 1, current.size - 1)
                    continue
                }
            }

            break
        }
    }

    /**
     * Once at least 3 bytes have been received ([0] == 0x7E, [1] = format/segmentation A0/A8, [2] = length LSB),
     * computes the exact expected HDLC frame length from the header:
     * totalExpectedBytes = expectedFramePayloadLen + 2 (+2 for opening 0x7E and closing 0x7E)
     */
    fun expectedTotalBytes(): Int? {
        val current = buffer.toByteArray()
        if (current.size < 3) return null
        if (current[0] != 0x7E.toByte()) return null

        val formatType = current[1].toInt() and 0xFF
        if ((formatType and 0xF0) != 0xA0) return null

        val expectedFramePayloadLen = ((formatType and 0x07) shl 8) or (current[2].toInt() and 0xFF)
        if (expectedFramePayloadLen < MIN_HDLC_PAYLOAD_LEN) return null

        return expectedFramePayloadLen + 2
    }

    /**
     * Strictly checks if the complete HDLC frame has arrived and ends with 0x7E at the exact header-derived index.
     */
    fun isComplete(): Boolean {
        val expected = expectedTotalBytes() ?: return false
        val current = buffer.toByteArray()
        return current.size >= expected && current[expected - 1] == 0x7E.toByte()
    }

    /**
     * Returns the assembled frame trimmed to the exact expected length (stripping any trailing noise).
     */
    fun getAssembledFrame(): ByteArray? {
        if (!isComplete()) return null
        val expected = expectedTotalBytes() ?: return null
        val current = buffer.toByteArray()
        return current.copyOfRange(0, expected)
    }
}