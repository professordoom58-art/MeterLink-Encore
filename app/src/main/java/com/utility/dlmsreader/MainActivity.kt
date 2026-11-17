package com.utility.dlmsreader

import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.utility.dlmsreader.dlms.ConnectionParams
import com.utility.dlmsreader.dlms.DlmsMeterEngine
import com.utility.dlmsreader.dlms.ExtractionProgress
import com.utility.dlmsreader.dlms.LoadSurveySelection
import com.utility.dlmsreader.dlms.MeterDataModule
import com.utility.dlmsreader.dlms.MeterDataReport
import com.utility.dlmsreader.dlms.MeterFileImportRouter
import com.utility.dlmsreader.dlms.MeterLinkExcelExporter
import com.utility.dlmsreader.dlms.MeterLinkPdfExporter
import com.utility.dlmsreader.dlms.VendorPasswordCatalog
import com.utility.dlmsreader.dlms.applyLoadSurveySelectionToReport
import com.utility.dlmsreader.storage.MeterReadingDatabase
import com.utility.dlmsreader.storage.SavedReadingSummary
import com.utility.dlmsreader.storage.identityValue
import com.utility.dlmsreader.transport.OpticalUsbTransport
import com.utility.dlmsreader.transport.TraceLog
import com.utility.dlmsreader.update.MeterLinkRelease
import com.utility.dlmsreader.update.MeterLinkUpdater
import gurux.dlms.enums.Authentication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.io.File
import com.utility.dlmsreader.ui.PreviewSection
import com.utility.dlmsreader.ui.HapticFeedback
import com.utility.dlmsreader.ui.components.ExtractionCenterCard
import com.utility.dlmsreader.ui.components.LandingHeader
import com.utility.dlmsreader.ui.components.LiveProtocolTraceSection
import com.utility.dlmsreader.ui.components.MeterReadingSummaryCard
import com.utility.dlmsreader.ui.components.SavedReadingsCard
import com.utility.dlmsreader.ui.components.VendorConfigurationCard
import com.utility.dlmsreader.ui.dialogs.AboutAppDialog
import com.utility.dlmsreader.ui.dialogs.SavedReadingDialog
import com.utility.dlmsreader.ui.dialogs.SavedReadingsHistoryDialog
import com.utility.dlmsreader.ui.dialogs.UpdateAvailableDialog
import com.utility.dlmsreader.ui.preview.SectionDataPreviewDialog
import com.utility.dlmsreader.ui.theme.*


/** Landing-screen feature flag for the live hex console; collection/export stay active. */
private const val SHOW_LIVE_PROTOCOL_TRACE = false

class MainActivity : ComponentActivity() {

    private lateinit var transport: OpticalUsbTransport
    private lateinit var engine: DlmsMeterEngine
    private lateinit var readingDatabase: MeterReadingDatabase

    @Suppress("DEPRECATION")
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
                    surfaceVariant = Color(0xFFF8FAFC),
                    onSurfaceVariant = Muted,
                    surfaceTint = Color.Transparent,
                    surfaceContainer = Color.White,
                    surfaceContainerLow = Color.White,
                    surfaceContainerHigh = Color.White,
                    surfaceContainerHighest = Color(0xFFF1F5F9),
                    secondaryContainer = Color(0xFFEFF6FF),
                    onSecondaryContainer = Color(0xFF1D4ED8),
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
    var isReading by remember { mutableStateOf(false) }

    // A 30-90 second full load-survey download must not be interrupted by the
    // screen timing out, which can also throttle the USB link.
    val activity = context as? android.app.Activity
    DisposableEffect(isReading) {
        if (isReading) {
            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var meterReport by remember { mutableStateOf(MeterDataReport()) }
    var progressText by remember { mutableStateOf<String?>(null) }
    var reportStatusText by remember { mutableStateOf<String?>(null) }
    var autoTryAlternatePasswords by remember { mutableStateOf(false) }
    var loadSurveySelection by remember { mutableStateOf(LoadSurveySelection.RECENT) }
    var lastHexLogFile by remember { mutableStateOf<File?>(null) }
    var lastGeneratedPdf by remember { mutableStateOf<File?>(null) }
    var savedReadings by remember { mutableStateOf<List<SavedReadingSummary>>(emptyList()) }
    var selectedSavedReading by remember { mutableStateOf<SavedReadingSummary?>(null) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var previewSection by remember { mutableStateOf<PreviewSection?>(null) }
    var selectedSavedReport by remember { mutableStateOf<MeterDataReport?>(null) }
    var savedPreviewSection by remember { mutableStateOf<PreviewSection?>(null) }
    var savedPreviewReport by remember { mutableStateOf<MeterDataReport?>(null) }
    var savedPreviewTitle by remember { mutableStateOf("") }
    var refreshingSavedReadings by remember { mutableStateOf(false) }
    val traceLogs = remember { mutableStateListOf<TraceLog>() }

    /**
     * Scoped-storage fallback. A reinstall gets a new app UID, so JSONs written
     * by the previous install are invisible to MediaStore/direct file access.
     * The picker grants read access to Documents/MeterLink again and the import
     * path re-adds the readings to the local database.
     */
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        refreshingSavedReadings = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val imported = readingDatabase.importFromTree(uri)
                    imported to readingDatabase.listSummaries()
                }
            }
            result
                .onSuccess { (imported, list) ->
                    savedReadings = list
                    reportStatusText = if (imported > 0) {
                        "✓ Restored $imported saved reading(s) from the selected folder."
                    } else {
                        "No new MeterLink JSON files were found in that folder."
                    }
                }
                .onFailure { errorMessage = it.message ?: "Folder restore failed" }
            refreshingSavedReadings = false
        }
    }

    /**
     * Import from Storage. Read access is enough: the router sniffs JSON,
     * tagged OBIS CSV and framed binary dumps from the raw bytes, so the
     * picker can offer JSON plus CSV, text, DAT and BIN files, or any file type.
     * Imported reports use the same GZIP SQLite row and Documents/MeterLink
     * JSON backup as a live optical read.
     */
    val importFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isReading = true
        errorMessage = null
        progressText = "Importing meter file…"
        reportStatusText = null
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val displayName = runCatching {
                        context.contentResolver.query(
                            uri,
                            arrayOf(OpenableColumns.DISPLAY_NAME),
                            null,
                            null,
                            null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        }
                    }.getOrNull() ?: uri.lastPathSegment ?: "Meter file"
                    val bytes = context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes()
                    } ?: error("The selected file could not be opened.")
                    val import = MeterFileImportRouter.route(
                        bytes,
                        displayName,
                        context.contentResolver.getType(uri)
                    )
                    val saved = readingDatabase.insertReport(
                        report = import.report,
                        readType = "Imported File"
                    )
                    Triple(displayName, import.report, saved) to readingDatabase.listSummaries()
                }
            }
            result
                .onSuccess { (imported, list) ->
                    val (name, report, savedRow) = imported
                    meterReport = applyLoadSurveySelectionToReport(report, loadSurveySelection)
                    savedReadings = list
                    selectedSavedReading = list.firstOrNull { it.id == savedRow }
                    reportStatusText = "✓ Imported $name and saved it to Documents/MeterLink."
                }
                .onFailure {
                    errorMessage = it.message ?: "Meter file import failed"
                }
            isReading = false
            progressText = null
        }
    }

    // ------------------------------------------------------------------
    // GitHub release updater
    // ------------------------------------------------------------------

    var checkingForUpdates by remember { mutableStateOf(false) }
    var availableRelease by remember { mutableStateOf<MeterLinkRelease?>(null) }
    var downloadingUpdate by remember { mutableStateOf(false) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var pendingUpdateApk by remember { mutableStateOf<File?>(null) }

    fun launchApkInstaller(apk: File) {
        runCatching { context.startActivity(MeterLinkUpdater.installIntent(context, apk)) }
            .onFailure { updateError = "Could not open the installer: ${it.message}" }
    }

    /** Returns from the "install unknown apps" screen; install continues if granted. */
    val installPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val apk = pendingUpdateApk
        pendingUpdateApk = null
        if (apk == null) return@rememberLauncherForActivityResult
        if (MeterLinkUpdater.canInstallPackages(context)) {
            launchApkInstaller(apk)
        } else {
            updateError = "Allow MeterLink to install unknown apps, then tap Download & Install again."
        }
    }

    fun installDownloadedApk(apk: File) {
        if (MeterLinkUpdater.canInstallPackages(context)) {
            launchApkInstaller(apk)
            return
        }
        pendingUpdateApk = apk
        runCatching {
            installPermissionLauncher.launch(
                MeterLinkUpdater.unknownSourcesSettingsIntent(context)
            )
        }.onFailure {
            pendingUpdateApk = null
            updateError = "Allow MeterLink to install unknown apps, then tap Download & Install again."
        }
    }

    fun downloadAndInstall(release: MeterLinkRelease) {
        if (downloadingUpdate) return
        downloadingUpdate = true
        updateError = null
        scope.launch {
            runCatching { MeterLinkUpdater.downloadApk(context, release) }
                .onSuccess { apk ->
                    downloadingUpdate = false
                    installDownloadedApk(apk)
                }
                .onFailure {
                    downloadingUpdate = false
                    updateError = it.message ?: "The update download failed."
                }
        }
    }

    fun checkForUpdates() {
        if (checkingForUpdates) return
        checkingForUpdates = true
        updateError = null
        scope.launch {
            runCatching { MeterLinkUpdater.checkForUpdate(context) }
                .onSuccess { release ->
                    checkingForUpdates = false
                    if (release == null) {
                        Toast.makeText(
                            context,
                            "MeterLink is up to date " +
                                "(${MeterLinkUpdater.installedVersion(context)}).",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        availableRelease = release
                    }
                }
                .onFailure {
                    checkingForUpdates = false
                    Toast.makeText(
                        context,
                        it.message ?: "Could not check for updates.",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

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

    /** Section label shown at 0% while the optical session is being opened. */
    fun moduleProgressStart(module: MeterDataModule): String = when (module) {
        MeterDataModule.IDENTITY, MeterDataModule.ALL ->
            ExtractionProgress.format(ExtractionProgress.IDENTITY, 0)
        MeterDataModule.ENERGY ->
            ExtractionProgress.format(ExtractionProgress.ENERGY, 0)
        MeterDataModule.INSTANTANEOUS ->
            ExtractionProgress.format(ExtractionProgress.INSTANTANEOUS, 0)
        MeterDataModule.BILLING ->
            ExtractionProgress.format(ExtractionProgress.BILLING, 0)
        MeterDataModule.BLOCK_LOAD ->
            ExtractionProgress.format(ExtractionProgress.BLOCK_LOAD, 0)
        MeterDataModule.DAILY_LOAD ->
            ExtractionProgress.format(ExtractionProgress.DAILY_LOAD, 0)
        MeterDataModule.TAMPER ->
            ExtractionProgress.format(ExtractionProgress.TAMPER, 0)
    }

    fun runModule(module: MeterDataModule) {
        isReading = true
        errorMessage = null
        progressText = moduleProgressStart(module)
        reportStatusText = null
        val readType = readTypeLabel(module)
        if (module == MeterDataModule.ALL) {
            meterReport = MeterDataReport()
            // Never expose a previous read's PDF as "current".
            lastGeneratedPdf = null
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
                                lastGeneratedPdf = bundle.pdf
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
                    HapticFeedback.notifySuccess(context)
                }
                .onFailure {
                    errorMessage = it.message ?: "${module.name} read failed"
                    HapticFeedback.notifyError(context)
                }
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
                    lastGeneratedPdf = bundle.pdf
                    reportStatusText =
                        "✓ PDF saved as ${bundle.pdf.name}; Hex Log saved as ${bundle.hexLog.name}."
                    runCatching { exporter.share(bundle.pdf) }
                        .onFailure { errorMessage = it.message ?: "PDF share failed" }
                }
                .onFailure { errorMessage = it.message ?: "PDF export failed" }
            isReading = false
        }
    }

    /** Opens the most recently generated PDF directly, without the share sheet. */
    fun viewCurrentPdf() {
        val file = lastGeneratedPdf ?: return
        runCatching { MeterLinkPdfExporter(context).viewPdf(file) }
            .onFailure { errorMessage = it.message ?: "Could not open PDF viewer" }
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

    fun previewSavedSection(reading: SavedReadingSummary, section: PreviewSection) {
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                readingDatabase.loadReport(reading.id)
            }
            if (report == null) {
                errorMessage = "Saved reading #${reading.id} no longer exists."
                return@launch
            }
            savedPreviewReport = applyLoadSurveySelectionToReport(report, loadSurveySelection)
            savedPreviewTitle = "Meter #${reading.serialNumber}"
            savedPreviewSection = section
        }
    }

    /**
     * Re-scans `Documents/MeterLink` and reloads the saved list. When nothing is
     * visible — the usual symptom after a reinstall — the SAF folder picker is
     * offered so the previous install's JSONs can be imported.
     */
    fun refreshSavedReadings() {
        if (refreshingSavedReadings) return
        refreshingSavedReadings = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val previousCount = savedReadings.size
                    val list = readingDatabase.refreshExternalBackups()
                    previousCount to list
                }
            }
            result
                .onSuccess { (previousCount, list) ->
                    savedReadings = list
                    val added = list.size - previousCount
                    if (added > 0) {
                        reportStatusText = "✓ $added new saved reading(s) found in Documents/MeterLink."
                    }
                    if (list.isEmpty()) folderPicker.launch(null)
                }
                .onFailure { errorMessage = it.message ?: "Refresh failed" }
            refreshingSavedReadings = false
        }
    }

    LaunchedEffect(Unit) {
        savedReadings = readingDatabase.listSummaries()
    }

    // Load the full report when a saved reading is opened so its section
    // boxes and preview dialog show real rows instead of summary text.
    LaunchedEffect(selectedSavedReading?.id) {
        val reading = selectedSavedReading
        selectedSavedReport = if (reading == null) {
            null
        } else {
            withContext(Dispatchers.IO) { readingDatabase.loadReport(reading.id) }
        }
    }

    // Trace collection only feeds the optional hex console. Skipping the
    // collector when the console is hidden avoids mutating a state list (and
    // recomposing) for every HDLC frame during a normal reading.
    if (SHOW_LIVE_PROTOCOL_TRACE) {
        LaunchedEffect(Unit) {
            transport.traceFlow.collect { log ->
                traceLogs.add(0, log)
                if (traceLogs.size > 150) traceLogs.removeLast()
            }
        }
    }

    Scaffold(
        topBar = {
            LandingHeader(
                probeStatus = probeStatus,
                historyCount = savedReadings.size,
                checkingForUpdates = checkingForUpdates,
                onScanUsb = onRefreshUsb,
                onHistory = { showHistoryDialog = true },
                onCheckUpdates = ::checkForUpdates,
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
            VendorConfigurationCard(
                selectedVendor = selectedVendor,
                onSelectVendor = { vendor ->
                    selectedVendor = vendor
                },
                llsPassword = llsPassword,
                onPasswordChange = { llsPassword = it },
                autoTryAlternatePasswords = autoTryAlternatePasswords,
                onAutoTryAlternateChange = { autoTryAlternatePasswords = it },
                errorMessage = errorMessage
            )

            // 2. Extraction command center.
            ExtractionCenterCard(
                isReading = isReading,
                progressText = progressText,
                reportStatusText = reportStatusText,
                loadSurveySelection = loadSurveySelection,
                onSelectLoadSurveyRange = { selection ->
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
                },
                onStartReading = ::runModule
            )

            if (meterReport.hasData) {
                MeterReadingSummaryCard(
                    report = meterReport,
                    busy = isReading,
                    lastHexLogName = lastHexLogFile?.name,
                    onExportPdf = ::exportReport,
                    onExportExcel = ::exportExcel,
                    onExportHex = ::exportHexLog,
                    onViewPdf = if (lastGeneratedPdf != null) ::viewCurrentPdf else null,
                    onSectionClick = { previewSection = it }
                )
            }

            SavedReadingsCard(
                readings = savedReadings,
                busy = isReading,
                refreshing = refreshingSavedReadings,
                onRefresh = ::refreshSavedReadings,
                onImport = {
                    importFilePicker.launch(
                        arrayOf(
                            "application/json",
                            "text/csv",
                            "text/plain",
                            "application/octet-stream",
                            "*/*"
                        )
                    )
                },
                onSelect = {
                    errorMessage = null
                    selectedSavedReading = it
                },
                onSectionClick = ::previewSavedSection
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
                AboutAppDialog(
                    version = MeterLinkUpdater.installedVersion(context),
                    onDismiss = { showAboutDialog = false }
                )
            }

            availableRelease?.let { release ->
                UpdateAvailableDialog(
                    release = release,
                    installedVersion = MeterLinkUpdater.installedVersion(context),
                    downloading = downloadingUpdate,
                    error = updateError,
                    onInstall = { downloadAndInstall(release) },
                    onDismiss = {
                        if (!downloadingUpdate) {
                            availableRelease = null
                            updateError = null
                        }
                    }
                )
            }

            selectedSavedReading?.let { reading ->
                SavedReadingDialog(
                    reading = reading,
                    report = selectedSavedReport,
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

            previewSection?.let { section ->
                SectionDataPreviewDialog(
                    section = section,
                    report = meterReport,
                    subtitle = "Meter #${meterReport.identityValue("0.0.96.1.0.255").ifBlank { "UNKNOWN" }}",
                    onDismiss = { previewSection = null }
                )
            }

            savedPreviewSection?.let { section ->
                savedPreviewReport?.let { report ->
                    SectionDataPreviewDialog(
                        section = section,
                        report = report,
                        subtitle = savedPreviewTitle,
                        onDismiss = {
                            savedPreviewSection = null
                            savedPreviewReport = null
                        }
                    )
                }
            }

            // 3. Live Hex Trace Console (kept for field diagnostics; off by default).
            if (SHOW_LIVE_PROTOCOL_TRACE) {
                LiveProtocolTraceSection(
                    traceLogs = traceLogs,
                    onExportHex = ::exportHexLog
                )
            }
        }
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
