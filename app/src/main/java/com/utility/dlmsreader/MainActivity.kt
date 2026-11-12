package com.utility.dlmsreader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.utility.dlmsreader.dlms.ConnectionParams
import com.utility.dlmsreader.dlms.DlmsMeterEngine
import com.utility.dlmsreader.dlms.LoadSurveySelection
import com.utility.dlmsreader.dlms.MeterDataModule
import com.utility.dlmsreader.dlms.MeterDataReport
import com.utility.dlmsreader.dlms.MeterReadSnapshot
import com.utility.dlmsreader.dlms.MeterLinkExcelExporter
import com.utility.dlmsreader.dlms.MeterLinkPdfExporter
import com.utility.dlmsreader.dlms.VendorPasswordCatalog
import com.utility.dlmsreader.dlms.applyLoadSurveySelectionToReport
import com.utility.dlmsreader.storage.MeterReadingDatabase
import com.utility.dlmsreader.storage.SavedReadingSummary
import com.utility.dlmsreader.storage.cumulativeKvahReading
import com.utility.dlmsreader.storage.cumulativeKwhReading
import com.utility.dlmsreader.storage.identityValue
import com.utility.dlmsreader.transport.OpticalUsbTransport
import com.utility.dlmsreader.transport.TraceDirection
import com.utility.dlmsreader.transport.TraceLog
import gurux.dlms.enums.Authentication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var transport: OpticalUsbTransport
    private lateinit var engine: DlmsMeterEngine
    private lateinit var readingDatabase: MeterReadingDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // Fixes camera cutout overlapping
        transport = OpticalUsbTransport(applicationContext)
        engine = DlmsMeterEngine(transport)
        readingDatabase = MeterReadingDatabase(applicationContext)
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
                        readingDatabase = readingDatabase,
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
        readingDatabase.close()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeterReaderScreen(
    transport: OpticalUsbTransport,
    engine: DlmsMeterEngine,
    readingDatabase: MeterReadingDatabase,
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
    var reportStatusText by remember { mutableStateOf<String?>(null) }
    var autoTryAlternatePasswords by remember { mutableStateOf(false) }
    var loadSurveySelection by remember { mutableStateOf(LoadSurveySelection.RECENT) }
    var lastHexLogFile by remember { mutableStateOf<File?>(null) }
    var savedReadings by remember { mutableStateOf<List<SavedReadingSummary>>(emptyList()) }
    var selectedSavedReading by remember { mutableStateOf<SavedReadingSummary?>(null) }
    val traceLogs = remember { mutableStateListOf<TraceLog>() }

    fun readTypeLabel(module: MeterDataModule): String = when (module) {
        MeterDataModule.ALL -> "Full Read (All)"
        MeterDataModule.BLOCK_LOAD -> "Block Load (${loadSurveySelection.label})"
        MeterDataModule.DAILY_LOAD -> "Daily Load (${loadSurveySelection.label})"
        MeterDataModule.TAMPER -> "Tamper Logs"
        else -> module.name.lowercase(Locale.US)
            .split('_')
            .joinToString(" ") { word ->
                word.replaceFirstChar { it.uppercase(Locale.US) }
            }
    }

    fun runModule(module: MeterDataModule) {
        isReading = true
        errorMessage = null
        progressText = "Starting ${module.name.lowercase(Locale.US)}..."
        reportStatusText = null
        val readType = readTypeLabel(module)
        if (module == MeterDataModule.ALL) {
            meterReport = MeterDataReport()
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
                useModeE = useModeE,
                loadSurveySelection = loadSurveySelection
            )
            engine.readMeterModule(params, module) { progressText = it }
                .onSuccess {
                    val completedReport = if (it.hasData) {
                        it
                    } else {
                        it.copy(errors = it.errors + "${module.name.replace('_', ' ')} completed successfully but returned no rows.")
                    }
                    val mergedReport = if (sameMeterSerial(meterReport, completedReport)) {
                        meterReport.merge(completedReport)
                    } else {
                        // A different meter was connected mid-session: keep the
                        // new read only, never blend two meters' data.
                        completedReport
                    }
                    meterReport = mergedReport
                    if (module == MeterDataModule.ALL) {
                        // PDF rendering and hex-log writing are file work: keep
                        // them off the UI thread even on the automatic save path.
                        val exportResult = withContext(Dispatchers.IO) {
                            runCatching {
                                val bundle = MeterLinkPdfExporter(context).exportBundle(
                                    mergedReport,
                                    transport.traceSnapshot(),
                                    transport.traceCounters(),
                                    transport.droppedTraceLineCount()
                                )
                                bundle
                            }
                        }
                        exportResult
                            .onSuccess { bundle ->
                                lastHexLogFile = bundle.hexLog
                                reportStatusText =
                                    "✓ Read All saved ${bundle.pdf.name} and ${bundle.hexLog.name}."
                            }
                            .onFailure {
                                errorMessage = it.message ?: "Automatic report save failed"
                            }
                    }
                    // Persist every successful read locally. Compression and
                    // SQLite work stay on IO so a 3,600-row buffer never blocks
                    // the UI thread.
                    val savedResult = runCatching {
                        withContext(Dispatchers.IO) {
                            readingDatabase.insertReport(mergedReport, readType)
                            readingDatabase.listSummaries()
                        }
                    }
                    savedResult
                        .onSuccess { savedReadings = it }
                        .onFailure {
                            errorMessage = "Read succeeded, but local save failed: " +
                                (it.message ?: "unknown error")
                        }
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
        isReading = true
        errorMessage = null
        // Capture only the Compose state on the UI thread; trace snapshot and
        // all PDF/hex file work stay on IO.
        val report = meterReport
        scope.launch {
            val exporter = MeterLinkPdfExporter(context)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    exporter.exportBundle(
                        report,
                        transport.traceSnapshot(),
                        transport.traceCounters(),
                        transport.droppedTraceLineCount()
                    )
                }
            }
            result
                .onSuccess { bundle ->
                    lastHexLogFile = bundle.hexLog
                    reportStatusText =
                        "✓ PDF saved as ${bundle.pdf.name}; Hex Log saved as ${bundle.hexLog.name}."
                    runCatching { exporter.share(bundle.pdf) }
                        .onFailure { errorMessage = it.message ?: "PDF share failed" }
                }
                .onFailure { errorMessage = it.message ?: "PDF export failed" }
            isReading = false
        }
    }

    fun exportExcel() {
        isReading = true
        errorMessage = null
        val report = meterReport
        scope.launch {
            val exporter = MeterLinkExcelExporter(context)
            // Rendering and ZIP writing are file work: keep them off the UI thread.
            val result = withContext(Dispatchers.IO) {
                runCatching { exporter.export(report) }
            }
            result
                .onSuccess { file ->
                    reportStatusText = "✓ Excel workbook saved as ${file.name}."
                    runCatching { exporter.share(file) }
                        .onFailure { errorMessage = it.message ?: "Excel share failed" }
                }
                .onFailure { errorMessage = it.message ?: "Excel export failed" }
            isReading = false
        }
    }

    fun exportHexLog() {
        isReading = true
        errorMessage = null
        val report = meterReport
        scope.launch {
            val exporter = MeterLinkPdfExporter(context)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    exporter.exportHexLog(
                        report,
                        transport.traceSnapshot(),
                        transport.traceCounters(),
                        transport.droppedTraceLineCount()
                    )
                }
            }
            result
                .onSuccess { file ->
                    lastHexLogFile = file
                    reportStatusText = "✓ Hex log saved as ${file.name} and ready to share."
                    runCatching { exporter.shareText(file) }
                        .onFailure { errorMessage = it.message ?: "Hex log share failed" }
                }
                .onFailure { errorMessage = it.message ?: "Hex log export failed" }
            isReading = false
        }
    }

    fun exportSavedReading(
        reading: SavedReadingSummary,
        selection: LoadSurveySelection,
        excel: Boolean
    ) {
        isReading = true
        errorMessage = null
        scope.launch {
            // Load, decompress, re-filter and render entirely on IO. Only the
            // Android share intent returns to the main thread.
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val report = readingDatabase.loadReport(reading.id)
                        ?: error("Saved reading #${reading.id} no longer exists.")
                    val filtered = applyLoadSurveySelectionToReport(report, selection)
                    if (excel) {
                        MeterLinkExcelExporter(context).export(filtered)
                    } else {
                        MeterLinkPdfExporter(context).export(filtered)
                    }
                }
            }
            result
                .onSuccess { file ->
                    reportStatusText = "✓ Saved reading exported as ${file.name}."
                    selectedSavedReading = null
                    runCatching {
                        if (excel) {
                            MeterLinkExcelExporter(context).share(file)
                        } else {
                            MeterLinkPdfExporter(context).share(file)
                        }
                    }.onFailure { errorMessage = it.message ?: "Share failed" }
                }
                .onFailure { errorMessage = it.message ?: "Saved reading export failed" }
            isReading = false
        }
    }

    fun deleteSavedReading(reading: SavedReadingSummary) {
        isReading = true
        errorMessage = null
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    readingDatabase.deleteReport(reading.id)
                    readingDatabase.listSummaries()
                }
            }
            result
                .onSuccess {
                    savedReadings = it
                    selectedSavedReading = null
                    reportStatusText = "✓ Saved reading deleted."
                }
                .onFailure { errorMessage = it.message ?: "Delete failed" }
            isReading = false
        }
    }

    LaunchedEffect(Unit) {
        savedReadings = readingDatabase.listSummaries()
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
                        first = "Instantaneous" to MeterDataModule.INSTANTANEOUS,
                        second = "Billing History" to MeterDataModule.BILLING,
                        enabled = !isReading,
                        onRead = ::runModule
                    )
                    ExtractionButtonRow(
                        first = "Read Block Load" to MeterDataModule.BLOCK_LOAD,
                        second = "Read Daily Load" to MeterDataModule.DAILY_LOAD,
                        enabled = !isReading,
                        onRead = ::runModule
                    )
                    LoadSurveyRangeChips(
                        selected = loadSurveySelection,
                        enabled = !isReading,
                        onSelect = { selection ->
                            loadSurveySelection = selection
                            // If a full buffer is already cached, re-filter the
                            // in-memory rows immediately instead of forcing a
                            // multi-minute optical re-read.
                            if (!isReading) {
                                meterReport = applyLoadSurveySelectionToReport(
                                    meterReport,
                                    selection
                                )
                            }
                        }
                    )
                    OutlinedButton(
                        onClick = { runModule(MeterDataModule.TAMPER) },
                        enabled = !isReading,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Read Tamper Logs")
                    }
                }
            }

            if (meterReport.hasData) {
                MeterReadingSummaryCard(
                    report = meterReport,
                    selectedRange = loadSurveySelection,
                    busy = isReading,
                    lastHexLogName = lastHexLogFile?.name,
                    onSelectRange = { selection ->
                        loadSurveySelection = selection
                        // Re-filter any cached full buffer instantly instead of
                        // forcing another multi-minute optical read.
                        if (!isReading) {
                            meterReport = applyLoadSurveySelectionToReport(
                                meterReport,
                                selection
                            )
                        }
                    },
                    onExportPdf = ::exportReport,
                    onExportExcel = ::exportExcel,
                    onExportHex = ::exportHexLog
                )
            }

            SavedReadingsCard(
                readings = savedReadings,
                busy = isReading,
                onSelect = {
                    errorMessage = null
                    selectedSavedReading = it
                }
            )

            selectedSavedReading?.let { reading ->
                SavedReadingDialog(
                    reading = reading,
                    busy = isReading,
                    error = errorMessage,
                    onDismiss = { if (!isReading) selectedSavedReading = null },
                    onExportPdf = { selection ->
                        exportSavedReading(reading, selection, excel = false)
                    },
                    onExportExcel = { selection ->
                        exportSavedReading(reading, selection, excel = true)
                    },
                    onDelete = { deleteSavedReading(reading) }
                )
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
                Text(
                    "Live Protocol Trace (latest ${minOf(traceLogs.size, 150)} of ${traceLogs.size})",
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = traceLogs.reversed().joinToString("\n") { "[${it.direction}] ${it.hexOrMessage}" }
                        clipboard.setPrimaryClip(ClipData.newPlainText("DLMS Trace", text))
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Text("Copy", fontSize = 12.sp)
                }
                TextButton(
                    onClick = ::exportHexLog,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Text("Export Hex Log", fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0F172A))
                    .padding(10.dp)
            ) {
                val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
                val visibleTraceLogs = traceLogs.take(150)
                if (traceLogs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Awaiting optical connection...", color = Color.Gray, fontSize = 11.sp)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(visibleTraceLogs) { item ->
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

/** True unless both reports positively identify different meters. */
private fun sameMeterSerial(existing: MeterDataReport, incoming: MeterDataReport): Boolean {
    val existingSerial = existing.identityValue("0.0.96.1.0.255")
    val incomingSerial = incoming.identityValue("0.0.96.1.0.255")
    return existingSerial.isBlank() ||
        incomingSerial.isBlank() ||
        existingSerial == incomingSerial
}

/** Subtitle without empty/"N/A" segments, e.g. for a module-only saved read. */
private fun savedReadingSubtitle(reading: SavedReadingSummary): String =
    listOf(reading.readType, reading.kwhReading, reading.summaryStats)
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.equals("N/A", ignoreCase = true) }
        .joinToString(" • ")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoadSurveyRangeChips(
    selected: LoadSurveySelection,
    enabled: Boolean,
    onSelect: (LoadSurveySelection) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Load Survey Range", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LoadSurveySelection.entries.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    enabled = enabled,
                    label = { Text(option.label, fontSize = 11.sp, maxLines = 1) }
                )
            }
        }
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
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
        ) {
            Text(first.first, fontSize = 13.sp, maxLines = 1)
        }
        OutlinedButton(
            onClick = { onRead(second.second) },
            enabled = enabled,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
        ) {
            Text(second.first, fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
private fun MeterReadingSummaryCard(
    report: MeterDataReport,
    selectedRange: LoadSurveySelection,
    busy: Boolean,
    lastHexLogName: String?,
    onSelectRange: (LoadSurveySelection) -> Unit,
    onExportPdf: () -> Unit,
    onExportExcel: () -> Unit,
    onExportHex: () -> Unit
) {
    val nameplateCount = report.identity?.nameplate?.totalRows
        ?: report.identity?.rows?.size
        ?: 0
    val sectionCounts = listOf(
        "Nameplate" to "$nameplateCount fields",
        "Instantaneous" to "${report.instantaneous?.totalRows ?: 0} params",
        "Billing History" to "${report.billing?.totalRows ?: 0} entries",
        "Daily Load" to "${report.dailyLoad?.totalRows ?: 0} entries",
        "Block Load" to "${report.blockLoad?.totalRows ?: 0} entries",
        "Tamper Events" to "${report.tamperEvents.size} entries"
    )
    val windowNotes = listOfNotNull(
        report.blockLoad?.takeIf { it.windowNote.isNotBlank() }?.let { "Block: ${it.windowNote}" },
        report.dailyLoad?.takeIf { it.windowNote.isNotBlank() }?.let { "Daily: ${it.windowNote}" }
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "Current Reading Summary",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp
            )
            HorizontalDivider()
            report.identityValue("0.0.96.1.0.255")
                .takeIf { it.isNotBlank() }
                ?.let { ResultRow("Serial Number", it) }
            report.identityValue("0.0.42.0.0.255")
                .takeIf { it.isNotBlank() }
                ?.let { ResultRow("Logical Device Name", it) }
            report.identityValue("0.0.96.1.1.255")
                .takeIf { it.isNotBlank() }
                ?.let { ResultRow("Manufacturer", it) }
            report.identityValue("0.0.96.1.4.255")
                .takeIf { it.isNotBlank() }
                ?.let { ResultRow("Firmware Version", it) }
            report.identityValue("0.0.1.0.0.255")
                .takeIf { it.isNotBlank() }
                ?.let { ResultRow("Meter RTC", it) }
            HorizontalDivider(Modifier.padding(vertical = 2.dp))
            report.cumulativeKwhReading()
                .takeIf { it != "N/A" && it.isNotBlank() }
                ?.let { ResultRow("Cumulative kWh", it) }
            report.cumulativeKvahReading()
                .takeIf { it != "N/A" && it.isNotBlank() }
                ?.let { ResultRow("Cumulative kVAh", it) }

            HorizontalDivider(Modifier.padding(vertical = 2.dp))
            Text("Section Row Counts", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            sectionCounts.forEach { (label, count) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Text(
                        count,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            if (windowNotes.isNotEmpty()) {
                Text(
                    windowNotes.joinToString("\n"),
                    fontSize = 10.sp,
                    color = Color.Gray
                )
            }
            report.errors.take(3).forEach {
                Text(it, color = Color(0xFFB45309), fontSize = 10.sp)
            }

            Spacer(Modifier.height(2.dp))
            LoadSurveyRangeChips(
                selected = selectedRange,
                enabled = !busy,
                onSelect = onSelectRange
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onExportPdf,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Export PDF", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = onExportHex,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Export Hex (.txt)", fontSize = 11.sp)
                }
            }
            Button(
                onClick = onExportExcel,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Export Excel (.xlsx)", fontSize = 11.sp)
            }
            lastHexLogName?.let {
                Text("Hex log ready: $it", fontSize = 10.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun SavedReadingsCard(
    readings: List<SavedReadingSummary>,
    busy: Boolean,
    onSelect: (SavedReadingSummary) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Saved Meter Readings",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp
            )
            if (readings.isEmpty()) {
                Text(
                    "No saved readings yet. Every completed read is stored on this device automatically.",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            } else {
                Text(
                    "${readings.size} reading(s) stored locally — newest first",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    readings.forEach { reading ->
                        Surface(
                            color = Color(0xFFF8FAFC),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) { onSelect(reading) }
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    "Meter #${reading.serialNumber} — ${reading.scannedAtFormatted}",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                savedReadingSubtitle(reading).takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        it,
                                        color = Color.Gray,
                                        fontSize = 11.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedReadingDialog(
    reading: SavedReadingSummary,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onExportPdf: (LoadSurveySelection) -> Unit,
    onExportExcel: (LoadSurveySelection) -> Unit,
    onDelete: () -> Unit
) {
    var selection by remember(reading.id) { mutableStateOf(LoadSurveySelection.FULL_BUFFER) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Meter #${reading.serialNumber} — ${reading.scannedAtFormatted}",
                fontSize = 16.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                savedReadingSubtitle(reading).takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
                Text(reading.summaryStats, fontSize = 11.sp, color = Color.Gray)
                reading.meterRtc
                    .takeIf { it.isNotBlank() && it != "N/A" }
                    ?.let { ResultRow("Meter RTC", it) }
                LoadSurveyRangeChips(
                    selected = selection,
                    enabled = !busy,
                    onSelect = { selection = it }
                )
                Button(
                    onClick = { onExportPdf(selection) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Export PDF Report", fontSize = 12.sp)
                }
                Button(
                    onClick = { onExportExcel(selection) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Export Excel (.xlsx)", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onDelete,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete Saved Reading", fontSize = 12.sp)
                }
                if (busy) {
                    Text(
                        "Working — loading and exporting the saved reading...",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp
                    )
                }
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("Close")
            }
        }
    )
}
