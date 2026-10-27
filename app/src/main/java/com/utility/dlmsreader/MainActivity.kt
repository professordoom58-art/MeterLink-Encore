package com.utility.dlmsreader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.MenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.utility.dlmsreader.dlms.ConnectionParams
import com.utility.dlmsreader.dlms.DlmsMeterEngine
import com.utility.dlmsreader.dlms.MeterDataModule
import com.utility.dlmsreader.dlms.MeterDataReport
import com.utility.dlmsreader.dlms.MeterReadSnapshot
import com.utility.dlmsreader.dlms.MeterLinkPdfExporter
import com.utility.dlmsreader.dlms.ProfileSection
import com.utility.dlmsreader.dlms.VendorPasswordCatalog
import com.utility.dlmsreader.transport.OpticalUsbTransport
import com.utility.dlmsreader.transport.TraceDirection
import com.utility.dlmsreader.transport.TraceLog
import gurux.dlms.enums.Authentication
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var transport: OpticalUsbTransport
    private lateinit var engine: DlmsMeterEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // Fixes camera cutout overlapping
        transport = OpticalUsbTransport(applicationContext)
        engine = DlmsMeterEngine(transport)
        transport.refreshProbeStatus()

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF1565C0),
                    secondary = Color(0xFF00ACC1),
                    background = Color(0xFFF4F6F9),
                    surface = Color(0xFFFFFFFF),
                    onSurface = Color(0xFF1E293B)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MeterReaderScreen(
                        transport = transport,
                        engine = engine,
                        onRefreshUsb = { transport.refreshProbeStatus() }
                    )
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeterReaderScreen(
    transport: OpticalUsbTransport,
    engine: DlmsMeterEngine,
    onRefreshUsb: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val probeStatus by transport.probeStatus.collectAsState()
    val probeStatusColor = when (probeStatus) {
        OpticalUsbTransport.STATUS_METER_LINK_ESTABLISHED -> Color(0xFF15803D)
        OpticalUsbTransport.STATUS_NO_METER_RESPONSE -> Color(0xFFB91C1C)
        OpticalUsbTransport.STATUS_USB_CABLE_WAITING -> Color(0xFFB45309)
        else -> Color.Gray
    }

    var useMeterReaderAuth by remember { mutableStateOf(true) }
    var selectedVendor by remember { mutableStateOf(VendorPasswordCatalog.VENDORS.first()) }
    var llsPassword by remember { mutableStateOf(selectedVendor.defaultPrimaryPassword) }
    var vendorMenuExpanded by remember { mutableStateOf(false) }
    var useModeE by remember { mutableStateOf(false) }
    var isReading by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf<MeterReadSnapshot?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var meterReport by remember { mutableStateOf(MeterDataReport()) }
    var progressText by remember { mutableStateOf<String?>(null) }
    var reportReady by remember { mutableStateOf(false) }
    var reportStatusText by remember { mutableStateOf<String?>(null) }
    var autoTryAlternatePasswords by remember { mutableStateOf(false) }
    var lastHexLogFile by remember { mutableStateOf<File?>(null) }
    val traceLogs = remember { mutableStateListOf<TraceLog>() }

    fun runModule(module: MeterDataModule) {
        isReading = true
        errorMessage = null
        progressText = "Starting ${module.name.lowercase(Locale.US)}..."
        reportStatusText = null
        if (module == MeterDataModule.ALL) {
            meterReport = MeterDataReport()
            reportReady = false
        }
        traceLogs.clear()
        scope.launch {
            val params = ConnectionParams(
                clientAddress = if (useMeterReaderAuth) 0x20 else 0x10,
                authentication = if (useMeterReaderAuth) Authentication.LOW else Authentication.NONE,
                password = llsPassword,
                passwordCandidates = selectedVendor.defaultPasswords,
                autoTryAlternatePasswords = autoTryAlternatePasswords,
                vendorName = selectedVendor.displayName,
                useModeE = useModeE
            )
            engine.readMeterModule(params, module) { progressText = it }
                .onSuccess {
                    val completedReport = if (it.hasData) {
                        it
                    } else {
                        it.copy(errors = it.errors + "${module.name.replace('_', ' ')} completed successfully but returned no rows.")
                    }
                    val mergedReport = meterReport.merge(completedReport)
                    meterReport = mergedReport
                    if (module == MeterDataModule.ALL) {
                        runCatching {
                            val bundle = MeterLinkPdfExporter(context).exportBundle(mergedReport, traceLogs.toList())
                            lastHexLogFile = bundle.hexLog
                            reportStatusText = "✓ Read All saved ${bundle.pdf.name} and ${bundle.hexLog.name}."
                        }.onFailure {
                            errorMessage = it.message ?: "Automatic report save failed"
                        }
                    }
                    reportReady = true
                    if (module != MeterDataModule.ALL) {
                        reportStatusText = "✓ ${module.name.replace('_', ' ')} extraction complete. Data is ready for PDF export."
                    }
                }
                .onFailure { errorMessage = it.message ?: "${module.name} read failed" }
            isReading = false
            progressText = null
        }
    }

    fun exportReport() {
        runCatching {
            val exporter = MeterLinkPdfExporter(context)
            val bundle = exporter.exportBundle(meterReport, traceLogs.toList())
            lastHexLogFile = bundle.hexLog
            exporter.share(bundle.pdf)
            reportStatusText = "✓ PDF saved as ${bundle.pdf.name}; Hex Log saved as ${bundle.hexLog.name}."
        }.onFailure { errorMessage = it.message ?: "PDF export failed" }
    }

    fun exportHexLog() {
        runCatching {
            val exporter = MeterLinkPdfExporter(context)
            val file = exporter.exportHexLog(meterReport, traceLogs.toList())
            lastHexLogFile = file
            exporter.shareText(file)
            reportStatusText = "✓ Hex log saved as ${file.name} and ready to share."
        }.onFailure { errorMessage = it.message ?: "Hex log export failed" }
    }

    LaunchedEffect(Unit) {
        transport.traceFlow.collect { log ->
            traceLogs.add(0, log)
            if (traceLogs.size > 5000) traceLogs.removeLast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "MeterLink DLMS Reader",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = "IS 15959 / IEC 62056 Field Tool",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // 1. USB Probe Status Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Optical Probe Status", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.Gray)
                        Spacer(Modifier.height(2.dp))
                        Surface(
                            color = probeStatusColor.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                probeStatus,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = probeStatusColor
                            )
                        }
                    }
                    FilledTonalButton(onClick = onRefreshUsb) {
                        Text("Scan USB")
                    }
                }
            }

            // 2. Association Configuration Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Session Configuration", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    // Client Selection Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { useMeterReaderAuth = false },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (!useMeterReaderAuth) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent
                            )
                        ) {
                            Text("Public (16)", fontSize = 12.sp, maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { useMeterReaderAuth = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (useMeterReaderAuth) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent
                            )
                        ) {
                            Text("Meter Reader (32)", fontSize = 12.sp, maxLines = 1)
                        }
                    }

                    if (useMeterReaderAuth) {
                        ExposedDropdownMenuBox(
                            expanded = vendorMenuExpanded,
                            onExpandedChange = { vendorMenuExpanded = !vendorMenuExpanded }
                        ) {
                            OutlinedTextField(
                                value = selectedVendor.displayName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Meter Manufacturer / Vendor") },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = vendorMenuExpanded)
                                },
                                modifier = Modifier
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                            ExposedDropdownMenu(
                                expanded = vendorMenuExpanded,
                                onDismissRequest = { vendorMenuExpanded = false }
                            ) {
                                VendorPasswordCatalog.VENDORS.forEach { vendor ->
                                    DropdownMenuItem(
                                        text = { Text(vendor.displayName) },
                                        onClick = {
                                            selectedVendor = vendor
                                            llsPassword = vendor.defaultPrimaryPassword
                                            vendorMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        if (selectedVendor.defaultPasswords.isEmpty()) {
                            OutlinedTextField(
                                value = llsPassword,
                                onValueChange = { llsPassword = it },
                                label = { Text("Custom LLS Password") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                        } else {
                            Text(
                                text = "Primary LLS password is used exclusively unless alternate-password retries are enabled.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = autoTryAlternatePasswords,
                                onCheckedChange = { autoTryAlternatePasswords = it }
                            )
                            Text("Auto-try alternate vendor passwords", fontSize = 12.sp)
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(checked = useModeE, onCheckedChange = { useModeE = it })
                        Text("IEC 62056-21 Mode E Handshake (300 7E1)", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            isReading = true
                            errorMessage = null
                            snapshot = null
                            traceLogs.clear()
                            scope.launch {
                                val openResult = transport.openPort(baudRate = 9600, useModeE = false)
                                if (openResult.isSuccess) {
                                    val directSuccess = transport.testDirectHdlcConnection()
                                    if (!directSuccess) {
                                        errorMessage = "Direct 9600 HDLC test failed. See the trace for raw RX bytes."
                                    }
                                    transport.closePort(preserveStatus = true)
                                } else {
                                    errorMessage = openResult.exceptionOrNull()?.message ?: "Unable to open direct HDLC port."
                                }
                                isReading = false
                            }
                        },
                        enabled = !isReading,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Test Direct 9600 HDLC")
                    }

                    Button(
                        onClick = {
                            isReading = true
                            errorMessage = null
                            snapshot = null
                            traceLogs.clear()
                            scope.launch {
                                val params = ConnectionParams(
                                    clientAddress = if (useMeterReaderAuth) 0x20 else 0x10,
                                    authentication = if (useMeterReaderAuth) Authentication.LOW else Authentication.NONE,
                                    password = llsPassword,
                                    passwordCandidates = selectedVendor.defaultPasswords,
                                    autoTryAlternatePasswords = autoTryAlternatePasswords,
                                    vendorName = selectedVendor.displayName,
                                    useModeE = useModeE
                                )
                                engine.readMeter(params)
                                    .onSuccess { snapshot = it }
                                    .onFailure { errorMessage = it.message ?: "Read failed" }
                                isReading = false
                            }
                        },
                        enabled = !isReading,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(if (isReading) "Communicating with Meter..." else "Connect & Read Meter")
                    }

                    errorMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("IS 15959 Data Extraction", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    progressText?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp) }
                    reportStatusText?.let {
                        Text(it, color = Color(0xFF15803D), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }
                    Button(
                        onClick = { runModule(MeterDataModule.ALL) },
                        enabled = !isReading,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Read All (Full Meter Dump)")
                    }
                    ExtractionButtonRow(
                        first = "Read Identity" to MeterDataModule.IDENTITY,
                        second = "Read Energy & MD" to MeterDataModule.ENERGY,
                        enabled = !isReading,
                        onRead = ::runModule
                    )
                    ExtractionButtonRow(
                        first = "Read Instantaneous" to MeterDataModule.INSTANTANEOUS,
                        second = "Read Billing History" to MeterDataModule.BILLING,
                        enabled = !isReading,
                        onRead = ::runModule
                    )
                    ExtractionButtonRow(
                        first = "Read Block Load" to MeterDataModule.BLOCK_LOAD,
                        second = "Read Daily Load" to MeterDataModule.DAILY_LOAD,
                        enabled = !isReading,
                        onRead = ::runModule
                    )
                    OutlinedButton(
                        onClick = { runModule(MeterDataModule.TAMPER) },
                        enabled = !isReading,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Read Tamper Logs")
                    }
                    if (reportReady) {
                        Surface(
                            color = Color(0xFFE8F5E9),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    "Meter data ready",
                                    color = Color(0xFF166534),
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Export or share the extracted report as a PDF.",
                                    color = Color(0xFF166534),
                                    fontSize = 12.sp
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = ::exportReport,
                                        enabled = !isReading && meterReport.hasData,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Export PDF Report", fontSize = 11.sp)
                                    }
                                    OutlinedButton(
                                        onClick = ::exportHexLog,
                                        enabled = !isReading,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Export Hex Log (.txt)", fontSize = 11.sp)
                                    }
                                }
                                lastHexLogFile?.let {
                                    Text("Hex log ready: ${it.name}", fontSize = 10.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }

            if (meterReport.hasData) {
                MeterDataReportView(meterReport)
            }

            // 3. Parsed Meter Results Card
            snapshot?.let { data ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Meter Reading Results", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        HorizontalDivider()
                        ResultRow("Serial Number", data.serialNumber)
                        ResultRow("Logical Device Name", data.logicalDeviceName)
                        ResultRow("Manufacturer", data.manufacturerName)
                        ResultRow("Firmware Version", data.firmwareVersion)
                        ResultRow("Meter RTC Time", data.meterClock)
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        ResultRow("Cumulative Energy", data.activeEnergyKwh)
                        ResultRow("Phase Voltage", data.instantaneousVoltage)
                        ResultRow("Phase Current", data.instantaneousCurrent)
                        ResultRow("Power Factor", data.powerFactor)
                    }
                }
            }

            // 4. Live Hex Trace Console
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Live Protocol Trace", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = traceLogs.reversed().joinToString("\n") { "[${it.direction}] ${it.hexOrMessage}" }
                        clipboard.setPrimaryClip(ClipData.newPlainText("DLMS Trace", text))
                    }) {
                        Text("Copy", fontSize = 12.sp)
                    }
                    TextButton(onClick = ::exportHexLog) {
                        Text("Export Hex Log", fontSize = 12.sp)
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0F172A))
                    .padding(10.dp)
            ) {
                val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
                if (traceLogs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Awaiting optical connection...", color = Color.Gray, fontSize = 11.sp)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(traceLogs) { item ->
                            val color = when (item.direction) {
                                TraceDirection.TX -> Color(0xFF60A5FA)
                                TraceDirection.RX -> Color(0xFF4ADE80)
                                TraceDirection.INFO -> Color(0xFFFACC15)
                                TraceDirection.ERROR -> Color(0xFFF87171)
                            }
                            Text(
                                text = "${timeFormat.format(Date(item.timestampMs))} [${item.direction}] ${item.hexOrMessage}",
                                color = color,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ExtractionButtonRow(
    first: Pair<String, MeterDataModule>,
    second: Pair<String, MeterDataModule>,
    enabled: Boolean,
    onRead: (MeterDataModule) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = { onRead(first.second) },
            enabled = enabled,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(first.first, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        OutlinedButton(
            onClick = { onRead(second.second) },
            enabled = enabled,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(second.first, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MeterDataReportView(report: MeterDataReport) {
    report.identity?.let { identity ->
        ReportCard("Meter Identity & Nameplate") {
            ReportTable(
                headers = listOf("Parameter", "OBIS", "Value", "Unit"),
                rows = identity.rows.map { listOf(it.parameterName, it.obisCode, it.formattedValue, it.unit) }
            )
            identity.nameplate?.let {
                Spacer(Modifier.height(8.dp))
                Text("Nameplate Profile", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                ProfileTable(it)
            }
        }
    }
    if (report.cumulativeEnergies.isNotEmpty()) {
        ReportCard("Cumulative Energy & Maximum Demand") {
            ReportTable(
                headers = listOf("Parameter", "OBIS", "Reading", "Unit", "Capture Time"),
                rows = report.cumulativeEnergies.map {
                    listOf(it.parameterName, it.obisCode, it.reading, it.unit, it.captureTimestamp)
                }
            )
        }
    }
    report.instantaneous?.let { ReportCard(it.title) { ProfileTable(it) } }
    report.billing?.let { ReportCard(it.title) { ProfileTable(it) } }
    report.dailyLoad?.let { ReportCard(it.title) { ProfileTable(it) } }
    report.blockLoad?.let { ReportCard(it.title) { ProfileTable(it) } }
    if (report.tamperEvents.isNotEmpty()) {
        ReportCard("Tamper & Event Logs") {
            ReportTable(
                headers = listOf("Compartment", "Date/Time", "Code", "Description", "V", "I", "PF", "kWh"),
                rows = report.tamperEvents.map {
                    listOf(it.compartment, it.dateTime, it.eventCode, it.eventDescription, it.voltage, it.current, it.powerFactor, it.energy)
                }
            )
        }
    }
    if (report.errors.isNotEmpty()) {
        ReportCard("Read Notes") {
            report.errors.forEach { Text(it, color = Color(0xFFB45309), fontSize = 11.sp) }
        }
    }
}

@Composable
private fun ProfileTable(section: ProfileSection) {
    if (section.columns.isEmpty()) {
        Text("No rows returned", color = Color.Gray, fontSize = 11.sp)
        return
    }
    ReportTable(section.columns.map { it.parameterName }, section.rows)
}

@Composable
private fun ReportCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            content()
        }
    }
}

@Composable
private fun ReportTable(headers: List<String>, rows: List<List<String>>) {
    if (headers.isEmpty()) return
    val tableWidth = (headers.size * 125).dp
    Column(Modifier.horizontalScroll(rememberScrollState()).width(tableWidth)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E3A8A))
                .padding(vertical = 5.dp)
        ) {
            headers.forEach { header ->
                Text(
                    header,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    modifier = Modifier.width(125.dp).padding(horizontal = 4.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        rows.forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (rowIndex % 2 == 0) Color(0xFFF8FAFC) else Color.White)
                    .padding(vertical = 4.dp)
            ) {
                headers.indices.forEach { index ->
                    Text(
                        row.getOrNull(index) ?: "N/A",
                        fontSize = 10.sp,
                        color = Color(0xFF1E293B),
                        modifier = Modifier.width(125.dp).padding(horizontal = 4.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
