package com.utility.dlmsreader.dlms

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConnectionDiagnostics {

    data class DiagnosticReport(
        val timestamp: String,
        val baudRateAttempted: Int,
        val modeEChecked: Boolean,
        val packetSentHex: String,
        val rawResponseHex: String,
        val failureReason: String,
        val suggestion: String
    )

    fun generateReport(
        modeEUsed: Boolean,
        sentBytes: ByteArray?,
        receivedBytes: ByteArray?,
        errorMessage: String
    ): DiagnosticReport {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val sentHex = sentBytes?.toHexString() ?: "None"
        val responseHex = receivedBytes
            ?.takeIf { it.isNotEmpty() }
            ?.toHexString()
            ?: "0x00 (No Bytes Received - Timeout)"

        val suggestion = if (receivedBytes == null || receivedBytes.isEmpty()) {
            "Check physical alignment of the optical probe, ensure DTR/RTS lines are powered, and press the meter push-button to wake the LCD."
        } else {
            "Meter responded with raw data, but framing or baud rate switch failed. Verify parity and stop bits."
        }

        return DiagnosticReport(
            timestamp = time,
            baudRateAttempted = if (modeEUsed) 300 else 9600,
            modeEChecked = modeEUsed,
            packetSentHex = sentHex,
            rawResponseHex = responseHex,
            failureReason = errorMessage,
            suggestion = suggestion
        )
    }

    private fun ByteArray.toHexString(): String =
        joinToString(" ") { "%02X".format(Locale.US, it.toInt() and 0xFF) }
}
