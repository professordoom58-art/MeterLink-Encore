package com.utility.dlmsreader.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
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
        try {
            log(TraceDirection.INFO, "Starting L&T Mode E Handshake (300 7E1)...")

            port?.let { p ->
                p.setParameters(300, 7, 1, com.hoho.android.usbserial.driver.UsbSerialPort.PARITY_EVEN)
                p.dtr = true
                p.rts = true
                Thread.sleep(200)

                val signOnQuery = byteArrayOf(0x2F, 0x3F, 0x21, 0x0D, 0x0A)
                p.write(signOnQuery, 500)
                log(TraceDirection.TX, "/?! (300 baud sign-on sent)")

                val buffer = ByteArray(64)
                val len = try {
                    readBytes(buffer, 2500)
                } catch (e: Exception) {
                    markNoMeterResponse(
                        "No physical meter response detected (Mode E read timeout): ${e.message ?: "timeout"}"
                    )
                    return false
                }

                if (len > 0) {
                    val responseStr = String(buffer, 0, len, Charsets.US_ASCII).trim()
                    log(TraceDirection.RX, "Meter Response: $responseStr")

                    val ackFrame = byteArrayOf(0x06, 0x30, 0x35, 0x30, 0x0D, 0x0A)
                    Thread.sleep(200)
                    p.write(ackFrame, 500)
                    log(TraceDirection.TX, "ACK Baud-Rate Switch sent")

                    Thread.sleep(300)
                    p.setParameters(9600, 8, 1, com.hoho.android.usbserial.driver.UsbSerialPort.PARITY_NONE)
                    log(TraceDirection.INFO, "Switched port to 9600 8N1. Ready for HDLC.")
                    return true
                } else {
                    markNoMeterResponse("No physical meter response detected (Mode E sign-on timeout).")
                }
            }
        } catch (e: Exception) {
            markNoMeterResponse(
                "No physical meter response detected (Mode E handshake failed): ${e.message ?: "communication error"}"
            )
        }
        return false
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
            val permissionIntent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(device, permissionIntent)

            cont.invokeOnCancellation {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
}

fun ByteArray.toHexString(length: Int = this.size): String =
    take(length).joinToString(" ") { "%02X".format(it) }
