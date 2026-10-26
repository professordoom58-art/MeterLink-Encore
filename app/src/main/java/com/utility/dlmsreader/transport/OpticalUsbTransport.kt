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
import kotlinx.coroutines.delay
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
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var serialPort: UsbSerialPort? = null

    private val _probeStatus = MutableStateFlow("No Optical Probe Connected")
    val probeStatus: StateFlow<String> = _probeStatus

    private val _traceFlow = MutableSharedFlow<TraceLog>(extraBufferCapacity = 256)
    val traceFlow: SharedFlow<TraceLog> = _traceFlow

    fun refreshProbeStatus() {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            _probeStatus.value = "No USB Optical Probe Detected"
        } else {
            val dev = drivers.first().device
            val hasPerm = usbManager.hasPermission(dev)
            _probeStatus.value = "Detected: ${dev.productName ?: dev.deviceName} (Perm: $hasPerm)"
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

                val port = driver.ports.first()
                port.open(connection)

                // CRITICAL: Optical probes draw power from DTR/RTS lines for IR TX/RX
                port.dtr = true
                port.rts = true

                if (useModeE) {
                    performModeEHandshake(port)
                } else {
                    port.setParameters(
                        baudRate,
                        8,
                        UsbSerialPort.STOPBITS_1,
                        UsbSerialPort.PARITY_NONE
                    )
                }

                // Purge any stale bytes in the UART FIFO buffer
                try {
                    port.purgeHwBuffers(true, true)
                } catch (_: UnsupportedOperationException) { }

                serialPort = port
                _probeStatus.value = "Connected (${device.productName ?: "Optical Probe"} @ $baudRate 8N1)"
                log(TraceDirection.INFO, "Optical port opened at $baudRate 8N1 (DTR/RTS ON)")
            }
        }

    /**
     * IEC 62056-21 Mode E Handshake: Starts at 300 7E1, sends /?!\r\n, negotiates 9600 8N1 HDLC.
     */
    private suspend fun performModeEHandshake(port: UsbSerialPort) {
        log(TraceDirection.INFO, "Starting IEC 62056-21 Mode E Handshake at 300 7E1...")
        port.setParameters(300, 7, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_EVEN)
        delay(200)

        val signOn = "/?!\r\n".toByteArray(Charsets.US_ASCII)
        log(TraceDirection.TX, signOn.toHexString())
        port.write(signOn, 1000)

        val rxBuf = ByteArray(128)
        val len = port.read(rxBuf, 2500)
        if (len <= 0) throw IOException("No response to Mode E /?! sign-on.")
        log(TraceDirection.RX, rxBuf.copyOf(len).toHexString())

        // Send <ACK>052\r\n (0 = Normal, 5 = 9600 bps, 2 = Binary HDLC Mode E)
        val ackSwitch = byteArrayOf(0x06, 0x30, 0x35, 0x32, 0x0D, 0x0A)
        log(TraceDirection.TX, ackSwitch.toHexString())
        port.write(ackSwitch, 1000)

        // Wait for UART transmit register to drain before switching baud rate
        delay(300)
        port.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        log(TraceDirection.INFO, "Switched to 9600 8N1 for HDLC.")
    }

    fun write(data: ByteArray, timeoutMs: Int = 2000) {
        val port = serialPort ?: throw IOException("Serial port is not open.")
        log(TraceDirection.TX, data.toHexString())
        port.write(data, timeoutMs)
    }

    fun readChunk(buffer: ByteArray, timeoutMs: Int = 200): Int {
        val port = serialPort ?: throw IOException("Serial port is not open.")
        return port.read(buffer, timeoutMs)
    }

    fun log(direction: TraceDirection, message: String) {
        _traceFlow.tryEmit(TraceLog(direction = direction, hexOrMessage = message))
    }

    fun closePort() {
        try {
            serialPort?.apply {
                dtr = false
                rts = false
                close()
            }
        } catch (_: Exception) {
        } finally {
            serialPort = null
            refreshProbeStatus()
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