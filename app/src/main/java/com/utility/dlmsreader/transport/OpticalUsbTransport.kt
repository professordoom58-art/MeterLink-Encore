package com.utility.dlmsreader.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.utility.dlmsreader.dlms.hdlcFcs16
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

enum class TraceDirection { TX, RX, INFO, ERROR }

data class TraceLog(
    val timestampMs: Long = System.currentTimeMillis(),
    val direction: TraceDirection,
    val hexOrMessage: String
)

data class TraceCounters(
    val tx: Long = 0,
    val rx: Long = 0,
    val info: Long = 0,
    val error: Long = 0
)

class OpticalUsbTransport(private val context: Context) {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.utility.dlmsreader.USB_PERMISSION"
        const val STATUS_USB_CABLE_WAITING = "USB Cable Detected"
        const val STATUS_NO_METER_RESPONSE = "❌ Cable Connected, but No Meter Response (Check alignment / push button)"
        const val STATUS_METER_LINK_ESTABLISHED = "✅ Meter Link Established Successfully!"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var port: UsbSerialPort? = null

    private val _probeStatus = MutableStateFlow("No Optical Probe Connected")
    val probeStatus: StateFlow<String> = _probeStatus

    private val _traceFlow = MutableSharedFlow<TraceLog>(extraBufferCapacity = 5000)
    val traceFlow: SharedFlow<TraceLog> = _traceFlow
    private val connectionDiagnostics = ConnectionDiagnostics
    private val txCounter = AtomicLong()
    private val rxCounter = AtomicLong()
    private val infoCounter = AtomicLong()
    private val errorCounter = AtomicLong()
    private val exportTraceLogs = ArrayDeque<TraceLog>()
    private var droppedTraceLines = 0L
    private val traceLock = Any()

    fun resetTraceDiagnostics() {
        txCounter.set(0)
        rxCounter.set(0)
        infoCounter.set(0)
        errorCounter.set(0)
        synchronized(traceLock) {
            exportTraceLogs.clear()
            droppedTraceLines = 0
        }
    }

    fun traceSnapshot(): List<TraceLog> = synchronized(traceLock) { exportTraceLogs.toList() }

    fun traceCounters(): TraceCounters = TraceCounters(
        tx = txCounter.get(),
        rx = rxCounter.get(),
        info = infoCounter.get(),
        error = errorCounter.get()
    )

    fun droppedTraceLineCount(): Long = synchronized(traceLock) { droppedTraceLines }

    fun refreshProbeStatus() {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            _probeStatus.value = "No USB Optical Probe Detected"
        } else {
            _probeStatus.value = STATUS_USB_CABLE_WAITING
        }
    }

    suspend fun openPort(baudRate: Int = 9600, useModeE: Boolean = false): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                closePort()
                resetTraceDiagnostics()
                val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
                if (drivers.isEmpty()) throw IOException("No USB-Serial optical probe found.")

                val driver = drivers.first()
                val device = driver.device

                if (!usbManager.hasPermission(device)) {
                    val granted = requestUsbPermission(device)
                    if (!granted) throw SecurityException("USB permission denied by user.")
                }

                val connection = usbManager.openDevice(device)
                    ?: throw IOException("Failed to open USB device connection.")

                val openedPort = driver.ports.first()
                openedPort.open(connection)

                // CRITICAL: Optical probes draw power from DTR/RTS lines for IR TX/RX
                openedPort.dtr = true
                openedPort.rts = true
                port = openedPort
                _probeStatus.value = STATUS_USB_CABLE_WAITING
                log(TraceDirection.INFO, STATUS_USB_CABLE_WAITING)

                var openedBaud = baudRate
                if (useModeE) {
                    val negotiated = performModeEHandshake()
                    if (negotiated != null) {
                        openedBaud = negotiated
                    } else {
                        log(
                            TraceDirection.INFO,
                            "Mode E did not receive a banner; falling back to direct 9600 8N1 HDLC."
                        )
                        openedBaud = 9600
                        openedPort.setParameters(
                            9600,
                            8,
                            UsbSerialPort.STOPBITS_1,
                            UsbSerialPort.PARITY_NONE
                        )
                        openedPort.dtr = true
                        openedPort.rts = true
                        _probeStatus.value = STATUS_USB_CABLE_WAITING
                    }
                } else {
                    openedPort.setParameters(
                        baudRate,
                        8,
                        UsbSerialPort.STOPBITS_1,
                        UsbSerialPort.PARITY_NONE
                    )
                }

                // Purge any stale bytes in the UART FIFO buffer
                try {
                    openedPort.purgeHwBuffers(true, true)
                } catch (_: UnsupportedOperationException) { }

                log(TraceDirection.INFO, "Optical port opened at $openedBaud 8N1 (DTR/RTS ON)")
            }
        }

    /**
     * IEC 62056-21 Mode E sign-on (see [Iec62056ModeE]). Returns the HDLC baud
     * rate the port was switched to, or null when the meter did not answer
     * with a valid identification (the caller then falls back to direct HDLC).
     */
    fun performModeEHandshake(): Int? {
        val signOnQuery = Iec62056ModeE.SIGN_ON_REQUEST
        var lastPacketSent: ByteArray? = null
        var receivedBytes: ByteArray? = null

        try {
            log(TraceDirection.INFO, "Starting IEC 62056-21 Mode E sign-on (300 7E1)...")
            val p = port ?: return null
            // openPort() has already opened and stored this port.
            p.setParameters(300, 7, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_EVEN)
            p.dtr = true
            p.rts = true
            // Clear stale noise before sending the 300-baud request.
            try {
                p.purgeHwBuffers(true, true)
            } catch (_: Exception) {
                // Some driver wrappers do not support hardware purging.
            }
            Thread.sleep(300)

            p.write(signOnQuery, 1000)
            lastPacketSent = signOnQuery
            log(TraceDirection.TX, "/?! (300 baud sign-on request)")

            // At 300 baud an identification of ~20 characters takes ~0.7 s and
            // arrives over several USB reads: collect until CR LF or 3 s.
            val collected = ByteArrayOutputStream()
            val chunk = ByteArray(64)
            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline &&
                !Iec62056ModeE.isIdentificationComplete(collected.toByteArray())
            ) {
                val len = readBytes(chunk, 200)
                if (len > 0) collected.write(chunk, 0, len)
            }
            receivedBytes = collected.toByteArray().takeIf { it.isNotEmpty() }

            val identification = receivedBytes?.let(Iec62056ModeE::parseIdentification)
            if (identification == null) {
                val error = if (receivedBytes == null) {
                    "No physical meter response detected (Mode E sign-on timeout)."
                } else {
                    "Mode E: meter reply is not a valid IEC 62056-21 identification."
                }
                markNoMeterResponse(error)
                logConnectionDiagnosticReport(true, lastPacketSent, receivedBytes, error)
                return null
            }
            log(TraceDirection.RX, "Identification: ${identification.raw}")
            if (!identification.supportsModeE) {
                log(
                    TraceDirection.INFO,
                    "Identification has no \\2 Mode E marker; requesting HDLC anyway."
                )
            }

            val ack = Iec62056ModeE.modeEAcknowledgement(identification.baudChar)
            Thread.sleep(300)
            p.write(ack, 1000)
            lastPacketSent = ack
            log(
                TraceDirection.TX,
                "ACK 2${identification.baudChar}2 (HDLC at ${identification.baudRate} baud)"
            )

            Thread.sleep(Iec62056ModeE.ACK_DRAIN_MS)
            p.setParameters(
                identification.baudRate,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            log(
                TraceDirection.INFO,
                "Switched port to ${identification.baudRate} 8N1. Ready for HDLC."
            )
            return identification.baudRate
        } catch (e: Exception) {
            val error = "Mode E error: ${e.message ?: "communication error"}"
            markNoMeterResponse(error)
            logConnectionDiagnosticReport(true, lastPacketSent ?: signOnQuery, receivedBytes, error)
        }
        return null
    }

    fun testDirectHdlcConnection(): Boolean {
        val snrmFrame = byteArrayOf(
            0x7E, 0xA0.toByte(), 0x07, 0x03, 0x41, 0x93.toByte(), 0x5A, 0x64, 0x7E
        )

        try {
            val p = port ?: throw IOException("Serial port is not open.")
            p.setParameters(9600, 8, 1, UsbSerialPort.PARITY_NONE)

            // Power the optical probe and clear stale bytes before the direct request.
            p.dtr = true
            p.rts = true
            try {
                p.purgeHwBuffers(true, true)
            } catch (_: Exception) {
                // Ignore if the specific driver wrapper does not support purging.
            }
            Thread.sleep(200)

            write(snrmFrame, 1000)
            log(TraceDirection.TX, "Sent Direct HDLC SNRM (9600 8N1, Public Client 0x10)")

            // The USB probe can split one HDLC frame across several reads
            // (for example 7E A0 1E 41 / 03 73 ... / ... 53 3B 7E). Keep
            // accumulating until the UA frame is complete instead of
            // rejecting the first partial chunk.
            val response = readHdlcUaResponse { readBytesRaw(64, 250) }
            if (containsHdlcUaFrame(response)) {
                log(TraceDirection.RX, "Meter Responded Directly with HDLC UA: ${response.toHexString()}")
                markMeterLinkEstablished()
                return true
            }

            val error = if (response.isEmpty()) {
                "Direct HDLC timeout - no UA received."
            } else {
                "Direct HDLC response was not a valid UA frame: ${response.toHexString()}"
            }
            if (response.isNotEmpty()) {
                log(TraceDirection.RX, "Ignored non-UA response: ${response.toHexString()}")
            }
            markNoMeterResponse("No physical meter response detected (Direct HDLC UA missing).")
            logConnectionDiagnosticReport(false, snrmFrame, response.takeIf { it.isNotEmpty() }, error)
        } catch (e: Exception) {
            val error = "Direct communication error: ${e.message ?: "unknown error"}"
            log(TraceDirection.ERROR, error)
            logConnectionDiagnosticReport(false, snrmFrame, null, error)
        }
        return false
    }

    fun logConnectionDiagnosticReport(
        modeEUsed: Boolean,
        sentBytes: ByteArray?,
        receivedBytes: ByteArray?,
        errorMessage: String
    ) {
        val report = connectionDiagnostics.generateReport(
            modeEUsed = modeEUsed,
            sentBytes = sentBytes,
            receivedBytes = receivedBytes,
            errorMessage = errorMessage
        )

        log(TraceDirection.ERROR, "=== CONNECTION FAILURE DIAGNOSTIC ===")
        log(TraceDirection.ERROR, "Time: ${report.timestamp}")
        log(TraceDirection.ERROR, "Mode E Enabled: ${report.modeEChecked} (${report.baudRateAttempted} baud)")
        log(TraceDirection.ERROR, "Packet Sent (TX): ${report.packetSentHex}")
        log(TraceDirection.ERROR, "Response Got (RX): ${report.rawResponseHex}")
        log(TraceDirection.ERROR, "Reason: ${report.failureReason}")
        log(TraceDirection.ERROR, "Fix: ${report.suggestion}")
        log(TraceDirection.ERROR, "=======================================")
    }

    fun markNoMeterResponse(traceMessage: String = "No physical meter response detected.") {
        _probeStatus.value = STATUS_NO_METER_RESPONSE
        log(TraceDirection.ERROR, traceMessage)
    }

    fun markMeterLinkEstablished() {
        _probeStatus.value = STATUS_METER_LINK_ESTABLISHED
        log(TraceDirection.INFO, STATUS_METER_LINK_ESTABLISHED)
    }

    private fun readBytes(buffer: ByteArray, timeoutMs: Int): Int {
        val p = port ?: return 0
        if (buffer.isEmpty()) return 0

        val bytesRead = p.read(buffer, buffer.size, timeoutMs.coerceAtLeast(0))
        return bytesRead.coerceIn(0, buffer.size)
    }

    fun write(data: ByteArray, timeoutMs: Int = 2000) {
        val activePort = port ?: throw IOException("Serial port is not open.")
        log(TraceDirection.TX, data.toHexString())
        activePort.write(data, timeoutMs)
    }

    fun readChunk(buffer: ByteArray, timeoutMs: Int = 200): Int {
        val activePort = port ?: throw IOException("Serial port is not open.")
        return activePort.read(buffer, timeoutMs)
    }

    fun readBytesRaw(maxBytes: Int, timeoutMs: Int = 200): ByteArray {
        val safeSize = maxBytes.coerceAtLeast(0)
        if (safeSize == 0) return ByteArray(0)

        val buffer = ByteArray(safeSize)
        val bytesRead = readChunk(buffer, timeoutMs).coerceIn(0, safeSize)
        return buffer.copyOf(bytesRead)
    }

    fun purgeBuffers() {
        try {
            port?.purgeHwBuffers(true, true)
        } catch (_: Exception) {
            // Some USB serial driver wrappers do not support hardware purging.
        }
    }

    fun log(direction: TraceDirection, message: String) {
        when (direction) {
            TraceDirection.TX -> txCounter.incrementAndGet()
            TraceDirection.RX -> rxCounter.incrementAndGet()
            TraceDirection.INFO -> infoCounter.incrementAndGet()
            TraceDirection.ERROR -> errorCounter.incrementAndGet()
        }
        val trace = TraceLog(direction = direction, hexOrMessage = message)
        synchronized(traceLock) {
            if (exportTraceLogs.size >= 3_000) {
                exportTraceLogs.removeFirst()
                droppedTraceLines++
            }
            exportTraceLogs.addLast(trace)
        }
        _traceFlow.tryEmit(trace)
    }

    fun closePort(preserveStatus: Boolean = false) {
        try {
            port?.apply {
                dtr = false
                rts = false
                close()
            }
        } catch (_: Exception) {
        } finally {
            port = null
            if (!preserveStatus) refreshProbeStatus()
        }
    }

    private suspend fun requestUsbPermission(device: UsbDevice): Boolean =
        suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action == ACTION_USB_PERMISSION) {
                        context.unregisterReceiver(this)
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (cont.isActive) cont.resume(granted)
                    }
                }
            }
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                flags
            )
            usbManager.requestPermission(device, permissionIntent)

            cont.invokeOnCancellation {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
}

/**
 * Reassembles chunked USB reads until a complete HDLC UA frame arrives, the
 * response deadline expires, or [idleTimeoutMs] passes after the last byte.
 *
 * The first read can contain only a fragment such as `7E A0 1E 41`; it must
 * not be rejected before the remaining chunks are collected.
 */
internal fun readHdlcUaResponse(
    responseTimeoutMs: Long = 1500L,
    idleTimeoutMs: Long = 250L,
    now: () -> Long = System::currentTimeMillis,
    read: () -> ByteArray
): ByteArray {
    val startedAt = now()
    var lastByteAt: Long? = null
    val response = ByteArrayOutputStream()

    while (true) {
        val chunk = read()
        if (chunk.isNotEmpty()) {
            response.write(chunk, 0, chunk.size)
            lastByteAt = now()
            val accumulated = response.toByteArray()
            if (containsHdlcUaFrame(accumulated)) return accumulated
        }

        val current = now()
        if (lastByteAt != null && current - lastByteAt >= idleTimeoutMs) {
            return response.toByteArray()
        }
        if (current - startedAt >= responseTimeoutMs) {
            return response.toByteArray()
        }
    }
}

/**
 * Returns true when [response] contains a structurally valid HDLC frame whose
 * control field is Unnumbered Acknowledgment (UA, 0x73 with the poll/final bit,
 * or 0x63 without it). IR noise or a partial byte can no longer be mistaken
 * for an established meter link.
 */
internal fun containsHdlcUaFrame(response: ByteArray): Boolean {
    if (response.size < 9) return false
    var openingFlag = -1
    response.forEachIndexed { index, byte ->
        if (byte == HDLC_FLAG) {
            if (openingFlag >= 0 && index - openingFlag > 1) {
                if (isHdlcUaFrame(response, openingFlag + 1, index)) return true
            }
            openingFlag = index
        }
    }
    return false
}

private fun isHdlcUaFrame(bytes: ByteArray, from: Int, closingFlag: Int): Boolean {
    val size = closingFlag - from
    // format + length + dest address + src address + control + 2-byte FCS
    if (size < 7) return false

    val format = bytes[from].toInt() and 0xFF
    if ((format and 0xF0) != 0xA0) return false
    if ((bytes[from + 1].toInt() and 0xFF) != size) return false

    val afterDestination = skipHdlcAddress(bytes, from + 2, closingFlag - 2) ?: return false
    val afterSource = skipHdlcAddress(bytes, afterDestination, closingFlag - 2) ?: return false
    if (afterSource >= closingFlag - 2) return false

    val control = bytes[afterSource].toInt() and 0xFF
    if (control != 0x73 && control != 0x63) return false

    // The FCS covers every octet between the flags except the FCS itself, so it
    // must include the information field: a UA carrying an I-frame payload has
    // its checksum computed over that payload too.
    val expectedFcs = hdlcFcs16(bytes, from, closingFlag - 2 - from)
    val actualFcs = (bytes[closingFlag - 2].toInt() and 0xFF) or
        ((bytes[closingFlag - 1].toInt() and 0xFF) shl 8)
    return expectedFcs == actualFcs
}

/** Skips one HDLC address field; each address ends on a byte with bit 0 set. */
private fun skipHdlcAddress(bytes: ByteArray, start: Int, endExclusive: Int): Int? {
    var index = start
    var count = 0
    while (index < endExclusive) {
        val value = bytes[index].toInt() and 0xFF
        index++
        count++
        if ((value and 0x01) == 1) return index
        if (count >= 4) return null
    }
    return null
}

private const val HDLC_FLAG: Byte = 0x7E

fun ByteArray.toHexString(length: Int = this.size): String =
    take(length).joinToString(" ") { "%02X".format(it) }

/**
 * Builds a self-contained failure report from the bytes exchanged on the wire.
 * Kept with the transport because every value it captures describes the
 * physical optical link (baud rate, Mode E, raw frames).
 */
object ConnectionDiagnostics {

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
