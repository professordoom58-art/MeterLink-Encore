package com.utility.dlmsreader.dlms

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Environment
import androidx.core.content.FileProvider
import com.utility.dlmsreader.transport.TraceDirection
import com.utility.dlmsreader.transport.TraceCounters
import com.utility.dlmsreader.transport.TraceLog
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MeterLinkExportBundle(
    val pdf: File,
    val hexLog: File
)

class MeterLinkPdfExporter(private val context: Context) {
    companion object {
        private const val PAGE_WIDTH = 595
        private const val PAGE_HEIGHT = 842
        private const val LEFT = 36f
        private const val RIGHT = 559f
        private const val BOTTOM = 780f
        private val NAVY = Color.rgb(30, 58, 138)
        private val LIGHT_ROW = Color.rgb(248, 250, 252)
        private val BORDER = Color.rgb(203, 213, 225)
        private val TIMESTAMP_PATTERN =
            Regex("""\d{1,4}[-/.]\d{1,2}[-/.]\d{1,4}\s+\d{1,2}:\d{2}(:\d{2})?""")
    }

    fun export(report: MeterDataReport): File {
        return exportBundle(report, emptyList()).pdf
    }

    fun exportBundle(
        report: MeterDataReport,
        traceLogs: List<TraceLog>,
        counters: TraceCounters? = null,
        droppedLineCount: Long = 0
    ): MeterLinkExportBundle {
        require(report.hasData) { "No meter data is available for export." }
        val generated = SimpleDateFormat("dd-MMM-yyyy HH:mm:ss", Locale.ENGLISH).format(Date())
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).format(Date())
        val serial = identityValue(report, "0.0.96.1.0.255")
            .ifBlank { "Meter" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: context.filesDir
        directory.mkdirs()
        val file = File(directory, "MeterLink_Report_${serial}_$stamp.pdf")

        val layout = ReportLayout(
            generated = generated,
            serial = serial,
            logicalDevice = identityValue(report, "0.0.42.0.0.255"),
            manufacturer = identityValue(report, "0.0.96.1.1.255")
        )
        buildReport(layout, report, traceLogs, counters, droppedLineCount)

        val document = PdfDocument()
        try {
            layout.render(document)
            FileOutputStream(file).use { document.writeTo(it) }
        } finally {
            // Always release the native document handle, even if rendering or
            // writing throws part-way through a large report.
            document.close()
        }
        val hexLog = File(directory, "MeterLink_HexLog_${serial}_$stamp.txt")
        writeHexLog(hexLog, traceLogs, generated, serial, counters, droppedLineCount)
        return MeterLinkExportBundle(file, hexLog)
    }

    fun exportHexLog(
        report: MeterDataReport,
        traceLogs: List<TraceLog>,
        counters: TraceCounters? = null,
        droppedLineCount: Long = 0
    ): File {
        val generated = SimpleDateFormat("dd-MMM-yyyy HH:mm:ss", Locale.ENGLISH).format(Date())
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).format(Date())
        val serial = identityValue(report, "0.0.96.1.0.255")
            .ifBlank { "Meter" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        directory.mkdirs()
        return File(directory, "MeterLink_HexLog_${serial}_$stamp.txt").also {
            writeHexLog(it, traceLogs, generated, serial, counters, droppedLineCount)
        }
    }

    fun share(file: File) {
        shareFile(file, "application/pdf", "Share MeterLink PDF Report")
    }

    fun shareText(file: File) {
        shareFile(file, "text/plain", "Share MeterLink Hex Log")
    }

    private fun shareFile(file: File, mimeType: String, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun buildReport(
        layout: ReportLayout,
        report: MeterDataReport,
        traceLogs: List<TraceLog>,
        counters: TraceCounters?,
        droppedLineCount: Long
    ) {
        report.identity?.let { identity ->
            layout.section("Section 1: Meter Identity & Nameplate Summary")
            layout.table(
                listOf("Parameter", "OBIS Code", "Value", "Unit"),
                identity.rows.map { listOf(it.parameterName, it.obisCode, it.formattedValue, it.unit) }
            )
            identity.nameplate?.let {
                drawProfile(layout, it, identity.rows.map { row -> row.obisCode }.toSet())
            }
        }

        if (report.cumulativeEnergies.isNotEmpty()) {
            layout.section("Section 2: Cumulative Energy & Maximum Demand Registers")
            layout.table(
                listOf("Parameter", "OBIS Code", "Reading", "Unit", "Capture Timestamp"),
                report.cumulativeEnergies.map {
                    listOf(it.parameterName, it.obisCode, it.reading, it.unit, it.captureTimestamp)
                }
            )
        }
        report.instantaneous?.let {
            layout.section("Section 3: Instantaneous Parameters Snapshot")
            drawProfile(layout, it)
        }
        report.billing?.let {
            layout.section("Section 4: Billing History Profile")
            drawBillingProfile(layout, it)
        }
        report.dailyLoad?.let {
            layout.section("Section 5: Daily Load Survey Profile")
            drawWideProfile(layout, it)
        }
        report.blockLoad?.let {
            layout.section("Section 6: Block Load Survey Profile")
            drawWideProfile(layout, it)
        }
        if (report.tamperEvents.isNotEmpty() || report.eventLogStatus.isNotEmpty()) {
            layout.section("Section 7: Tamper & Event Logs")
            if (report.eventLogStatus.isNotEmpty()) {
                // MeterDataReport.merge() concatenates status lists, so re-running
                // the tamper module (or merging it into a prior report) can carry
                // the same seven compartments twice. Render each compartment once.
                val statusRows = report.eventLogStatus
                    .distinctBy { it.first.trim().lowercase(Locale.ENGLISH) }
                    .map { listOf(it.first, it.second) }
                layout.table(listOf("Compartment", "Status"), statusRows)
            }
            report.tamperEvents.groupBy { it.compartment }.forEach { (compartment, rows) ->
                val compact = rows.all { row ->
                    listOf(row.voltage, row.current, row.powerFactor, row.energy, row.kvah, row.neutralCurrent)
                        .all { it == "N/A" }
                }
                val headers = if (compact) {
                    listOf("Compartment", "Date/Time", "Event Code", "Event Description")
                } else {
                    listOf("Compartment", "Date/Time", "Event Code", "Event Description", "V", "I", "PF", "kWh", "kVAh", "Neutral I")
                }
                val values = rows.map { row ->
                    if (compact) {
                        listOf(row.compartment, row.dateTime, row.eventCode, row.eventDescription)
                    } else {
                        listOf(row.compartment, row.dateTime, row.eventCode, row.eventDescription, row.voltage, row.current, row.powerFactor, row.energy, row.kvah, row.neutralCurrent)
                    }
                }
                layout.subsection(compartment)
                layout.table(headers, values)
            }
        }
        if (report.errors.isNotEmpty()) {
            layout.section("Read Notes")
            layout.table(listOf("Section", "Status"), report.errors.map { listOf("MeterLink", it) })
        }
        layout.section("Section 8: Diagnostic Protocol & Execution Log (Summary)")
        layout.table(listOf("Category", "Details"), diagnosticRows(traceLogs, counters, droppedLineCount))
    }

    private fun drawProfile(layout: ReportLayout, section: ProfileSection) {
        drawProfile(layout, section, emptySet())
    }

    private fun drawProfile(layout: ReportLayout, section: ProfileSection, excludedObis: Set<String>) {
        val included = section.columns.withIndex().filter { it.value.obisCode !in excludedObis }
        val rows = section.rows.flatMap { row ->
            included.map { indexed ->
                val column = indexed.value
                listOf(column.parameterName, column.obisCode, row.getOrNull(indexed.index) ?: "N/A", column.unit)
            }
        }
        if (rows.isNotEmpty()) layout.table(listOf("Parameter", "OBIS Code", "Value", "Unit"), rows)
    }

    private fun drawWideProfile(layout: ReportLayout, section: ProfileSection) {
        val headers = section.columns.map { it.parameterName }
        if (headers.isEmpty()) {
            layout.table(listOf("Profile", "Status"), listOf(listOf(section.profileObis, "No rows returned")))
        } else {
            drawProfileChunks(layout, section, section.columns.indices.toList(), section.title)
            if (section.rows.size < section.totalRows) {
                val oldestShown = section.rows.firstOrNull()?.firstOrNull() ?: "N/A"
                layout.note("Showing ${section.rows.size} of ${section.totalRows} entries (oldest shown: $oldestShown)")
            }
            if (section.title.contains("Block Load", ignoreCase = true)) {
                layout.note("Timestamps are interval-end. Zero rows before a power restoration are meter zero-fill.")
            }
        }
    }

    private fun drawBillingProfile(layout: ReportLayout, section: ProfileSection) {
        if (section.columns.isEmpty()) {
            layout.table(listOf("Profile", "Status"), listOf(listOf(section.profileObis, "No rows returned")))
        } else {
            drawProfileChunks(layout, section, section.columns.indices.toList(), "Billing History Profile", billing = true)
        }
    }

    private fun drawSelectedProfile(
        layout: ReportLayout,
        section: ProfileSection,
        selected: List<Pair<Int, String>>
    ) {
        val headers = selected.map { it.second }
        val rows = section.rows.map { row -> selected.map { row.getOrNull(it.first) ?: "N/A" } }
        layout.table(headers, rows)
    }

    private fun drawProfileChunks(
        layout: ReportLayout,
        section: ProfileSection,
        indexes: List<Int>,
        title: String,
        billing: Boolean = false
    ) {
        val units = mutableListOf<List<Int>>()
        var position = 0
        while (position < indexes.size) {
            val index = indexes[position]
            val column = section.columns[index]
            val next = indexes.getOrNull(position + 1)?.let { section.columns[it] }
            if (column.attributeIndex == 2 && next != null && next.attributeIndex == 5 && next.obisCode == column.obisCode) {
                units += listOf(index, indexes[position + 1])
                position += 2
            } else {
                units += listOf(index)
                position++
            }
        }

        val chunks = mutableListOf<MutableList<Int>>()
        var current = mutableListOf<Int>()
        units.forEach { unit ->
            if (current.isNotEmpty() && current.size + unit.size > 7) {
                chunks += current
                current = mutableListOf()
            }
            current.addAll(unit)
        }
        if (current.isNotEmpty()) chunks += current
        if (chunks.size > 1 && chunks.last().size == 1) {
            val previous = chunks[chunks.lastIndex - 1].toMutableList()
            val moveCount = if (previous.size >= 2) {
                val valueColumn = section.columns[previous[previous.lastIndex - 1]]
                val timeColumn = section.columns[previous.last()]
                if (valueColumn.attributeIndex == 2 && timeColumn.attributeIndex == 5 &&
                    valueColumn.obisCode == timeColumn.obisCode
                ) 2 else 1
            } else 1
            if (previous.size > moveCount) {
                val moved = previous.takeLast(moveCount)
                repeat(moveCount) { previous.removeAt(previous.lastIndex) }
                chunks[chunks.lastIndex - 1] = previous
                chunks[chunks.lastIndex].addAll(0, moved)
            }
        }

        // Pin column 0 (Billing Date / Real-Time Clock) as the first column of every split part
        val pinIndex = 0
        if (indexes.contains(pinIndex)) {
            for (i in chunks.indices) {
                val chunk = chunks[i]
                if (!chunk.contains(pinIndex)) {
                    chunk.add(0, pinIndex)
                } else if (chunk.first() != pinIndex) {
                    // removeAt, not remove: on a MutableList<Int> remove(Int) deletes
                    // the element equal to that value, which shifts every column.
                    chunk.removeAt(chunk.indexOf(pinIndex))
                    chunk.add(0, pinIndex)
                }
            }
        }

        chunks.forEachIndexed { chunkIndex, chunk ->
            val chunkTitle = if (chunks.size == 1) title else "$title (Part ${chunkIndex + 1} of ${chunks.size})"
            layout.subsection(chunkTitle)
            val selected = chunk.map { index ->
                val column = section.columns[index]
                // TODO: confirm code 65 against L&T manual; reset values remain unchanged.
                val label = if (billing && column.obisCode == "0.0.94.91.14.255" && column.attributeIndex == 2) {
                    "Power-On Duration in Period (min)"
                } else column.parameterName
                index to label
            }
            drawSelectedProfile(layout, section, selected)
        }
    }

    private fun diagnosticRows(
        traceLogs: List<TraceLog>,
        counters: TraceCounters? = null,
        droppedLineCount: Long = 0
    ): List<List<String>> {
        val counts = TraceDirection.entries.associateWith { direction ->
            traceLogs.count { it.direction == direction }
        }
        val tx = counters?.tx ?: (counts[TraceDirection.TX] ?: 0)
        val rx = counters?.rx ?: (counts[TraceDirection.RX] ?: 0)
        val info = counters?.info ?: (counts[TraceDirection.INFO] ?: 0)
        val error = counters?.error ?: (counts[TraceDirection.ERROR] ?: 0)
        val rows = mutableListOf(
            listOf(
                "Counters (full session)",
                "TX=$tx, RX=$rx, INFO=$info, ERROR=$error"
            )
        )
        rows += listOf("Log lines retained", "${traceLogs.size} (${droppedLineCount} dropped)")
        traceLogs.asReversed()
            .filter { it.direction == TraceDirection.INFO && it.hexOrMessage.startsWith("Reading Section:") }
            .distinctBy { it.hexOrMessage }
            .forEach { rows += listOf("Section", it.hexOrMessage) }
        traceLogs.asReversed()
            .filter { it.direction == TraceDirection.ERROR }
            .take(80)
            .forEach { rows += listOf("Error / OBIS", it.hexOrMessage) }
        return rows
    }

    private fun writeHexLog(
        file: File,
        traceLogs: List<TraceLog>,
        generated: String,
        serial: String,
        counters: TraceCounters? = null,
        droppedLineCount: Long = 0
    ) {
        val timeFormat = SimpleDateFormat("dd-MMM-yyyy HH:mm:ss.SSS", Locale.ENGLISH)
        file.bufferedWriter().use { writer ->
            writer.appendLine("MeterLink Hex Terminal Log")
            writer.appendLine("Generated (phone time): $generated")
            writer.appendLine("Serial: $serial")
            val counts = counters ?: TraceCounters(
                tx = traceLogs.count { it.direction == TraceDirection.TX }.toLong(),
                rx = traceLogs.count { it.direction == TraceDirection.RX }.toLong(),
                info = traceLogs.count { it.direction == TraceDirection.INFO }.toLong(),
                error = traceLogs.count { it.direction == TraceDirection.ERROR }.toLong()
            )
            writer.appendLine("Counters (full session): TX=${counts.tx}, RX=${counts.rx}, INFO=${counts.info}, ERROR=${counts.error}")
            writer.appendLine("Log lines retained: ${traceLogs.size} ($droppedLineCount dropped)")
            writer.appendLine("Timestamps: phone time")
            writer.appendLine()
            traceLogs.asReversed().forEach { log ->
                writer.appendLine("${timeFormat.format(Date(log.timestampMs))} (phone time) [${log.direction}] ${log.hexOrMessage}")
            }
        }
    }

    private fun identityValue(report: MeterDataReport, obis: String): String =
        report.identity?.rows?.firstOrNull { it.obisCode == obis }?.formattedValue ?: ""

    private class ReportLayout(
        private val generated: String,
        private val serial: String,
        private val logicalDevice: String,
        private val manufacturer: String
    ) {
        private sealed class Op {
            data class Text(
                val value: String,
                val x: Float,
                val baseline: Float,
                val size: Float,
                val color: Int,
                val bold: Boolean = false,
                val align: Paint.Align = Paint.Align.LEFT
            ) : Op()

            data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val color: Int) : Op()
            data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float, val color: Int, val fill: Boolean) : Op()
        }

        private val pages = mutableListOf<MutableList<Op>>()
        private var current = mutableListOf<Op>()
        private var y = 104f

        init {
            pages += current
        }

        fun section(title: String) {
            ensure(34f)
            add(Op.Text(title, LEFT, y + 18f, 13f, NAVY, true))
            y += 32f
        }

        fun subsection(title: String) {
            ensure(26f)
            add(Op.Text(title, LEFT, y + 14f, 10f, NAVY, true))
            y += 22f
        }

        fun note(text: String) {
            ensure(22f)
            add(Op.Text(text, LEFT, y + 12f, 8f, Color.DKGRAY, false))
            y += 18f
        }

        fun table(headers: List<String>, rows: List<List<String>>) {
            if (headers.isEmpty()) return
            currentTableHeaders = headers
            val widths = widths(headers.size)
            tableHeader(headers, widths)
            rows.forEachIndexed { rowIndex, row ->
                val numeric = headers.indices.map { index ->
                    (row.getOrNull(index) ?: "").matches(Regex("[-+]?([0-9][0-9,]*)(\\.[0-9]+)?(.*)"))
                }
                val fitted = headers.indices.map { index ->
                    val value = row.getOrNull(index) ?: ""
                    when {
                        // Check timestamps before numeric: the numeric regex also
                        // accepts "12-12-23 00:00" because of its trailing (.*).
                        TIMESTAMP_PATTERN.matches(value.trim()) ->
                            fitTimestamp(value, widths[index] - 8f)
                        numeric[index] -> fitNumeric(value, widths[index] - 8f)
                        else -> null
                    }
                }
                val wrapped = headers.indices.map { index ->
                    fitted[index]?.let { listOf(it.first) }
                        ?: wrapText(row.getOrNull(index) ?: "", widths[index] - 8f, 8f)
                }
                val rowHeight = maxOf(20f, (wrapped.maxOfOrNull { it.size } ?: 1) * 10f + 8f)
                if (y + rowHeight > BOTTOM) {
                    newPage()
                    tableHeader(headers, widths)
                }
                val top = y
                add(Op.Rect(LEFT, top, RIGHT, top + rowHeight, if (rowIndex % 2 == 0) LIGHT_ROW else Color.WHITE, true))
                var x = LEFT
                headers.forEachIndexed { index, _ ->
                    val value = row.getOrNull(index) ?: ""
                    val align = if (numeric[index] && index > 0) Paint.Align.RIGHT else Paint.Align.LEFT
                    val textSize = fitted[index]?.second ?: 8f
                    wrapped[index].forEachIndexed { lineIndex, line ->
                        val textX = if (align == Paint.Align.RIGHT) x + widths[index] - 4f else x + 4f
                        add(Op.Text(line, textX, top + 14f + lineIndex * 10f, textSize, Color.rgb(30, 41, 59), false, align))
                    }
                    add(Op.Rect(x, top, x + widths[index], top + rowHeight, BORDER, false))
                    x += widths[index]
                }
                y += rowHeight
            }
            y += 8f
        }

        fun render(document: PdfDocument) {
            pages.forEachIndexed { index, ops ->
                val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create()
                val page = document.startPage(pageInfo)
                val canvas = page.canvas
                drawHeader(canvas)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                ops.forEach { op ->
                    when (op) {
                        is Op.Text -> drawText(canvas, paint, op)
                        is Op.Line -> {
                            paint.color = op.color
                            paint.strokeWidth = 1f
                            canvas.drawLine(op.x1, op.y1, op.x2, op.y2, paint)
                        }
                        is Op.Rect -> {
                            paint.color = op.color
                            paint.style = if (op.fill) Paint.Style.FILL else Paint.Style.STROKE
                            paint.strokeWidth = 1f
                            canvas.drawRect(op.left, op.top, op.right, op.bottom, paint)
                            paint.style = Paint.Style.FILL
                        }
                    }
                }
                paint.color = Color.DKGRAY
                paint.textSize = 8f
                canvas.drawText("MeterLink  |  Page ${index + 1} of ${pages.size}", LEFT, 818f, paint)
                document.finishPage(page)
            }
        }

        private fun tableHeader(headers: List<String>, widths: List<Float>) {
            val wrapped = headers.mapIndexed { index, header -> wrapText(header, widths[index] - 8f, 8f, bold = true) }
            val height = maxOf(22f, (wrapped.maxOfOrNull { it.size } ?: 1) * 10f + 8f)
            ensure(height)
            val top = y
            add(Op.Rect(LEFT, top, RIGHT, top + height, NAVY, true))
            var x = LEFT
            headers.forEachIndexed { index, _ ->
                wrapped[index].forEachIndexed { lineIndex, line ->
                    add(Op.Text(line, x + 4f, top + 14f + lineIndex * 10f, 8f, Color.WHITE, true))
                }
                add(Op.Rect(x, top, x + widths[index], top + height, BORDER, false))
                x += widths[index]
            }
            y += height
        }

        private fun widths(count: Int): List<Float> {
            if (count == 0) return emptyList()
            val weights = List(count) { index ->
                val header = currentTableHeaders[index].lowercase(Locale.ENGLISH)
                when {
                    // Column 0 holds the profile timestamp; give it extra room so
                    // "dd-MM-yy HH:mm" never breaks across lines.
                    index == 0 && ("date" in header || "time" in header) -> 2.2f
                    "date" in header || "time" in header -> 1.6f
                    "obis" in header || "code" in header || "unit" in header -> 0.6f
                    header in setOf("v", "i", "pf") -> 0.6f
                    header in setOf("kwh", "kvah", "kvarh", "neutral i") -> 0.85f
                    "description" in header -> 1.3f
                    "parameter" in header -> 1.35f
                    else -> 1.0f
                }
            }
            val unit = (RIGHT - LEFT) / weights.sum()
            return weights.map { it * unit }
        }

        private var currentTableHeaders: List<String> = emptyList()

        private fun ensure(height: Float) {
            if (y + height > BOTTOM) newPage()
        }

        private fun newPage() {
            current = mutableListOf()
            pages += current
            y = 104f
        }

        private fun add(op: Op) {
            current += op
        }

        private fun drawHeader(canvas: Canvas) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = NAVY
            canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), 48f, paint)
            paint.color = Color.WHITE
            paint.textSize = 14f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("MeterLink — IS 15959 DLMS Meter Reading Report", LEFT, 29f, paint)
            paint.color = Color.rgb(51, 65, 85)
            paint.textSize = 8f
            paint.typeface = Typeface.DEFAULT
            canvas.drawText("Generated (phone time): $generated    Serial: $serial    LDN: $logicalDevice    Manufacturer: $manufacturer", LEFT, 65f, paint)
            paint.color = BORDER
            canvas.drawLine(LEFT, 76f, RIGHT, 76f, paint)
        }

        private fun drawText(canvas: Canvas, paint: Paint, op: Op.Text) {
            paint.color = op.color
            paint.textSize = op.size
            paint.typeface = if (op.bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            paint.textAlign = op.align
            canvas.drawText(op.value, op.x, op.baseline, paint)
            paint.textAlign = Paint.Align.LEFT
        }

        private fun wrapText(value: String, width: Float, size: Float, bold: Boolean = false): List<String> {
            val safeWidth = width.coerceAtLeast(10f)
            val lines = mutableListOf<String>()
            value.ifEmpty { " " }.split('\n').forEach { paragraph ->
                if (paragraph.isEmpty()) {
                    lines += ""
                    return@forEach
                }
                var line = ""
                paragraph.split(Regex("\\s+")).forEach { word ->
                    val candidate = if (line.isEmpty()) word else "$line $word"
                    if (measureText(candidate, size, bold) <= safeWidth) {
                        line = candidate
                    } else {
                        if (line.isNotEmpty()) lines += line
                        val pieces = breakWord(word, safeWidth, size, bold)
                        if (pieces.size > 1) {
                            lines += pieces.dropLast(1)
                            line = pieces.last()
                        } else {
                            line = pieces.firstOrNull().orEmpty()
                        }
                    }
                }
                if (line.isNotEmpty()) lines += line
            }
            return lines.ifEmpty { listOf("") }
        }

        private fun breakWord(value: String, width: Float, size: Float, bold: Boolean): List<String> {
            if (measureText(value, size, bold) <= width) return listOf(value)
            val parts = mutableListOf<String>()
            var part = ""
            value.forEach { character ->
                val candidate = part + character
                if (part.isNotEmpty() && measureText(candidate, size, bold) > width) {
                    parts += part
                    part = character.toString()
                } else {
                    part = candidate
                }
            }
            if (part.isNotEmpty()) parts += part
            return parts.ifEmpty { listOf(value) }
        }

        private fun measureText(value: String, size: Float, bold: Boolean): Float {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            }
            return paint.measureText(value)
        }

        private fun fitTimestamp(value: String, width: Float): Pair<String, Float> {
            listOf(8f, 7.5f, 7f, 6.5f, 6f, 5.5f).forEach { size ->
                if (measureText(value, size, false) <= width) return value to size
            }
            return value to 5.5f
        }

        private fun fitNumeric(value: String, width: Float): Pair<String, Float> {
            listOf(8f, 7.5f, 7f, 6.5f, 6f).forEach { size ->
                if (measureText(value, size, false) <= width) return value to size
            }
            var clipped = ""
            for (character in value) {
                val candidate = "$clipped$character…"
                if (measureText(candidate, 6f, false) > width) break
                clipped += character
            }
            clipped += '…'
            return clipped to 6f
        }
    }
}
