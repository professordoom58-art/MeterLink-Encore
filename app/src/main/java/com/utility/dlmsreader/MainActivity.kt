package com.utility.dlmsreader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.utility.dlmsreader.dlms.ConnectionParams
import com.utility.dlmsreader.dlms.DlmsMeterEngine
import com.utility.dlmsreader.dlms.MeterReadSnapshot
import com.utility.dlmsreader.transport.OpticalUsbTransport
import com.utility.dlmsreader.transport.TraceDirection
import com.utility.dlmsreader.transport.TraceLog
import gurux.dlms.enums.Authentication
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var transport: OpticalUsbTransport
    private lateinit var engine: DlmsMeterEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        transport = OpticalUsbTransport(applicationContext)
        engine = DlmsMeterEngine(transport)
        transport.refreshProbeStatus()

        setContent {
            MaterialTheme {
                MeterReaderScreen(
                    transport = transport,
                    engine = engine,
                    onRefreshUsb = { transport.refreshProbeStatus() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        transport.refreshProbeStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        transport.closePort()
    }
}

@Composable
fun MeterReaderScreen(
    transport: OpticalUsbTransport,
    engine: DlmsMeterEngine,
    onRefreshUsb: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val probeStatus by transport.probeStatus.collectAsState()

    var useMeterReaderAuth by remember { mutableStateOf(true) }
    var llsPassword by remember { mutableStateOf("lnt1") }
    var useModeE by remember { mutableStateOf(false) }
    var isReading by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf<MeterReadSnapshot?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val traceLogs = remember { mutableStateListOf<TraceLog>() }

    LaunchedEffect(Unit) {
        transport.traceFlow.collect { log ->
            traceLogs.add(0, log)
            if (traceLogs.size > 200) traceLogs.removeLast()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "DLMS/COSEM Optical Reader (IS 15959 PoC)",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        // 1. USB Probe Status Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("USB Optical Probe", fontWeight = FontWeight.SemiBold)
                    Text(probeStatus, style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(onClick = onRefreshUsb) {
                    Text("Scan USB")
                }
            }
        }

        // 2. Association & Protocol Parameters
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Association Configuration", fontWeight = FontWeight.SemiBold)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = !useMeterReaderAuth,
                        onClick = { useMeterReaderAuth = false }
                    )
                    Text("Public (Client 16 - No Auth)")
                    Spacer(Modifier.width(12.dp))
                    RadioButton(
                        selected = useMeterReaderAuth,
                        onClick = { useMeterReaderAuth = true }
                    )
                    Text("Meter Reader (Client 32 - LLS)")
                }

                if (useMeterReaderAuth) {
                    OutlinedTextField(
                        value = llsPassword,
                        onValueChange = { llsPassword = it },
                        label = { Text("LLS Password (e.g. lnt1, 111111111111)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useModeE, onCheckedChange = { useModeE = it })
                    Text("Use IEC 62056-21 Mode E Handshake (300 7E1 -> 9600 8N1)")
                }

                Button(
                    onClick = {
                        isReading = true
                        errorMessage = null
                        traceLogs.clear()
                        scope.launch {
                            val params = ConnectionParams(
                                clientAddress = if (useMeterReaderAuth) 0x20 else 0x10,
                                authentication = if (useMeterReaderAuth) Authentication.LOW else Authentication.NONE,
                                password = llsPassword,
                                useModeE = useModeE
                            )
                            engine.readMeter(params)
                                .onSuccess { snapshot = it }
                                .onFailure { errorMessage = it.message ?: "Read failed" }
                            isReading = false
                        }
                    },
                    enabled = !isReading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isReading) "Reading Meter over Optical Port..." else "Connect & Read Meter")
                }

                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
                }
            }
        }

        // 3. Parsed Meter Results
        snapshot?.let { data ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Meter Reading Results", fontWeight = FontWeight.Bold)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    ResultRow("Logical Device Name (0.0.42.0.0.255)", data.logicalDeviceName)
                    ResultRow("Meter Serial (0.0.96.1.0.255)", data.serialNumber)
                    ResultRow("Manufacturer (0.0.96.1.1.255)", data.manufacturerName)
                    ResultRow("Firmware (1.0.0.2.0.255)", data.firmwareVersion)
                    ResultRow("Meter RTC (0.0.1.0.0.255)", data.meterClock)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    ResultRow("Active Energy Import (1.0.1.8.0.255)", data.activeEnergyKwh)
                    ResultRow("Phase Voltage (1.0.32.7.0.255)", data.instantaneousVoltage)
                    ResultRow("Phase Current (1.0.31.7.0.255)", data.instantaneousCurrent)
                    ResultRow("Power Factor (1.0.13.7.0.255)", data.powerFactor)
                }
            }
        }

        // 4. Live Hex Trace Console
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Live HDLC / DLMS Frame Trace", fontWeight = FontWeight.SemiBold)
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = traceLogs.reversed().joinToString("\n") {
                    "[${it.direction}] ${it.hexOrMessage}"
                }
                clipboard.setPrimaryClip(ClipData.newPlainText("DLMS Trace", text))
            }) {
                Text("Copy Trace")
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(Color(0xFF121212))
                .padding(8.dp)
        ) {
            val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
            LazyColumn {
                items(traceLogs) { item ->
                    val color = when (item.direction) {
                        TraceDirection.TX -> Color(0xFF64B5F6)
                        TraceDirection.RX -> Color(0xFF81C784)
                        TraceDirection.INFO -> Color(0xFFFFD54F)
                        TraceDirection.ERROR -> Color(0xFFE57373)
                    }
                    Text(
                        text = "${timeFormat.format(Date(item.timestampMs))} [${item.direction}] ${item.hexOrMessage}",
                        color = color,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}