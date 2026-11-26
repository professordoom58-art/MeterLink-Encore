package com.utility.dlmsreader.transport

/**
 * IEC 62056-21 optical sign-on, Mode E (switch to binary HDLC).
 *
 * Sequence, all at 300 baud 7E1 until the switch:
 * 1. Client sends the request `/?!<CR><LF>`.
 * 2. Meter answers with its identification `/XXXZ<ident><CR><LF>`: XXX is
 *    the manufacturer code, Z the highest baud rate it offers, and an
 *    identification containing `\2` advertises Mode E (HDLC) support.
 * 3. Client sends the acknowledgement `ACK V Z Y <CR><LF>` with V = '2'
 *    (HDLC protocol procedure), Z = the meter's baud character, and
 *    Y = '2' (binary mode). `ACK 0 Z 0` would instead request a plain
 *    ASCII data readout (Mode C), which is not what the DLMS engine expects.
 * 4. Both sides switch to the Z baud rate at 8N1 and HDLC starts with SNRM.
 */
internal object Iec62056ModeE {

    /** `/?!<CR><LF>` request message. */
    val SIGN_ON_REQUEST: ByteArray = "/?!\r\n".toByteArray(Charsets.US_ASCII)

    /** Default baud character when the identification has no usable one ('5' = 9600). */
    const val DEFAULT_BAUD_CHAR = '5'

    /** Parsed identification message. */
    data class Identification(
        val manufacturer: String,
        val baudChar: Char,
        val baudRate: Int,
        /** True when the identification carries the `\2` Mode E marker. */
        val supportsModeE: Boolean,
        val raw: String
    )

    /** Mode C/E baud rate characters '0'..'6' -> 300..19200 baud. */
    fun baudRateFor(baudChar: Char): Int? = when (baudChar) {
        '0' -> 300
        '1' -> 600
        '2' -> 1200
        '3' -> 2400
        '4' -> 4800
        '5' -> 9600
        '6' -> 19200
        else -> null
    }

    /**
     * Parses `/XXXZ...` from [bytes]. Leading noise before `/` is skipped, the
     * 7-bit parity bit is masked off, and the message must end in CR LF.
     * Returns null for anything that is not a complete identification.
     */
    fun parseIdentification(bytes: ByteArray): Identification? {
        val text = String(ByteArray(bytes.size) { (bytes[it].toInt() and 0x7F).toByte() }, Charsets.US_ASCII)
        val start = text.indexOf('/')
        if (start < 0) return null
        val end = text.indexOf("\r\n", start)
        if (end < 0) return null
        val message = text.substring(start, end)
        // '/' + 3-letter manufacturer + baud char + at least one ident char.
        if (message.length < 6) return null
        val manufacturer = message.substring(1, 4)
        if (!manufacturer.all { it.isLetter() }) return null
        val baudChar = message[4]
        val baudRate = baudRateFor(baudChar) ?: return null
        return Identification(
            manufacturer = manufacturer,
            baudChar = baudChar,
            baudRate = baudRate,
            supportsModeE = message.substring(5).contains("\\2"),
            raw = message
        )
    }

    /** True once [bytes] hold a complete identification line (ends with CR LF after '/'). */
    fun isIdentificationComplete(bytes: ByteArray): Boolean {
        val slash = bytes.indexOfFirst { (it.toInt() and 0x7F) == '/'.code }
        if (slash < 0) return false
        for (i in slash + 1 until bytes.size - 1) {
            if ((bytes[i].toInt() and 0x7F) == 0x0D && (bytes[i + 1].toInt() and 0x7F) == 0x0A) return true
        }
        return false
    }

    /** `ACK 2 Z 2 <CR><LF>`: HDLC protocol procedure at baud [baudChar], binary mode. */
    fun modeEAcknowledgement(baudChar: Char): ByteArray =
        byteArrayOf(0x06, '2'.code.toByte(), baudChar.code.toByte(), '2'.code.toByte(), 0x0D, 0x0A)

    /**
     * Time to let the acknowledgement leave the UART before changing the baud
     * rate: 6 characters x 10 bits at 300 baud = 200 ms, plus margin.
     */
    const val ACK_DRAIN_MS = 300L
}
