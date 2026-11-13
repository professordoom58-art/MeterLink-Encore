package com.utility.dlmsreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.material3.MenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.utility.dlmsreader.dlms.ConnectionParams
import com.utility.dlmsreader.dlms.DlmsMeterEngine
import com.utility.dlmsreader.dlms.LoadSurveySelection
import com.utility.dlmsreader.dlms.MeterDataModule
import com.utility.dlmsreader.dlms.MeterDataReport
import com.utility.dlmsreader.dlms.MeterLinkExcelExporter
import com.utility.dlmsreader.dlms.MeterLinkPdfExporter
import com.utility.dlmsreader.dlms.VendorPasswordCatalog
import com.utility.dlmsreader.dlms.applyLoadSurveySelectionToReport
import com.utility.dlmsreader.storage.MeterReadingDatabase
import com.utility.dlmsreader.storage.SavedReadingSummary
import com.utility.dlmsreader.storage.identityValue
import com.utility.dlmsreader.storage.instantaneousParameterCount
import com.utility.dlmsreader.storage.nameplateFieldCount
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

private val Ink = Color(0xFF0F172A)
private val Muted = Color(0xFF64748B)
private val Cobalt = Color(0xFF1D4ED8)
private val SoftBlue = Color(0xFFEFF6FF)
private val SoftBlueBorder = Color(0xFFBFDBFE)
private val TileFill = Color(0xFFF8FAFC)
private val Hairline = Color(0xFFE2E8F0)

class MainActivity : ComponentActivity() {

    private lateinit var transport: OpticalUsbTransport
    private lateinit var engine: DlmsMeterEngine
    private lateinit var readingDatabase: MeterReadingDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The Compose UI below always uses the light palette, so the system bars
        // must stay visible with dark icons whatever the device night mode is.
        // enableEdgeToEdge() used to pick the bar style from the device night
        // mode, which drew white icons on the white header and hid the clock and
        // battery. The Scaffold/TopAppBar insets already keep the content clear
        // of the status bar, so no extra padding is applied here.
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = android.graphics.Color.parseColor("#F8FAFC")
        WindowCompat.getInsetsController(window, window.decorView).apply {
            show(WindowInsetsCompat.Type.statusBars())
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        transport = OpticalUsbTransport(applicationContext)
        engine = DlmsMeterEngine(transport)
        readingDatabase = MeterReadingDatabase(applicationContext)
        transport.refreshProbeStatus()

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Cobalt,
                    onPrimary = Color.White,
                    secondary = Color(0xFF3B82F6),
                    onSecondary = Color.White,
                    background = Color(0xFFF8FAFC),
                    onBackground = Ink,
                    surface = Color.White,
                    onSurface = Ink,
                    surfaceVariant = SoftBlue,
                    onSurfaceVariant = Muted,
                    outline = Hairline,
                    error = Color(0xFFEF4444)
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

    var selectedVendor by remember { mutableStateOf(VendorPasswordCatalog.VENDORS.first()) }
    var llsPassword by remember { mutableStateOf(selectedVendor.defaultPrimaryPassword) }
    var vendorMenuExpanded by remember { mutableStateOf(false) }
    var isReading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var meterReport by remember { mutableStateOf(MeterDataReport()) }
    var progressText by remember { mutableStateOf<String?>(null) }
    var reportStatusText by remember { mutableStateOf<String?>(null) }
    var autoTryAlternatePasswords by remember { mutableStateOf(false) }
    var loadSurveySelection by remember { mutableStateOf(LoadSurveySelection.RECENT) }
    var lastHexLogFile by remember { mutableStateOf<File?>(null) }
    var savedReadings by remember { mutableStateOf<List<SavedReadingSummary>>(emptyList()) }
    var selectedSavedReading by remember { mutableStateOf<SavedReadingSummary?>(null) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
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
                clientAddress = 0x20,
                authentication = Authentication.LOW,
                password = llsPassword,
                passwordCandidates = selectedVendor.defaultPasswords,
                autoTryAlternatePasswords = autoTryAlternatePasswords,
                vendorName = selectedVendor.displayName,
                useModeE = false,
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
            if (traceLogs.size > 150) traceLogs.removeLast()
        }
    }

    Scaffold(
        topBar = {
            LandingHeader(
                probeStatus = probeStatus,
                historyCount = savedReadings.size,
                onScanUsb = onRefreshUsb,
                onHistory = { showHistoryDialog = true },
                onAbout = { showAboutDialog = true }
            )
        },
        // The custom header and content apply system-bar insets explicitly, so
        // Scaffold must not add its own copy (that would double-pad on API 35+).
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(2.dp))

            // 1. Ultra-compact vendor & authentication card.
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Hairline),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
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
                            shape = RoundedCornerShape(12.dp)
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
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Switch(
                            checked = autoTryAlternatePasswords,
                            onCheckedChange = { autoTryAlternatePasswords = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Cobalt,
                                checkedBorderColor = Cobalt,
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = Color(0xFFE2E8F0),
                                uncheckedBorderColor = Color(0xFFCBD5E1)
                            )
                        )
                        Text(
                            "Auto-try alternate vendor passwords",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Ink
                        )
                    }

                    errorMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // 2. Extraction command center.
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Hairline),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val bannerLines = listOfNotNull(progressText, reportStatusText)
                    if (isReading || bannerLines.isNotEmpty()) {
                        Surface(
                            color = SoftBlue,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, SoftBlueBorder)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    bannerLines.joinToString("\n").ifBlank { "Reading meter..." },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Cobalt,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val barShape = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(50))
                                if (isReading) {
                                    // Indeterminate sweep while a module is streaming.
                                    LinearProgressIndicator(
                                        modifier = barShape,
                                        color = Cobalt,
                                        trackColor = Color(0xFFDBEAFE)
                                    )
                                } else {
                                    // Completed run: full tonal bar under the status line.
                                    LinearProgressIndicator(
                                        progress = { 1f },
                                        modifier = barShape,
                                        color = Cobalt,
                                        trackColor = Color(0xFFDBEAFE)
                                    )
                                }
                            }
                        }
                    }
                    Button(
                        onClick = { runModule(MeterDataModule.ALL) },
                        enabled = !isReading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Cobalt,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text(
                            "Read All (Full Meter Dump)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFF3B82F6)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cobalt),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text(
                            "Read Tamper Logs",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (meterReport.hasData) {
                MeterReadingSummaryCard(
                    report = meterReport,
                    busy = isReading,
                    lastHexLogName = lastHexLogFile?.name,
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

            if (showHistoryDialog) {
                SavedReadingsHistoryDialog(
                    readings = savedReadings,
                    onOpen = { reading ->
                        showHistoryDialog = false
                        selectedSavedReading = reading
                    },
                    onDismiss = { showHistoryDialog = false }
                )
            }

            if (showAboutDialog) {
                AboutAppDialog(onDismiss = { showAboutDialog = false })
            }

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

            // 3. Live Hex Trace Console
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

private val ScanNegativeMarkers = listOf(
    "no ", "not ", "none", "error", "fail", "denied", "disconnect"
)
private val ScanHealthyMarkers = listOf("ready", "link", "established", "connected")
private val ScanDetectedMarkers = listOf("detected", "cable")

/**
 * Dot colour for the SCAN USB pill.
 *
 * Negative/error wording is matched first: "No USB Optical Probe Detected"
 * contains both "no " and "detected", and "Cable Connected, but No Meter
 * Response" contains both "cable" and "connected", so a positive-first match
 * would paint a disconnected probe amber or green.
 */
internal fun scanUsbDotColor(probeStatus: String): Color {
    val status = probeStatus.lowercase(Locale.US)
    return when {
        ScanNegativeMarkers.any(status::contains) -> Color(0xFFEF4444)
        ScanHealthyMarkers.any(status::contains) -> Color(0xFF10B981)
        ScanDetectedMarkers.any(status::contains) -> Color(0xFFF59E0B)
        else -> Color(0xFFEF4444)
    }
}

/** Unified landing header: wordmark, beta pill, USB scan status, and overflow. */
@Composable
private fun LandingHeader(
    probeStatus: String,
    historyCount: Int,
    onScanUsb: () -> Unit,
    onHistory: () -> Unit,
    onAbout: () -> Unit
) {
    val dotColor = scanUsbDotColor(probeStatus)

    Surface(color = Color(0xFFF8FAFC)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "MeterLink",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Ink
                )
                Surface(
                    color = SoftBlue,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        "BETA",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Cobalt
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = onScanUsb,
                    modifier = Modifier.height(34.dp),
                    shape = RoundedCornerShape(percent = 50),
                    color = Color.White,
                    border = BorderStroke(1.dp, Hairline)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(dotColor, CircleShape)
                        )
                        Text(
                            "SCAN USB",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink
                        )
                    }
                }
                HeaderOverflowMenu(
                    historyCount = historyCount,
                    onHistory = onHistory,
                    onAbout = onAbout
                )
            }
        }
    }
}

/** Borderless 3-dot overflow button pinned to the right of the landing header. */
@Composable
private fun HeaderOverflowMenu(
    historyCount: Int,
    onHistory: () -> Unit,
    onAbout: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "More options",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(x = (-16).dp, y = 4.dp)
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (historyCount > 0) "History ($historyCount)" else "History",
                        fontSize = 14.sp
                    )
                },
                onClick = {
                    expanded = false
                    onHistory()
                }
            )
            DropdownMenuItem(
                text = { Text("About App", fontSize = 14.sp) },
                onClick = {
                    expanded = false
                    onAbout()
                }
            )
        }
    }
}

/**
 * Shared Material 3 card dialog shell: rounded elevated surface, icon + bold
 * title header with a dismiss button, padded scrollable body and tonal actions.
 */
@Composable
private fun MeterLinkDialog(
    title: String,
    icon: ImageVector,
    onDismiss: () -> Unit,
    dismissEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = { if (dismissEnabled) onDismiss() }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, enabled = dismissEnabled) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    FilledTonalButton(
                        onClick = onDismiss,
                        enabled = dismissEnabled,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Close", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}


/** Scrollable list of every saved reading, with the detail dialog one tap away. */
@Composable
private fun SavedReadingsHistoryDialog(
    readings: List<SavedReadingSummary>,
    onOpen: (SavedReadingSummary) -> Unit,
    onDismiss: () -> Unit
) {
    MeterLinkDialog(
        title = "Saved Meter Readings",
        icon = Icons.AutoMirrored.Filled.List,
        onDismiss = onDismiss
    ) {
        if (readings.isEmpty()) {
            Text(
                "No saved readings yet. Every completed read is stored on this device automatically.",
                color = Color.Gray,
                fontSize = 12.sp
            )
        } else {
            readings.forEach { reading ->
                Surface(
                    color = Color(0xFFF8FAFC),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpen(reading) }
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
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

@Composable
private fun AboutAppDialog(onDismiss: () -> Unit) {
    MeterLinkDialog(
        title = "About MeterLink",
        icon = Icons.Default.Info,
        onDismiss = onDismiss
    ) {
        Text(
            "MeterLink DLMS meter reader (beta)",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "Supported capabilities",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        CapabilityBullet("Supported meter companies: L&T / Schneider, Genus, Secure, HPL, Avon")
        CapabilityBullet("Saved meter readings with instant offline PDF & Excel re-export")
        CapabilityBullet(
            "All kinds of readings supported: Identity, Cumulative Energy & MD, " +
                "Instantaneous, Billing History, Block Load, Daily Load, and Tamper Logs"
        )
        CapabilityBullet("Optical USB probe")
        AboutBlock("Builder", "Made by Mihir")
    }
}

@Composable
private fun CapabilityBullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("•", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        Text(text, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AboutBlock(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(value, fontSize = 12.sp)
    }
}

@Composable
private fun LoadSurveyRangeChips(
    selected: LoadSurveySelection,
    enabled: Boolean,
    onSelect: (LoadSurveySelection) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Load Survey Range",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF475569)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LoadSurveySelection.entries.forEach { option ->
                val isSelected = selected == option
                val labelColor = when {
                    !enabled -> Muted.copy(alpha = 0.5f)
                    isSelected -> Cobalt
                    else -> Color(0xFF334155)
                }
                Surface(
                    onClick = { onSelect(option) },
                    enabled = enabled,
                    shape = RoundedCornerShape(percent = 50),
                    color = if (isSelected) SoftBlue else Color.White,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) Color(0xFF93C5FD) else Hairline
                    ),
                    contentColor = labelColor
                ) {
                    Box(
                        modifier = Modifier
                            .height(32.dp)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            option.label,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = labelColor,
                            maxLines = 1
                        )
                    }
                }
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
        ExtractionModuleButton(
            label = first.first,
            enabled = enabled,
            onClick = { onRead(first.second) },
            modifier = Modifier.weight(1f)
        )
        ExtractionModuleButton(
            label = second.first,
            enabled = enabled,
            onClick = { onRead(second.second) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ExtractionModuleButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = TileFill,
            contentColor = Ink,
            disabledContainerColor = TileFill,
            disabledContentColor = Muted
        ),
        border = BorderStroke(1.dp, Hairline),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
            disabledElevation = 0.dp
        ),
        contentPadding = PaddingValues(horizontal = 6.dp)
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MeterReadingSummaryCard(
    report: MeterDataReport,
    busy: Boolean,
    lastHexLogName: String?,
    onExportPdf: () -> Unit,
    onExportExcel: () -> Unit,
    onExportHex: () -> Unit
) {
    val serial = report.identityValue("0.0.96.1.0.255").ifBlank { "UNKNOWN" }
    val ldn = report.identityValue("0.0.42.0.0.255")
    val manufacturerTag = manufacturerTag(report.identityValue("0.0.96.1.1.255"))
    val activeEnergy = report.energyReading("1.0.1.8.0.255")
    val apparentEnergy = report.energyReading("1.0.9.8.0.255")

    val instParamCount = report.instantaneousParameterCount()
    val nameplate = report.nameplateFieldCount()
    val billing = report.billing?.rows?.size ?: 0
    val daily = report.dailyLoad?.rows?.size ?: 0
    val block = report.blockLoad?.rows?.size ?: 0
    val tamper = report.tamperEvents.size
    val sectionRows = listOf(
        listOf("Nameplate" to nameplate, "Instantaneous" to instParamCount, "Billing" to billing),
        listOf("Daily" to daily, "Block" to block, "Tamper" to tamper)
    )
    val windowNotes = listOfNotNull(
        report.blockLoad?.takeIf { it.windowNote.isNotBlank() }?.let { "Block: ${it.windowNote}" },
        report.dailyLoad?.takeIf { it.windowNote.isNotBlank() }?.let { "Daily: ${it.windowNote}" }
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Hairline),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "#$serial",
                    modifier = Modifier.weight(1f),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.weight(1.2f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = SoftBlue,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            manufacturerTag,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Cobalt,
                            maxLines = 1
                        )
                    }
                    if (ldn.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            ldn,
                            modifier = Modifier.weight(1f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Muted,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EnergyReadout(
                    label = "ACTIVE ENERGY",
                    value = activeEnergy,
                    unit = "kWh",
                    modifier = Modifier.weight(1f)
                )
                EnergyReadout(
                    label = "APPARENT ENERGY",
                    value = apparentEnergy,
                    unit = "kVAh",
                    modifier = Modifier.weight(1f)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sectionRows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { (label, count) ->
                            SectionCountBadge(
                                label = label,
                                count = count,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            if (windowNotes.isNotEmpty()) {
                Text(
                    windowNotes.joinToString("\n"),
                    fontSize = 10.sp,
                    color = Muted
                )
            }
            report.errors.take(3).forEach {
                Text(it, color = Color(0xFFB45309), fontSize = 10.sp)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onExportPdf,
                    enabled = !busy,
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Cobalt,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text(
                        "↓ Export PDF",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
                Button(
                    onClick = onExportExcel,
                    enabled = !busy,
                    modifier = Modifier
                        .weight(1.3f)
                        .height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Cobalt,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text(
                        "↓ Export Excel (.xlsx)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
            OutlinedButton(
                onClick = onExportHex,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Text(
                    "Export Hex (.txt)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            lastHexLogName?.let {
                Text("Hex log ready: $it", fontSize = 10.sp, color = Muted)
            }
        }
    }
}

@Composable
private fun EnergyReadout(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color(0xFFF0F7FF),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFFDBEAFE))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Muted,
                maxLines = 1
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    " $unit",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Cobalt,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SectionCountBadge(
    label: String,
    count: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = TileFill,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Hairline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                modifier = Modifier.weight(1f),
                fontSize = 11.sp,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(4.dp))
            Text(
                count.toString(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Ink
            )
        }
    }
}

private fun MeterDataReport.energyReading(obis: String): String =
    cumulativeEnergies.firstOrNull { it.obisCode == obis }
        ?.reading
        ?.trim()
        .orEmpty()
        .ifBlank { "—" }

private fun manufacturerTag(manufacturer: String): String {
    val upper = manufacturer.uppercase(Locale.US)
    return when {
        "LARSEN" in upper || "L&T" in upper || "LNT" in upper -> "L&T"
        manufacturer.isBlank() -> "METER"
        else -> manufacturer.trim().substringBefore(' ').take(10).uppercase(Locale.US)
    }
}

@Composable
private fun SavedReadingsCard(
    readings: List<SavedReadingSummary>,
    busy: Boolean,
    onSelect: (SavedReadingSummary) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Hairline),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Saved Meter Readings",
                fontWeight = FontWeight.Bold,
                color = Ink,
                fontSize = 14.sp
            )
            if (readings.isEmpty()) {
                Text(
                    "No saved readings yet. Every completed read is stored on this device automatically.",
                    color = Muted,
                    fontSize = 11.sp
                )
            } else {
                Text(
                    "${readings.size} reading(s) stored locally — newest first",
                    color = Muted,
                    fontSize = 11.sp
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    readings.forEach { reading ->
                        Surface(
                            color = TileFill,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Hairline),
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

    MeterLinkDialog(
        title = "Meter #${reading.serialNumber}",
        icon = Icons.AutoMirrored.Filled.List,
        onDismiss = onDismiss,
        dismissEnabled = !busy
    ) {
        Text(
            reading.scannedAtFormatted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        savedReadingSubtitle(reading).takeIf { it.isNotBlank() }?.let {
            Text(it, fontSize = 12.sp, color = Color.Gray)
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
            shape = RoundedCornerShape(10.dp)
        ) {
            Text("Export PDF Report", fontSize = 12.sp)
        }
        Button(
            onClick = { onExportExcel(selection) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text("Export Excel (.xlsx)", fontSize = 12.sp)
        }
        OutlinedButton(
            onClick = onDelete,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
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
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
        }
    }
}
