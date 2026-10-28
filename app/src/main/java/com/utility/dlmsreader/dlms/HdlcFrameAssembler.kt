package com.utility.dlmsreader.dlms

import java.io.ByteArrayOutputStream

/**
 * Reassembles HDLC frames received in arbitrary USB serial chunks.
 *
 * Enforces the HDLC framing rules:
 * 1. Discards leading noise bytes before opening 0x7E flag.
 * 2. Computes the expected frame length from format/segmentation header bytes (Format Type 3: A0/A8).
 * 3. Confirms completion only when rxBuffer.size >= totalExpectedBytes AND the frame boundary is 0x7E.
 */
class HdlcFrameAssembler {
    private val buffer = ByteArrayOutputStream()

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
        sanitizeLeadingNoise()
    }

    /**
     * Discards any leading noise bytes before opening 0x7E flag.
     * Also strips duplicate opening flags (e.g., 7E 7E) and spurious flags followed by non-HDLC bytes.
     */
    private fun sanitizeLeadingNoise() {
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

            // If we have at least 2 bytes, check if format byte is HDLC Type 3 (0xA0..0xAF)
            if (current.size >= 2) {
                val formatType = current[1].toInt() and 0xFF
                if ((formatType and 0xF0) != 0xA0) {
                    // Spurious 0x7E flag in noise, drop it and search for next 0x7E
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
     * val formatType = rxBuffer[1].toInt() and 0xFF
     * val expectedFramePayloadLen = ((formatType and 0x07) shl 8) or (rxBuffer[2].toInt() and 0xFF)
     * val totalExpectedBytes = expectedFramePayloadLen + 2 // +2 for opening 0x7E and closing 0x7E
     */
    fun expectedTotalBytes(): Int? {
        val current = buffer.toByteArray()
        if (current.size < 3) return null
        if (current[0] != 0x7E.toByte()) return null

        val formatType = current[1].toInt() and 0xFF
        val expectedFramePayloadLen = ((formatType and 0x07) shl 8) or (current[2].toInt() and 0xFF)
        return expectedFramePayloadLen + 2
    }

    /**
     * Checks if the complete HDLC frame has arrived.
     */
    fun isComplete(): Boolean {
        val expected = expectedTotalBytes() ?: return false
        val current = buffer.toByteArray()
        if (current.size < expected) return false
        return (expected > 0 && current[expected - 1] == 0x7E.toByte()) ||
            current.lastOrNull() == 0x7E.toByte()
    }

    /**
     * Returns the assembled frame trimmed to the exact expected length if available.
     */
    fun getAssembledFrame(): ByteArray? {
        if (!isComplete()) return null
        val expected = expectedTotalBytes() ?: return null
        val current = buffer.toByteArray()
        return if (current.size >= expected && current[expected - 1] == 0x7E.toByte()) {
            current.copyOfRange(0, expected)
        } else {
            current
        }
    }
}

