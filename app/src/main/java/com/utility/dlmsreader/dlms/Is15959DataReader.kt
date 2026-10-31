package com.utility.dlmsreader.dlms

import com.utility.dlmsreader.transport.OpticalUsbTransport
import gurux.dlms.GXDLMSClient
import gurux.dlms.GXDateTime
import gurux.dlms.GXDLMSException
import gurux.dlms.enums.DataType
import gurux.dlms.enums.DateTimeSkips
import gurux.dlms.enums.Unit as DlmsUnit
import gurux.dlms.objects.GXDLMSClock
import gurux.dlms.objects.GXDLMSData
import gurux.dlms.objects.GXDLMSDemandRegister
import gurux.dlms.objects.GXDLMSExtendedRegister
import gurux.dlms.objects.GXDLMSObject
import gurux.dlms.objects.GXDLMSProfileGeneric
import gurux.dlms.objects.GXDLMSRegister
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.pow

const val DAILY_LOAD_MAX_ENTRIES = 400
const val BLOCK_LOAD_MAX_ENTRIES = 48

/**
 * Load-survey range selection. Every day-window option uses 96 intervals per
 * day, which is the IS 15959 15-minute block cadence. [RECENT] keeps its
 * historical name for callers but now represents the explicit "2 Days" chip.
 * [FULL_BUFFER] has no day window and bypasses selective range access.
 */
enum class LoadSurveySelection(
    val label: String,
    val days: Int?,
    val maxEntries: Int
) {
    RECENT("2 Days", 2, 2 * 96),
    DAYS_30("30 Days", 30, 30 * 96),
    DAYS_45("45 Days", 45, 45 * 96),
    DAYS_75("75 Days", 75, 75 * 96),
    FULL_BUFFER("Full Buffer", null, Int.MAX_VALUE);

    /** Backwards-compatible alias for callers that used the old property name. */
    val daysBack: Int? get() = days
}

/** Resolved selective-access window for one load-survey read. */
data class LoadSurveyWindow(
    val start: Calendar,
    val end: Calendar,
    val maxEntries: Int,
    val daysBack: Int?
)

/**
 * Pure window calculation so the date maths is unit-testable without a meter.
 * Start is floored to 00:00:00.000 and end is ceiled to 23:59:59.000 of the
 * meter RTC day, so a partial final day is never truncated.
 */
fun loadSurveyWindow(
    selection: LoadSurveySelection,
    meterRtc: Calendar,
    isBlockLoad: Boolean
): LoadSurveyWindow {
    // Day-window selections are profile-independent: 2/30/45/75 days all use
    // 96 entries per day for both block and daily load.
    val daysBack = selection.days
    val maxEntries = selection.maxEntries
    val end = (meterRtc.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 0)
    }
    val start = (meterRtc.clone() as Calendar).apply {
        if (daysBack != null) add(Calendar.DAY_OF_YEAR, -daysBack)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return LoadSurveyWindow(start, end, maxEntries, daysBack)
}

/**
 * True when [selection] is anchored to a day window and therefore needs the
 * meter RTC, Selector 1 range access, and post-read window filtering.
 * FULL_BUFFER has no window: it reads the complete Selector 0 buffer as-is.
 */
internal fun loadSurveyNeedsWindow(selection: LoadSurveySelection): Boolean =
    selection.days != null

private val ISO_DAY_PREFIX = Regex("""^(\d{4})-(\d{2})-(\d{2})""")
private val DMY_DAY_PREFIX = Regex("""^(\d{2})-(\d{2})-(\d{2})(?![0-9])""")

/** Epoch millis for a raw profile timestamp cell, or null when unparseable. */
internal fun profileRowTimestamp(raw: Any?): Long? = when (raw) {
    is GXDateTime -> runCatching { MeterTime.wallClock(raw).timeInMillis }.getOrNull()
    is Date -> raw.time
    is ByteArray -> if (raw.size == 12) {
        runCatching {
            (GXDLMSClient.changeType(raw, DataType.DATETIME, false) as? GXDateTime)
                ?.let { MeterTime.wallClock(it).timeInMillis }
        }.getOrNull()
    } else null
    else -> null
}

/** Canonical `yyyy-MM-dd` day key for a raw profile timestamp cell. */
internal fun profileRowDay(raw: Any?): String? = when (raw) {
    is GXDateTime -> runCatching { dayKey(MeterTime.wallClock(raw)) }.getOrNull()
    is Date -> dayKey(Calendar.getInstance().apply { timeInMillis = raw.time })
    is ByteArray -> if (raw.size == 12) {
        runCatching {
            (GXDLMSClient.changeType(raw, DataType.DATETIME, false) as? GXDateTime)
                ?.let { dayKey(MeterTime.wallClock(it)) }
        }.getOrNull()
    } else null
    is String -> canonicalDay(raw.trim())
    else -> null
}

/** Canonical `yyyy-MM-dd` day key for an epoch-millis timestamp. */
internal fun profileDayKey(timestampMs: Long): String =
    dayKey(Calendar.getInstance().apply { timeInMillis = timestampMs })

private fun dayKey(calendar: Calendar): String = String.format(
    Locale.US,
    "%04d-%02d-%02d",
    calendar.get(Calendar.YEAR),
    calendar.get(Calendar.MONTH) + 1,
    calendar.get(Calendar.DAY_OF_MONTH)
)

private fun canonicalDay(value: String): String? {
    ISO_DAY_PREFIX.find(value)?.let { match ->
        return "${match.groupValues[1]}-${match.groupValues[2]}-${match.groupValues[3]}"
    }
    DMY_DAY_PREFIX.find(value)?.let { match ->
        return "20${match.groupValues[3]}-${match.groupValues[2]}-${match.groupValues[1]}"
    }
    return null
}

/** Number of distinct recorded calendar days in [rows]. */
internal fun countDistinctDays(rows: List<List<Any?>>, timeIndex: Int = 0): Int {
    if (timeIndex < 0) return 0
    return rows.mapNotNull { row ->
        row.getOrNull(timeIndex)?.let { profileRowDay(it) }
    }.toSet().size
}

internal data class LoadSurveyFilterResult(
    val rows: List<List<Any?>>,
    val rawRowCount: Int,
    val keptRowCount: Int,
    val distinctDays: Int,
    val usedGapFallback: Boolean
)

/**
 * Pure window filter for load-survey rows.
 *
 * Rows inside `[windowStartMs, windowEndMs]` are kept first. If that leaves
 * fewer than [requiredDays] distinct calendar days (the meter was powered off
 * during the requested window), the most recent [requiredDays] recorded days
 * in the whole buffer are retained instead. Unparseable rows are always kept.
 */
internal fun filterLoadSurveyRows(
    rows: List<List<Any?>>,
    timeIndex: Int,
    windowStartMs: Long,
    windowEndMs: Long,
    requiredDays: Int?,
    maxEntries: Int
): LoadSurveyFilterResult {
    if (timeIndex < 0) {
        return LoadSurveyFilterResult(rows, rows.size, rows.size, 0, false)
    }
    val unparseable = mutableListOf<List<Any?>>()
    val parsed = mutableListOf<Pair<Long, List<Any?>>>()
    rows.forEach { row ->
        val timestamp = profileRowTimestamp(row.getOrNull(timeIndex))
        if (timestamp == null) unparseable += row else parsed += timestamp to row
    }
    val inWindow = parsed.filter { (timestamp, _) -> timestamp in windowStartMs..windowEndMs }
    val inWindowDays = inWindow.map { profileDayKey(it.first) }.distinct().size
    var kept = inWindow
    var usedGapFallback = false
    if (requiredDays != null && inWindowDays < requiredDays && parsed.isNotEmpty()) {
        val recentDays = parsed
            .map { profileDayKey(it.first) }
            .distinct()
            .sortedDescending()
            .take(requiredDays)
            .toSet()
        kept = parsed.filter { profileDayKey(it.first) in recentDays }
        usedGapFallback = true
    }
    val ordered = kept.sortedBy { it.first }
    val capped = if (maxEntries > 0 && ordered.size > maxEntries) {
        ordered.takeLast(maxEntries)
    } else {
        ordered
    }
    val resultRows = capped.map { it.second } + unparseable
    val distinctDays = countDistinctDays(resultRows, timeIndex)
    return LoadSurveyFilterResult(
        rows = resultRows,
        rawRowCount = rows.size,
        keptRowCount = resultRows.size,
        distinctDays = distinctDays,
        usedGapFallback = usedGapFallback
    )
}

data class ParameterRow(
    val parameterName: String,
    val obisCode: String,
    val formattedValue: String,
    val unit: String = ""
)

data class RegisterReading(
    val parameterName: String,
    val obisCode: String,
    val reading: String,
    val unit: String,
    val captureTimestamp: String = ""
)

data class ProfileColumn(
    val parameterName: String,
    val obisCode: String,
    val unit: String = "",
    val attributeIndex: Int = 2
)

data class ProfileSection(
    val title: String,
    val profileObis: String,
    val columns: List<ProfileColumn>,
    val rows: List<List<String>>,
    val totalRows: Int = rows.size
)

private data class ProfileScaler(
    val multiplier: Double,
    val unit: DlmsUnit?
)

data class IdentitySection(
    val rows: List<ParameterRow>,
    val nameplate: ProfileSection? = null
) {
    fun merge(other: IdentitySection): IdentitySection {
        val existingObis = rows.map { it.obisCode }.toSet()
        return IdentitySection(
            rows = rows + other.rows.filter { it.obisCode !in existingObis },
            nameplate = other.nameplate ?: nameplate
        )
    }
}

data class TamperEventRow(
    val compartment: String = "N/A",
    val dateTime: String = "N/A",
    val eventCode: String = "N/A",
    val eventDescription: String = "N/A",
    val voltage: String = "N/A",
    val current: String = "N/A",
    val powerFactor: String = "N/A",
    val energy: String = "N/A",
    val kvah: String = "N/A",
    val neutralCurrent: String = "N/A"
)

data class MeterDataReport(
    val identity: IdentitySection? = null,
    val cumulativeEnergies: List<RegisterReading> = emptyList(),
    val instantaneous: ProfileSection? = null,
    val billing: ProfileSection? = null,
    val blockLoad: ProfileSection? = null,
    val dailyLoad: ProfileSection? = null,
    val tamperEvents: List<TamperEventRow> = emptyList(),
    val eventLogStatus: List<Pair<String, String>> = emptyList(),
    val errors: List<String> = emptyList()
) {
    val hasData: Boolean
        get() = identity != null || cumulativeEnergies.isNotEmpty() ||
            instantaneous != null || billing != null || blockLoad != null ||
            dailyLoad != null || tamperEvents.isNotEmpty() || eventLogStatus.isNotEmpty()

    fun merge(other: MeterDataReport): MeterDataReport = MeterDataReport(
        identity = when {
            identity == null -> other.identity
            other.identity == null -> identity
            else -> identity.merge(other.identity)
        },
        cumulativeEnergies = if (other.cumulativeEnergies.isNotEmpty()) other.cumulativeEnergies else cumulativeEnergies,
        instantaneous = other.instantaneous ?: instantaneous,
        billing = other.billing ?: billing,
        blockLoad = other.blockLoad ?: blockLoad,
        dailyLoad = other.dailyLoad ?: dailyLoad,
        tamperEvents = if (other.tamperEvents.isNotEmpty()) other.tamperEvents else tamperEvents,
        eventLogStatus = eventLogStatus + other.eventLogStatus,
        errors = errors + other.errors
    )
}

class Is15959DataReader(
    private val transport: OpticalUsbTransport,
    private val client: gurux.dlms.secure.GXDLMSSecureClient,
    packetTimeoutMs: Long = 3000L,
    private val onProgress: suspend (String) -> Unit = {},
    private val runtimeMetadata: Map<String, ObisMetadata> = emptyMap(),
    private val meterMake: String? = null,
    private val loadSurveySelection: LoadSurveySelection = LoadSurveySelection.RECENT
) {
    private var meterRtc: GXDateTime? = null
    private var meterTimeConventionChecked = false
    private var meterTimeConventionFlipped = false
    private var rangeTierDenied = false
    private var rangeVariantTierDenied = false
    private var entryTierDenied = false
    private var lastEventLogStatus: List<Pair<String, String>> = emptyList()
    val eventLogStatus: List<Pair<String, String>> get() = lastEventLogStatus
    private val executor = GuruxPacketExecutor(
        transport,
        client,
        packetTimeoutMs = packetTimeoutMs.coerceAtLeast(8000L)
    )

    suspend fun readIdentityAndNameplate(): IdentitySection {
        progress("Reading identity and nameplate...")
        val rows = mutableListOf<ParameterRow>()
        readTextObject("0.0.42.0.0.255", rows)
        readTextObject("0.0.96.1.0.255", rows)
        readTextObject("0.0.96.1.1.255", rows)
        readTextObject("0.0.96.1.4.255", rows)
        readTextObject("1.0.0.2.0.255", rows)
        runCatching {
            val clock = GXDLMSClock("0.0.1.0.0.255")
            val value = executor.readObject(clock, 2)
            acceptMeterRtc(value)
            addRow(rows, "0.0.1.0.0.255", value)
        }
        val nameplate = runCatching {
            val profile = Is15959Catalog.profileByName("Nameplate Profile")
            loadProfile(profile.dataObis, profile.name)
        }.getOrNull()
        return IdentitySection(rows, nameplate)
    }

    suspend fun readIdentityHeader(): IdentitySection {
        progress("Reading identity header...")
        val rows = mutableListOf<ParameterRow>()
        readTextObject("0.0.42.0.0.255", rows)
        readTextObject("0.0.96.1.0.255", rows)
        readTextObject("0.0.96.1.1.255", rows)
        runCatching { readMeterRtc() }
        return IdentitySection(rows)
    }

    suspend fun readCumulativeEnergies(): List<RegisterReading> {
        progress("Reading cumulative energy and maximum demand registers...")
        val definitions = listOf(
            "1.0.1.8.0.255",
            "1.0.9.8.0.255",
            "1.0.5.8.0.255",
            "1.0.6.8.0.255",
            "1.0.7.8.0.255",
            "1.0.8.8.0.255",
            "1.0.1.6.0.255",
            "1.0.9.6.0.255"
        )
        return definitions.mapNotNull { obis ->
            runCatching { readRegister(obis, obis.endsWith(".6.0.255")) }.getOrNull()
        }
    }

    suspend fun readInstantaneousProfile(): ProfileSection {
        progress("Reading instantaneous profile...")
        val profile = Is15959Catalog.profileByName("Instantaneous Profile")
        return loadProfile(profile.dataObis, "Instantaneous Parameters Snapshot", profile.scalerObis)
    }

    suspend fun readBillingProfile(): ProfileSection {
        progress("Reading billing history profile...")
        val profile = Is15959Catalog.profileByName("Billing Profile")
        return loadProfile(profile.dataObis, "Billing History Profile", profile.scalerObis)
    }

    suspend fun readBlockLoadSurvey(
        maxEntries: Int = BLOCK_LOAD_MAX_ENTRIES,
        selection: LoadSurveySelection = loadSurveySelection
    ): ProfileSection {
        progress("Reading block load survey (${selection.label})...")
        val profile = Is15959Catalog.profileByName("Block Load Profile")
        return loadRecentProfile(
            profile.dataObis,
            "Block Load Survey",
            maxEntries,
            profile.scalerObis,
            selection
        )
    }

    suspend fun readDailyLoadSurvey(
        maxEntries: Int = DAILY_LOAD_MAX_ENTRIES,
        selection: LoadSurveySelection = loadSurveySelection
    ): ProfileSection {
        progress("Reading daily load survey (${selection.label})...")
        val profile = Is15959Catalog.profileByName("Daily Load Profile")
        return loadRecentProfile(
            profile.dataObis,
            "Daily Load Survey",
            maxEntries,
            profile.scalerObis,
            selection
        )
    }

    suspend fun readTamperEventLogs(): List<TamperEventRow> {
        progress("Reading tamper and event logs...")
        val statuses = mutableListOf<Pair<String, String>>()
        val rows = Is15959Catalog.PROFILES
            .filter { it.dataObis.startsWith("0.0.99.98.") }
            .flatMap { profile ->
                runCatching { readTamperProfile(profile.name, profile.dataObis) }
                    .onSuccess { values -> statuses += profile.name to "${values.size} entries" }
                    .onFailure { error ->
                        if (isObjectUndefined(error)) {
                            statuses += profile.name to "not supported by this meter"
                            transport.log(
                                com.utility.dlmsreader.transport.TraceDirection.INFO,
                                "${profile.name}: object undefined; treating as not supported"
                            )
                        } else {
                            statuses += profile.name to "read failed: ${error.message ?: "unknown error"}"
                        }
                    }
                    .getOrDefault(emptyList())
            }
        lastEventLogStatus = statuses
        return rows
    }

    private fun isObjectUndefined(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is gurux.dlms.GXDLMSException && current.errorCode == 4) return true
            val message = current.message?.lowercase().orEmpty()
            if ("object undefined" in message || "objectundefined" in message) return true
            current = current.cause
        }
        return false
    }

    suspend fun readAllMeterData(): MeterDataReport {
        var report = MeterDataReport()
        val errors = mutableListOf<String>()

        suspend fun section(name: String, action: suspend () -> MeterDataReport) {
            runCatching { action() }
                .onSuccess { report = report.merge(it) }
                .onFailure {
                    val message = "$name unavailable: ${it.message ?: "unsupported or unreadable"}"
                    errors += message
                    transport.log(
                        com.utility.dlmsreader.transport.TraceDirection.ERROR,
                        "Section $name failed: ${it.message ?: "unsupported or unreadable"}"
                    )
                    progress(message)
                }
        }

        section("Identity") {
            MeterDataReport(identity = readIdentityAndNameplate())
        }
        section("Cumulative energy") {
            MeterDataReport(cumulativeEnergies = readCumulativeEnergies())
        }
        section("Instantaneous profile") {
            MeterDataReport(instantaneous = readInstantaneousProfile())
        }
        section("Billing profile") {
            MeterDataReport(billing = readBillingProfile())
        }
        section("Block load survey") {
            MeterDataReport(blockLoad = readBlockLoadSurvey())
        }
        section("Daily load survey") {
            MeterDataReport(dailyLoad = readDailyLoadSurvey())
        }
        section("Tamper logs") {
            MeterDataReport(
                tamperEvents = readTamperEventLogs(),
                eventLogStatus = eventLogStatus
            )
        }
        progress("Full meter extraction complete.")
        return report.copy(errors = report.errors + errors)
    }

    private suspend fun readTextObject(obis: String, rows: MutableList<ParameterRow>) {
        runCatching {
            val obj = GXDLMSData(obis)
            obj.setUIDataType(2, gurux.dlms.enums.DataType.STRING)
            addRow(rows, obis, executor.readObject(obj, 2))
        }
    }

    private suspend fun readRegister(obis: String, extended: Boolean): RegisterReading {
        val register = if (extended) GXDLMSExtendedRegister(obis) else GXDLMSRegister(obis)
        executor.readObject(register, 3)
        val rawRead = executor.readObjectWithRaw(register, 2)
        val raw = rawRead.value
        if (obis == "1.0.1.8.0.255") {
            transport.log(
                com.utility.dlmsreader.transport.TraceDirection.INFO,
                "OBIS $obis raw=${rawRead.raw} scaler=${register.scaler} unit=${register.unit}"
            )
        }
        if (register is GXDLMSExtendedRegister) {
            runCatching { executor.readObject(register, 5) }
        }
        val descriptor = descriptor(obis)
        val formatted = Is15959Formatter.formatValue(
            raw,
            scaler = 1.0,
            unit = register.unit,
            obis = obis,
            includeUnit = false,
            runtimeMetadata = runtimeMetadata,
            meterMake = meterMake
        )
        val capture = if (register is GXDLMSExtendedRegister) {
            if (isZeroValue(raw)) "—" else Is15959Formatter.formatValue(register.captureTime)
        } else ""
        return RegisterReading(
            parameterName = descriptor.label,
            obisCode = obis,
            reading = formatted,
            unit = descriptor.unit.ifEmpty { Is15959Formatter.unitSymbol(register.unit) },
            captureTimestamp = capture
        )
    }

    private suspend fun loadProfile(obis: String, title: String, scalerObis: String? = null): ProfileSection {
        val profile = GXDLMSProfileGeneric(obis)
        executor.readObject(profile, 3)
        configureRangeRestrictingObject(profile)
        progress("Profile $obis captureObjects count=${profile.captureObjects.size}, scaler=$scalerObis")
        val scalers = loadCompanionScalers(scalerObis ?: Is15959Catalog.profileByDataObis(obis).scalerObis)
        if (scalers.isEmpty()) loadCaptureScalers(profile)
        executor.readObject(profile, 2)
        return formatProfile(profile, title, scalers)
    }

    private suspend fun loadRecentProfile(
        obis: String,
        title: String,
        maxEntries: Int,
        scalerObis: String?,
        selection: LoadSurveySelection = LoadSurveySelection.RECENT
    ): ProfileSection {
        val profile = GXDLMSProfileGeneric(obis)
        executor.readObject(profile, 3)
        configureRangeRestrictingObject(profile)
        progress("Profile $obis captureObjects count=${profile.captureObjects.size}, scaler=$scalerObis")
        val scalers = loadCompanionScalers(scalerObis)
        if (scalers.isEmpty()) loadCaptureScalers(profile)

        var totalRows = profile.buffer.size
        var loaded = false
        var tierUsed = "None"
        val isBlockLoad = obis == "1.0.99.1.0.255"
        val requiredDays = selection.days
        // FULL_BUFFER needs no clock/range window; only day-window selections
        // read the meter RTC to anchor Selector 1.
        val window = if (loadSurveyNeedsWindow(selection)) {
            val endDate = readMeterRtc()
                ?: error("Meter RTC unavailable for selective range access.")
            loadSurveyWindow(selection, MeterTime.wallClock(endDate), isBlockLoad)
        } else {
            null
        }
        // The chip selection is authoritative: 2 Days = 192 entries even
        // though the legacy daily-load default was 400. The maxEntries
        // parameter remains for source compatibility with older callers.
        val effectiveMax = selection.maxEntries

        var bestRows = 0
        var bestDays = 0
        var bestBuffer: Array<Array<Any?>>? = null

        fun rememberBuffer(tier: String, snapshot: Boolean = true) {
            val rows = profile.buffer.size
            if (rows <= 0) return
            val days = countDistinctDays(profile)
            if (rows > bestRows || (rows == bestRows && days > bestDays)) {
                bestRows = rows
                bestDays = days
                tierUsed = tier
                loaded = true
                if (snapshot) {
                    bestBuffer = profile.buffer.map { rowValues(it).toTypedArray() }.toTypedArray()
                }
            }
        }

        fun restoreBest() {
            bestBuffer?.let { profile.setBuffer(it) }
        }

        fun currentDays(): Int = countDistinctDays(profile)

        if (selection == LoadSurveySelection.FULL_BUFFER) {
            // Full Buffer must never use Selector 1 anchored at today's
            // midnight: that only returns the newest day. Read Selector 0
            // (the complete buffer) first, with Selector 2 as fallback.
            progress("Tier1 range: skipped for Full Buffer (reading complete Selector 0 buffer)")
            try {
                progress("Tier3 full: requesting complete buffer")
                executor.readObject(profile, 2)
                progress("Tier3 full: success(${profile.buffer.size})")
                totalRows = profile.buffer.size
                rememberBuffer("Tier 3 Full Buffer", snapshot = false)
            } catch (error: Exception) {
                progress("Tier3 full: failed(${error.message ?: "unknown error"})")
            }
            if (!loaded && !entryTierDenied) {
                try {
                    val entriesRaw = executor.readObject(profile, 7)
                    val entriesInUse = (entriesRaw as? Number)?.toInt()?.takeIf { it > 0 }
                        ?: profile.entriesInUse
                    val requestCount = entriesInUse.takeIf { it > 0 } ?: maxEntries.coerceAtLeast(1)
                    progress("Tier2 entry: requesting index=1 count=$requestCount of $entriesInUse")
                    executor.readRowsByEntry(profile, 1, requestCount)
                    progress("Tier2 entry: success(${profile.buffer.size})")
                    totalRows = maxOf(entriesInUse, profile.buffer.size)
                    rememberBuffer("Tier 2 Entry")
                } catch (error: Exception) {
                    if (error is GXDLMSException) {
                        entryTierDenied = true
                        progress("Tier2 entry: denied(${error.errorCode})")
                    } else {
                        progress("Tier2 entry: failed(${error.message ?: "unknown error"})")
                    }
                    restoreBest()
                }
            }
            if (!loaded) {
                error("Load survey full-buffer read returned no rows for $obis")
            }
        } else {
            val surveyWindow = window
                ?: error("Load survey window unavailable for ${selection.label}")
            val startDate = GXDateTime(surveyWindow.start)
            val endRangeDate = GXDateTime(surveyWindow.end)
            if (!rangeTierDenied) {
                try {
                    progress("Tier1 range: requesting ${MeterTime.format(startDate)} to ${MeterTime.format(endRangeDate)}")
                    executor.readRowsByRange(profile, startDate, endRangeDate)
                    progress("Tier1 range: success(${profile.buffer.size})")
                    totalRows = profile.buffer.size
                    rememberBuffer("Tier 1 Range")
                } catch (error: GXDLMSException) {
                    rangeTierDenied = true
                    progress("Tier1 range: denied(${error.errorCode})")
                    if (error.errorCode == 3 && !meterTimeConventionFlipped && !rangeVariantTierDenied) {
                        val variantStart = GXDateTime(startDate).apply {
                            deviation = 0x8000
                            skip.add(DateTimeSkips.DEVIATION)
                        }
                        val variantEnd = GXDateTime(endRangeDate).apply {
                            deviation = 0x8000
                            skip.add(DateTimeSkips.DEVIATION)
                        }
                        try {
                            progress("Tier1 range variant: requesting deviation 0x8000")
                            executor.readRowsByRange(profile, variantStart, variantEnd)
                            progress("Tier1 range variant: success(${profile.buffer.size})")
                            totalRows = profile.buffer.size
                            rememberBuffer("Tier 1 Range")
                        } catch (variantError: GXDLMSException) {
                            rangeVariantTierDenied = true
                            progress("Tier1 range variant: denied(${variantError.errorCode})")
                        }
                    }
                }
            } else {
                progress("Tier1 range: skipped(denied earlier in session)")
            }

            // A successful range read is not proof that the whole window is
            // present: meters often retain only 1-2 days after a long power
            // outage. Compare distinct recorded days and probe older storage.
            var needsOlderRecords = loaded && requiredDays != null &&
                currentDays() < requiredDays
            if (needsOlderRecords) {
                progress(
                    "Tier1 range: only ${currentDays()} distinct day(s) for ${selection.label}; " +
                        "checking older pre-outage records"
                )
            }

            if ((!loaded || needsOlderRecords) && !entryTierDenied) {
                try {
                    val entriesRaw = executor.readObject(profile, 7)
                    val entriesInUse = (entriesRaw as? Number)?.toInt()?.takeIf { it > 0 }
                        ?: profile.entriesInUse
                    val requestCount = if (entriesInUse > 0) {
                        minOf(entriesInUse, effectiveMax)
                    } else {
                        effectiveMax
                    }
                    val index = if (entriesInUse > 0) {
                        maxOf(1, entriesInUse - requestCount + 1)
                    } else {
                        1
                    }
                    progress("Tier2 entry: requesting index=$index count=$requestCount of $entriesInUse")
                    executor.readRowsByEntry(profile, index, requestCount)
                    progress("Tier2 entry: success(${profile.buffer.size})")
                    totalRows = maxOf(totalRows, entriesInUse, profile.buffer.size)
                    rememberBuffer("Tier 2 Entry")
                    if (profile.buffer.size < bestRows) restoreBest()
                } catch (error: Exception) {
                    if (error is GXDLMSException) {
                        entryTierDenied = true
                        progress("Tier2 entry: denied(${error.errorCode})")
                    } else {
                        progress("Tier2 entry: failed(${error.message ?: "unknown error"})")
                    }
                    restoreBest()
                }
            } else if (!loaded) {
                progress("Tier2 entry: skipped(denied earlier in session)")
            }

            if (requiredDays != null) {
                needsOlderRecords = loaded && currentDays() < requiredDays
            }

            if (!loaded || needsOlderRecords) {
                try {
                    progress(
                        "Tier3 full: requesting complete buffer" +
                            if (needsOlderRecords) " for pre-outage history" else ""
                    )
                    executor.readObject(profile, 2)
                    progress("Tier3 full: success(${profile.buffer.size})")
                    totalRows = maxOf(totalRows, profile.buffer.size)
                    rememberBuffer("Tier 3 Full Buffer", snapshot = false)
                    if (profile.buffer.size < bestRows) restoreBest()
                } catch (error: Exception) {
                    progress("Tier3 full: failed(${error.message ?: "unknown error"})")
                    restoreBest()
                    if (!loaded) throw error
                }
            }
        }

        // Day-window selections (including 2 Days) honour their window even
        // when the meter answered via Selector 2 or the full buffer.
        if (window != null) {
            val before = profile.buffer.size
            val filterResult = filterBufferToWindow(profile, window, effectiveMax)
            progress(
                "Window filter: kept ${filterResult.keptRowCount} of $before rows across " +
                    "${filterResult.distinctDays} distinct day(s) for ${selection.label}" +
                    if (filterResult.usedGapFallback) " (power-off gap fallback)" else ""
            )
        }

        val retainedDays = countDistinctDays(profile)
        transport.log(
            com.utility.dlmsreader.transport.TraceDirection.INFO,
            "Reading Section: Load Survey Diagnostics — $title: " +
                "selection=${selection.label}, tier=$tierUsed, rawRows=$bestRows, " +
                "retainedRows=${profile.buffer.size}, retainedDays=$retainedDays"
        )
        return formatProfile(profile, title, scalers, totalRows)
    }

    /** Index of the capture object that carries the row timestamp, or -1. */
    private fun timeColumnIndex(profile: GXDLMSProfileGeneric): Int {
        val captures = profile.captureObjects
        val clock = captures.indexOfFirst { it.key is GXDLMSClock }
        if (clock >= 0) return clock
        val captureTime = captures.indexOfFirst { it.value.attributeIndex == 5 }
        if (captureTime >= 0) return captureTime
        return captures.indexOfFirst { it.key.logicalName == "0.0.1.0.0.255" }
    }

    private fun countDistinctDays(profile: GXDLMSProfileGeneric): Int {
        val index = timeColumnIndex(profile)
        if (index < 0) return 0
        return countDistinctDays(profile.buffer.map { rowValues(it) }, index)
    }

    /**
     * Keeps only rows inside the requested window. When the meter's storage
     * has a long power-off gap, the window can contain fewer days than the
     * operator asked for, so the most recent recorded days in the buffer are
     * retained instead. Rows whose timestamp cannot be parsed are always kept.
     */
    private fun filterBufferToWindow(
        profile: GXDLMSProfileGeneric,
        window: LoadSurveyWindow,
        maxEntries: Int
    ): LoadSurveyFilterResult {
        val index = timeColumnIndex(profile)
        if (index < 0) {
            val rawSize = profile.buffer.size
            val kept = if (rawSize > maxEntries) {
                profile.buffer.takeLast(maxEntries.coerceAtLeast(1))
            } else {
                profile.buffer.toList()
            }
            val resultRows = kept.map { rowValues(it) }
            profile.setBuffer(resultRows.map { it.toTypedArray() }.toTypedArray())
            return LoadSurveyFilterResult(resultRows, rawSize, resultRows.size, 0, false)
        }
        val rows = profile.buffer.map { rowValues(it) }
        val result = filterLoadSurveyRows(
            rows = rows,
            timeIndex = index,
            windowStartMs = window.start.timeInMillis,
            windowEndMs = window.end.timeInMillis,
            requiredDays = window.daysBack,
            maxEntries = maxEntries
        )
        profile.setBuffer(result.rows.map { it.toTypedArray() }.toTypedArray())
        return result
    }

    private fun configureRangeRestrictingObject(profile: GXDLMSProfileGeneric) {
        if (profile.captureObjects.isEmpty()) return
        val first = profile.captureObjects.first().key
        if (first.logicalName == "0.0.1.0.0.255") {
            profile.setSortObject(first)
            profile.setSortObjectAttributeIndex(2)
            profile.setSortObject(0)
        }
    }

    private suspend fun readMeterRtc(): GXDateTime? {
        meterRtc?.let { return it }
        val clock = GXDLMSClock("0.0.1.0.0.255")
        val value = executor.readObject(clock, 2)
        return acceptMeterRtc(value)
    }

    private fun acceptMeterRtc(value: Any?): GXDateTime? {
        val dateTime = value as? GXDateTime ?: return null
        meterRtc = dateTime
        if (!meterTimeConventionChecked) {
            meterTimeConventionChecked = true
            if (dateTime.deviation == 330 && !dateTime.skip.contains(DateTimeSkips.DEVIATION)) {
                client.setUseUtc2NormalTime(true)
                meterTimeConventionFlipped = true
                transport.log(
                    com.utility.dlmsreader.transport.TraceDirection.INFO,
                    "Meter RTC uses positive IST deviation (+330); enabled Gurux UTC-to-normal time conversion."
                )
            }
        }
        return dateTime
    }

    private suspend fun loadCaptureScalers(profile: GXDLMSProfileGeneric) {
        for (entry in profile.captureObjects) {
            val obj = entry.key
            if (obj is GXDLMSRegister) {
                runCatching { executor.readObject(obj, 3) }
            } else if (obj is GXDLMSDemandRegister) {
                runCatching { executor.readObject(obj, 4) }
            }
        }
    }

    private suspend fun loadCompanionScalers(obis: String?): Map<String, ProfileScaler> {
        if (obis == null) return emptyMap()
        return runCatching {
            val scalerProfile = GXDLMSProfileGeneric(obis)
            executor.readObject(scalerProfile, 3)
            executor.readObject(scalerProfile, 2)
            val row = scalerProfile.buffer.lastOrNull()?.let { rowValues(it) }.orEmpty()
            scalerProfile.captureObjects.mapIndexedNotNull { index, entry ->
                val structure = row.getOrNull(index)?.let { rowValues(it) } ?: return@mapIndexedNotNull null
                val exponent = (structure.getOrNull(0) as? Number)?.toInt() ?: return@mapIndexedNotNull null
                val unitValue = (structure.getOrNull(1) as? Number)?.toInt()
                val unit = unitValue?.let { DlmsUnit.forValue(it) }
                entry.key.logicalName to ProfileScaler(10.0.pow(exponent), unit)
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun formatProfile(
        profile: GXDLMSProfileGeneric,
        title: String,
        scalers: Map<String, ProfileScaler> = emptyMap(),
        totalRows: Int = profile.buffer.size
    ): ProfileSection {
        val captures = profile.captureObjects
        val objects = captures.map { it.key }
        val hasCaptureTime = captures.any { it.value.attributeIndex == 5 }
        val columns = captures.map { capture ->
            val obj = capture.key
            val obis = obj.logicalName
            val descriptor = descriptor(obis)
            val profileScaler = scalers[obis]
            val attributeIndex = capture.value.attributeIndex
            val label = when {
                attributeIndex == 5 -> "${descriptor.label} — Capture Time"
                attributeIndex == 2 && hasCaptureTime && captures.any {
                    it.key.logicalName == obis && it.value.attributeIndex == 5
                } -> "${descriptor.label} — Value"
                else -> descriptor.label
            }
            ProfileColumn(
                parameterName = label,
                obisCode = obis,
                unit = if (attributeIndex == 5) "" else descriptor.unit.ifEmpty {
                    profileScaler?.unit?.let { Is15959Formatter.unitSymbol(it) }
                        ?: (obj as? GXDLMSRegister)?.let { Is15959Formatter.unitSymbol(it.unit) }
                        ?: (obj as? GXDLMSDemandRegister)?.let { Is15959Formatter.unitSymbol(it.unit) }
                        ?: ""
                },
                attributeIndex = attributeIndex
            )
        }
        val rows = profile.buffer.map { row ->
            val values = rowValues(row)
            objects.indices.map { index ->
                val capture = captures[index].value
                val pairedValueIndex = objects.indices.firstOrNull { candidate ->
                    captures[candidate].value.attributeIndex == 2 &&
                        captures[candidate].key.logicalName == objects[index].logicalName
                }
                formatProfileValue(
                    objects[index],
                    values.getOrNull(index),
                    scalers[objects[index].logicalName],
                    zeroSentinel = capture.attributeIndex == 5 &&
                        pairedValueIndex != null && isZeroValue(values.getOrNull(pairedValueIndex))
                )
            }
        }
        return ProfileSection(title, profile.logicalName, columns, rows, totalRows)
    }

    private suspend fun readTamperProfile(compartment: String, obis: String): List<TamperEventRow> {
        val profile = GXDLMSProfileGeneric(obis)
        executor.readObject(profile, 3)
        val scalers = loadCompanionScalers(Is15959Catalog.profileByDataObis(obis).scalerObis)
        if (scalers.isEmpty()) loadCaptureScalers(profile)
        executor.readObject(profile, 2)
        val formatted = formatProfile(profile, "$compartment Tamper Events", scalers)
        val objects = profile.captureObjects.map { it.key }
        val dateIndex = objects.indexOfFirst { it is GXDLMSClock || descriptor(it.logicalName).label.contains("time", true) }
        val eventIndex = objects.indexOfFirst {
            descriptor(it.logicalName).label.contains("event", true) ||
                descriptor(it.logicalName).label.contains("code", true)
        }
        val voltageIndex = objects.indexOfFirst { descriptor(it.logicalName).unit == "V" }
        val currentIndex = objects.indexOfFirst { descriptor(it.logicalName).unit == "A" }
        val pfIndex = objects.indexOfFirst {
            it.logicalName == "1.0.13.7.0.255" || descriptor(it.logicalName).unit == "PF"
        }
        val energyIndex = objects.indexOfFirst { it.logicalName == "1.0.1.8.0.255" }
        val kvahIndex = objects.indexOfFirst {
            descriptor(it.logicalName).unit.contains("VAh", true)
        }
        val neutralCurrentIndex = objects.indexOfFirst {
            it.logicalName == "1.0.91.7.0.255"
        }

        return profile.buffer.mapIndexed { rowIndex, rawRow ->
            val raw = rowValues(rawRow)
            val values = formatted.rows.getOrNull(rowIndex).orEmpty()
            val code = raw.getOrNull(eventIndex).toIntValue()
            TamperEventRow(
                compartment = compartment,
                dateTime = values.valueAt(dateIndex),
                eventCode = code?.toString() ?: values.valueAt(eventIndex),
                eventDescription = code?.let { Is15959Formatter.decodeTamperEvent(it) } ?: "Unknown Event",
                voltage = values.valueAt(voltageIndex),
                current = values.valueAt(currentIndex),
                powerFactor = values.valueAt(pfIndex),
                energy = values.valueAt(energyIndex),
                kvah = values.valueAt(kvahIndex),
                neutralCurrent = values.valueAt(neutralCurrentIndex)
            )
        }
    }

    private fun formatProfileValue(
        obj: GXDLMSObject,
        raw: Any?,
        profileScaler: ProfileScaler?,
        zeroSentinel: Boolean = false
    ): String {
        if (zeroSentinel) return "—"
        if (raw is GXDateTime || raw is Date || (raw is ByteArray && raw.size == 12)) {
            return Is15959Formatter.formatCompactDateTime(raw)
        }
        val register = obj as? GXDLMSRegister
        val demandRegister = obj as? GXDLMSDemandRegister
        val unit = profileScaler?.unit ?: register?.unit ?: demandRegister?.unit
        val value = Is15959Formatter.formatValue(
            raw,
            scaler = profileScaler?.multiplier ?: 1.0,
            unit = unit,
            obis = obj.logicalName,
            includeUnit = false,
            runtimeMetadata = runtimeMetadata,
            meterMake = meterMake
        )
        return value
    }

    private fun isZeroValue(raw: Any?): Boolean = when (raw) {
        is Number -> raw.toDouble() == 0.0
        is ByteArray -> raw.all { it.toInt() == 0 }
        else -> raw?.toString()?.trim()?.toDoubleOrNull() == 0.0
    }

    private fun addRow(rows: MutableList<ParameterRow>, obis: String, raw: Any?) {
        val descriptor = descriptor(obis)
        rows += ParameterRow(
            descriptor.label,
            obis,
            Is15959Formatter.formatValue(
                raw,
                obis = obis,
                includeUnit = false,
                runtimeMetadata = runtimeMetadata,
                meterMake = meterMake
            ),
            descriptor.unit
        )
    }

    private fun descriptor(obis: String): ObisDescriptor =
        Is15959Formatter.getObisLabelAndUnit(obis, runtimeMetadata, meterMake)

    private suspend fun progress(message: String) {
        onProgress(message)
        transport.log(com.utility.dlmsreader.transport.TraceDirection.INFO, message)
    }

    private fun rowValues(row: Any?): List<Any?> = when (row) {
        is Array<*> -> row.toList()
        is Iterable<*> -> row.toList()
        null -> emptyList()
        else -> listOf(row)
    }

    private fun Any?.toIntValue(): Int? = when (this) {
        is Number -> toInt()
        is ByteArray -> String(this, Charsets.US_ASCII).trim().toIntOrNull()
        else -> this?.toString()?.trim()?.toIntOrNull()
    }

    private fun List<String>.valueAt(index: Int): String = if (index >= 0) getOrNull(index) ?: "N/A" else "N/A"
}
