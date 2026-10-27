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
    }

    fun export(report: MeterDataReport): File {
        return exportBundle(report, emptyList()).pdf
    }

    fun exportBundle(report: MeterDataReport, traceLogs: List<TraceLog>): MeterLinkExportBundle {
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
        buildReport(layout, report, traceLogs)

        val document = PdfDocument()
        layout.render(document)
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
        val hexLog = File(directory, "MeterLink_HexLog_${serial}_$stamp.txt")
        writeHexLog(hexLog, traceLogs, generated, serial)
        return MeterLinkExportBundle(file, hexLog)
    }

    fun exportHexLog(report: MeterDataReport, traceLogs: List<TraceLog>): File {
        val generated = SimpleDateFormat("dd-MMM-yyyy HH:mm:ss", Locale.ENGLISH).format(Date())
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).format(Date())
        val serial = identityValue(report, "0.0.96.1.0.255")
            .ifBlank { "Meter" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        directory.mkdirs()
        return File(directory, "MeterLink_HexLog_${serial}_$stamp.txt").also {
            writeHexLog(it, traceLogs, generated, serial)
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

    private fun buildReport(layout: ReportLayout, report: MeterDataReport, traceLogs: List<TraceLog>) {
        report.identity?.let { identity ->
            layout.section("Section 1: Meter Identity & Nameplate Summary")
            layout.table(
                listOf("Parameter", "OBIS Code", "Value", "Unit"),
                identity.rows.map { listOf(it.parameterName, it.obisCode, it.formattedValue, it.unit) }
            )
            identity.nameplate?.let { drawProfile(layout, it) }
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
        if (report.tamperEvents.isNotEmpty()) {
            layout.section("Section 7: Tamper & Event Logs")
            layout.table(
                listOf("Compartment", "Date/Time", "Event Code", "Event Description", "V", "I", "PF", "kWh"),
                report.tamperEvents.map {
                    listOf(it.compartment, it.dateTime, it.eventCode, it.eventDescription, it.voltage, it.current, it.powerFactor, it.energy)
                }
            )
        }
        if (report.errors.isNotEmpty()) {
            layout.section("Read Notes")
            layout.table(listOf("Section", "Status"), report.errors.map { listOf("MeterLink", it) })
        }
        layout.section("Section 8: Diagnostic Protocol & Execution Log (Summary)")
        layout.table(listOf("Category", "Details"), diagnosticRows(traceLogs))
    }

    private fun drawProfile(layout: ReportLayout, section: ProfileSection) {
        val rows = section.rows.flatMap { row ->
            section.columns.mapIndexed { index, column ->
                listOf(column.parameterName, column.obisCode, row.getOrNull(index) ?: "N/A", column.unit)
            }
        }
        layout.table(listOf("Parameter", "OBIS Code", "Value", "Unit"), rows)
    }

    private fun drawWideProfile(layout: ReportLayout, section: ProfileSection) {
        val headers = section.columns.map { it.parameterName }
        if (headers.isEmpty()) {
            layout.table(listOf("Profile", "Status"), listOf(listOf(section.profileObis, "No rows returned")))
        } else {
            drawProfileChunks(layout, section, section.columns.indices.toList(), section.title)
        }
    }

    private fun drawBillingProfile(layout: ReportLayout, section: ProfileSection) {
        if (section.columns.isEmpty()) {
            layout.table(listOf("Profile", "Status"), listOf(listOf(section.profileObis, "No rows returned")))
        } else {
            drawProfileChunks(layout, section, section.columns.indices.toList(), "Billing History Profile")
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
        title: String
    ) {
        if (indexes.size <= 7) {
            layout.subsection(title)
            drawSelectedProfile(layout, section, indexes.map { it to section.columns[it].parameterName })
            return
        }

        val firstIndex = indexes.first()
        val chunks = indexes.drop(1).chunked(6)
        chunks.forEachIndexed { chunkIndex, chunk ->
            val chunkTitle = "$title (Part ${chunkIndex + 1} of ${chunks.size})"
            layout.subsection(chunkTitle)
            val selected = listOf(firstIndex) + chunk
            drawSelectedProfile(layout, section, selected.map { it to section.columns[it].parameterName })
        }
    }

    private fun diagnosticRows(traceLogs: List<TraceLog>): List<List<String>> {
        if (traceLogs.isEmpty()) return listOf(listOf("Trace", "No protocol trace lines captured."))
        val counts = TraceDirection.entries.associateWith { direction ->
            traceLogs.count { it.direction == direction }
        }
        val rows = mutableListOf(
            listOf(
                "Packet counts",
                "TX=${counts[TraceDirection.TX] ?: 0}, RX=${counts[TraceDirection.RX] ?: 0}, " +
                    "INFO=${counts[TraceDirection.INFO] ?: 0}, ERROR=${counts[TraceDirection.ERROR] ?: 0}"
            )
        )
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

    private fun writeHexLog(file: File, traceLogs: List<TraceLog>, generated: String, serial: String) {
        val timeFormat = SimpleDateFormat("dd-MMM-yyyy HH:mm:ss.SSS", Locale.ENGLISH)
        file.bufferedWriter().use { writer ->
            writer.appendLine("MeterLink Hex Terminal Log")
            writer.appendLine("Generated (phone time): $generated")
            writer.appendLine("Serial: $serial")
            writer.appendLine()
            traceLogs.asReversed().forEach { log ->
                writer.appendLine("${timeFormat.format(Date(log.timestampMs))} [${log.direction}] ${log.hexOrMessage}")
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

        fun table(headers: List<String>, rows: List<List<String>>) {
            if (headers.isEmpty()) return
            currentTableHeaders = headers
            val widths = widths(headers.size)
            tableHeader(headers, widths)
            rows.forEachIndexed { rowIndex, row ->
                val wrapped = headers.indices.map { index ->
                    wrapText(row.getOrNull(index) ?: "", widths[index] - 8f, 8f)
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
                    val numeric = value.matches(Regex("[-+]?([0-9][0-9,]*)(\\.[0-9]+)?(.*)"))
                    val align = if (numeric && index > 0) Paint.Align.RIGHT else Paint.Align.LEFT
                    wrapped[index].forEachIndexed { lineIndex, line ->
                        val textX = if (align == Paint.Align.RIGHT) x + widths[index] - 4f else x + 4f
                        add(Op.Text(line, textX, top + 14f + lineIndex * 10f, 8f, Color.rgb(30, 41, 59), false, align))
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
                    "date" in header || "time" in header || "description" in header -> 1.6f
                    "obis" in header || "code" in header || "unit" in header -> 0.6f
                    header in setOf("v", "i", "pf", "kwh", "kvah", "kvarh") -> 0.6f
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
    }
}
