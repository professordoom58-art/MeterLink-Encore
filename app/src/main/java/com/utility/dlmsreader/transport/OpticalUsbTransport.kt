package com.utility.dlmsreader.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.utility.dlmsreader.dlms.ConnectionDiagnostics
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume

enum class TraceDirection { TX, RX, INFO, ERROR }

data class TraceLog(
    val timestampMs: Long = System.currentTimeMillis(),
    val direction: TraceDirection,
    val hexOrMessage: String
)

class OpticalUsbTransport(private val context: Context) {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.utility.dlmsreader.USB_PERMISSION"
        const val STATUS_USB_CABLE_WAITING = "USB Cable Detected (Waiting for Meter...)"
        const val STATUS_NO_METER_RESPONSE = "❌ Cable Connected, but No Meter Response (Check alignment / push button)"
        const val STATUS_METER_LINK_ESTABLISHED = "✅ Meter Link Established Successfully!"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var port: UsbSerialPort? = null

    private val _probeStatus = MutableStateFlow("No Optical Probe Connected")
    val probeStatus: StateFlow<String> = _probeStatus

    private val _traceFlow = MutableSharedFlow<TraceLog>(extraBufferCapacity = 256)
    val traceFlow: SharedFlow<TraceLog> = _traceFlow
    private val connectionDiagnostics = ConnectionDiagnostics()

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

                if (useModeE) {
                    if (!performModeEHandshake()) {
                        closePort(preserveStatus = true)
                        throw IOException("L&T Mode E handshake failed.")
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

                log(TraceDirection.INFO, "Optical port opened at $baudRate 8N1 (DTR/RTS ON)")
            }
        }

    fun performModeEHandshake(): Boolean {
        val signOnQuery = byteArrayOf(0x2F, 0x3F, 0x21, 0x0D, 0x0A)
        var lastPacketSent: ByteArray? = null
        var receivedBytes: ByteArray? = null

        try {
            log(TraceDirection.INFO, "Starting L&T Mode E Handshake (300 7E1)...")

            port?.let { p ->
                // openPort() has already opened and stored this port.
                p.setParameters(300, 7, 1, com.hoho.android.usbserial.driver.UsbSerialPort.PARITY_EVEN)
                p.dtr = true
                p.rts = true

                // CRITICAL: Clear stale noise before sending the 300-baud sign-on.
                try {
                    p.purgeHwBuffers(true, true)
                } catch (_: Exception) {
                    // Ignore if the specific driver wrapper does not support purging.
                }

                Thread.sleep(300)

                p.write(signOnQuery, 1000)
                lastPacketSent = signOnQuery
                log(TraceDirection.TX, "/?! (300 baud sign-on sent)")

                val buffer = ByteArray(128)
                val len = try {
                    readBytes(buffer, 3000)
                } catch (e: Exception) {
                    val error = "No physical meter response detected (Mode E read timeout): ${e.message ?: "timeout"}"
                    markNoMeterResponse(error)
                    logConnectionDiagnosticReport(true, lastPacketSent ?: signOnQuery, null, error)
                    return false
                }

                if (len > 0) {
                    receivedBytes = buffer.copyOf(len)
                    val responseStr = String(buffer, 0, len, Charsets.US_ASCII).trim()
                    log(TraceDirection.RX, "Meter Response: $responseStr")

                    val ackFrame = byteArrayOf(0x06, 0x30, 0x35, 0x30, 0x0D, 0x0A)
                    Thread.sleep(300)
                    p.write(ackFrame, 1000)
                    lastPacketSent = ackFrame
                    log(TraceDirection.TX, "ACK Baud-Rate Switch sent")

                    Thread.sleep(400)
                    p.setParameters(9600, 8, 1, com.hoho.android.usbserial.driver.UsbSerialPort.PARITY_NONE)
                    log(TraceDirection.INFO, "Switched port to 9600 8N1. Ready for HDLC.")
                    return true
                } else {
                    val error = "No physical meter response detected (Mode E sign-on timeout)."
                    markNoMeterResponse(error)
                    logConnectionDiagnosticReport(true, lastPacketSent, null, error)
                }
            }
        } catch (e: Exception) {
            val error = "Mode E error: ${e.message ?: "communication error"}"
            markNoMeterResponse(error)
            logConnectionDiagnosticReport(true, lastPacketSent ?: signOnQuery, receivedBytes, error)
        }
        return false
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

            val response = readBytesRaw(64, 2000)
            if (response.isNotEmpty()) {
                log(TraceDirection.RX, "Meter Responded Directly: ${response.toHexString()}")
                markMeterLinkEstablished()
                return true
            }

            val error = "Direct HDLC timeout - no UA received."
            markNoMeterResponse("No physical meter response detected (Direct HDLC timeout).")
            logConnectionDiagnosticReport(false, snrmFrame, null, error)
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

    fun log(direction: TraceDirection, message: String) {
        _traceFlow.tryEmit(TraceLog(direction = direction, hexOrMessage = message))
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

fun ByteArray.toHexString(length: Int = this.size): String =
    take(length).joinToString(" ") { "%02X".format(it) }
